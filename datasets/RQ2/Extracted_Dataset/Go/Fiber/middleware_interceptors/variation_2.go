package main

import (
	"fmt"
	"log"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/gofiber/fiber/v2/middleware/cors"
	"github.com/gofiber/fiber/v2/middleware/limiter"
	"github.comcom/gofiber/fiber/v2/middleware/logger"
	"github.com/google/uuid"
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

type User struct {
	ID           uuid.UUID `json:"id"`
	Email        string    `json:"email"`
	PasswordHash string    `json:"-"`
	Role         UserRole  `json:"role"`
	IsActive     bool      `json:"is_active"`
	CreatedAt    time.Time `json:"created_at"`
}

type Post struct {
	ID      uuid.UUID  `json:"id"`
	UserID  uuid.UUID  `json:"user_id"`
	Title   string     `json:"title"`
	Content string     `json:"content"`
	Status  PostStatus `json:"status"`
}

// --- Simulated 'middleware' package ---

// This block simulates functions that would be in a separate `middleware` package.
var Middleware = struct {
	Logger       func() fiber.Handler
	CORS         func() fiber.Handler
	RateLimiter  func() fiber.Handler
	Transform    func() fiber.Handler
	ErrorHandler func(c *fiber.Ctx, err error) error
}{
	// 1. Request Logging
	Logger: func() fiber.Handler {
		return logger.New(logger.Config{
			Format:     "${cyan}[${time}] ${white}${pid} ${red}${status} ${blue}[${method}] ${white}${path}\n",
			TimeFormat: "02-Jan-2006 15:04:05",
			TimeZone:   "Local",
		})
	},
	// 2. CORS Handling
	CORS: func() fiber.Handler {
		return cors.New() // Use default permissive config
	},
	// 3. Rate Limiting
	RateLimiter: func() fiber.Handler {
		return limiter.New(limiter.Config{
			Max:        100,
			Expiration: 1 * time.Minute,
		})
	},
	// 4. Request/Response Transformation
	Transform: func() fiber.Handler {
		return func(c *fiber.Ctx) error {
			c.Locals("request_start_time", time.Now())
			err := c.Next()
			latency := time.Since(c.Locals("request_start_time").(time.Time))
			c.Set("X-Response-Latency-ms", fmt.Sprintf("%d", latency.Milliseconds()))
			return err
		}
	},
	// 5. Error Handling
	ErrorHandler: func(c *fiber.Ctx, err error) error {
		code := fiber.StatusInternalServerError
		var message interface{} = "An unexpected error occurred."

		if e, ok := err.(*fiber.Error); ok {
			code = e.Code
			message = e.Message
		} else {
			log.Printf("Unhandled error: %v", err)
		}

		c.Set(fiber.HeaderContentType, fiber.MIMEApplicationJSON)
		return c.Status(code).JSON(fiber.Map{"status": "error", "message": message})
	},
}

// --- Main Application: Modular ---

func main() {
	app := fiber.New(fiber.Config{
		ErrorHandler: Middleware.ErrorHandler,
	})

	// Register middleware from the 'middleware' module
	app.Use(Middleware.Logger())
	app.Use(Middleware.CORS())
	app.Use(Middleware.RateLimiter())
	app.Use(Middleware.Transform())

	// --- API Routes ---
	v1 := app.Group("/v1")
	v1.Get("/users/profile", handleGetUserProfile)
	v1.Get("/posts", handleGetPosts)
	v1.Get("/error", func(c *fiber.Ctx) error {
		return fiber.NewError(fiber.StatusServiceUnavailable, "This service is not available.")
	})

	log.Println("Variation 2: Server starting on http://localhost:3000")
	log.Fatal(app.Listen(":3000"))
}

// --- Handlers ---

func handleGetUserProfile(c *fiber.Ctx) error {
	user := User{
		ID:        uuid.New(),
		Email:     "modular.user@example.com",
		Role:      RoleAdmin,
		IsActive:  true,
		CreatedAt: time.Now().Add(-24 * time.Hour),
	}
	return c.Status(fiber.StatusOK).JSON(user)
}

func handleGetPosts(c *fiber.Ctx) error {
	posts := []Post{
		{ID: uuid.New(), UserID: uuid.New(), Title: "First Post", Status: StatusPublished},
		{ID: uuid.New(), UserID: uuid.New(), Title: "Second Post", Status: StatusDraft},
	}
	return c.Status(fiber.StatusOK).JSON(posts)
}