package main

import (
	"fmt"
	"log"
	"sync"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/gofiber/fiber/v2/middleware/logger"
	"github.com/google/uuid"
	"github.com/patrickmn/go-cache"
)

// --- DOMAIN MODELS ---

type Role string
const (
	AdminRole Role = "ADMIN"
	UserRole  Role = "USER"
)

type User struct {
	ID           uuid.UUID `json:"id"`
	Email        string    `json:"email"`
	PasswordHash string    `json:"-"`
	Role         Role      `json:"role"`
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

// --- MOCK DATABASE ---

var (
	mockUserDB = make(map[uuid.UUID]User)
	mockPostDB = make(map[uuid.UUID]Post)
	dbMutex    = &sync.RWMutex{}
)

func seedDatabase() {
	adminID := uuid.New()
	mockUserDB[adminID] = User{ID: adminID, Email: "admin@example.com", Role: AdminRole, IsActive: true, CreatedAt: time.Now()}
	userID := uuid.New()
	mockUserDB[userID] = User{ID: userID, Email: "user@example.com", Role: UserRole, IsActive: true, CreatedAt: time.Now()}
	fmt.Println("Database seeded.")
	fmt.Printf("Admin User ID: %s\n", adminID)
	fmt.Printf("Test User ID: %s\n", userID)
}

// --- CACHING LAYER ---

// Using go-cache for automatic expiration
var responseCache = cache.New(5*time.Minute, 10*time.Minute)

// --- CACHING MIDDLEWARE ---

func CacheMiddleware(c *fiber.Ctx) error {
	// Only cache GET requests
	if c.Method() != fiber.MethodGet {
		return c.Next()
	}

	cacheKey := c.Path()
	if cachedResponse, found := responseCache.Get(cacheKey); found {
		fmt.Printf("MIDDLEWARE CACHE HIT for key: %s\n", cacheKey)
		c.Set(fiber.HeaderContentType, fiber.MIMEApplicationJSON)
		return c.Send(cachedResponse.([]byte))
	}

	fmt.Printf("MIDDLEWARE CACHE MISS for key: %s\n", cacheKey)

	// Proceed to handler
	if err := c.Next(); err != nil {
		return err
	}

	// After handler runs, cache the response if it's a 2xx
	if c.Response().StatusCode() >= 200 && c.Response().StatusCode() < 300 {
		body := c.Response().Body()
		// Important: The body buffer might be reused. We need to copy it.
		bodyCopy := make([]byte, len(body))
		copy(bodyCopy, body)
		responseCache.Set(cacheKey, bodyCopy, cache.DefaultExpiration)
		fmt.Printf("CACHED RESPONSE for key: %s\n", cacheKey)
	}

	return nil
}

// --- HANDLERS ---

// The GET handler is now "dumb" - it only knows how to fetch from the DB.
// The middleware handles the cache-aside logic for reads.
func getUserHandler(c *fiber.Ctx) error {
	id, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "invalid user ID format"})
	}

	dbMutex.RLock()
	user, ok := mockUserDB[id]
	dbMutex.RUnlock()

	if !ok {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": "user not found"})
	}

	return c.JSON(user)
}

// The UPDATE handler must perform cache invalidation explicitly.
func updateUserHandler(c *fiber.Ctx) error {
	id, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "invalid user ID format"})
	}

	dbMutex.Lock()
	defer dbMutex.Unlock()

	user, ok := mockUserDB[id]
	if !ok {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": "user not found"})
	}

	var payload struct {
		IsActive bool `json:"is_active"`
	}
	if err := c.BodyParser(&payload); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "invalid request body"})
	}
	user.IsActive = payload.IsActive
	mockUserDB[id] = user

	// Cache Invalidation
	// We must construct the key that the middleware would have used for the GET request.
	cacheKey := fmt.Sprintf("/users/%s", id.String())
	fmt.Printf("INVALIDATING CACHE for key: %s\n", cacheKey)
	responseCache.Delete(cacheKey)

	return c.Status(fiber.StatusOK).JSON(user)
}

func deleteUserHandler(c *fiber.Ctx) error {
	id, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "invalid user ID format"})
	}

	dbMutex.Lock()
	_, ok := mockUserDB[id]
	if ok {
		delete(mockUserDB, id)
	}
	dbMutex.Unlock()

	if !ok {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": "user not found"})
	}

	// Cache Invalidation
	cacheKey := fmt.Sprintf("/users/%s", id.String())
	fmt.Printf("INVALIDATING CACHE for key: %s\n", cacheKey)
	responseCache.Delete(cacheKey)

	return c.SendStatus(fiber.StatusNoContent)
}

// --- MAIN APPLICATION ---
// To run this:
// 1. go mod init example.com/cache
// 2. go get github.com/gofiber/fiber/v2
// 3. go get github.com/google/uuid
// 4. go get github.com/patrickmn/go-cache
// 5. go run .
func main() {
	seedDatabase()

	app := fiber.New()
	app.Use(logger.New())

	// Group for user routes
	userApi := app.Group("/users")

	// Apply middleware to the GET route
	userApi.Get("/:id", CacheMiddleware, getUserHandler)

	// Write operations do not use the cache middleware but must invalidate the cache
	userApi.Patch("/:id", updateUserHandler)
	userApi.Delete("/:id", deleteUserHandler)

	log.Fatal(app.Listen(":3000"))
}