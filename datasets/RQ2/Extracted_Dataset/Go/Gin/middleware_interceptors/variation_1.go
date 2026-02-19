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

// --- models.go ---

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

// --- middlewares.go ---

// 1. Request Logging Middleware
func RequestLogger() gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		path := c.Request.URL.Path
		raw := c.Request.URL.RawQuery

		c.Next()

		latency := time.Since(start)
		clientIP := c.ClientIP()
		method := c.Request.Method
		statusCode := c.Writer.Status()
		errorMessage := c.Errors.ByType(gin.ErrorTypePrivate).String()

		if raw != "" {
			path = path + "?" + raw
		}

		log.Printf("[GIN] %v | %3d | %13v | %15s | %-7s %#v\n%s",
			start.Format("2006/01/02 - 15:04:05"),
			statusCode,
			latency,
			clientIP,
			method,
			path,
			errorMessage,
		)
	}
}

// 2. CORS Handling Middleware
func CORSMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Writer.Header().Set("Access-Control-Allow-Origin", "*")
		c.Writer.Header().Set("Access-Control-Allow-Credentials", "true")
		c.Writer.Header().Set("Access-Control-Allow-Headers", "Content-Type, Content-Length, Accept-Encoding, X-CSRF-Token, Authorization, accept, origin, Cache-Control, X-Requested-With")
		c.Writer.Header().Set("Access-Control-Allow-Methods", "POST, OPTIONS, GET, PUT, DELETE")

		if c.Request.Method == "OPTIONS" {
			c.AbortWithStatus(204)
			return
		}
		c.Next()
	}
}

// 3. Rate Limiting Middleware
var (
	visitors = make(map[string]time.Time)
	mu       sync.Mutex
)

func RateLimiter(limit time.Duration) gin.HandlerFunc {
	return func(c *gin.Context) {
		mu.Lock()
		defer mu.Unlock()

		ip := c.ClientIP()
		if lastVisit, found := visitors[ip]; found {
			if time.Since(lastVisit) < limit {
				c.AbortWithStatusJSON(http.StatusTooManyRequests, gin.H{"error": "Too many requests. Please try again later."})
				return
			}
		}
		visitors[ip] = time.Now()
		c.Next()
	}
}

// 4. Request/Response Transformation Middleware
func TransformMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		// Request Transformation: Add a request ID
		requestID := uuid.New().String()
		c.Set("RequestID", requestID)
		c.Header("X-Request-ID", requestID)

		c.Next()

		// Response Transformation: Add a response timestamp header
		c.Header("X-Response-Timestamp", time.Now().UTC().Format(time.RFC3339))
	}
}

// 5. Error Handling Middleware
func ErrorHandler() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Next() // Process request

		// After request, check for errors
		if len(c.Errors) > 0 {
			err := c.Errors.Last()
			log.Printf("Error handled: %v", err.Err)

			// A real app would have more sophisticated logic here
			c.AbortWithStatusJSON(http.StatusInternalServerError, gin.H{
				"error":      "An internal server error occurred.",
				"message":    err.Err.Error(),
				"request_id": c.GetString("RequestID"),
			})
		}
	}
}

// --- handlers.go ---

var mockPosts = []Post{
	{ID: uuid.New(), UserID: uuid.New(), Title: "Intro to Go", Content: "Go is a statically typed, compiled language...", Status: PublishedStatus},
	{ID: uuid.New(), UserID: uuid.New(), Title: "Gin Web Framework", Content: "Gin is a high-performance web framework...", Status: PublishedStatus},
}

func getPostsHandler(c *gin.Context) {
	c.JSON(http.StatusOK, mockPosts)
}

func getServerErrorHandler(c *gin.Context) {
	// Simulate a business logic error
	err := errors.New("database connection failed")
	_ = c.Error(err).SetType(gin.ErrorTypePrivate)
	// The ErrorHandler middleware will catch this
}

// --- main.go ---

func main() {
	// Using New() to have full control over middleware order
	router := gin.New()

	// Apply Middleware Globally
	// The order is important!
	router.Use(RequestLogger())
	router.Use(gin.Recovery()) // Use Gin's default recovery middleware to catch panics
	router.Use(ErrorHandler()) // Custom error handler to format responses
	router.Use(CORSMiddleware())
	router.Use(TransformMiddleware())
	router.Use(RateLimiter(1 * time.Second)) // Allow 1 request per second per IP

	// API Routes
	api := router.Group("/api/v1")
	{
		api.GET("/posts", getPostsHandler)
		api.GET("/error", getServerErrorHandler)
	}

	log.Println("Server (Functional Style) starting on port 8080...")
	if err := router.Run(":8080"); err != nil {
		log.Fatalf("Failed to run server: %v", err)
	}
}