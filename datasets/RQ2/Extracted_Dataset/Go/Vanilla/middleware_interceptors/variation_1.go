<pre>
package main

import (
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

// UUID represents a universally unique identifier.
type UUID string

// NewUUID generates a new random UUID.
func NewUUID() (UUID, error) {
	b := make([]byte, 16)
	_, err := rand.Read(b)
	if err != nil {
		return "", err
	}
	b[6] = (b[6] & 0x0f) | 0x40 // Version 4
	b[8] = (b[8] & 0x3f) | 0x80 // Variant 1
	return UUID(fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:])), nil
}

type User struct {
	ID           UUID      `json:"id"`
	Email        string    `json:"email"`
	PasswordHash string    `json:"-"`
	UserRole     Role      `json:"role"`
	IsActive     bool      `json:"is_active"`
	CreatedAt    time.Time `json:"created_at"`
}

type Post struct {
	ID      UUID      `json:"id"`
	UserID  UUID      `json:"user_id"`
	Title   string    `json:"title"`
	Content string    `json:"content"`
	Status  Status    `json:"status"`
}

// --- Mock Data ---

var mockUsers = make(map[UUID]User)
var mockPosts = make(map[UUID]Post)

func setupMockData() {
	adminID, _ := NewUUID()
	userID, _ := NewUUID()

	mockUsers[adminID] = User{
		ID:           adminID,
		Email:        "admin@example.com",
		PasswordHash: "hashed_password",
		UserRole:     ADMIN,
		IsActive:     true,
		CreatedAt:    time.Now().UTC(),
	}
	mockUsers[userID] = User{
		ID:           userID,
		Email:        "user@example.com",
		PasswordHash: "hashed_password",
		UserRole:     USER,
		IsActive:     true,
		CreatedAt:    time.Now().UTC(),
	}

	postID, _ := NewUUID()
	mockPosts[postID] = Post{
		ID:      postID,
		UserID:  adminID,
		Title:   "First Post",
		Content: "This is the content of the first post.",
		Status:  PUBLISHED,
	}
}

// --- Middleware Implementation (Functional Chaining) ---

// loggingMiddleware logs the incoming HTTP request.
func loggingMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		log.Printf("Started %s %s", r.Method, r.URL.Path)
		next.ServeHTTP(w, r)
		log.Printf("Completed in %v", time.Since(start))
	})
}

// corsMiddleware adds CORS headers to the response.
func corsMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")
		if r.Method == "OPTIONS" {
			w.WriteHeader(http.StatusOK)
			return
		}
		next.ServeHTTP(w, r)
	})
}

// rateLimiterState holds the state for our simple rate limiter.
var (
	rateLimiterState = make(map[string]time.Time)
	rateLimiterMutex = &sync.Mutex{}
	rateLimit        = 10 // requests
	rateLimitWindow  = time.Minute
)

// rateLimitMiddleware limits requests per IP.
func rateLimitMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ip, _, err := net.SplitHostPort(r.RemoteAddr)
		if err != nil {
			http.Error(w, "Internal Server Error", http.StatusInternalServerError)
			return
		}

		rateLimiterMutex.Lock()
		// This is a very basic implementation. A real one would use a token bucket.
		if lastSeen, ok := rateLimiterState[ip]; ok {
			if time.Since(lastSeen) < rateLimitWindow/time.Duration(rateLimit) {
				rateLimiterMutex.Unlock()
				http.Error(w, "Too Many Requests", http.StatusTooManyRequests)
				return
			}
		}
		rateLimiterState[ip] = time.Now()
		rateLimiterMutex.Unlock()

		next.ServeHTTP(w, r)
	})
}

// errorHandlingMiddleware recovers from panics and returns a JSON error.
func errorHandlingMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if err := recover(); err != nil {
				log.Printf("Panic recovered: %v", err)
				w.Header().Set("Content-Type", "application/json")
				w.WriteHeader(http.StatusInternalServerError)
				json.NewEncoder(w).Encode(map[string]string{"error": "An unexpected error occurred"})
			}
		}()
		next.ServeHTTP(w, r)
	})
}

// responseWrapper captures the response body and status code.
type responseWrapper struct {
	http.ResponseWriter
	body       []byte
	statusCode int
}

func (rw *responseWrapper) Write(b []byte) (int, error) {
	rw.body = append(rw.body, b...)
	return len(b), nil
}

func (rw *responseWrapper) WriteHeader(statusCode int) {
	rw.statusCode = statusCode
}

// transformationMiddleware wraps the response in a standard JSON envelope.
func transformationMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Don't transform if the request is not for our API
		if r.URL.Path != "/api/posts" {
			next.ServeHTTP(w, r)
			return
		}

		wrapper := &responseWrapper{ResponseWriter: w, statusCode: http.StatusOK}
		next.ServeHTTP(wrapper, r)

		var responseData interface{}
		// If there was a body, try to unmarshal it.
		if len(wrapper.body) > 0 {
			if err := json.Unmarshal(wrapper.body, &responseData); err != nil {
				// If unmarshal fails, it might not be JSON. Treat as raw data.
				responseData = string(wrapper.body)
			}
		}

		envelope := struct {
			Data   interface{} `json:"data"`
			Status int         `json:"status"`
		}{
			Data:   responseData,
			Status: wrapper.statusCode,
		}

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(wrapper.statusCode) // Write the actual status code to the real ResponseWriter
		json.NewEncoder(w).Encode(envelope)
	})
}

// --- Handlers ---

func getPostsHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}
	
	posts := make([]Post, 0, len(mockPosts))
	for _, post := range mockPosts {
		posts = append(posts, post)
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(posts)
}

func panicHandler(w http.ResponseWriter, r *http.Request) {
	panic("This is a deliberate panic to test error handling.")
}

// --- Main Application ---

func main() {
	setupMockData()

	mux := http.NewServeMux()

	// Create handlers
	postsHandler := http.HandlerFunc(getPostsHandler)
	panicHandler := http.HandlerFunc(panicHandler)

	// Chain middleware
	// The order is important: error handling should be outermost, then logging, etc.
	chainedPostsHandler := errorHandlingMiddleware(
		loggingMiddleware(
			corsMiddleware(
				rateLimitMiddleware(
					transformationMiddleware(postsHandler),
				),
			),
		),
	)
	
	chainedPanicHandler := errorHandlingMiddleware(
		loggingMiddleware(
			corsMiddleware(
				rateLimitMiddleware(panicHandler),
			),
		),
	)

	mux.Handle("/api/posts", chainedPostsHandler)
	mux.Handle("/api/panic", chainedPanicHandler)

	log.Println("Server starting on :8080")
	if err := http.ListenAndServe(":8080", mux); err != nil {
		log.Fatalf("Could not start server: %s\n", err)
	}
}
</pre>