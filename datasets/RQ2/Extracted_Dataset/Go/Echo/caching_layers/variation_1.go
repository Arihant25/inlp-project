package main

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/hashicorp/golang-lru"
	"github.com/labstack/echo/v4"
)

// --- Domain Models ---

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

// --- Mock Database Layer ---

type UserDatabase struct {
	mu    sync.RWMutex
	users map[uuid.UUID]User
}

func NewUserDatabase() *UserDatabase {
	db := &UserDatabase{
		users: make(map[uuid.UUID]User),
	}
	// Seed with some data
	adminID := uuid.New()
	db.users[adminID] = User{
		ID:           adminID,
		Email:        "admin@example.com",
		PasswordHash: "hashed_password",
		Role:         AdminRole,
		IsActive:     true,
		CreatedAt:    time.Now(),
	}
	return db
}

func (db *UserDatabase) FindUserByID(ctx context.Context, id uuid.UUID) (*User, error) {
	db.mu.RLock()
	defer db.mu.RUnlock()

	// Simulate DB latency
	time.Sleep(100 * time.Millisecond)

	if user, ok := db.users[id]; ok {
		return &user, nil
	}
	return nil, fmt.Errorf("user with ID %s not found", id)
}

func (db *UserDatabase) UpdateUser(ctx context.Context, user User) error {
	db.mu.Lock()
	defer db.mu.Unlock()

	// Simulate DB latency
	time.Sleep(50 * time.Millisecond)

	if _, ok := db.users[user.ID]; !ok {
		return fmt.Errorf("user with ID %s not found for update", user.ID)
	}
	db.users[user.ID] = user
	return nil
}

// --- Caching Layer (OOP Style) ---

type cacheItem struct {
	value     interface{}
	expiresAt time.Time
}

func (item cacheItem) isExpired() bool {
	return time.Now().After(item.expiresAt)
}

type CacheService struct {
	lruCache *lru.Cache
	mu       sync.RWMutex
}

func NewCacheService(size int) (*CacheService, error) {
	l, err := lru.New(size)
	if err != nil {
		return nil, err
	}
	return &CacheService{
		lruCache: l,
	}, nil
}

func (cs *CacheService) Get(key string) (interface{}, bool) {
	cs.mu.RLock()
	defer cs.mu.RUnlock()

	item, ok := cs.lruCache.Get(key)
	if !ok {
		return nil, false
	}

	cached := item.(cacheItem)
	if cached.isExpired() {
		// Lazy eviction
		cs.lruCache.Remove(key)
		return nil, false
	}

	return cached.value, true
}

func (cs *CacheService) Set(key string, value interface{}, ttl time.Duration) {
	cs.mu.Lock()
	defer cs.mu.Unlock()

	item := cacheItem{
		value:     value,
		expiresAt: time.Now().Add(ttl),
	}
	cs.lruCache.Add(key, item)
}

func (cs *CacheService) Delete(key string) {
	cs.mu.Lock()
	defer cs.mu.Unlock()

	cs.lruCache.Remove(key)
}

// --- Handlers (OOP Style) ---

type UserHandler struct {
	db    *UserDatabase
	cache *CacheService
}

func NewUserHandler(db *UserDatabase, cache *CacheService) *UserHandler {
	return &UserHandler{
		db:    db,
		cache: cache,
	}
}

// GetUser implements the cache-aside pattern
func (h *UserHandler) GetUser(c echo.Context) error {
	userID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "Invalid user ID format"})
	}

	cacheKey := fmt.Sprintf("user:%s", userID.String())

	// 1. Try to get from cache
	if cachedUser, ok := h.cache.Get(cacheKey); ok {
		log.Println("Cache HIT for user:", userID)
		return c.JSON(http.StatusOK, cachedUser)
	}

	log.Println("Cache MISS for user:", userID)

	// 2. If miss, get from data source
	user, err := h.db.FindUserByID(c.Request().Context(), userID)
	if err != nil {
		return c.JSON(http.StatusNotFound, map[string]string{"error": err.Error()})
	}

	// 3. Set the result in the cache
	h.cache.Set(cacheKey, user, 5*time.Minute)
	log.Println("Set cache for user:", userID)

	return c.JSON(http.StatusOK, user)
}

// UpdateUser implements cache invalidation
func (h *UserHandler) UpdateUser(c echo.Context) error {
	userID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "Invalid user ID format"})
	}

	var reqBody struct {
		IsActive bool `json:"is_active"`
	}
	if err := c.Bind(&reqBody); err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "Invalid request body"})
	}

	// In a real app, you'd fetch the user first
	user, err := h.db.FindUserByID(c.Request().Context(), userID)
	if err != nil {
		return c.JSON(http.StatusNotFound, map[string]string{"error": err.Error()})
	}

	user.IsActive = reqBody.IsActive
	if err := h.db.UpdateUser(c.Request().Context(), *user); err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": "Failed to update user"})
	}

	// Invalidate the cache
	cacheKey := fmt.Sprintf("user:%s", userID.String())
	h.cache.Delete(cacheKey)
	log.Println("Cache INVALIDATED for user:", userID)

	return c.JSON(http.StatusOK, user)
}

func main() {
	e := echo.New()

	// --- Dependencies ---
	userDB := NewUserDatabase()
	cache, err := NewCacheService(128) // LRU cache with 128 items capacity
	if err != nil {
		e.Logger.Fatal(err)
	}
	userHandler := NewUserHandler(userDB, cache)

	// --- Routes ---
	e.GET("/users/:id", userHandler.GetUser)
	e.PATCH("/users/:id", userHandler.UpdateUser)

	// Log the seeded user ID for testing
	for id := range userDB.users {
		log.Printf("Server started. Test with user ID: %s\n", id)
		log.Printf("GET http://localhost:1323/users/%s\n", id)
		log.Printf("PATCH http://localhost:1323/users/%s with JSON body {\"is_active\": false}\n", id)
		break
	}

	e.Logger.Fatal(e.Start(":1323"))
}