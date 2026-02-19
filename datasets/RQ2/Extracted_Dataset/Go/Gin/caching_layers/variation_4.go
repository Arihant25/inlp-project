package main

import (
	"container/list"
	"encoding/json"
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

// --- Domain Schema ---

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

// --- Mock Datastore ---

var (
	mockDB = struct {
		sync.RWMutex
		users map[uuid.UUID]User
		posts map[uuid.UUID]Post
	}{
		users: make(map[uuid.UUID]User),
		posts: make(map[uuid.UUID]Post),
	}
)

func init() {
	userID := uuid.New()
	mockDB.users[userID] = User{
		ID:           userID,
		Email:        "generic.user@example.com",
		PasswordHash: "hashed_password_generic",
		Role:         UserRole,
		IsActive:     true,
		CreatedAt:    time.Now(),
	}
}

// --- Generic LRU Cache Implementation ---

type cacheItem[V any] struct {
	key       string
	value     V
	expiresAt time.Time
}

type GenericCache[V any] struct {
	capacity int
	items    map[string]*list.Element
	queue    *list.List
	mu       sync.Mutex
}

func NewGenericCache[V any](capacity int) *GenericCache[V] {
	return &GenericCache[V]{
		capacity: capacity,
		items:    make(map[string]*list.Element),
		queue:    list.New(),
	}
}

func (c *GenericCache[V]) Get(key string) (V, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()

	var zeroV V
	element, exists := c.items[key]
	if !exists {
		return zeroV, false
	}

	item := element.Value.(*cacheItem[V])
	if time.Now().After(item.expiresAt) {
		c.removeElement(element)
		return zeroV, false
	}

	c.queue.MoveToFront(element)
	return item.value, true
}

func (c *GenericCache[V]) Set(key string, value V, ttl time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if element, exists := c.items[key]; exists {
		c.queue.MoveToFront(element)
		item := element.Value.(*cacheItem[V])
		item.value = value
		item.expiresAt = time.Now().Add(ttl)
		return
	}

	if c.queue.Len() >= c.capacity {
		oldest := c.queue.Back()
		if oldest != nil {
			c.removeElement(oldest)
		}
	}

	item := &cacheItem[V]{
		key:       key,
		value:     value,
		expiresAt: time.Now().Add(ttl),
	}
	element := c.queue.PushFront(item)
	c.items[key] = element
}

func (c *GenericCache[V]) Delete(key string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if element, exists := c.items[key]; exists {
		c.removeElement(element)
	}
}

func (c *GenericCache[V]) removeElement(e *list.Element) {
	c.queue.Remove(e)
	delete(c.items, e.Value.(*cacheItem[V]).key)
}

// --- Context and Middleware ---

const CacheContextKey = "AppCache"

// We use []byte as the value type to store any serialized entity.
type AppCache = GenericCache[[]byte]

func CacheInjector(cache *AppCache) gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Set(CacheContextKey, cache)
		c.Next()
	}
}

// --- Generic Cache-Aside Helper ---

func FetchFromCacheOrDB[T any](c *gin.Context, key string, fetcher func() (T, bool)) (T, bool) {
	var result T
	cache := c.MustGet(CacheContextKey).(*AppCache)

	// 1. Try to get from cache
	if data, ok := cache.Get(key); ok {
		log.Printf("CACHE HIT for key %s", key)
		if err := json.Unmarshal(data, &result); err == nil {
			return result, true
		}
	}
	log.Printf("CACHE MISS for key %s", key)

	// 2. On miss, get from database via the fetcher function
	entity, found := fetcher()
	if !found {
		return result, false
	}

	// 3. Serialize and add to cache
	bytes, err := json.Marshal(entity)
	if err == nil {
		cache.Set(key, bytes, 5*time.Minute)
		log.Printf("CACHE SET for key %s", key)
	}

	return entity, true
}

// --- API Handlers ---

func getUser(c *gin.Context) {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid id"})
		return
	}

	userFetcher := func() (User, bool) {
		mockDB.RLock()
		defer mockDB.RUnlock()
		user, ok := mockDB.users[id]
		return user, ok
	}

	user, found := FetchFromCacheOrDB(c, "user:"+id.String(), userFetcher)
	if !found {
		c.JSON(http.StatusNotFound, gin.H{"error": "user not found"})
		return
	}

	c.JSON(http.StatusOK, user)
}

func updateUser(c *gin.Context) {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid id"})
		return
	}

	var updatedUser User
	if err := c.ShouldBindJSON(&updatedUser); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	updatedUser.ID = id

	mockDB.Lock()
	mockDB.users[id] = updatedUser
	mockDB.Unlock()

	// Invalidate cache
	cache := c.MustGet(CacheContextKey).(*AppCache)
	cacheKey := "user:" + id.String()
	cache.Delete(cacheKey)
	log.Printf("CACHE INVALIDATED for key %s", cacheKey)

	c.JSON(http.StatusOK, updatedUser)
}

// --- Main Application Setup ---

func main() {
	// Initialize a generic cache that stores byte slices
	appCache := NewGenericCache[[]byte](512)

	// Setup Gin router and middleware
	router := gin.Default()
	router.Use(CacheInjector(appCache))

	// Setup routes
	router.GET("/users/:id", getUser)
	router.PUT("/users/:id", updateUser)

	log.Println("Server starting on port 8080...")
	// To test, run the server and use a tool like curl:
	// 1. First request (cache miss): curl http://localhost:8080/users/<user_id>
	// 2. Second request (cache hit): curl http://localhost:8080/users/<user_id>
	// 3. Update (invalidate): curl -X PUT -H "Content-Type: application/json" -d '{"email":"new.email.generic@example.com"}' http://localhost:8080/users/<user_id>
	// 4. Get again (cache miss): curl http://localhost:8080/users/<user_id>
	router.Run(":8080")
}