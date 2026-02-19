package main

import (
	"context"
	"encoding/json"
	"log"
	"net/http"
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
	PasswordHash string    `json:"-"` // Omit from JSON responses
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
	Email:        "test.user@example.com",
	PasswordHash: "some_hash",
	Role:         UserRole_,
	IsActive:     true,
	CreatedAt:    time.Now(),
}

// --- API Handlers ---

func getUserHandler(c echo.Context) error {
	return c.JSON(http.StatusOK, mockUser)
}

func createPostHandler(c echo.Context) error {
	var post Post
	if err := c.Bind(&post); err != nil {
		return echo.NewHTTPError(http.StatusBadRequest, "Invalid post data")
	}
	post.ID = uuid.New()
	post.UserID = mockUser.ID // Assume authenticated user
	post.Status = DraftStatus
	return c.JSON(http.StatusCreated, post)
}

func getErroringHandler(c echo.Context) error {
	// Simulate a database error or other internal issue
	return echo.NewHTTPError(http.StatusInternalServerError, "Something went terribly wrong")
}

// --- Custom Middleware ---

// ResponseTransformerMiddleware wraps successful JSON responses in a standard structure.
func ResponseTransformerMiddleware(next echo.HandlerFunc) echo.HandlerFunc {
	return func(c echo.Context) error {
		// We use a custom response writer to capture the response body
		res := c.Response()
		w := &responseBodyWriter{ResponseWriter: res.Writer, body: []byte{}}
		res.Writer = w

		err := next(c)
		if err != nil {
			// Let the error handler deal with it
			return err
		}

		// Only transform successful JSON responses
		if res.Status >= 200 && res.Status < 300 && res.Header().Get(echo.HeaderContentType) == echo.MIMEApplicationJSONCharsetUTF8 {
			var originalBody interface{}
			if err := json.Unmarshal(w.body, &originalBody); err != nil {
				// If we can't unmarshal, something is wrong, pass it through
				res.Writer = w.ResponseWriter
				res.Write(w.body)
				return nil
			}

			responseWrapper := map[string]interface{}{
				"status": "success",
				"data":   originalBody,
			}
			
			// Reset the writer and write the new wrapped response
			res.Writer = w.ResponseWriter
			return c.JSON(res.Status, responseWrapper)
		}

		// For non-JSON or non-2xx responses, write the original body back
		res.Writer = w.ResponseWriter
		if len(w.body) > 0 {
			res.Write(w.body)
		}

		return nil
	}
}

// Helper for ResponseTransformerMiddleware
type responseBodyWriter struct {
	http.ResponseWriter
	body []byte
}

func (w *responseBodyWriter) Write(b []byte) (int, error) {
	w.body = append(w.body, b...)
	return len(b), nil // Don't write to the original writer yet
}

// CustomHTTPErrorHandler provides a centralized error response format.
func CustomHTTPErrorHandler(err error, c echo.Context) {
	code := http.StatusInternalServerError
	message := "Internal Server Error"

	if he, ok := err.(*echo.HTTPError); ok {
		code = he.Code
		message = he.Message.(string)
	}

	// Avoid sending response if one has already been sent
	if !c.Response().Committed {
		c.JSON(code, map[string]interface{}{
			"status":  "error",
			"message": message,
		})
	}
}

// main function: The Pragmatic Functionalist
// This style is straightforward, defining middleware and handlers as simple functions.
// It uses built-in Echo middleware where possible for simplicity and clarity.
func main() {
	e := echo.New()

	// --- Middleware Registration ---

	// 1. Request Logging: Use the default logger middleware.
	e.Use(middleware.LoggerWithConfig(middleware.LoggerConfig{
		Format: "method=${method}, uri=${uri}, status=${status}, latency=${latency_human}\n",
	}))
	e.Use(middleware.Recover())

	// 2. CORS Handling: A permissive CORS configuration for development.
	e.Use(middleware.CORSWithConfig(middleware.CORSConfig{
		AllowOrigins: []string{"*"},
		AllowMethods: []string{http.MethodGet, http.MethodPost, http.MethodPut, http.MethodDelete},
	}))

	// 3. Rate Limiting: Use the built-in rate limiter with an in-memory store.
	// Limits to 10 requests per second.
	e.Use(middleware.RateLimiter(middleware.NewRateLimiterMemoryStore(rate.Limit(10))))

	// 4. Request/Response Transformation: Custom middleware to wrap successful responses.
	e.Use(ResponseTransformerMiddleware)

	// 5. Error Handling: Set a custom HTTP error handler for the whole application.
	e.HTTPErrorHandler = CustomHTTPErrorHandler

	// --- Route Definitions ---
	e.GET("/users/me", getUserHandler)
	e.POST("/posts", createPostHandler)
	e.GET("/error", getErroringHandler)

	log.Println("Variation 1: Pragmatic Functionalist Server starting on :1323")
	if err := e.Start(":1323"); err != nil && err != http.ErrServerClosed {
		e.Logger.Fatal(err)
	}
}