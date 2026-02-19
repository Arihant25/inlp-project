package main

import (
	"container/list"
	"fmt"
	"log"
	"sync"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/gofiber/fiber/v2/middleware/logger"
	"github.com/google/uuid"
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

// --- MOCK DATABASE REPOSITORY ---

type MockDatabase struct {
	users map[uuid.UUID]User
	posts map[uuid.UUID]Post
	mu    sync.RWMutex
}

func NewMockDatabase() *MockDatabase {
	db := &MockDatabase{
		users: make(map[uuid.UUID]User),
		posts: make(map[uuid.UUID]Post),
	}
	db.seed()
	return db
}

func (db *MockDatabase) seed() {
	adminID := uuid.New()
	db.users[adminID] = User{ID: adminID, Email: "admin@example.com", Role: AdminRole, IsActive: true, CreatedAt: time.Now()}
	postID := uuid.New()
	db.posts[postID] = Post{ID: postID, UserID: adminID, Title: "OOP Post", Content: "Content here", Status: PublishedStatus}
	fmt.Println("Database seeded.")
	fmt.Printf("Admin User ID: %s\n", adminID)
	fmt.Printf("Test Post ID: %s\n", postID)
}

func (db *MockDatabase) FindUserByID(id uuid.UUID) (User, error) {
	db.mu.RLock()
	defer db.mu.RUnlock()
	user, ok := db.users[id]
	if !ok {
		return User{}, fmt.Errorf("user with id %s not found", id)
	}
	return user, nil
}

func (db *MockDatabase) UpdateUser(user User) error {
	db.mu.Lock()
	defer db.mu.Unlock()
	if _, ok := db.users[user.ID]; !ok {
		return fmt.Errorf("user with id %s not found", user.ID)
	}
	db.users[user.ID] = user
	return nil
}

// --- CUSTOM LRU CACHE IMPLEMENTATION ---

type CacheEntry[V any] struct {
	key       uuid.UUID
	value     V
	expiresAt time.Time
}

type LRUCache[V any] struct {
	capacity int
	items    map[uuid.UUID]*list.Element
	queue    *list.List
	mu       sync.Mutex
	ttl      time.Duration
}

func NewLRUCache[V any](capacity int, ttl time.Duration) *LRUCache[V] {
	return &LRUCache[V]{
		capacity: capacity,
		items:    make(map[uuid.UUID]*list.Element),
		queue:    list.New(),
		ttl:      ttl,
	}
}

func (c *LRUCache[V]) Get(key uuid.UUID) (V, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()

	element, exists := c.items[key]
	if !exists {
		var zero V
		return zero, false
	}

	entry := element.Value.(*CacheEntry[V])
	if time.Now().After(entry.expiresAt) {
		c.removeElement(element)
		var zero V
		return zero, false
	}

	c.queue.MoveToFront(element)
	return entry.value, true
}

func (c *LRUCache[V]) Set(key uuid.UUID, value V) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if element, exists := c.items[key]; exists {
		c.queue.MoveToFront(element)
		element.Value.(*CacheEntry[V]).value = value
		element.Value.(*CacheEntry[V]).expiresAt = time.Now().Add(c.ttl)
		return
	}

	if c.queue.Len() >= c.capacity {
		c.evict()
	}

	entry := &CacheEntry[V]{
		key:       key,
		value:     value,
		expiresAt: time.Now().Add(c.ttl),
	}
	element := c.queue.PushFront(entry)
	c.items[key] = element
}

func (c *LRUCache[V]) Delete(key uuid.UUID) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if element, exists := c.items[key]; exists {
		c.removeElement(element)
	}
}

func (c *LRUCache[V]) evict() {
	element := c.queue.Back()
	if element != nil {
		c.removeElement(element)
	}
}

func (c *LRUCache[V]) removeElement(element *list.Element) {
	c.queue.Remove(element)
	delete(c.items, element.Value.(*CacheEntry[V]).key)
}

// --- SERVICE LAYER ---

type UserService struct {
	db    *MockDatabase
	cache *LRUCache[User]
}

func NewUserService(db *MockDatabase, cache *LRUCache[User]) *UserService {
	return &UserService{db: db, cache: cache}
}

func (s *UserService) GetUser(id uuid.UUID) (User, error) {
	// 1. Cache-Aside: Check cache
	if user, found := s.cache.Get(id); found {
		fmt.Printf("CACHE HIT for user %s\n", id)
		return user, nil
	}

	fmt.Printf("CACHE MISS for user %s\n", id)
	// 2. On miss, fetch from DB
	user, err := s.db.FindUserByID(id)
	if err != nil {
		return User{}, err
	}

	// 3. Store in cache
	s.cache.Set(id, user)
	return user, nil
}

func (s *UserService) UpdateUserActivity(id uuid.UUID, isActive bool) (User, error) {
	user, err := s.db.FindUserByID(id)
	if err != nil {
		return User{}, err
	}
	user.IsActive = isActive
	if err := s.db.UpdateUser(user); err != nil {
		return User{}, err
	}

	// Cache Invalidation
	fmt.Printf("INVALIDATING CACHE for user %s\n", id)
	s.cache.Delete(id)
	return user, nil
}

// --- HANDLER/CONTROLLER LAYER ---

type UserHandler struct {
	service *UserService
}

func NewUserHandler(service *UserService) *UserHandler {
	return &UserHandler{service: service}
}

func (h *UserHandler) GetUser(c *fiber.Ctx) error {
	id, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "invalid user ID"})
	}

	user, err := h.service.GetUser(id)
	if err != nil {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": err.Error()})
	}

	return c.JSON(user)
}

func (h *UserHandler) UpdateUser(c *fiber.Ctx) error {
	id, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "invalid user ID"})
	}

	var payload struct {
		IsActive bool `json:"is_active"`
	}
	if err := c.BodyParser(&payload); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "invalid request body"})
	}

	user, err := h.service.UpdateUserActivity(id, payload.IsActive)
	if err != nil {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": err.Error()})
	}

	return c.JSON(user)
}

// --- MAIN APPLICATION ---
// To run this:
// 1. go mod init example.com/cache
// 2. go get github.com/gofiber/fiber/v2
// 3. go get github.com/google/uuid
// 4. go run .
func main() {
	// Dependency Injection
	db := NewMockDatabase()
	userCache := NewLRUCache[User](100, 5*time.Minute)
	userService := NewUserService(db, userCache)
	userHandler := NewUserHandler(userService)

	app := fiber.New()
	app.Use(logger.New())

	userRoutes := app.Group("/users")
	userRoutes.Get("/:id", userHandler.GetUser)
	userRoutes.Patch("/:id", userHandler.UpdateUser)

	log.Fatal(app.Listen(":3000"))
}