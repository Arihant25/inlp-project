package main

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/sha512"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

// --- Domain Schema & Constants ---
type UserRole string
const (
	RoleAdmin UserRole = "ADMIN"
	RoleUser  UserRole = "USER"
)

type PostStatus string
const (
	StatusDraft     PostStatus = "DRAFT"
	StatusPublished PostStatus = "PUBLISHED"
)

type User struct {
	ID           string    `json:"id"`
	Email        string    `json:"email"`
	PasswordHash string    `json:"-"`
	Role         UserRole  `json:"role"`
	IsActive     bool      `json:"is_active"`
	CreatedAt    time.Time `json:"created_at"`
}

type Post struct {
	ID      string     `json:"id"`
	UserID  string     `json:"user_id"`
	Title   string     `json:"title"`
	Content string     `json:"content"`
	Status  PostStatus `json:"status"`
}

// --- Context Keys ---
type contextKey string
var userContextKey = contextKey("user")

// --- In-Memory Store ---
var (
	userStore = make(map[string]User)
	postStore = make(map[string]Post)
	storeLock = sync.RWMutex{}
)

// --- JWT Manager ---
type JWTManager struct {
	secretKey []byte
	issuer    string
}

type UserClaims struct {
	UserID string   `json:"uid"`
	Role   UserRole `json:"rol"`
	Exp    int64    `json:"exp"`
	Iss    string   `json:"iss"`
}

func NewJWTManager(secret string, issuer string) *JWTManager {
	return &JWTManager{secretKey: []byte(secret), issuer: issuer}
}

func (m *JWTManager) Generate(user User) (string, error) {
	header := `{"alg":"HS256","typ":"JWT"}`
	claims := UserClaims{
		UserID: user.ID,
		Role:   user.Role,
		Exp:    time.Now().Add(time.Hour * 1).Unix(),
		Iss:    m.issuer,
	}
	
	headerB64 := base64.RawURLEncoding.EncodeToString([]byte(header))
	claimsJSON, err := json.Marshal(claims)
	if err != nil {
		return "", err
	}
	claimsB64 := base64.RawURLEncoding.EncodeToString(claimsJSON)
	
	message := headerB64 + "." + claimsB64
	mac := hmac.New(sha256.New, m.secretKey)
	mac.Write([]byte(message))
	signature := mac.Sum(nil)
	sigB64 := base64.RawURLEncoding.EncodeToString(signature)
	
	return message + "." + sigB64, nil
}

func (m *JWTManager) Parse(tokenString string) (*UserClaims, error) {
	parts := strings.Split(tokenString, ".")
	if len(parts) != 3 {
		return nil, fmt.Errorf("malformed token")
	}

	message := parts[0] + "." + parts[1]
	mac := hmac.New(sha256.New, m.secretKey)
	mac.Write([]byte(message))
	expectedMAC := mac.Sum(nil)

	signature, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil {
		return nil, fmt.Errorf("invalid signature")
	}

	if !hmac.Equal(signature, expectedMAC) {
		return nil, fmt.Errorf("invalid signature")
	}

	payload, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return nil, fmt.Errorf("malformed payload")
	}

	var claims UserClaims
	if err := json.Unmarshal(payload, &claims); err != nil {
		return nil, err
	}

	if claims.Exp < time.Now().Unix() {
		return nil, fmt.Errorf("token is expired")
	}
	if claims.Iss != m.issuer {
		return nil, fmt.Errorf("invalid issuer")
	}

	return &claims, nil
}

// --- Password Utils ---
// NOTE: Using salted SHA512 due to standard library constraints. Use bcrypt in production.
func hashPassword(password string) (string, error) {
	salt := make([]byte, 16)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	hash := sha512.Sum512(append(salt, []byte(password)...))
	return hex.EncodeToString(salt) + "." + hex.EncodeToString(hash[:]), nil
}

func verifyPassword(hashedPassword, password string) bool {
	parts := strings.Split(hashedPassword, ".")
	if len(parts) != 2 { return false }
	salt, err := hex.DecodeString(parts[0])
	if err != nil { return false }
	hash := sha512.Sum512(append(salt, []byte(password)...))
	return parts[1] == hex.EncodeToString(hash[:])
}

// --- Middleware Chain ---
func authenticate(jwtManager *JWTManager) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			header := r.Header.Get("Authorization")
			if !strings.HasPrefix(header, "Bearer ") {
				http.Error(w, "Missing or invalid token", http.StatusUnauthorized)
				return
			}
			token := strings.TrimPrefix(header, "Bearer ")
			claims, err := jwtManager.Parse(token)
			if err != nil {
				http.Error(w, "Token validation failed: "+err.Error(), http.StatusUnauthorized)
				return
			}
			ctx := context.WithValue(r.Context(), userContextKey, claims)
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

func requireRole(role UserRole) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			claims, ok := r.Context().Value(userContextKey).(*UserClaims)
			if !ok || claims.Role != role {
				http.Error(w, "Forbidden", http.StatusForbidden)
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}

// --- Handlers ---
func loginHandler(jwtManager *JWTManager) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var creds struct { Email, Password string }
		if err := json.NewDecoder(r.Body).Decode(&creds); err != nil {
			http.Error(w, "Bad request", http.StatusBadRequest)
			return
		}
		
		storeLock.RLock()
		var user User
		var found bool
		for _, u := range userStore {
			if u.Email == creds.Email {
				user, found = u, true
				break
			}
		}
		storeLock.RUnlock()

		if !found || !user.IsActive || !verifyPassword(user.PasswordHash, creds.Password) {
			http.Error(w, "Invalid credentials", http.StatusUnauthorized)
			return
		}

		token, err := jwtManager.Generate(user)
		if err != nil {
			http.Error(w, "Internal server error", http.StatusInternalServerError)
			return
		}
		json.NewEncoder(w).Encode(map[string]string{"access_token": token})
	}
}

func myPostsHandler(w http.ResponseWriter, r *http.Request) {
	claims := r.Context().Value(userContextKey).(*UserClaims)
	storeLock.RLock()
	defer storeLock.RUnlock()
	
	var results []Post
	for _, p := range postStore {
		if p.UserID == claims.UserID {
			results = append(results, p)
		}
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(results)
}

func adminStatsHandler(w http.ResponseWriter, r *http.Request) {
	storeLock.RLock()
	defer storeLock.RUnlock()
	stats := map[string]int{"users": len(userStore), "posts": len(postStore)}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(stats)
}

// --- OAuth2 Client Simulation ---
const (
	oauthClientID     = "my-client-id"
	oauthClientSecret = "my-client-secret"
	oauthRedirectURI  = "http://localhost:8082/oauth/callback"
	mockProviderAuthURL = "http://localhost:9090/auth"
	mockProviderTokenURL = "http://localhost:9090/token"
)

func oauthLoginHandler(w http.ResponseWriter, r *http.Request) {
	authURL := fmt.Sprintf("%s?response_type=code&client_id=%s&redirect_uri=%s&scope=read",
		mockProviderAuthURL, oauthClientID, url.QueryEscape(oauthRedirectURI))
	http.Redirect(w, r, authURL, http.StatusFound)
}

func oauthCallbackHandler(jwtManager *JWTManager) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		code := r.URL.Query().Get("code")
		if code == "" {
			http.Error(w, "Code not found", http.StatusBadRequest)
			return
		}
		
		// Exchange code for token
		resp, err := http.PostForm(mockProviderTokenURL, url.Values{
			"grant_type":    {"authorization_code"},
			"code":          {code},
			"client_id":     {oauthClientID},
			"client_secret": {oauthClientSecret},
			"redirect_uri":  {oauthRedirectURI},
		})
		if err != nil || resp.StatusCode != http.StatusOK {
			http.Error(w, "Failed to exchange code for token", http.StatusInternalServerError)
			return
		}
		
		var tokenResp struct { AccessToken string `json:"access_token"` }
		if err := json.NewDecoder(resp.Body).Decode(&tokenResp); err != nil {
			http.Error(w, "Failed to parse token response", http.StatusInternalServerError)
			return
		}
		
		// In a real scenario, you'd use this token to get user info from the provider.
		// Here, we'll just find or create a user and issue our own JWT.
		oauthUserEmail := "oauth.user@example.com"
		storeLock.Lock()
		var user User
		var found bool
		for _, u := range userStore {
			if u.Email == oauthUserEmail {
				user, found = u, true
				break
			}
		}
		if !found {
			// Create a new user for this OAuth login
			id := newUUID()
			user = User{ID: id, Email: oauthUserEmail, Role: RoleUser, IsActive: true, CreatedAt: time.Now()}
			userStore[id] = user
		}
		storeLock.Unlock()

		appToken, err := jwtManager.Generate(user)
		if err != nil {
			http.Error(w, "Could not create session", http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]string{"app_token": appToken})
	}
}

func startMockOAuthProvider() {
	mux := http.NewServeMux()
	mux.HandleFunc("/auth", func(w http.ResponseWriter, r *http.Request) {
		redirectURI := r.URL.Query().Get("redirect_uri")
		// In a real provider, user would log in here. We just redirect back with a fake code.
		callbackURL := fmt.Sprintf("%s?code=fake-auth-code-12345", redirectURI)
		http.Redirect(w, r, callbackURL, http.StatusFound)
	})
	mux.HandleFunc("/token", func(w http.ResponseWriter, r *http.Request) {
		r.ParseForm()
		if r.FormValue("code") == "fake-auth-code-12345" && r.FormValue("client_id") == oauthClientID {
			w.Header().Set("Content-Type", "application/json")
			io.WriteString(w, `{"access_token":"fake-provider-token","token_type":"Bearer"}`)
		} else {
			http.Error(w, "Invalid code", http.StatusBadRequest)
		}
	})
	log.Println("Mock OAuth Provider starting on :9090")
	http.ListenAndServe(":9090", mux)
}

// --- Main Setup ---
func newUUID() string {
	b := make([]byte, 16); rand.Read(b)
	b[6] = (b[6] & 0x0f) | 0x40; b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:])
}

func main() {
	// Seed data
	adminPass, _ := hashPassword("adminpass")
	adminID := newUUID()
	userStore[adminID] = User{ID: adminID, Email: "admin@test.com", PasswordHash: adminPass, Role: RoleAdmin, IsActive: true, CreatedAt: time.Now()}
	
	jwtManager := NewJWTManager("a-very-secure-secret-for-variation-3", "my-app")

	go startMockOAuthProvider()

	// Main App Router
	mainRouter := http.NewServeMux()
	mainRouter.HandleFunc("/login", loginHandler(jwtManager))
	mainRouter.HandleFunc("/login/oauth", oauthLoginHandler)
	mainRouter.HandleFunc("/oauth/callback", oauthCallbackHandler(jwtManager))

	// Authenticated User Routes
	userAPI := http.NewServeMux()
	userAPI.HandleFunc("/posts", myPostsHandler)
	mainRouter.Handle("/api/user/", http.StripPrefix("/api/user", authenticate(jwtManager)(userAPI)))

	// Authenticated Admin Routes
	adminAPI := http.NewServeMux()
	adminAPI.HandleFunc("/stats", adminStatsHandler)
	adminChain := authenticate(jwtManager)(requireRole(RoleAdmin)(adminAPI))
	mainRouter.Handle("/api/admin/", http.StripPrefix("/api/admin", adminChain))

	log.Println("Context-based server starting on :8082")
	if err := http.ListenAndServe(":8082", mainRouter); err != nil {
		log.Fatal(err)
	}
}