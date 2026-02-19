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

// --- Mock "Slow" Datastore ---

var (
	userStore = make(map[string]User)
	postStore = make(map[string]Post)
	storeLock = &sync.RWMutex{}
)

func init() {
	userID := uuid.New()
	userStore[userID.String()] = User{
		ID:           userID,
		Email:        "functional.user@example.com",
		PasswordHash: "hashed_password_func",
		Role:         UserRole,
		IsActive:     true,
		CreatedAt:    time.Now(),
	}
}

// --- Custom LRU Cache Implementation ---

type cacheEntry struct {
	key       string
	value     interface{}
	expiresAt time.Time
}

type LruCache struct {
	capacity int
	items    map[string]*list.Element
	queue    *list.List
	lock     sync.Mutex
}

func NewLruCache(capacity int) *LruCache {
	return &LruCache{
		capacity: capacity,
		items:    make(map[string]*list.Element),
		queue:    list.New(),
	}
}

func (c *LruCache) Get(key string) (interface{}, bool) {
	c.lock.Lock()
	defer c.lock.Unlock()

	element, exists := c.items[key]
	if !exists {
		return nil, false
	}

	entry := element.Value.(*cacheEntry)
	if time.Now().After(entry.expiresAt) {
		c.removeElement(element)
		return nil, false
	}

	c.queue.MoveToFront(element)
	return entry.value, true
}

func (c *LruCache) Set(key string, value interface{}, ttl time.Duration) {
	c.lock.Lock()
	defer c.lock.Unlock()

	if element, exists := c.items[key]; exists {
		c.queue.MoveToFront(element)
		element.Value.(*cacheEntry).value = value
		element.Value.(*cacheEntry).expiresAt = time.Now().Add(ttl)
		return
	}

	if c.queue.Len() == c.capacity {
		c.evict()
	}

	entry := &cacheEntry{
		key:       key,
		value:     value,
		expiresAt: time.Now().Add(ttl),
	}
	element := c.queue.PushFront(entry)
	c.items[key] = element
}

func (c *LruCache) Delete(key string) {
	c.lock.Lock()
	defer c.lock.Unlock()

	if element, exists := c.items[key]; exists {
		c.removeElement(element)
	}
}

func (c *LruCache) evict() {
	element := c.queue.Back()
	if element != nil {
		c.removeElement(element)
	}
}

func (c *LruCache) removeElement(e *list.Element) {
	c.queue.Remove(e)
	delete(c.items, e.Value.(*cacheEntry).key)
}

// --- API Handlers (Functional Style) ---

func getUser(c *gin.Context) {
	userID := c.Param("id")
	cache := c.MustGet("cache").(*LruCache)

	// Cache-Aside: The handler is responsible for setting the cache on miss
	storeLock.RLock()
	user, ok := userStore[userID]
	storeLock.RUnlock()

	if !ok {
		c.JSON(http.StatusNotFound, gin.H{"error": "User not found"})
		return
	}

	// Simulate DB latency
	time.Sleep(100 * time.Millisecond)

	// Set cache for subsequent requests
	cache.Set(userID, user, 5*time.Minute)
	log.Printf("DATABASE HIT & CACHE SET for user %s", userID)

	c.JSON(http.StatusOK, user)
}

func updateUser(c *gin.Context) {
	userID := c.Param("id")
	cache := c.MustGet("cache").(*LruCache)

	var reqBody struct {
		Email string `json:"email"`
	}
	if err := c.ShouldBindJSON(&reqBody); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	storeLock.Lock()
	user, ok := userStore[userID]
	if ok {
		user.Email = reqBody.Email
		userStore[userID] = user
	}
	storeLock.Unlock()

	if !ok {
		c.JSON(http.StatusNotFound, gin.H{"error": "User not found"})
		return
	}

	// Invalidation strategy: delete from cache
	cache.Delete(userID)
	log.Printf("CACHE INVALIDATED for user %s", userID)

	c.JSON(http.StatusOK, user)
}

// --- Gin Middleware ---

func CacheMiddleware(cache *LruCache) gin.HandlerFunc {
	return func(c *gin.Context) {
		// Inject cache for all handlers to use
		c.Set("cache", cache)

		// Only apply GET caching logic for specific methods/paths
		if c.Request.Method != "GET" {
			c.Next()
			return
		}

		userID := c.Param("id")
		if userID == "" {
			c.Next()
			return
		}

		// Cache-Aside: Check cache first
		cached, found := cache.Get(userID)
		if found {
			log.Printf("CACHE HIT for user %s", userID)
			c.JSON(http.StatusOK, cached)
			c.Abort() // Stop processing chain
			return
		}

		log.Printf("CACHE MISS for user %s", userID)
		c.Next() // Proceed to the handler to fetch from DB
	}
}

func main() {
	appCache := NewLruCache(100)
	router := gin.Default()

	userGroup := router.Group("/users")
	userGroup.Use(CacheMiddleware(appCache))
	{
		userGroup.GET("/:id", getUser)
		userGroup.PUT("/:id", updateUser)
	}

	log.Println("Server starting on port 8080...")
	// To test, run the server and use a tool like curl:
	// 1. First request (cache miss): curl http://localhost:8080/users/<user_id>
	// 2. Second request (cache hit): curl http://localhost:8080/users/<user_id>
	// 3. Update (invalidate): curl -X PUT -H "Content-Type: application/json" -d '{"email":"new.email.func@example.com"}' http://localhost:8080/users/<user_id>
	// 4. Get again (cache miss): curl http://localhost:8080/users/<user_id>
	router.Run(":8080")
}