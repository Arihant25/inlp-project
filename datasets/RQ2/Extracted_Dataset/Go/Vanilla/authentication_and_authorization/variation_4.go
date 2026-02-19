package main

import (
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

// --- Global State & Config ---
var (
	user_db    = make(map[string]User)
	post_db    = make(map[string]Post)
	db_mutex   = &sync.RWMutex{}
	jwt_secret = []byte("minimalist_secret_key_!@#$")
)

// --- Domain Types ---
type UserRole string
const (
	ADMIN UserRole = "ADMIN"
	USER  UserRole = "USER"
)

type PostStatus string
const (
	DRAFT     PostStatus = "DRAFT"
	PUBLISHED PostStatus = "PUBLISHED"
)

type User struct {
	Id           string
	Email        string
	PasswordHash string
	Role         UserRole
	IsActive     bool
	CreatedAt    time.Time
}

type Post struct {
	Id      string
	UserId  string
	Title   string
	Content string
	Status  PostStatus
}

// --- Security Helpers ---

// NOTE: Using salted SHA512 due to standard library constraints. Use bcrypt in production.
func create_password_hash(password string) (string, error) {
	salt := make([]byte, 16)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	hash := sha512.Sum512(append(salt, []byte(password)...))
	return hex.EncodeToString(salt) + ":" + hex.EncodeToString(hash[:]), nil
}

func check_password_hash(password, hash_string string) bool {
	parts := strings.Split(hash_string, ":")
	if len(parts) != 2 {
		return false
	}
	salt, err := hex.DecodeString(parts[0])
	if err != nil {
		return false
	}
	expected_hash := sha512.Sum512(append(salt, []byte(password)...))
	return parts[1] == hex.EncodeToString(expected_hash[:])
}

// --- JWT Helpers ---
type jwt_claims struct {
	UserId string   `json:"user_id"`
	Role   UserRole `json:"role"`
	Exp    int64    `json:"exp"`
}

func create_token(user_id string, role UserRole) (string, error) {
	header := `{"alg":"HS256","typ":"JWT"}`
	claims := jwt_claims{
		UserId: user_id,
		Role:   role,
		Exp:    time.Now().Add(time.Hour * 8).Unix(),
	}
	
	b64_header := base64.RawURLEncoding.EncodeToString([]byte(header))
	json_claims, err := json.Marshal(claims)
	if err != nil {
		return "", err
	}
	b64_claims := base64.RawURLEncoding.EncodeToString(json_claims)
	
	token_content := b64_header + "." + b64_claims
	
	h := hmac.New(sha256.New, jwt_secret)
	h.Write([]byte(token_content))
	signature := base64.RawURLEncoding.EncodeToString(h.Sum(nil))
	
	return token_content + "." + signature, nil
}

func parse_token(token_str string) (*jwt_claims, error) {
	parts := strings.Split(token_str, ".")
	if len(parts) != 3 {
		return nil, fmt.Errorf("invalid token structure")
	}
	
	h := hmac.New(sha256.New, jwt_secret)
	h.Write([]byte(parts[0] + "." + parts[1]))
	expected_sig := h.Sum(nil)
	
	sig, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil || !hmac.Equal(sig, expected_sig) {
		return nil, fmt.Errorf("invalid signature")
	}
	
	payload, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return nil, fmt.Errorf("invalid payload")
	}
	
	var claims jwt_claims
	if json.Unmarshal(payload, &claims) != nil {
		return nil, fmt.Errorf("cannot unmarshal claims")
	}
	
	if time.Now().Unix() > claims.Exp {
		return nil, fmt.Errorf("token has expired")
	}
	
	return &claims, nil
}

// --- Middleware (as closures) ---
func with_auth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		auth_header := r.Header.Get("Authorization")
		token_str := strings.TrimPrefix(auth_header, "Bearer ")
		
		if token_str == "" || token_str == auth_header {
			http.Error(w, "Unauthorized: Missing token", http.StatusUnauthorized)
			return
		}
		
		claims, err := parse_token(token_str)
		if err != nil {
			http.Error(w, "Unauthorized: "+err.Error(), http.StatusUnauthorized)
			return
		}
		
		// Add claims to header for downstream handlers (less common, but avoids context)
		r.Header.Set("X-User-ID", claims.UserId)
		r.Header.Set("X-User-Role", string(claims.Role))
		
		next.ServeHTTP(w, r)
	}
}

func with_admin_role(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		role := r.Header.Get("X-User-Role")
		if UserRole(role) != ADMIN {
			http.Error(w, "Forbidden: Admin role required", http.StatusForbidden)
			return
		}
		next.ServeHTTP(w, r)
	}
}

// --- Handlers ---
func login_handler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}
	
	var body struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		http.Error(w, "Invalid request body", http.StatusBadRequest)
		return
	}
	
	db_mutex.RLock()
	var target_user User
	found := false
	for _, u := range user_db {
		if u.Email == body.Email {
			target_user = u
			found = true
			break
		}
	}
	db_mutex.RUnlock()
	
	if !found || !target_user.IsActive || !check_password_hash(body.Password, target_user.PasswordHash) {
		http.Error(w, "Invalid email or password", http.StatusUnauthorized)
		return
	}
	
	token, err := create_token(target_user.Id, target_user.Role)
	if err != nil {
		log.Printf("Token creation error: %v", err)
		http.Error(w, "Could not process login", http.StatusInternalServerError)
		return
	}
	
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"token": token})
}

func get_my_posts_handler(w http.ResponseWriter, r *http.Request) {
	user_id := r.Header.Get("X-User-ID")
	
	db_mutex.RLock()
	user_posts := []Post{}
	for _, p := range post_db {
		if p.UserId == user_id {
			user_posts = append(user_posts, p)
		}
	}
	db_mutex.RUnlock()
	
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(user_posts)
}

func get_all_users_handler(w http.ResponseWriter, r *http.Request) {
	db_mutex.RLock()
	all_users := make([]User, 0, len(user_db))
	for _, u := range user_db {
		all_users = append(all_users, u)
	}
	db_mutex.RUnlock()
	
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(all_users)
}

// --- Main ---
func generate_uuid() string {
	b := make([]byte, 16)
	rand.Read(b)
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:])
}

func main() {
	// Seed data
	admin_pass, _ := create_password_hash("adminpass")
	user_pass, _ := create_password_hash("userpass")
	admin_id := generate_uuid()
	user_id := generate_uuid()
	
	user_db[admin_id] = User{Id: admin_id, Email: "admin@localhost", PasswordHash: admin_pass, Role: ADMIN, IsActive: true, CreatedAt: time.Now()}
	user_db[user_id] = User{Id: user_id, Email: "user@localhost", PasswordHash: user_pass, Role: USER, IsActive: true, CreatedAt: time.Now()}
	
	post_id := generate_uuid()
	post_db[post_id] = Post{Id: post_id, UserId: user_id, Title: "A User's Post", Content: "Content here."}
	
	// Setup routes
	http.HandleFunc("/login", login_handler)
	http.HandleFunc("/api/posts", with_auth(get_my_posts_handler))
	http.HandleFunc("/admin/users", with_auth(with_admin_role(get_all_users_handler)))
	
	log.Println("Starting minimalist server on :8083...")
	err := http.ListenAndServe(":8083", nil)
	if err != nil {
		log.Fatalf("Server failed to start: %v", err)
	}
}