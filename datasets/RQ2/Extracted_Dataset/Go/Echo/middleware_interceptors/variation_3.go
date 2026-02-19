package main

import (
	"encoding/json"
	"log"
	"net/http"
	"time"

	"github.com/google/uuid"
	"github.com/labstack/echo/v4"
	"github.com/labstack/echo/v4/middleware"
	"golang.org/x/time/rate"
)

// --- BEGIN: domain/models.go ---

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

// --- END: domain/models.go ---

// --- BEGIN: config/config.go ---

type Config struct {
	Server    ServerConfig
	CORS      middleware.CORSConfig
	RateLimit RateLimitConfig
}

type ServerConfig struct {
	Port string
}

type RateLimitConfig struct {
	Enabled bool
	Limit   rate.Limit
	Burst   int
}

func LoadConfig() *Config {
	// In a real app, this would load from a file (e.g., YAML, JSON) or env vars.
	return &Config{
		Server: ServerConfig{
			Port: ":1325",
		},
		CORS: middleware.CORSConfig{
			AllowOrigins: []string{"https://example.com", "http://localhost:3000"},
			AllowHeaders: []string{echo.HeaderOrigin, echo.HeaderContentType, echo.HeaderAccept, echo.HeaderAuthorization},
			AllowMethods: []string{http.MethodGet, http.MethodHead, http.MethodPut, http.MethodPatch, http.MethodPost, http.MethodDelete},
		},
		RateLimit: RateLimitConfig{
			Enabled: true,
			Limit:   20, // 20 requests per second
			Burst:   50,
		},
	}
}

// --- END: config/config.go ---

// --- BEGIN: internal/middleware/setup.go ---

// AttachMiddlewares configures and attaches all application middleware.
func AttachMiddlewares(e *echo.Echo, cfg *Config) {
	// Request Logging
	e.Use(middleware.Logger())
	e.Use(middleware.Recover())

	// CORS
	e.Use(middleware.CORSWithConfig(cfg.CORS))

	// Rate Limiting
	if cfg.RateLimit.Enabled {
		e.Use(middleware.RateLimiterWithConfig(middleware.RateLimiterConfig{
			Store: middleware.NewRateLimiterMemoryStoreWithConfig(
				middleware.RateLimiterMemoryStoreConfig{
					Rate:      cfg.RateLimit.Limit,
					Burst:     cfg.RateLimit.Burst,
					ExpiresIn: 3 * time.Minute,
				},
			),
			IdentifierExtractor: func(ctx echo.Context) (string, error) {
				id := ctx.RealIP()
				return id, nil
			},
			ErrorHandler: func(context echo.Context, err error) error {
				return context.JSON(http.StatusTooManyRequests, nil)
			},
		}))
	}

	// Response Transformation
	e.Use(ResponseWrapperMiddleware)

	// Error Handling
	e.HTTPErrorHandler = CustomHTTPErrorHandler
}

// ResponseWrapperMiddleware wraps successful JSON responses.
func ResponseWrapperMiddleware(next echo.HandlerFunc) echo.HandlerFunc {
	return func(c echo.Context) error {
		// This implementation is simpler and transforms the response after the handler runs.
		err := next(c)
		if err != nil {
			return err // Pass to the global error handler
		}

		// Don't wrap if response is already committed or not successful
		if c.Response().Committed || c.Response().Status < 200 || c.Response().Status >= 300 {
			return nil
		}

		// This is a trick: read the response body after it has been written.
		// This is not efficient and should be used with caution.
		// A better approach is in Variation 1. This is for stylistic variety.
		// For this to work, the handler must return `c.JSON`, which writes to the buffer.
		// We assume the handler has already written a JSON response.
		// This is a fragile pattern but sometimes seen.
		// A more robust way is to have handlers return data and let middleware format it.
		// Let's assume for this variation, handlers just return data.
		// Re-implementing to be more robust for this variation.
		c.Response().Before(func() {
			if c.Get("response_data") != nil {
				originalData := c.Get("response_data")
				c.Response().Header().Set(echo.HeaderContentType, echo.MIMEApplicationJSONCharsetUTF8)
				
				wrappedData := map[string]interface{}{
					"status": "success",
					"data":   originalData,
				}
				
				// Manually encode to the response writer
				enc := json.NewEncoder(c.Response().Writer)
				if err := enc.Encode(wrappedData); err != nil {
					log.Printf("Failed to encode wrapped response: %v", err)
				}
				// Mark as committed
				c.Response().Committed = true
			}
		})

		return nil
	}
}

// CustomHTTPErrorHandler formats errors into a standard JSON structure.
func CustomHTTPErrorHandler(err error, c echo.Context) {
	code := http.StatusInternalServerError
	message := "Internal Server Error"

	if he, ok := err.(*echo.HTTPError); ok {
		code = he.Code
		message = he.Message.(string)
	}

	if !c.Response().Committed {
		c.JSON(code, map[string]interface{}{
			"status":  "error",
			"message": message,
		})
	}
}

// --- END: internal/middleware/setup.go ---

// --- BEGIN: internal/api/handlers.go ---

var mockUser = User{
	ID:        uuid.New(),
	Email:     "modular.user@example.com",
	Role:      UserRole_,
	IsActive:  true,
	CreatedAt: time.Now(),
}

// GetUserHandler demonstrates returning data to be wrapped by middleware.
func GetUserHandler(c echo.Context) error {
	c.Set("response_data", mockUser)
	return c.NoContent(http.StatusOK) // Middleware will write the body
}

func CreatePostHandler(c echo.Context) error {
	var p Post
	if err := c.Bind(&p); err != nil {
		return echo.NewHTTPError(http.StatusBadRequest, "Invalid post data")
	}
	p.ID = uuid.New()
	p.UserID = mockUser.ID
	c.Set("response_data", p)
	return c.NoContent(http.StatusCreated) // Middleware will write the body
}

func ErrorHandler(c echo.Context) error {
	return echo.NewHTTPError(http.StatusForbidden, "You do not have permission")
}

// --- END: internal/api/handlers.go ---

// main function: The Modular Maintainer
// This style emphasizes separation of concerns (config, middleware, handlers)
// as if they were in different packages, making the system easier to maintain and scale.
func main() {
	// 1. Load Configuration
	cfg := LoadConfig()

	// 2. Initialize Echo instance
	e := echo.New()

	// 3. Attach all middleware
	AttachMiddlewares(e, cfg)

	// 4. Register routes
	e.GET("/users/me", GetUserHandler)
	e.POST("/posts", CreatePostHandler)
	e.GET("/error", ErrorHandler)

	// 5. Start server
	log.Printf("Variation 3: Modular Maintainer Server starting on %s", cfg.Server.Port)
	if err := e.Start(cfg.Server.Port); err != nil && err != http.ErrServerClosed {
		e.Logger.Fatal(err)
	}
}