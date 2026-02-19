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

// --- Domain Schema ---

type Role string
const (
	ADMIN Role = "ADMIN"
	USER  Role = "USER"
)

type Status string
const (
	DRAFT     Status = "DRAFT"
	PUBLISHED Status = "PUBLISHED"
)

type User struct {
	ID           string    `json:"id"`
	Email        string    `json:"email"`
	PasswordHash string    `json:"-"`
	UserRole     Role      `json:"role"`
	IsActive     bool      `json:"is_active"`
	CreatedAt    time.Time `json:"created_at"`
}

type Post struct {
	ID      string    `json:"id"`
	UserID  string    `json:"user_id"`
	Title   string    `json:"title"`
	Content string    `json:"content"`
	Status  Status    `json:"status"`
}

// --- Mock Datastore ---

var (
	users     = make(map[string]User)
	posts     = make(map[string]Post)
	usersLock = sync.RWMutex{}
	postsLock = sync.RWMutex{}
	jwtSecret = []byte("a-very-secret-key-that-is-at-least-32-bytes-long")
)

// --- Utility Functions ---

func newUUID() string {
	uuid := make([]byte, 16)
	_, err := rand.Read(uuid)
	if err != nil {
		log.Fatalf("Failed to generate UUID: %v", err)
	}
	uuid[6] = (uuid[6] & 0x0f) | 0x40 // Version 4
	uuid[8] = (uuid[8] & 0x3f) | 0x80 // Variant RFC4122
	return fmt.Sprintf("%x-%x-%x-%x-%x", uuid[0:4], uuid[4:6], uuid[6:8], uuid[8:10], uuid[10:])
}

func logRequest(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		log.Printf("Request: %s %s", r.Method, r.URL.Path)
		next.ServeHTTP(w, r)
	})
}

func respondWithError(w http.ResponseWriter, code int, message string) {
	respondWithJSON(w, code, map[string]string{"error": message})
}

func respondWithJSON(w http.ResponseWriter, code int, payload interface{}) {
	response, _ := json.Marshal(payload)
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	w.Write(response)
}

// --- Password Hashing (Standard Library Only) ---
// NOTE: In a real production system, use a library like golang.org/x/crypto/bcrypt
// which is designed for password hashing. This is a simplified implementation
// using only the standard library as per the requirements.

func hashPassword(password string) (string, error) {
	salt := make([]byte, 16)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	// Combine salt and password
	saltedPassword := append(salt, []byte(password)...)
	// Hash the combined value
	hash := sha512.Sum512(saltedPassword)
	// Return salt and hash concatenated with a separator
	return hex.EncodeToString(salt) + ":" + hex.EncodeToString(hash[:]), nil
}

func checkPassword(hashedPassword, password string) bool {
	parts := strings.Split(hashedPassword, ":")
	if len(parts) != 2 {
		return false
	}
	salt, err := hex.DecodeString(parts[0])
	if err != nil {
		return false
	}
	
	saltedPassword := append(salt, []byte(password)...)
	hash := sha512.Sum512(saltedPassword)
	
	return parts[1] == hex.EncodeToString(hash[:])
}

// --- JWT Generation & Validation (Standard Library Only) ---

type Claims struct {
	UserID string `json:"user_id"`
	Role   Role   `json:"role"`
	Exp    int64  `json:"exp"`
}

func generateJWT(user User) (string, error) {
	header := map[string]string{"alg": "HS256", "typ": "JWT"}
	headerJSON, err := json.Marshal(header)
	if err != nil {
		return "", err
	}
	encodedHeader := base64.RawURLEncoding.EncodeToString(headerJSON)

	claims := Claims{
		UserID: user.ID,
		Role:   user.UserRole,
		Exp:    time.Now().Add(time.Hour * 24).Unix(),
	}
	claimsJSON, err := json.Marshal(claims)
	if err != nil {
		return "", err
	}
	encodedClaims := base64.RawURLEncoding.EncodeToString(claimsJSON)

	unsignedToken := encodedHeader + "." + encodedClaims
	mac := hmac.New(sha256.New, jwtSecret)
	mac.Write([]byte(unsignedToken))
	signature := mac.Sum(nil)
	encodedSignature := base64.RawURLEncoding.EncodeToString(signature)

	return unsignedToken + "." + encodedSignature, nil
}

func validateJWT(tokenString string) (*Claims, error) {
	parts := strings.Split(tokenString, ".")
	if len(parts) != 3 {
		return nil, fmt.Errorf("invalid token format")
	}

	unsignedToken := parts[0] + "." + parts[1]
	mac := hmac.New(sha256.New, jwtSecret)
	mac.Write([]byte(unsignedToken))
	expectedSignature := mac.Sum(nil)

	signature, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil {
		return nil, fmt.Errorf("invalid signature encoding: %w", err)
	}

	if !hmac.Equal(signature, expectedSignature) {
		return nil, fmt.Errorf("invalid signature")
	}

	payload, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return nil, fmt.Errorf("invalid claims encoding: %w", err)
	}

	var claims Claims
	if err := json.Unmarshal(payload, &claims); err != nil {
		return nil, fmt.Errorf("invalid claims json: %w", err)
	}

	if time.Now().Unix() > claims.Exp {
		return nil, fmt.Errorf("token expired")
	}

	return &claims, nil
}

// --- Middleware ---

type contextKey string
const userClaimsKey contextKey = "userClaims"

func authMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		authHeader := r.Header.Get("Authorization")
		if authHeader == "" {
			respondWithError(w, http.StatusUnauthorized, "Authorization header required")
			return
		}

		parts := strings.Split(authHeader, " ")
		if len(parts) != 2 || parts[0] != "Bearer" {
			respondWithError(w, http.StatusUnauthorized, "Invalid Authorization header format")
			return
		}

		claims, err := validateJWT(parts[1])
		if err != nil {
			respondWithError(w, http.StatusUnauthorized, fmt.Sprintf("Invalid token: %v", err))
			return
		}

		ctx := context.WithValue(r.Context(), userClaimsKey, claims)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

func rbacMiddleware(requiredRole Role) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			claims, ok := r.Context().Value(userClaimsKey).(*Claims)
			if !ok {
				respondWithError(w, http.StatusForbidden, "Access denied: No user claims found")
				return
			}

			if claims.Role != requiredRole {
				respondWithError(w, http.StatusForbidden, "Access denied: Insufficient permissions")
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}

// --- Handlers ---

func handleLogin(w http.ResponseWriter, r *http.Request) {
	var creds struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&creds); err != nil {
		respondWithError(w, http.StatusBadRequest, "Invalid request payload")
		return
	}

	usersLock.RLock()
	var foundUser User
	var userExists bool
	for _, user := range users {
		if user.Email == creds.Email {
			foundUser = user
			userExists = true
			break
		}
	}
	usersLock.RUnlock()

	if !userExists || !foundUser.IsActive {
		respondWithError(w, http.StatusUnauthorized, "Invalid credentials or user inactive")
		return
	}

	if !checkPassword(foundUser.PasswordHash, creds.Password) {
		respondWithError(w, http.StatusUnauthorized, "Invalid credentials")
		return
	}

	token, err := generateJWT(foundUser)
	if err != nil {
		respondWithError(w, http.StatusInternalServerError, "Could not generate token")
		return
	}

	respondWithJSON(w, http.StatusOK, map[string]string{"token": token})
}

func handleGetPosts(w http.ResponseWriter, r *http.Request) {
	postsLock.RLock()
	defer postsLock.RUnlock()

	claims := r.Context().Value(userClaimsKey).(*Claims)
	userPosts := []Post{}
	for _, post := range posts {
		if post.UserID == claims.UserID {
			userPosts = append(userPosts, post)
		}
	}
	respondWithJSON(w, http.StatusOK, userPosts)
}

func handleAdminDashboard(w http.ResponseWriter, r *http.Request) {
	usersLock.RLock()
	defer usersLock.RUnlock()
	respondWithJSON(w, http.StatusOK, map[string]interface{}{
		"message": "Welcome to the admin dashboard!",
		"user_count": len(users),
	})
}

// --- Main Function ---

func main() {
	// Seed data
	adminPassword, _ := hashPassword("admin123")
	userPassword, _ := hashPassword("user123")
	adminID, userID := newUUID(), newUUID()

	users[adminID] = User{ID: adminID, Email: "admin@example.com", PasswordHash: adminPassword, UserRole: ADMIN, IsActive: true, CreatedAt: time.Now()}
	users[userID] = User{ID: userID, Email: "user@example.com", PasswordHash: userPassword, UserRole: USER, IsActive: true, CreatedAt: time.Now()}
	
	postID := newUUID()
	posts[postID] = Post{ID: postID, UserID: userID, Title: "My First Post", Content: "Hello, world!", Status: PUBLISHED}

	// Routing
	mux := http.NewServeMux()
	mux.HandleFunc("/login", handleLogin)

	// User-specific routes
	userMux := http.NewServeMux()
	userMux.HandleFunc("/posts", handleGetPosts)
	mux.Handle("/api/v1/", http.StripPrefix("/api/v1", authMiddleware(userMux)))

	// Admin-specific routes
	adminMux := http.NewServeMux()
	adminMux.HandleFunc("/dashboard", handleAdminDashboard)
	mux.Handle("/admin/api/v1/", http.StripPrefix("/admin/api/v1", authMiddleware(rbacMiddleware(ADMIN)(adminMux))))

	// Apply a global logger
	loggedMux := logRequest(mux)

	log.Println("Server starting on :8080...")
	if err := http.ListenAndServe(":8080", loggedMux); err != nil {
		log.Fatalf("Server failed to start: %v", err)
	}
}