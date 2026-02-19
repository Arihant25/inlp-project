package main

import (
	"log"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/gofiber/fiber/v2/middleware/cors"
	"github.com/gofiber/fiber/v2/middleware/limiter"
	"github.com/gofiber/fiber/v2/middleware/logger"
	"github.com/google/uuid"
)

// --- Domain Schema ---

type Role string

const (
	ADMIN_ROLE Role = "ADMIN"
	USER_ROLE  Role = "USER"
)

type Status string

const (
	DRAFT_STATUS     Status = "DRAFT"
	PUBLISHED_STATUS Status = "PUBLISHED"
)

type User struct {
	Id           uuid.UUID `json:"id"`
	Email        string    `json:"email"`
	PasswordHash string    `json:"password_hash,omitempty"`
	Role         Role      `json:"role"`
	IsActive     bool      `json:"is_active"`
	CreatedAt    time.Time `json:"created_at"`
}

type Post struct {
	Id      uuid.UUID `json:"id"`
	UserId  uuid.UUID `json:"user_id"`
	Title   string    `json:"title"`
	Content string    `json:"content"`
	Status  Status    `json:"status"`
}

// --- Configuration-Driven Setup ---

type MiddlewareConfig struct {
	AppName         string
	EnableCORS      bool
	CORSOrigins     string
	EnableRateLimit bool
	RateLimitMax    int
	RateLimitExpiry time.Duration
	EnableLogger    bool
}

// SetupMiddleware configures and registers all middleware based on a config struct.
func SetupMiddleware(app *fiber.App, cfg *MiddlewareConfig) {
	// 5. Centralized Error Handling (always on)
	app.Config().ErrorHandler = func(c *fiber.Ctx, err error) error {
		code := fiber.StatusInternalServerError
		if e, ok := err.(*fiber.Error); ok {
			code = e.Code
		}
		log.Printf("Error handled by config-driven handler: %v", err)
		return c.Status(code).JSON(fiber.Map{"error": err.Error()})
	}

	// 1. Request Logging
	if cfg.EnableLogger {
		app.Use(logger.New())
	}

	// 2. CORS Handling
	if cfg.EnableCORS {
		app.Use(cors.New(cors.Config{
			AllowOrigins: cfg.CORSOrigins,
		}))
	}

	// 3. Rate Limiting
	if cfg.EnableRateLimit {
		app.Use(limiter.New(limiter.Config{
			Max:        cfg.RateLimitMax,
			Expiration: cfg.RateLimitExpiry,
		}))
	}

	// 4. Request/Response Transformation
	app.Use(func(c *fiber.Ctx) error {
		c.Set("X-App-Name", cfg.AppName)
		return c.Next()
	})
}

// --- Main Application: Configuration-Driven ---

func main() {
	// Load configuration (simulated here)
	config := &MiddlewareConfig{
		AppName:         "MyGoApp",
		EnableCORS:      true,
		CORSOrigins:     "http://localhost:8080",
		EnableRateLimit: true,
		RateLimitMax:    50,
		RateLimitExpiry: 1 * time.Minute,
		EnableLogger:    true,
	}

	app := fiber.New()

	// Apply all middleware using the single setup function
	SetupMiddleware(app, config)

	// --- API Routes ---
	apiGroup := app.Group("/api/v2")

	apiGroup.Get("/posts/:id", func(c *fiber.Ctx) error {
		postId, err := uuid.Parse(c.Params("id"))
		if err != nil {
			// This will be caught by the error handler
			return fiber.NewError(fiber.StatusBadRequest, "Invalid post ID format")
		}

		return c.JSON(&Post{
			Id:      postId,
			UserId:  uuid.New(),
			Title:   "Config-Driven Post",
			Content: "This content is served by an app with config-driven middleware.",
			Status:  PUBLISHED_STATUS,
		})
	})

	apiGroup.Get("/health", func(c *fiber.Ctx) error {
		return c.SendStatus(fiber.StatusOK)
	})

	log.Println("Variation 4: Server starting on http://localhost:3000")
	log.Fatal(app.Listen(":3000"))
}