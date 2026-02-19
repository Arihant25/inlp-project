package main

import (
	"log"
	"os"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/gofiber/fiber/v2/middleware/cors"
	"github.com/gofiber/fiber/v2/middleware/limiter"
	"github.com/gofiber/fiber/v2/middleware/logger"
	"github.com/google/uuid"
)

// --- Domain Schema ---

type UserRoleEnum string

const (
	ADMIN UserRoleEnum = "ADMIN"
	USER  UserRoleEnum = "USER"
)

type PostStatusEnum string

const (
	DRAFT     PostStatusEnum = "DRAFT"
	PUBLISHED PostStatusEnum = "PUBLISHED"
)

type User struct {
	ID           uuid.UUID    `json:"id"`
	Email        string       `json:"email"`
	PasswordHash string       `json:"-"`
	Role         UserRoleEnum `json:"role"`
	IsActive     bool         `json:"is_active"`
	CreatedAt    time.Time    `json:"created_at"`
}

type Post struct {
	ID      uuid.UUID      `json:"id"`
	UserID  uuid.UUID      `json:"user_id"`
	Title   string         `json:"title"`
	Content string         `json:"content"`
	Status  PostStatusEnum `json:"status"`
}

// --- Middleware Manager (OOP Approach) ---

type MiddlewareManager struct {
	logger *log.Logger
	env    string
}

func NewMiddlewareManager(env string) *MiddlewareManager {
	return &MiddlewareManager{
		logger: log.New(os.Stdout, "[MIDDLEWARE] ", log.LstdFlags),
		env:    env,
	}
}

// 1. Request Logging
func (m *MiddlewareManager) RequestLogger() fiber.Handler {
	return logger.New()
}

// 2. CORS Handling
func (m *MiddlewareManager) CORSHandler() fiber.Handler {
	return cors.New(cors.Config{
		AllowOrigins: "*", // More permissive for dev
		AllowMethods: "GET,POST,HEAD,PUT,DELETE,PATCH",
	})
}

// 3. Rate Limiting
func (m *MiddlewareManager) RateLimiter() fiber.Handler {
	return limiter.New(limiter.Config{
		Max:        5,
		Expiration: 30 * time.Second,
	})
}

// 4. Response Transformation
func (m *MiddlewareManager) ResponseTransformer() fiber.Handler {
	return func(c *fiber.Ctx) error {
		c.Set("X-Environment", m.env)
		return c.Next()
	}
}

// 5. Error Handling
func (m *MiddlewareManager) ErrorHandler(c *fiber.Ctx, err error) error {
	code := fiber.StatusInternalServerError
	if e, ok := err.(*fiber.Error); ok {
		code = e.Code
	}
	m.logger.Printf("Caught error on path %s: %v", c.Path(), err)
	c.Set(fiber.HeaderContentType, fiber.MIMETextHTML)
	return c.Status(code).SendString("<h1>An Error Occurred</h1><p>Please try again later.</p>")
}

// --- Main Application: OOP ---

func main() {
	// Instantiate the manager
	middlewareManager := NewMiddlewareManager("development")

	app := fiber.New(fiber.Config{
		ErrorHandler: middlewareManager.ErrorHandler,
	})

	// Register middleware using methods from the manager instance
	app.Use(middlewareManager.RequestLogger())
	app.Use(middlewareManager.CORSHandler())
	app.Use(middlewareManager.RateLimiter())
	app.Use(middlewareManager.ResponseTransformer())

	// --- API Routes ---
	app.Get("/users", func(c *fiber.Ctx) error {
		return c.JSON([]User{
			{ID: uuid.New(), Email: "oop.user1@example.com", Role: ADMIN, IsActive: true},
			{ID: uuid.New(), Email: "oop.user2@example.com", Role: USER, IsActive: false},
		})
	})

	app.Get("/force-error", func(c *fiber.Ctx) error {
		// This route will trigger the custom error handler
		return fiber.ErrForbidden
	})

	log.Println("Variation 3: Server starting on http://localhost:3000")
	log.Fatal(app.Listen(":3000"))
}