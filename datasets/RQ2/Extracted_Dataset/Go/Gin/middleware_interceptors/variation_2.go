package main

import (
	"errors"
	"log"
	"net/http"
	"os"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

// --- domain/models.go ---

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

// --- middleware/provider.go ---

type MiddlewareProvider struct {
	logger         *log.Logger
	rateLimitStore map[string]time.Time
	mu             sync.RWMutex
	rateLimit      time.Duration
}

func NewMiddlewareProvider(logger *log.Logger, rateLimit time.Duration) *MiddlewareProvider {
	return &MiddlewareProvider{
		logger:         logger,
		rateLimitStore: make(map[string]time.Time),
		rateLimit:      rateLimit,
	}
}

// 1. Request Logging Middleware
func (mp *MiddlewareProvider) LoggingMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		startTime := time.Now()
		c.Next()
		duration := time.Since(startTime)
		mp.logger.Printf(
			"method=%s path=%s status=%d duration=%s client_ip=%s",
			c.Request.Method,
			c.Request.URL.Path,
			c.Writer.Status(),
			duration,
			c.ClientIP(),
		)
	}
}

// 2. CORS Handling Middleware
func (mp *MiddlewareProvider) CORSMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Header("Access-Control-Allow-Origin", "*")
		c.Header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		c.Header("Access-Control-Allow-Headers", "Origin, Content-Type, Authorization")
		if c.Request.Method == "OPTIONS" {
			c.AbortWithStatus(http.StatusNoContent)
			return
		}
		c.Next()
	}
}

// 3. Rate Limiting Middleware
func (mp *MiddlewareProvider) RateLimiterMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		ip := c.ClientIP()

		mp.mu.Lock()
		defer mp.mu.Unlock()

		if lastRequest, exists := mp.rateLimitStore[ip]; exists {
			if time.Since(lastRequest) < mp.rateLimit {
				c.AbortWithStatusJSON(http.StatusTooManyRequests, gin.H{"error": "Rate limit exceeded"})
				return
			}
		}
		mp.rateLimitStore[ip] = time.Now()
		c.Next()
	}
}

// 4. Request/Response Transformation Middleware
func (mp *MiddlewareProvider) TransformationMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		// Request Transformation: Add a transaction ID
		txID := uuid.New().String()
		c.Set("TransactionID", txID)
		c.Header("X-Transaction-ID", txID)

		c.Next()

		// Response Transformation: Add a server name header
		c.Header("X-Server-Name", "MyAwesomeServer")
	}
}

// 5. Error Handling Middleware
func (mp *MiddlewareProvider) ErrorHandlingMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Next()

		if len(c.Errors) > 0 {
			err := c.Errors.Last().Err
			mp.logger.Printf("An error occurred: %v", err)

			c.AbortWithStatusJSON(http.StatusInternalServerError, gin.H{
				"error_message":  err.Error(),
				"transaction_id": c.GetString("TransactionID"),
			})
		}
	}
}

// --- api/handlers.go ---

var mockPostsStore = []Post{
	{ID: uuid.New(), UserID: uuid.New(), Title: "OOP in Go", Content: "...", Status: PublishedStatus},
	{ID: uuid.New(), UserID: uuid.New(), Title: "Dependency Injection", Content: "...", Status: PublishedStatus},
}

func handleGetPosts(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{"posts": mockPostsStore})
}

func handleGetError(c *gin.Context) {
	_ = c.Error(errors.New("simulated service layer error"))
}

// --- main.go ---

func main() {
	// Setup dependencies
	logger := log.New(os.Stdout, "[API] ", log.LstdFlags)
	middlewareProvider := NewMiddlewareProvider(logger, 2*time.Second) // 1 request every 2 seconds

	// Setup Gin router
	router := gin.New()

	// Register middleware using the provider
	router.Use(middlewareProvider.LoggingMiddleware())
	router.Use(gin.Recovery())
	router.Use(middlewareProvider.ErrorHandlingMiddleware()) // Error handler should be early in the chain
	router.Use(middlewareProvider.CORSMiddleware())
	router.Use(middlewareProvider.TransformationMiddleware())
	router.Use(middlewareProvider.RateLimiterMiddleware())

	// Setup routes
	v1 := router.Group("/v1")
	{
		v1.GET("/posts", handleGetPosts)
		v1.GET("/force-error", handleGetError)
	}

	logger.Println("Starting server (OOP Style) on :8080")
	if err := router.Run(":8080"); err != nil {
		logger.Fatalf("Could not start server: %s\n", err)
	}
}