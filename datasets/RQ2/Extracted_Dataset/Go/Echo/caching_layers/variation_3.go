package main

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/golang/groupcache/lru"
	"github.com/google/uuid"
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

// --- Mock Persistence Layer ---

type UserStore struct {
	users sync.Map
}

func NewUserStore() *UserStore {
	store := &UserStore{}
	adminID := uuid.New()
	store.users.Store(adminID, User{
		ID:           adminID,
		Email:        "admin@example.com",
		PasswordHash: "secret",
		Role:         AdminRole,
		IsActive:     true,
		CreatedAt:    time.Now().UTC(),
	})
	return store
}

func (s *UserStore) GetUser(ctx context.Context, id uuid.UUID) (*User, error) {
	time.Sleep(150 * time.Millisecond) // Simulate DB latency
	if val, ok := s.users.Load(id); ok {
		user := val.(User)
		return &user, nil
	}
	return nil, fmt.Errorf("user not found")
}

func (s *UserStore) SaveUser(ctx context.Context, user User) error {
	time.Sleep(80 * time.Millisecond) // Simulate DB latency
	s.users.Store(user.ID, user)
	return nil
}

// --- Caching Layer ---

type TimedCacheEntry struct {
	Payload   interface{}
	ExpiresAt int64
}

type AppCache struct {
	lru  *lru.Cache
	lock sync.RWMutex
}

func NewAppCache(maxEntries int) *AppCache {
	return &AppCache{
		lru: lru.New(maxEntries),
	}
}

func (c *AppCache) Read(key string) (interface{}, bool) {
	c.lock.RLock()
	defer c.lock.RUnlock()

	val, ok := c.lru.Get(key)
	if !ok {
		return nil, false
	}

	entry := val.(TimedCacheEntry)
	if time.Now().UnixNano() > entry.ExpiresAt {
		// Don't remove here, let write/overwrite handle it (lazy)
		return nil, false
	}
	return entry.Payload, true
}

func (c *AppCache) Write(key string, value interface{}, ttl time.Duration) {
	c.lock.Lock()
	defer c.lock.Unlock()

	expiresAt := time.Now().Add(ttl).UnixNano()
	entry := TimedCacheEntry{Payload: value, ExpiresAt: expiresAt}
	c.lru.Add(key, entry)
}

func (c *AppCache) Invalidate(key string) {
	c.lock.Lock()
	defer c.lock.Unlock()

	c.lru.Remove(key)
}

// --- Application Context & Middleware ---

type AppContext struct {
	echo.Context
	Store *UserStore
	Cache *AppCache
}

func AppContextMiddleware(store *UserStore, cache *AppCache) echo.MiddlewareFunc {
	return func(next echo.HandlerFunc) echo.HandlerFunc {
		return func(c echo.Context) error {
			ac := &AppContext{
				Context: c,
				Store:   store,
				Cache:   cache,
			}
			return next(ac)
		}
	}
}

// --- Handlers (Context-Driven) ---

func findUserByID(c echo.Context) error {
	ac := c.(*AppContext)
	userID, err := uuid.Parse(ac.Param("id"))
	if err != nil {
		return ac.JSON(http.StatusBadRequest, map[string]string{"error": "invalid UUID"})
	}

	cacheKey := "user:" + userID.String()

	// 1. Attempt to read from cache
	if data, found := ac.Cache.Read(cacheKey); found {
		log.Println("Serving from CACHE for user:", userID)
		return ac.JSON(http.StatusOK, data)
	}

	log.Println("Serving from STORE for user:", userID)
	// 2. On miss, read from store
	user, err := ac.Store.GetUser(ac.Request().Context(), userID)
	if err != nil {
		return ac.JSON(http.StatusNotFound, map[string]string{"error": err.Error()})
	}

	// 3. Write result to cache
	ac.Cache.Write(cacheKey, user, 10*time.Minute)

	return ac.JSON(http.StatusOK, user)
}

func updateUserStatus(c echo.Context) error {
	ac := c.(*AppContext)
	userID, err := uuid.Parse(ac.Param("id"))
	if err != nil {
		return ac.JSON(http.StatusBadRequest, map[string]string{"error": "invalid UUID"})
	}

	user, err := ac.Store.GetUser(ac.Request().Context(), userID)
	if err != nil {
		return ac.JSON(http.StatusNotFound, map[string]string{"error": err.Error()})
	}

	user.IsActive = !user.IsActive // Toggle status
	if err := ac.Store.SaveUser(ac.Request().Context(), *user); err != nil {
		return ac.JSON(http.StatusInternalServerError, map[string]string{"error": "could not save user"})
	}

	// Invalidate cache to prevent serving stale data
	cacheKey := "user:" + userID.String()
	ac.Cache.Invalidate(cacheKey)
	log.Println("INVALIDATED cache for user:", userID)

	return ac.JSON(http.StatusOK, user)
}

func main() {
	e := echo.New()

	// --- Dependency Injection Setup ---
	userStore := NewUserStore()
	appCache := NewAppCache(500)

	// --- Middleware ---
	e.Use(AppContextMiddleware(userStore, appCache))

	// --- Routes ---
	e.GET("/users/:id", findUserByID)
	e.PUT("/users/:id/toggle-status", updateUserStatus)

	// Log the seeded user ID for testing
	userStore.users.Range(func(key, value interface{}) bool {
		id := key.(uuid.UUID)
		log.Printf("Server is ready. Test with user ID: %s\n", id)
		log.Printf("GET http://localhost:1323/users/%s\n", id)
		log.Printf("PUT http://localhost:1323/users/%s/toggle-status\n", id)
		return false // stop after first
	})

	e.Logger.Fatal(e.Start(":1323"))
}