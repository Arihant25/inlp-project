package main

import (
	"encoding/json"
	"fmt"
	"log"
	"sync"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/gofiber/fiber/v2/middleware/logger"
	"github.com/google/uuid"
	lru "github.com/hashicorp/lru/v2"
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
	mockUserDB[adminID] = User{
		ID:           adminID,
		Email:        "admin@example.com",
		PasswordHash: "hashed_password",
		Role:         AdminRole,
		IsActive:     true,
		CreatedAt:    time.Now(),
	}

	userID := uuid.New()
	mockUserDB[userID] = User{
		ID:           userID,
		Email:        "user@example.com",
		PasswordHash: "hashed_password_2",
		Role:         UserRole,
		IsActive:     true,
		CreatedAt:    time.Now(),
	}

	postID := uuid.New()
	mockPostDB[postID] = Post{
		ID:      postID,
		UserID:  adminID,
		Title:   "First Post by Admin",
		Content: "This is the content of the first post.",
		Status:  PublishedStatus,
	}
	fmt.Println("Database seeded.")
	fmt.Printf("Admin User ID: %s\n", adminID)
	fmt.Printf("Test Post ID: %s\n", postID)
}

// --- CACHING LAYER (Functional Approach) ---

// User cache: Simple time-based expiration
type UserCacheItem struct {
	User      User
	ExpiresAt time.Time
}
var userCache = &sync.Map{}

// Post cache: LRU (Least Recently Used)
var postCache *lru.Cache[uuid.UUID, Post]

func initCaches() {
	var err error
	postCache, err = lru.New[uuid.UUID, Post](128) // Cache up to 128 posts
	if err != nil {
		log.Fatalf("Could not initialize post cache: %v", err)
	}
	fmt.Println("Caches initialized.")
}

func getUserFromCache(id uuid.UUID) (*User, bool) {
	if item, ok := userCache.Load(id); ok {
		cacheItem := item.(UserCacheItem)
		if time.Now().Before(cacheItem.ExpiresAt) {
			return &cacheItem.User, true
		}
		// Expired item, delete it
		userCache.Delete(id)
	}
	return nil, false
}

func setUserInCache(user User) {
	item := UserCacheItem{
		User:      user,
		ExpiresAt: time.Now().Add(5 * time.Minute), // 5-minute TTL
	}
	userCache.Store(user.ID, item)
}

func deleteUserFromCache(id uuid.UUID) {
	userCache.Delete(id)
}

// --- HANDLERS (Functional Style) ---

func handleGetUser(c *fiber.Ctx) error {
	id, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "invalid user ID format"})
	}

	// 1. Cache-Aside: Check cache first
	if user, found := getUserFromCache(id); found {
		fmt.Printf("CACHE HIT for user %s\n", id)
		return c.JSON(user)
	}

	fmt.Printf("CACHE MISS for user %s\n", id)

	// 2. If miss, get from "database"
	dbMutex.RLock()
	user, ok := mockUserDB[id]
	dbMutex.RUnlock()

	if !ok {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": "user not found"})
	}

	// 3. Set data in cache
	setUserInCache(user)

	return c.JSON(user)
}

func handleUpdateUser(c *fiber.Ctx) error {
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

	// Dummy update logic
	var payload struct {
		IsActive bool `json:"is_active"`
	}
	if err := c.BodyParser(&payload); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "invalid request body"})
	}
	user.IsActive = payload.IsActive
	mockUserDB[id] = user

	// Cache Invalidation: Delete the old entry
	fmt.Printf("INVALIDATING CACHE for user %s\n", id)
	deleteUserFromCache(id)

	return c.Status(fiber.StatusOK).JSON(user)
}

func handleGetPost(c *fiber.Ctx) error {
	id, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "invalid post ID format"})
	}

	// 1. Cache-Aside: Check LRU cache first
	if post, found := postCache.Get(id); found {
		fmt.Printf("LRU CACHE HIT for post %s\n", id)
		return c.JSON(post)
	}

	fmt.Printf("LRU CACHE MISS for post %s\n", id)

	// 2. If miss, get from "database"
	dbMutex.RLock()
	post, ok := mockPostDB[id]
	dbMutex.RUnlock()

	if !ok {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": "post not found"})
	}

	// 3. Set data in LRU cache
	postCache.Add(id, post)

	return c.JSON(post)
}

func handleDeletePost(c *fiber.Ctx) error {
	id, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "invalid post ID format"})
	}

	dbMutex.Lock()
	_, ok := mockPostDB[id]
	if ok {
		delete(mockPostDB, id)
	}
	dbMutex.Unlock()

	if !ok {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": "post not found"})
	}

	// Cache Invalidation: Remove from LRU cache
	fmt.Printf("INVALIDATING LRU CACHE for post %s\n", id)
	postCache.Remove(id)

	return c.SendStatus(fiber.StatusNoContent)
}


// --- MAIN APPLICATION ---
// To run this:
// 1. go mod init example.com/cache
// 2. go get github.com/gofiber/fiber/v2
// 3. go get github.com/google/uuid
// 4. go get github.com/hashicorp/lru/v2
// 5. go run .
func main() {
	seedDatabase()
	initCaches()

	app := fiber.New()
	app.Use(logger.New())

	app.Get("/users/:id", handleGetUser)
	app.Patch("/users/:id", handleUpdateUser)

	app.Get("/posts/:id", handleGetPost)
	app.Delete("/posts/:id", handleDeletePost)

	log.Fatal(app.Listen(":3000"))
}