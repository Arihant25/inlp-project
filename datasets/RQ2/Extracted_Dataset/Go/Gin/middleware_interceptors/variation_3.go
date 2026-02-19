package main

import (
	"errors"
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

// --- FILE: pkg/models/domain.go ---
// package models
type UserRole string

const (
	AdminRole UserRole = "ADMIN"
	UserRole  UserRole = "USER"
)

type User struct {
	ID           uuid.UUID `json:"id"`
	Email        string    `json:"email"`
	PasswordHash string    `json:"-"`
	Role         UserRole  `json:"role"`
	IsActive     bool      `json:"is_active"`
	CreatedAt    time.Time `json:"created_at"`
}

type PostStatus string

const (
	DraftStatus     PostStatus = "DRAFT"
	PublishedStatus PostStatus = "PUBLISHED"
)

type Post struct {
	ID      uuid.UUID  `json:"id"`
	UserID  uuid.UUID  `json:"user_id"`
	Title   string     `json:"title"`
	Content string     `json:"content"`
	Status  PostStatus `json:"status"`
}

// --- FILE: pkg/middleware/logging/logging.go ---
// package logging
func NewLoggingMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		t := time.Now()
		c.Next()
		latency := time.Since(t)
		log.Printf("path=%s method=%s status=%d latency=%s", c.Request.URL.Path, c.Request.Method, c.Writer.Status(), latency)
	}
}

// --- FILE: pkg/middleware/cors/cors.go ---
// package cors
func NewCORSMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Writer.Header().Set("Access-Control-Allow-Origin", "*")
		c.Writer.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		c.Writer.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")
		if c.Request.Method == "OPTIONS" {
			c.AbortWithStatus(http.StatusNoContent)
			return
		}
		c.Next()
	}
}

// --- FILE: pkg/middleware/ratelimit/ratelimit.go ---
// package ratelimit
type inMemoryStore struct {
	visitors map[string]time.Time
	mu       sync.Mutex
}

func (s *inMemoryStore) Allow(ip string, limit time.Duration) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if lastVisit, found := s.visitors[ip]; found {
		if time.Since(lastVisit) < limit {
			return false
		}
	}
	s.visitors[ip] = time.Now()
	return true
}

func NewRateLimitMiddleware(limitPerSecond int) gin.HandlerFunc {
	store := &inMemoryStore{visitors: make(map[string]time.Time)}
	limitDuration := time.Second / time.Duration(limitPerSecond)

	return func(c *gin.Context) {
		if !store.Allow(c.ClientIP(), limitDuration) {
			c.AbortWithStatusJSON(http.StatusTooManyRequests, gin.H{"message": "Too many requests"})
			return
		}
		c.Next()
	}
}

// --- FILE: pkg/middleware/transform/transform.go ---
// package transform
func NewTransformMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		// Request transform: add a unique ID
		c.Set("request_id", uuid.New().String())
		c.Next()
		// Response transform: add a custom header
		if id, exists := c.Get("request_id"); exists {
			c.Header("X-Request-ID", id.(string))
		}
	}
}

// --- FILE: pkg/middleware/errorhandler/errorhandler.go ---
// package errorhandler
type ErrorResponse struct {
	Error   string `json:"error"`
	Details string `json:"details,omitempty"`
}

func NewErrorHandlerMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Next()
		if len(c.Errors) > 0 {
			err := c.Errors.ByType(gin.ErrorTypePrivate).Last()
			if err != nil {
				log.Printf("Internal error: %v", err.Err)
				c.AbortWithStatusJSON(http.StatusInternalServerError, ErrorResponse{
					Error:   "An unexpected error occurred",
					Details: err.Err.Error(),
				})
			}
		}
	}
}

// --- FILE: pkg/api/handlers.go ---
// package api
var postsDB = []Post{
	{ID: uuid.New(), UserID: uuid.New(), Title: "Modular Architecture", Content: "...", Status: PublishedStatus},
}

func GetPostsHandler(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{"data": postsDB})
}

func CauseErrorHandler(c *gin.Context) {
	_ = c.Error(errors.New("a wild error appeared")).SetType(gin.ErrorTypePrivate)
}

// --- FILE: cmd/server/main.go ---
// package main
func main() {
	engine := gin.New()

	// Wire up middleware from their respective packages
	engine.Use(NewLoggingMiddleware())
	engine.Use(gin.Recovery())
	engine.Use(NewErrorHandlerMiddleware())
	engine.Use(NewCORSMiddleware())
	engine.Use(NewTransformMiddleware())
	engine.Use(NewRateLimitMiddleware(5)) // 5 requests per second

	// Setup routes
	routerGroup := engine.Group("/api")
	{
		routerGroup.GET("/posts", GetPostsHandler)
		routerGroup.GET("/error", CauseErrorHandler)
	}

	log.Println("Modular server starting on :8080")
	if err := engine.Run(":8080"); err != nil {
		log.Fatalf("Server failed to start: %v", err)
	}
}