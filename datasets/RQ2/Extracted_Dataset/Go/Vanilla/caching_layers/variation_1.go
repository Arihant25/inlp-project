<pre>
package main

import (
	"container/list"
	"fmt"
	"sync"
	"time"
)

// --- Domain Schema ---

// Note: Using string for UUIDs to avoid external dependencies.
type UserID string
type PostID string

type UserRole int

const (
	ADMIN UserRole = iota
	USER
)

func (r UserRole) String() string {
	return [...]string{"ADMIN", "USER"}[r]
}

type PostStatus int

const (
	DRAFT PostStatus = iota
	PUBLISHED
)

func (s PostStatus) String() string {
	return [...]string{"DRAFT", "PUBLISHED"}[s]
}

type User struct {
	ID           UserID
	Email        string
	PasswordHash string
	Role         UserRole
	IsActive     bool
	CreatedAt    time.Time
}

type Post struct {
	ID      PostID
	UserID  UserID
	Title   string
	Content string
	Status  PostStatus
}

// --- Mock Data Store ---

type MockDB struct {
	users map[UserID]User
	posts map[PostID]Post
	mu    sync.RWMutex
}

func NewMockDB() *MockDB {
	user1ID := UserID("1111-1111-1111-1111")
	post1ID := PostID("aaaa-aaaa-aaaa-aaaa")
	return &MockDB{
		users: map[UserID]User{
			user1ID: {
				ID:           user1ID,
				Email:        "admin@example.com",
				PasswordHash: "hashed_password_1",
				Role:         ADMIN,
				IsActive:     true,
				CreatedAt:    time.Now().Add(-24 * time.Hour),
			},
		},
		posts: map[PostID]Post{
			post1ID: {
				ID:      post1ID,
				UserID:  user1ID,
				Title:   "First Post",
				Content: "This is the content of the first post.",
				Status:  PUBLISHED,
			},
		},
	}
}

func (db *MockDB) GetUser(id UserID) (*User, error) {
	db.mu.RLock()
	defer db.mu.RUnlock()
	fmt.Printf("[DATABASE] Fetching user with ID: %s\n", id)
	time.Sleep(50 * time.Millisecond) // Simulate DB latency
	if user, ok := db.users[id]; ok {
		return &user, nil
	}
	return nil, fmt.Errorf("user not found")
}

func (db *MockDB) UpdateUser(user *User) error {
	db.mu.Lock()
	defer db.mu.Unlock()
	fmt.Printf("[DATABASE] Updating user with ID: %s\n", user.ID)
	time.Sleep(20 * time.Millisecond) // Simulate DB latency
	db.users[user.ID] = *user
	return nil
}

// --- Caching Layer: Classic OOP Approach ---

type CacheItem struct {
	key        string
	value      interface{}
	expiresAt  time.Time
	listElement *list.Element
}

type LRUCache struct {
	capacity int
	items    map[string]*CacheItem
	evictList *list.List
	mu       sync.RWMutex
}

func NewLRUCache(capacity int) *LRUCache {
	return &LRUCache{
		capacity: capacity,
		items:    make(map[string]*CacheItem),
		evictList: list.New(),
	}
}

func (c *LRUCache) Set(key string, value interface{}, ttl time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()

	expiresAt := time.Now().Add(ttl)

	if item, ok := c.items[key]; ok {
		item.value = value
		item.expiresAt = expiresAt
		c.evictList.MoveToFront(item.listElement)
		return
	}

	if c.evictList.Len() >= c.capacity {
		c.evictOldest()
	}

	item := &CacheItem{
		key:       key,
		value:     value,
		expiresAt: expiresAt,
	}
	element := c.evictList.PushFront(item)
	item.listElement = element
	c.items[key] = item
}

func (c *LRUCache) Get(key string) (interface{}, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	if item, ok := c.items[key]; ok {
		if time.Now().After(item.expiresAt) {
			// Item has expired, but we need a write lock to delete it.
			// To avoid complex lock upgrades, we'll let the next Set or a dedicated cleanup job handle it.
			// For this Get, we treat it as a miss.
			return nil, false
		}
		c.evictList.MoveToFront(item.listElement)
		return item.value, true
	}
	return nil, false
}

func (c *LRUCache) Delete(key string) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if item, ok := c.items[key]; ok {
		c.evictList.Remove(item.listElement)
		delete(c.items, key)
	}
}

func (c *LRUCache) evictOldest() {
	// This must be called within a write lock
	oldest := c.evictList.Back()
	if oldest != nil {
		item := c.evictList.Remove(oldest).(*CacheItem)
		delete(c.items, item.key)
		fmt.Printf("[CACHE] Evicted item with key: %s\n", item.key)
	}
}

// --- Repository with Cache-Aside Pattern ---

type UserRepository struct {
	db    *MockDB
	cache *LRUCache
}

func NewUserRepository(db *MockDB, cache *LRUCache) *UserRepository {
	return &UserRepository{db: db, cache: cache}
}

func (r *UserRepository) GetUserByID(id UserID) (*User, error) {
	cacheKey := fmt.Sprintf("user:%s", id)

	// 1. Try to get from cache
	if cached, found := r.cache.Get(cacheKey); found {
		fmt.Printf("[CACHE] HIT for key: %s\n", cacheKey)
		if user, ok := cached.(*User); ok {
			return user, nil
		}
	}
	fmt.Printf("[CACHE] MISS for key: %s\n", cacheKey)

	// 2. Cache miss, get from DB
	user, err := r.db.GetUser(id)
	if err != nil {
		return nil, err
	}

	// 3. Set in cache
	fmt.Printf("[CACHE] SETTING key: %s\n", cacheKey)
	r.cache.Set(cacheKey, user, 5*time.Second)

	return user, nil
}

func (r *UserRepository) UpdateUserEmail(id UserID, newEmail string) error {
	user, err := r.GetUserByID(id)
	if err != nil {
		return err
	}
	user.Email = newEmail

	// Update the database first
	if err := r.db.UpdateUser(user); err != nil {
		return err
	}

	// Invalidate the cache
	cacheKey := fmt.Sprintf("user:%s", id)
	fmt.Printf("[CACHE] INVALIDATING key: %s\n", cacheKey)
	r.cache.Delete(cacheKey)

	return nil
}

func main() {
	fmt.Println("--- Variation 1: Classic OOP Approach ---")
	db := NewMockDB()
	cache := NewLRUCache(10)
	userRepo := NewUserRepository(db, cache)

	userID := UserID("1111-1111-1111-1111")

	// First call: Cache miss, fetches from DB
	fmt.Println("\n1. First fetch for user:")
	user, _ := userRepo.GetUserByID(userID)
	fmt.Printf("   - Fetched user: %s\n", user.Email)

	// Second call: Cache hit
	fmt.Println("\n2. Second fetch for user (should be a cache hit):")
	user, _ = userRepo.GetUserByID(userID)
	fmt.Printf("   - Fetched user: %s\n", user.Email)

	// Update user: Invalidates cache
	fmt.Println("\n3. Updating user email:")
	userRepo.UpdateUserEmail(userID, "new.admin@example.com")

	// Third call: Cache miss again due to invalidation
	fmt.Println("\n4. Fetching user after update (should be a miss):")
	user, _ = userRepo.GetUserByID(userID)
	fmt.Printf("   - Fetched user: %s\n", user.Email)

	// Wait for cache to expire
	fmt.Println("\n5. Waiting for TTL to expire (5 seconds)...")
	time.Sleep(6 * time.Second)

	// Fourth call: Cache miss due to expiration
	fmt.Println("\n6. Fetching user after TTL expiry (should be a miss):")
	user, _ = userRepo.GetUserByID(userID)
	fmt.Printf("   - Fetched user: %s\n", user.Email)
}
</pre>