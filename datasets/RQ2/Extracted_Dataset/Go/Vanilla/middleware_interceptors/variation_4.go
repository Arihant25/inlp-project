<pre>
package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/json"
	"fmt"
	"log"
	"net"
	"net/http"
	"sync"
	"time"
)

// --- Domain Schema ---

type UserRole string
const (
	ADMIN_ROLE UserRole = "ADMIN"
	USER_ROLE  UserRole = "USER"
)

type PostStatus string
const (
	DRAFT_STATUS     PostStatus = "DRAFT"
	PUBLISHED_STATUS PostStatus = "PUBLISHED"
)

type UUID string

func generateNewUUID() (UUID, error) {
	b := make([]byte, 16)
	_, err := rand.Read(b)
	if err != nil {
		return "", err
	}
	return UUID(fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], (b[6]&0x0f)|0x40, (b[8]&0x3f)|0x80, b[10:])), nil
}

type User struct {
	ID          UUID      `json:"id"`
	Email       string    `json:"email"`
	PasswordHash string    `json:"-"`
	Role        UserRole  `json:"role"`
	IsActive    bool      `json:"is_active"`
	CreatedAt   time.Time `json:"created_at"`
}

type Post struct {
	ID      UUID       `json:"id"`
	UserID  UUID       `json:"user_id"`
	Title   string     `json:"title"`
	Content string     `json:"content"`
	Status  PostStatus `json:"status"`
}

// --- Context-based Middleware and Custom Handler ---

type key int
const requestIDKey key = 0

// AppError represents a custom error with an HTTP status code.
type AppError struct {
	Code    int
	Message string
}

func (e *AppError) Error() string {
	return e.Message
}

// AppHandler is a custom handler that can return an AppError.
type AppHandler func(http.ResponseWriter, *http.Request) *AppError

// ServeHTTP makes AppHandler compatible with http.Handler.
func (fn AppHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if err := fn(w, r); err != nil { // Call the actual handler
		log.Printf("ERROR: %v", err.Error())
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.WriteHeader(err.Code)
		json.NewEncoder(w).Encode(map[string]interface{}{"error": err.Message})
	}
}

// RequestLoggingMiddleware adds a request ID to the context.
func RequestLoggingMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requestID, _ := generateNewUUID()
		ctx := context.WithValue(r.Context(), requestIDKey, requestID)
		log.Printf("[%s] %s %s", requestID, r.Method, r.URL.Path)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

// CORSHandlingMiddleware sets CORS headers.
func CORSHandlingMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Accept, Content-Type, Content-Length, Accept-Encoding, X-CSRF-Token, Authorization")
		if r.Method == "OPTIONS" {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}

// RateLimiter is a dependency for the rate limiting middleware.
type RateLimiter struct {
	sync.Mutex
	clients map[string]time.Time
}

// RateLimitingMiddlewareFactory creates a rate limiting middleware.
func (rl *RateLimiter) RateLimitingMiddlewareFactory(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ip, _, _ := net.SplitHostPort(r.RemoteAddr)
		rl.Lock()
		if last, found := rl.clients[ip]; found && time.Since(last) < time.Second {
			rl.Unlock()
			http.Error(w, "Calm down!", http.StatusTooManyRequests)
			return
		}
		rl.clients[ip] = time.Now()
		rl.Unlock()
		next.ServeHTTP(w, r)
	})
}

// ResponseTransformer is a decorator for http.ResponseWriter.
type ResponseTransformer struct {
	http.ResponseWriter
	body *bytes.Buffer
}

func (rt *ResponseTransformer) Write(p []byte) (int, error) {
	return rt.body.Write(p)
}

// ResponseTransformationMiddleware wraps the response in a standard envelope.
func ResponseTransformationMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		transformer := &ResponseTransformer{ResponseWriter: w, body: &bytes.Buffer{}}
		next.ServeHTTP(transformer, r)

		var data json.RawMessage = transformer.body.Bytes()
		envelope := map[string]interface{}{
			"data": data,
			"request_id": r.Context().Value(requestIDKey),
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(envelope)
	})
}

// --- Handlers ---

var mockDB = struct {
	Users map[UUID]User
	Posts map[UUID]Post
}{
	Users: make(map[UUID]User),
	Posts: make(map[UUID]Post),
}

func handleGetPosts(w http.ResponseWriter, r *http.Request) *AppError {
	if r.Method != http.MethodGet {
		return &AppError{Code: http.StatusMethodNotAllowed, Message: "Method not allowed"}
	}

	posts := make([]Post, 0, len(mockDB.Posts))
	for _, p := range mockDB.Posts {
		posts = append(posts, p)
	}

	w.Header().Set("Content-Type", "application/json")
	err := json.NewEncoder(w).Encode(posts)
	if err != nil {
		return &AppError{Code: http.StatusInternalServerError, Message: "Failed to encode posts"}
	}
	return nil
}

func handleCreateUser(w http.ResponseWriter, r *http.Request) *AppError {
	if r.Method != http.MethodPost {
		return &AppError{Code: http.StatusMethodNotAllowed, Message: "Method not allowed"}
	}

	var req struct{ Email string `json:"email"`; Password string `json:"password"` }
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		return &AppError{Code: http.StatusBadRequest, Message: "Invalid request payload"}
	}
	if req.Email == "" || req.Password == "" {
		return &AppError{Code: http.StatusBadRequest, Message: "Email and password are required"}
	}

	id, _ := generateNewUUID()
	newUser := User{
		ID: id, Email: req.Email, PasswordHash: "...", Role: USER_ROLE, IsActive: true, CreatedAt: time.Now(),
	}
	mockDB.Users[id] = newUser

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	if err := json.NewEncoder(w).Encode(newUser); err != nil {
		return &AppError{Code: http.StatusInternalServerError, Message: "Failed to encode new user"}
	}
	return nil
}

// --- Main ---

func main() {
	// Setup mock data
	uid, _ := generateNewUUID()
	mockDB.Users[uid] = User{ID: uid, Email: "admin@example.com", Role: ADMIN_ROLE, IsActive: true}
	pid, _ := generateNewUUID()
	mockDB.Posts[pid] = Post{ID: pid, UserID: uid, Title: "Context-based Middleware", Content: "This is a post."}

	// Setup dependencies
	rateLimiter := &RateLimiter{clients: make(map[string]time.Time)}

	// Setup handlers with our custom AppHandler type
	postsHandler := AppHandler(handleGetPosts)
	usersHandler := AppHandler(handleCreateUser)

	// Setup router
	mux := http.NewServeMux()
	
	// Chain middleware. Order: Logging -> CORS -> Rate Limiting -> Transformation -> Handler
	mux.Handle("/posts", RequestLoggingMiddleware(CORSHandlingMiddleware(rateLimiter.RateLimitingMiddlewareFactory(ResponseTransformationMiddleware(postsHandler)))))
	mux.Handle("/users", RequestLoggingMiddleware(CORSHandlingMiddleware(rateLimiter.RateLimitingMiddlewareFactory(usersHandler))))

	log.Println("Starting context-based server on :8083")
	if err := http.ListenAndServe(":8083", mux); err != nil {
		log.Fatalf("Server failed to start: %v", err)
	}
}
</pre>