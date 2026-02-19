package main

import (
	"encoding/json"
	"log"
	"net/http"
	"os"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/labstack/echo/v4"
	"github.com/labstack/echo/v4/middleware"
	"golang.org/x/time/rate"
)

// --- Domain Schema ---

type UserRole string

const (
	AdminRole UserRole = "ADMIN"
	UserRole_ UserRole = "USER"
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

// --- Mock Data ---

var mockUser = User{
	ID:           uuid.New(),
	Email:        "oop.user@example.com",
	PasswordHash: "another_hash",
	Role:         AdminRole,
	IsActive:     true,
	CreatedAt:    time.Now(),
}

// --- API Handlers ---

type APIHandler struct {
	// In a real app, this would hold dependencies like a database connection.
}

func (h *APIHandler) GetUser(c echo.Context) error {
	return c.JSON(http.StatusOK, mockUser)
}

func (h *APIHandler) CreatePost(c echo.Context) error {
	var p Post
	if err := c.Bind(&p); err != nil {
		return echo.NewHTTPError(http.StatusBadRequest, "Invalid request body")
	}
	p.ID = uuid.New()
	p.UserID = mockUser.ID
	return c.JSON(http.StatusCreated, p)
}

func (h *APIHandler) TriggerError(c echo.Context) error {
	return &CustomError{
		Code:    http.StatusServiceUnavailable,
		Message: "Simulated database connection failure",
	}
}

// --- Custom Error Type ---
type CustomError struct {
	Code    int
	Message string
}

func (e *CustomError) Error() string {
	return e.Message
}

// --- OOP Middleware Provider ---

// MiddlewareProvider encapsulates middleware logic and dependencies.
type MiddlewareProvider struct {
	logger       *log.Logger
	rateLimiters *sync.Map // For IP-based rate limiting
}

func NewMiddlewareProvider(logger *log.Logger) *MiddlewareProvider {
	return &MiddlewareProvider{
		logger:       logger,
		rateLimiters: &sync.Map{},
	}
}

// Logging is a method that provides request logging middleware.
func (m *MiddlewareProvider) Logging(next echo.HandlerFunc) echo.HandlerFunc {
	return func(c echo.Context) error {
		start := time.Now()
		err := next(c)
		stop := time.Now()
		latency := stop.Sub(start)
		req := c.Request()
		res := c.Response()

		m.logger.Printf(
			`{"method":"%s", "uri":"%s", "status":%d, "latency":"%s", "ip":"%s"}`,
			req.Method,
			req.RequestURI,
			res.Status,
			latency.String(),
			c.RealIP(),
		)
		return err
	}
}

// RateLimit provides a custom IP-based rate limiter.
func (m *MiddlewareProvider) RateLimit(next echo.HandlerFunc) echo.HandlerFunc {
	return func(c echo.Context) error {
		ip := c.RealIP()
		limiter, _ := m.rateLimiters.LoadOrStore(ip, rate.NewLimiter(rate.Every(time.Second), 5))

		if !limiter.(*rate.Limiter).Allow() {
			return echo.NewHTTPError(http.StatusTooManyRequests, "Rate limit exceeded")
		}
		return next(c)
	}
}

// ResponseTransformer wraps successful responses.
func (m *MiddlewareProvider) ResponseTransformer(next echo.HandlerFunc) echo.HandlerFunc {
	return func(c echo.Context) error {
		if err := next(c); err != nil {
			return err
		}

		// This is a simpler but less robust way to transform than Variation 1.
		// It relies on the handler setting c.Get("data") for transformation.
		// This is a stylistic choice.
		if data := c.Get("data"); data != nil {
			return c.JSON(http.StatusOK, map[string]interface{}{
				"status": "success",
				"data":   data,
			})
		}
		return nil
	}
}

// ErrorHandler provides centralized error handling.
func (m *MiddlewareProvider) ErrorHandler(err error, c echo.Context) {
	code := http.StatusInternalServerError
	message := "An unexpected error occurred"

	if he, ok := err.(*echo.HTTPError); ok {
		code = he.Code
		if msg, ok := he.Message.(string); ok {
			message = msg
		}
	} else if ce, ok := err.(*CustomError); ok {
		code = ce.Code
		message = ce.Message
	} else {
		// Log untyped errors for debugging
		m.logger.Printf("UNHANDLED_ERROR: %v", err)
	}

	if !c.Response().Committed {
		if err := c.JSON(code, map[string]interface{}{"status": "error", "message": message}); err != nil {
			m.logger.Printf("Error writing error response: %v", err)
		}
	}
}

// main function: The OOP Architect
// This style uses structs and methods to organize middleware and handlers,
// making dependencies explicit and improving testability.
func main() {
	e := echo.New()

	// Setup dependencies
	logger := log.New(os.Stdout, "API_LOGGER: ", log.LstdFlags)
	mwProvider := NewMiddlewareProvider(logger)
	apiHandler := &APIHandler{}

	// --- Middleware Registration ---
	e.Use(mwProvider.Logging)
	e.Use(middleware.Recover())
	e.Use(middleware.CORS()) // Default CORS
	e.Use(mwProvider.RateLimit)
	// Note: ResponseTransformer is not used globally here, as it depends on handler cooperation.
	// This is a valid design choice in some architectures. We will apply it selectively.

	// --- Error Handling ---
	e.HTTPErrorHandler = mwProvider.ErrorHandler

	// --- Route Definitions ---
	// We'll create a custom response wrapper for the successful route.
	// This shows a different way to apply transformations.
	wrappedGetUser := func(c echo.Context) error {
		res := map[string]interface{}{
			"status": "success",
			"data":   mockUser,
		}
		return c.JSON(http.StatusOK, res)
	}

	e.GET("/users/me", wrappedGetUser)
	e.POST("/posts", apiHandler.CreatePost)
	e.GET("/error", apiHandler.TriggerError)

	log.Println("Variation 2: OOP Architect Server starting on :1324")
	if err := e.Start(":1324"); err != nil && err != http.ErrServerClosed {
		e.Logger.Fatal(err)
	}
}