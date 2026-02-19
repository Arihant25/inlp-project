<pre>
package main

import (
	"bytes"
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

type UUID string

func newUUID() (UUID, error) {
	b := make([]byte, 16)
	_, err := rand.Read(b)
	if err != nil {
		return "", err
	}
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return UUID(fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:])), nil
}

type User struct {
	ID        UUID      `json:"id"`
	Email     string    `json:"email"`
	PassHash  string    `json:"-"`
	Role      Role      `json:"role"`
	IsActive  bool      `json:"is_active"`
	CreatedAt time.Time `json:"created_at"`
}

type Post struct {
	ID      UUID   `json:"id"`
	UserID  UUID   `json:"user_id"`
	Title   string `json:"title"`
	Content string `json:"content"`
	Status  Status `json:"status"`
}

// --- Middleware as a Slice of Functions ---

// Middleware defines a function that wraps an http.Handler.
type Middleware func(http.Handler) http.Handler

// Chain applies a slice of Middleware to an http.Handler.
func Chain(h http.Handler, middlewares ...Middleware) http.Handler {
	for i := len(middlewares) - 1; i >= 0; i-- {
		h = middlewares[i](h)
	}
	return h
}

// logMW logs requests.
func logMW(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		log.Printf("Request: %s %s", r.Method, r.URL.Path)
		next.ServeHTTP(w, r)
	})
}

// corsMW handles CORS headers.
func corsMW(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type")
		if r.Method == "OPTIONS" {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}

// RateLimiter encapsulates the state for rate limiting.
type RateLimiter struct {
	ips map[string]int
	mu  sync.Mutex
	limit int
}

// newRateLimiterMW creates a rate-limiting middleware.
func newRateLimiterMW(limit int, resetInterval time.Duration) Middleware {
	limiter := &RateLimiter{
		ips:   make(map[string]int),
		limit: limit,
	}

	// Goroutine to reset the counts periodically.
	go func() {
		for {
			time.Sleep(resetInterval)
			limiter.mu.Lock()
			limiter.ips = make(map[string]int)
			limiter.mu.Unlock()
		}
	}()

	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			ip, _, err := net.SplitHostPort(r.RemoteAddr)
			if err != nil {
				http.Error(w, "Internal Server Error", http.StatusInternalServerError)
				return
			}

			limiter.mu.Lock()
			limiter.ips[ip]++
			count := limiter.ips[ip]
			limiter.mu.Unlock()

			if count > limiter.limit {
				http.Error(w, "Rate limit exceeded", http.StatusTooManyRequests)
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}

// errorMW handles panics.
func errorMW(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if r := recover(); r != nil {
				log.Printf("Recovered from panic: %v", r)
				http.Error(w, "An internal error occurred", http.StatusInternalServerError)
			}
		}()
		next.ServeHTTP(w, r)
	})
}

// responseWrapper is a decorator for http.ResponseWriter to capture output.
type responseWrapper struct {
	http.ResponseWriter
	body *bytes.Buffer
}

func (rw *responseWrapper) Write(b []byte) (int, error) {
	return rw.body.Write(b)
}

// transformMW wraps the JSON response in a `result` object.
func transformMW(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		rw := &responseWrapper{
			ResponseWriter: w,
			body:           &bytes.Buffer{},
		}
		next.ServeHTTP(rw, r)

		var response map[string]interface{}
		// Check if the original response was JSON
		if err := json.Unmarshal(rw.body.Bytes(), &response); err != nil {
			// If not JSON, pass through
			w.Write(rw.body.Bytes())
			return
		}

		wrappedResponse := struct {
			Result interface{} `json:"result"`
		}{
			Result: response,
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(wrappedResponse)
	})
}

// --- Handlers & Main ---

var db = struct {
	users map[UUID]User
	posts map[UUID]Post
	sync.RWMutex
}{
	users: make(map[UUID]User),
	posts: make(map[UUID]Post),
}

func getUsers(w http.ResponseWriter, r *http.Request) {
	db.RLock()
	defer db.RUnlock()
	
	userList := make([]User, 0, len(db.users))
	for _, u := range db.users {
		userList = append(userList, u)
	}
	
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(userList)
}

func getPosts(w http.ResponseWriter, r *http.Request) {
	db.RLock()
	defer db.RUnlock()

	postList := make([]Post, 0, len(db.posts))
	for _, p := range db.posts {
		postList = append(postList, p)
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(postList)
}

func main() {
	// Seed data
	userID, _ := newUUID()
	db.users[userID] = User{ID: userID, Email: "test@test.com", Role: USER, IsActive: true}
	postID, _ := newUUID()
	db.posts[postID] = Post{ID: postID, UserID: userID, Title: "A Post Title", Content: "Some content here."}

	mux := http.NewServeMux()

	// Define middleware chains
	apiMiddlewares := []Middleware{
		logMW,
		corsMW,
		newRateLimiterMW(100, time.Minute), // 100 requests per minute
	}

	// Apply chains to handlers
	// Note: errorMW is outermost, transformMW is innermost before the handler
	mux.Handle("/users", Chain(errorMW(transformMW(http.HandlerFunc(getUsers))), apiMiddlewares...))
	mux.Handle("/posts", Chain(errorMW(transformMW(http.HandlerFunc(getPosts))), apiMiddlewares...))

	log.Println("Starting server with middleware slices on :8082")
	if err := http.ListenAndServe(":8082", mux); err != nil {
		log.Fatal(err)
	}
}
</pre>