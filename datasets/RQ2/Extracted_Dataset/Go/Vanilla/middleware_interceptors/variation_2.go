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

type UUID string

func GenerateUUID() (UUID, error) {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return UUID(fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:])), nil
}

type User struct {
	Id           UUID      `json:"id"`
	Email        string    `json:"email"`
	PasswordHash string    `json:"-"`
	Role         UserRole  `json:"role"`
	IsActive     bool      `json:"is_active"`
	CreatedAt    time.Time `json:"created_at"`
}

type Post struct {
	Id      UUID       `json:"id"`
	UserId  UUID       `json:"user_id"`
	Title   string     `json:"title"`
	Content string     `json:"content"`
	Status  PostStatus `json:"status"`
}

// --- Object-Oriented Server and Middleware ---

type RateLimiter struct {
	visitors map[string]time.Time
	mu       sync.Mutex
	requests int
	window   time.Duration
}

func NewRateLimiter(requests int, window time.Duration) *RateLimiter {
	return &RateLimiter{
		visitors: make(map[string]time.Time),
		requests: requests,
		window:   window,
	}
}

func (rl *RateLimiter) Allow(ip string) bool {
	rl.mu.Lock()
	defer rl.mu.Unlock()

	if lastSeen, exists := rl.visitors[ip]; exists {
		if time.Since(lastSeen) < rl.window/time.Duration(rl.requests) {
			return false
		}
	}
	rl.visitors[ip] = time.Now()
	return true
}

type AppServer struct {
	router    *http.ServeMux
	limiter   *RateLimiter
	mockUsers map[UUID]User
	mockPosts map[UUID]Post
}

func NewAppServer() *AppServer {
	server := &AppServer{
		router:    http.NewServeMux(),
		limiter:   NewRateLimiter(20, time.Minute),
		mockUsers: make(map[UUID]User),
		mockPosts: make(map[UUID]Post),
	}
	server.setupData()
	server.routes()
	return server
}

func (s *AppServer) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	s.router.ServeHTTP(w, r)
}

// Middleware Methods
func (s *AppServer) LogRequest(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		log.Printf("-> %s %s from %s", r.Method, r.URL.Path, r.RemoteAddr)
		next.ServeHTTP(w, r)
	})
}

func (s *AppServer) HandleCORS(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		if r.Method == http.MethodOptions {
			w.Header().Set("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
			w.Header().Set("Access-Control-Allow-Headers", "Content-Type")
			w.WriteHeader(http.StatusOK)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (s *AppServer) RateLimit(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ip, _, err := net.SplitHostPort(r.RemoteAddr)
		if err != nil {
			http.Error(w, "Internal Server Error", http.StatusInternalServerError)
			return
		}
		if !s.limiter.Allow(ip) {
			http.Error(w, http.StatusText(http.StatusTooManyRequests), http.StatusTooManyRequests)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (s *AppServer) RecoverPanic(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if err := recover(); err != nil {
				w.Header().Set("Connection", "close")
				log.Printf("PANIC: %v", err)
				http.Error(w, "Something went wrong", http.StatusInternalServerError)
			}
		}()
		next.ServeHTTP(w, r)
	})
}

// Decorator for response transformation
type responseTransformer struct {
	http.ResponseWriter
	buffer bytes.Buffer
}

func (rt *responseTransformer) Write(b []byte) (int, error) {
	return rt.buffer.Write(b)
}

func (s *AppServer) TransformResponse(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		transformer := &responseTransformer{ResponseWriter: w}
		next.ServeHTTP(transformer, r)

		var originalBody interface{}
		if err := json.Unmarshal(transformer.buffer.Bytes(), &originalBody); err != nil {
			// Not JSON, write as is
			w.Write(transformer.buffer.Bytes())
			return
		}

		responseEnvelope := map[string]interface{}{
			"payload": originalBody,
			"server_time": time.Now().UTC().Format(time.RFC3339),
		}

		w.Header().Set("Content-Type", "application/json")
		finalBody, _ := json.Marshal(responseEnvelope)
		w.Write(finalBody)
	})
}

// Routes and Handlers
func (s *AppServer) routes() {
	getPosts := http.HandlerFunc(s.handleGetPosts)
	createUser := http.HandlerFunc(s.handleCreateUser)

	s.router.Handle("/posts", s.applyMiddleware(getPosts, s.TransformResponse, s.RateLimit, s.HandleCORS, s.LogRequest))
	s.router.Handle("/users", s.applyMiddleware(createUser, s.RateLimit, s.HandleCORS, s.LogRequest))
}

// Helper to chain middleware
func (s *AppServer) applyMiddleware(h http.Handler, mws ...func(http.Handler) http.Handler) http.Handler {
	// Apply in reverse order so the first in the list is the outermost
	for i := len(mws) - 1; i >= 0; i-- {
		h = mws[i](h)
	}
	// The panic recoverer should always be the absolute outermost
	return s.RecoverPanic(h)
}

func (s *AppServer) handleGetPosts(w http.ResponseWriter, r *http.Request) {
	posts := make([]Post, 0, len(s.mockPosts))
	for _, p := range s.mockPosts {
		posts = append(posts, p)
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(posts)
}

func (s *AppServer) handleCreateUser(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Only POST method is allowed", http.StatusMethodNotAllowed)
		return
	}
	
	var userData struct {
		Email string `json:"email"`
	}
	if err := json.NewDecoder(r.Body).Decode(&userData); err != nil {
		http.Error(w, "Invalid request body", http.StatusBadRequest)
		return
	}

	newId, _ := GenerateUUID()
	newUser := User{
		Id: newId,
		Email: userData.Email,
		Role: RoleUser,
		IsActive: false,
		CreatedAt: time.Now().UTC(),
	}
	s.mockUsers[newId] = newUser

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(newUser)
}

func (s *AppServer) setupData() {
	adminId, _ := GenerateUUID()
	s.mockUsers[adminId] = User{Id: adminId, Email: "admin@corp.com", Role: RoleAdmin, IsActive: true}
	postId, _ := GenerateUUID()
	s.mockPosts[postId] = Post{Id: postId, UserId: adminId, Title: "Server-Side Post", Content: "Content from AppServer"}
}

// --- Main ---
func main() {
	server := NewAppServer()
	log.Println("Starting OOP server on port 8081")
	if err := http.ListenAndServe(":8081", server); err != nil {
		log.Fatalf("Server failed: %s", err)
	}
}
</pre>