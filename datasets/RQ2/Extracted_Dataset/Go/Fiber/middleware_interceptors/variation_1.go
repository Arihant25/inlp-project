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
	AdminRole Role = "ADMIN"
	UserRole  Role = "USER"
)

type Status string

const (
	DraftStatus     Status = "DRAFT"
	PublishedStatus Status = "PUBLISHED"
)

type User struct {
	ID           uuid.UUID `json:"id"`
	Email        string    `json:"email"`
	PasswordHash string    `json:"-"` // Omit from JSON responses
	Role         Role      `json:"role"`
	IsActive     bool      `json:"is_active"`
	CreatedAt    time.Time `json:"created_at"`
}

type Post struct {
	ID      uuid.UUID `json:"id"`
	UserID  uuid.UUID `json:"user_id"`
	Title   string    `json:"title"`
	Content string    `json:"content"`
	Status  Status    `json:"status"`
}

// --- Mock Data ---
var mockUser = User{
	ID:           uuid.New(),
	Email:        "test.user@example.com",
	PasswordHash: "some_bcrypt_hash",
	Role:         UserRole,
	IsActive:     true,
	CreatedAt:    time.Now().UTC(),
}

// --- Main Application: Functional & Direct ---

func main() {
	// 5. Error Handling: Defined during app initialization
	app := fiber.New(fiber.Config{
		ErrorHandler: func(c *fiber.Ctx, err error) error {
			code := fiber.StatusInternalServerError
			message := "Internal Server Error"

			if e, ok := err.(*fiber.Error); ok {
				code = e.Code
				message = e.Message
			}

			log.Printf("Error: %v - Path: %s", err, c.Path())

			return c.Status(code).JSON(fiber.Map{
				"error":   true,
				"message": message,
			})
		},
	})

	// --- Middleware Registration ---

	// 1. Request Logging
	app.Use(logger.New(logger.Config{
		Format: "[${ip}]:${port} ${status} - ${method} ${path} (${latency})\n",
	}))

	// 2. CORS Handling
	app.Use(cors.New(cors.Config{
		AllowOrigins: "https://*.example.com, http://localhost:3000",
		AllowHeaders: "Origin, Content-Type, Accept",
	}))

	// 3. Rate Limiting
	app.Use(limiter.New(limiter.Config{
		Max:        20,
		Expiration: 1 * time.Minute,
		KeyGenerator: func(c *fiber.Ctx) string {
			return c.IP()
		},
		LimitReached: func(c *fiber.Ctx) error {
			return c.Status(fiber.StatusTooManyRequests).JSON(fiber.Map{
				"error":   true,
				"message": "Too many requests, please try again later.",
			})
		},
	}))

	// 4. Request/Response Transformation (Custom Middleware)
	app.Use(func(c *fiber.Ctx) error {
		// Add a custom header to every response
		c.Set("X-App-Version", "1.0.0")
		return c.Next()
	})

	// --- API Routes ---
	api := app.Group("/api")

	api.Get("/user/:id", func(c *fiber.Ctx) error {
		// In a real app, you'd fetch the user by c.Params("id")
		return c.JSON(mockUser)
	})

	api.Get("/panic", func(c *fiber.Ctx) error {
		// This route will trigger the custom error handler
		panic("This is a controlled panic to test the error handler.")
	})

	log.Println("Variation 1: Server starting on http://localhost:3000")
	log.Fatal(app.Listen(":3000"))
}