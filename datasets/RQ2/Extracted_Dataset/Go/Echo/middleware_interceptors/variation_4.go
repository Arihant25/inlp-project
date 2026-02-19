package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/labstack/echo/v4"
	"golang.org/x/time/rate"
)

// --- Domain ---
type (
	UserRole   string
	PostStatus string

	User struct {
		ID           uuid.UUID `json:"id"`
		Email        string    `json:"email"`
		PasswordHash string    `json:"-"`
		Role         UserRole  `json:"role"`
		IsActive     bool      `json:"is_active"`
		CreatedAt    time.Time `json:"created_at"`
	}

	Post struct {
		ID      uuid.UUID  `json:"id"`
		UserID  uuid.UUID  `json:"user_id"`
		Title   string     `json:"title"`
		Content string     `json:"content"`
		Status  PostStatus `json:"status"`
	}
)

const (
	AdminRole       UserRole   = "ADMIN"
	UserRole_       UserRole   = "USER"
	DraftStatus     PostStatus = "DRAFT"
	PublishedStatus PostStatus = "PUBLISHED"
)

// --- Custom Errors ---
var ErrDBNotFound = errors.New("database: record not found")

// --- Middleware Factory ---

// createStructuredLogger returns a middleware for structured logging with slog.
func createStructuredLogger(logger *slog.Logger) echo.MiddlewareFunc {
	return func(next echo.HandlerFunc) echo.HandlerFunc {
		return func(c echo.Context) error {
			start := time.Now()
			req := c.Request()
			err := next(c)
			res := c.Response()

			attrs := []slog.Attr{
				slog.String("method", req.Method),
				slog.String("path", req.URL.Path),
				slog.Int("status", res.Status),
				slog.Duration("latency", time.Since(start)),
				slog.String("ip", c.RealIP()),
			}
			if err != nil {
				attrs = append(attrs, slog.String("error", err.Error()))
				logger.Error("request error", attrs...)
			} else {
				logger.Info("request handled", attrs...)
			}
			return err
		}
	}
}

// createRateLimiter returns a middleware for IP-based rate limiting using a closure.
func createRateLimiter(r rate.Limit, b int) echo.MiddlewareFunc {
	var (
		mu      sync.Mutex
		clients = make(map[string]*rate.Limiter)
	)
	return func(next echo.HandlerFunc) echo.HandlerFunc {
		return func(c echo.Context) error {
			ip := c.RealIP()
			mu.Lock()
			if _, found := clients[ip]; !found {
				clients[ip] = rate.NewLimiter(r, b)
			}
			limiter := clients[ip]
			mu.Unlock()

			if !limiter.Allow() {
				return c.JSON(http.StatusTooManyRequests, map[string]string{"message": "slow down"})
			}
			return next(c)
		}
	}
}

// createResponseTransformer wraps responses in a standard envelope.
func createResponseTransformer() echo.MiddlewareFunc {
	type successResponse struct {
		Status string      `json:"status"`
		Data   interface{} `json:"data"`
	}
	return func(next echo.HandlerFunc) echo.HandlerFunc {
		return func(c echo.Context) error {
			err := next(c)
			if err != nil {
				return err // Let the error handler take care of it
			}
			// This pattern assumes the handler has returned a value that can be marshaled.
			// It's a bit magical but can lead to clean handlers.
			// We'll use a custom context to pass data up from the handler.
			if data := c.Get("response_data"); data != nil {
				return c.JSON(c.Response().Status, successResponse{Status: "success", Data: data})
			}
			return nil
		}
	}
}

// createErrorHandler returns a sophisticated HTTPErrorHandler.
func createErrorHandler(logger *slog.Logger) echo.HTTPErrorHandler {
	type errorResponse struct {
		Status  string `json:"status"`
		Message string `json:"message"`
	}
	return func(err error, c echo.Context) {
		if c.Response().Committed {
			return
		}

		code := http.StatusInternalServerError
		msg := "Internal Server Error"

		var he *echo.HTTPError
		if errors.As(err, &he) {
			code = he.Code
			if m, ok := he.Message.(string); ok {
				msg = m
			}
		} else if errors.Is(err, ErrDBNotFound) {
			code = http.StatusNotFound
			msg = "The requested resource was not found"
		} else {
			logger.Error("unhandled internal error", slog.String("error", err.Error()))
		}

		if err := c.JSON(code, errorResponse{Status: "error", Message: msg}); err != nil {
			logger.Error("failed to send error response", slog.String("error", err.Error()))
		}
	}
}

// --- API Handlers ---

// This custom handler type allows returning (data, error) like a typical Go function.
type AppHandler func(c echo.Context) (interface{}, error)

// adapt converts our custom AppHandler to an echo.HandlerFunc.
func adapt(h AppHandler) echo.HandlerFunc {
	return func(c echo.Context) error {
		data, err := h(c)
		if err != nil {
			return err
		}
		if data != nil {
			c.Set("response_data", data)
		}
		return nil
	}
}

func getUser(c echo.Context) (interface{}, error) {
	return User{
		ID:        uuid.New(),
		Email:     "clever.user@example.com",
		Role:      AdminRole,
		IsActive:  true,
		CreatedAt: time.Now(),
	}, nil
}

func getMissingUser(c echo.Context) (interface{}, error) {
	// Simulate a failed database lookup
	return nil, ErrDBNotFound
}

// main function: The "Clever" Functional Programmer
// This style uses factory functions for middleware, closures to manage state,
// and a custom handler adapter for cleaner handler logic. It's concise and expressive.
func main() {
	e := echo.New()
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))

	// --- Middleware Registration ---
	e.Use(createStructuredLogger(logger))
	e.Use(echo.WrapMiddleware(func(h http.Handler) http.Handler {
		return http.TimeoutHandler(h, 10*time.Second, "request timed out")
	}))
	e.Use(middleware.CORS())
	e.Use(createRateLimiter(5, 10)) // 5 req/sec, burst of 10
	e.Use(createResponseTransformer())

	// --- Error Handling ---
	e.HTTPErrorHandler = createErrorHandler(logger)

	// --- Route Definitions ---
	e.GET("/users/me", adapt(getUser))
	e.GET("/users/missing", adapt(getMissingUser))

	logger.Info("Variation 4: Clever Functional Server starting on :1326")
	if err := e.Start(":1326"); err != nil && err != http.ErrServerClosed {
		logger.Error("server failed to start", slog.String("error", err.Error()))
		os.Exit(1)
	}
}