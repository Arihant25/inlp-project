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
	"log"
	"net/http"
	"strings"
	"sync"
	"time"
)

// --- Domain Models ---

type Role string
const (
	ADMIN_ROLE Role = "ADMIN"
	USER_ROLE  Role = "USER"
)

type PostStatus string
const (
	DRAFT_STATUS     PostStatus = "DRAFT"
	PUBLISHED_STATUS PostStatus = "PUBLISHED"
)

type User struct {
	ID           string
	Email        string
	PasswordHash string
	Role         Role
	IsActive     bool
	CreatedAt    time.Time
}

type Post struct {
	ID      string
	UserID  string
	Title   string
	Content string
	Status  PostStatus
}

// --- Data Storage Layer ---

type UserDataStore interface {
	FindUserByEmail(email string) (*User, error)
	FindUserByID(id string) (*User, error)
}

type InMemoryUserStore struct {
	sync.RWMutex
	users map[string]*User
}

func NewInMemoryUserStore() *InMemoryUserStore {
	return &InMemoryUserStore{users: make(map[string]*User)}
}

func (s *InMemoryUserStore) Seed(users ...*User) {
	s.Lock()
	defer s.Unlock()
	for _, u := range users {
		s.users[u.ID] = u
	}
}

func (s *InMemoryUserStore) FindUserByEmail(email string) (*User, error) {
	s.RLock()
	defer s.RUnlock()
	for _, u := range s.users {
		if u.Email == email {
			return u, nil
		}
	}
	return nil, fmt.Errorf("user not found")
}

func (s *InMemoryUserStore) FindUserByID(id string) (*User, error) {
	s.RLock()
	defer s.RUnlock()
	user, ok := s.users[id]
	if !ok {
		return nil, fmt.Errorf("user not found")
	}
	return user, nil
}

// --- Security Service ---

type SecurityService struct {
	jwtSecret []byte
}

func NewSecurityService(secret string) *SecurityService {
	return &SecurityService{jwtSecret: []byte(secret)}
}

// NOTE: Using salted SHA512 due to standard library constraints.
// In production, use bcrypt or scrypt.
func (s *SecurityService) HashPassword(password string) (string, error) {
	salt := make([]byte, 16)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	hash := sha512.Sum512(append(salt, []byte(password)...))
	return fmt.Sprintf("%s:%s", hex.EncodeToString(salt), hex.EncodeToString(hash[:])), nil
}

func (s *SecurityService) ValidatePassword(hashedPassword, password string) bool {
	parts := strings.Split(hashedPassword, ":")
	if len(parts) != 2 {
		return false
	}
	salt, err := hex.DecodeString(parts[0])
	if err != nil {
		return false
	}
	hash := sha512.Sum512(append(salt, []byte(password)...))
	return hmac.Equal([]byte(parts[1]), []byte(hex.EncodeToString(hash[:])))
}

// --- Authentication Service ---

type TokenClaims struct {
	UserID string `json:"user_id"`
	Role   Role   `json:"role"`
	Exp    int64  `json:"exp"`
}

type AuthenticationService struct {
	userStore UserDataStore
	secSvc    *SecurityService
}

func NewAuthenticationService(store UserDataStore, secSvc *SecurityService) *AuthenticationService {
	return &AuthenticationService{userStore: store, secSvc: secSvc}
}

func (s *AuthenticationService) Login(email, password string) (string, error) {
	user, err := s.userStore.FindUserByEmail(email)
	if err != nil || !user.IsActive {
		return "", fmt.Errorf("invalid credentials")
	}

	if !s.secSvc.ValidatePassword(user.PasswordHash, password) {
		return "", fmt.Errorf("invalid credentials")
	}

	return s.generateToken(user)
}

func (s *AuthenticationService) generateToken(user *User) (string, error) {
	header := `{"alg":"HS256","typ":"JWT"}`
	encodedHeader := base64.RawURLEncoding.EncodeToString([]byte(header))

	claims := TokenClaims{
		UserID: user.ID,
		Role:   user.Role,
		Exp:    time.Now().Add(time.Hour * 24).Unix(),
	}
	claimsJSON, _ := json.Marshal(claims)
	encodedClaims := base64.RawURLEncoding.EncodeToString(claimsJSON)

	signatureInput := fmt.Sprintf("%s.%s", encodedHeader, encodedClaims)
	mac := hmac.New(sha256.New, s.secSvc.jwtSecret)
	mac.Write([]byte(signatureInput))
	signature := mac.Sum(nil)
	encodedSignature := base64.RawURLEncoding.EncodeToString(signature)

	return fmt.Sprintf("%s.%s", signatureInput, encodedSignature), nil
}

func (s *AuthenticationService) ValidateToken(tokenStr string) (*TokenClaims, error) {
	parts := strings.Split(tokenStr, ".")
	if len(parts) != 3 {
		return nil, fmt.Errorf("invalid token format")
	}

	mac := hmac.New(sha256.New, s.secSvc.jwtSecret)
	mac.Write([]byte(parts[0] + "." + parts[1]))
	expectedSignature := mac.Sum(nil)

	signature, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil || !hmac.Equal(signature, expectedSignature) {
		return nil, fmt.Errorf("invalid signature")
	}

	payload, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return nil, fmt.Errorf("invalid payload")
	}

	var claims TokenClaims
	if json.Unmarshal(payload, &claims) != nil || time.Now().Unix() > claims.Exp {
		return nil, fmt.Errorf("invalid claims or token expired")
	}

	return &claims, nil
}

// --- API Server & Handlers ---

type ApiServer struct {
	authSvc *AuthenticationService
}

func NewApiServer(authSvc *AuthenticationService) *ApiServer {
	return &ApiServer{authSvc: authSvc}
}

func (s *ApiServer) handleLogin(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if json.NewDecoder(r.Body).Decode(&req) != nil {
		http.Error(w, "Bad Request", http.StatusBadRequest)
		return
	}

	token, err := s.authSvc.Login(req.Email, req.Password)
	if err != nil {
		http.Error(w, "Unauthorized", http.StatusUnauthorized)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"token": token})
}

func (s *ApiServer) handleGetProfile(w http.ResponseWriter, r *http.Request) {
	claims, ok := r.Context().Value("user_claims").(*TokenClaims)
	if !ok {
		http.Error(w, "Forbidden", http.StatusForbidden)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"user_id": claims.UserID, "role": string(claims.Role)})
}

func (s *ApiServer) handleAdminData(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"data": "This is secret admin data"})
}

// --- Middleware ---

func (s *ApiServer) AuthenticationMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		authHeader := r.Header.Get("Authorization")
		tokenStr := strings.TrimPrefix(authHeader, "Bearer ")
		if tokenStr == authHeader {
			http.Error(w, "Unauthorized", http.StatusUnauthorized)
			return
		}

		claims, err := s.authSvc.ValidateToken(tokenStr)
		if err != nil {
			http.Error(w, "Unauthorized", http.StatusUnauthorized)
			return
		}

		ctx := context.WithValue(r.Context(), "user_claims", claims)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

func (s *ApiServer) AuthorizationMiddleware(requiredRole Role) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			claims, ok := r.Context().Value("user_claims").(*TokenClaims)
			if !ok || claims.Role != requiredRole {
				http.Error(w, "Forbidden", http.StatusForbidden)
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}

// --- Main ---

func generateUUID() string {
	b := make([]byte, 16)
	rand.Read(b)
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:])
}

func main() {
	// Dependencies
	userStore := NewInMemoryUserStore()
	securitySvc := NewSecurityService("my-super-secure-secret-for-hs256")
	authSvc := NewAuthenticationService(userStore, securitySvc)
	server := NewApiServer(authSvc)

	// Seed Data
	adminPass, _ := securitySvc.HashPassword("secureadmin")
	userPass, _ := securitySvc.HashPassword("secureuser")
	adminID, userID := generateUUID(), generateUUID()
	userStore.Seed(
		&User{ID: adminID, Email: "admin@corp.com", PasswordHash: adminPass, Role: ADMIN_ROLE, IsActive: true},
		&User{ID: userID, Email: "user@corp.com", PasswordHash: userPass, Role: USER_ROLE, IsActive: true},
	)

	// Routing
	mux := http.NewServeMux()
	mux.HandleFunc("/login", server.handleLogin)

	// Protected routes
	userRouter := http.NewServeMux()
	userRouter.HandleFunc("/profile", server.handleGetProfile)
	mux.Handle("/user/", http.StripPrefix("/user", server.AuthenticationMiddleware(userRouter)))

	adminRouter := http.NewServeMux()
	adminRouter.HandleFunc("/data", server.handleAdminData)
	adminAuthChain := server.AuthenticationMiddleware(server.AuthorizationMiddleware(ADMIN_ROLE)(adminRouter))
	mux.Handle("/admin/", http.StripPrefix("/admin", adminAuthChain))

	log.Println("Starting OOP-based server on :8081...")
	if err := http.ListenAndServe(":8081", mux); err != nil {
		log.Fatalf("Server failed: %v", err)
	}
}