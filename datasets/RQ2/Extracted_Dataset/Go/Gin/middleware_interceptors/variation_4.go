package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/rs/zerolog"
	"golang.org/x/time/rate"
)

// --- DOMAIN ---

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

// --- MIDDLEWARE ---

// 1. Structured Logging Middleware (using zerolog)
func StructuredLogger(log zerolog.Logger) gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		path := c.Request.URL.Path
		query := c.Request.URL.RawQuery

		c.Next()

		latency := time.Since(start)

		event := log.Info()
		if len(c.Errors) > 0 {
			event = log.Error().Err(c.Errors.Last())
		}

		event.
			Int("status", c.Writer.Status()).
			Str("method", c.Request.Method).
			Str("path", path).
			Str("query", query).
			Str("ip", c.ClientIP()).
			Str("user_agent", c.Request.UserAgent()).
			Dur("latency", latency).
			Msg("request handled")
	}
}

// 2. Configurable CORS Middleware (using closures)
func CORSMiddleware(allowedOrigin string) gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Writer.Header().Set("Access-Control-Allow-Origin", allowedOrigin)
		c.Writer.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
		c.Writer.Header().Set("Access-Control-Allow-Headers", "Accept, Authorization, Content-Type, X-CSRF-Token")
		if c.Request.Method == "OPTIONS" {
			c.AbortWithStatus(http.StatusNoContent)
			return
		}
		c.Next()
	}
}

// 3. Rate Limiting Middleware (using golang.org/x/time/rate)
func RateLimiter(r rate.Limit, b int) gin.HandlerFunc {
	var (
		mu      sync.Mutex
		clients = make(map[string]*rate.Limiter)
	)
	return func(c *gin.Context) {
		mu.Lock()
		ip := c.ClientIP()
		if _, found := clients[ip]; !found {
			clients[ip] = rate.NewLimiter(r, b)
		}
		limiter := clients[ip]
		mu.Unlock()

		if !limiter.Allow() {
			c.AbortWithStatusJSON(http.StatusTooManyRequests, gin.H{"error": "too many requests"})
			return
		}
		c.Next()
	}
}

// 4. Response Transformation Middleware (advanced body rewrite)
type responseBodyWriter struct {
	gin.ResponseWriter
	body *bytes.Buffer
}

func (w responseBodyWriter) Write(b []byte) (int, error) {
	return w.body.Write(b)
}

func ResponseTransformer() gin.HandlerFunc {
	return func(c *gin.Context) {
		reqID := uuid.New().String()
		c.Set("RequestID", reqID)

		rbw := &responseBodyWriter{body: bytes.NewBufferString(""), ResponseWriter: c.Writer}
		c.Writer = rbw

		c.Next()

		// Only wrap successful JSON responses
		if c.Writer.Status() >= 200 && c.Writer.Status() < 300 && c.Writer.Header().Get("Content-Type") == "application/json; charset=utf-8" {
			var originalBody json.RawMessage
			if err := json.Unmarshal(rbw.body.Bytes(), &originalBody); err == nil {
				wrappedResponse := gin.H{
					"meta":    gin.H{"request_id": reqID, "timestamp": time.Now().UTC()},
					"payload": originalBody,
				}
				newBody, _ := json.Marshal(wrappedResponse)
				c.Writer.Header().Set("Content-Length", fmt.Sprint(len(newBody)))
				_, _ = rbw.ResponseWriter.Write(newBody)
				return
			}
		}
		// For non-200, non-JSON, or failed parsing, write original body back
		_, _ = rbw.ResponseWriter.Write(rbw.body.Bytes())
	}
}

// 5. Error Handling Middleware (with error type mapping)
var ErrNotFound = errors.New("resource not found")

func ErrorHandler() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Next()
		err := c.Errors.Last()
		if err == nil {
			return
		}

		var status int
		var message string

		switch {
		case errors.Is(err.Err, ErrNotFound):
			status = http.StatusNotFound
			message = err.Err.Error()
		default:
			status = http.StatusInternalServerError
			message = "An internal error occurred"
		}
		c.AbortWithStatusJSON(status, gin.H{"error": message, "request_id": c.GetString("RequestID")})
	}
}

// --- HANDLERS ---
func getPosts(c *gin.Context) {
	posts := []Post{
		{ID: uuid.New(), Title: "Minimalist Go", Content: "Less is more.", Status: PublishedStatus},
	}
	c.JSON(http.StatusOK, posts)
}

func getSpecificError(c *gin.Context) {
	_ = c.Error(ErrNotFound)
}

// --- MAIN ---
func main() {
	log := zerolog.New(os.Stdout).With().Timestamp().Logger()
	gin.SetMode(gin.ReleaseMode)
	r := gin.New()

	// Attach middleware in a deliberate order
	r.Use(StructuredLogger(log))
	r.Use(gin.Recovery())
	r.Use(ErrorHandler())
	r.Use(ResponseTransformer())
	r.Use(CORSMiddleware("http://localhost:3000"))
	r.Use(RateLimiter(rate.Every(1*time.Second), 5)) // 5 req/s burst

	// Routes
	api := r.Group("/api")
	{
		api.GET("/posts", getPosts)
		api.GET("/notfound", getSpecificError)
	}

	log.Info().Msg("Server (Minimalist/Utility Style) starting on port 8080")
	if err := r.Run(":8080"); err != nil {
		log.Fatal().Err(err).Msg("Server failed to start")
	}
}