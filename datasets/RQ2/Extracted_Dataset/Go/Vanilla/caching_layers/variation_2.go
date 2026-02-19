<pre>
package main

import (
	"container/list"
	"fmt"
	"sync"
	"time"
)

// --- Domain Schema ---

type UserRole string
const (
	RoleAdmin UserRole = "ADMIN"
	RoleUser  UserRole = "USER"
)

type PostStatus string
const (
	StatusDraft     PostStatus = "DRAFT"
	StatusPublished PostStatus = "PUBLISHED"
)

// Using string for UUID to avoid 3rd party libs
type User struct {
	ID          string
	Email       string
	PasswordHash string
	Role        UserRole
	IsActive    bool
	CreatedAt   time.Time
}

type Post struct {
	ID      string
	UserID  string
	Title   string
	Content string
	Status  PostStatus
}

// --- Interfaces for Abstraction ---

type Cacher interface {
	Get(key string) (any, bool)
	Set(key string, value any, ttl time.Duration)
	Delete(key string)
}

type UserDataStore interface {
	FindUserByID(id string) (*User, error)
	SaveUser(user *User) error
}

// --- Mock Data Store Implementation ---

type mockDB struct {
	users map[string]*User
	posts map[string]*Post
}

func newMockDB() *mockDB {
	userID := "user-abc-123"
	return &mockDB{
		users: map[string]*User{
			userID: {
				ID:          userID,
				Email:       "test.user@domain.com",
				PasswordHash: "secret_hash",
				Role:        RoleUser,
				IsActive:    true,
				CreatedAt:   time.Now(),
			},
		},
		posts: make(map[string]*Post),
	}
}

func (db *mockDB) FindUserByID(id string) (*User, error) {
	fmt.Printf("~> [DataStore] Querying for user ID: %s\n", id)
	time.Sleep(50 * time.Millisecond) // Simulate latency
	if u, ok := db.users[id]; ok {
		return u, nil
	}
	return nil, fmt.Errorf("user %s not found", id)
}

func (db *mockDB) SaveUser(user *User) error {
	fmt.Printf("~> [DataStore] Saving user ID: %s\n", user.ID)
	time.Sleep(25 * time.Millisecond) // Simulate latency
	db.users[user.ID] = user
	return nil
}

// --- LRU Cache Implementation ---

type lruCacheEntry struct {
	key       string
	value     any
	expiresAt time.Time
}

type lruCache struct {
	capacity int
	items    map[string]*list.Element
	ll       *list.List
	lock     sync.Mutex
}

func NewLRUCache(cap int) Cacher {
	return &lruCache{
		capacity: cap,
		items:    make(map[string]*list.Element),
		ll:       list.New(),
	}
}

func (c *lruCache) Get(key string) (any, bool) {
	c.lock.Lock()
	defer c.lock.Unlock()

	if elem, ok := c.items[key]; ok {
		entry := elem.Value.(*lruCacheEntry)
		if time.Now().After(entry.expiresAt) {
			c.removeElement(elem)
			return nil, false
		}
		c.ll.MoveToFront(elem)
		return entry.value, true
	}
	return nil, false
}

func (c *lruCache) Set(key string, value any, ttl time.Duration) {
	c.lock.Lock()
	defer c.lock.Unlock()

	if elem, ok := c.items[key]; ok {
		c.ll.MoveToFront(elem)
		entry := elem.Value.(*lruCacheEntry)
		entry.value = value
		entry.expiresAt = time.Now().Add(ttl)
		return
	}

	if c.ll.Len() >= c.capacity {
		c.evict()
	}

	entry := &lruCacheEntry{
		key:       key,
		value:     value,
		expiresAt: time.Now().Add(ttl),
	}
	elem := c.ll.PushFront(entry)
	c.items[key] = elem
}

func (c *lruCache) Delete(key string) {
	c.lock.Lock()
	defer c.lock.Unlock()
	if elem, ok := c.items[key]; ok {
		c.removeElement(elem)
	}
}

func (c *lruCache) removeElement(e *list.Element) {
	c.ll.Remove(e)
	entry := e.Value.(*lruCacheEntry)
	delete(c.items, entry.key)
}

func (c *lruCache) evict() {
	elem := c.ll.Back()
	if elem != nil {
		c.removeElement(elem)
	}
}

// --- Service Layer with Cache-Aside ---

type UserService struct {
	cache Cacher
	store UserDataStore
}

func NewUserService(c Cacher, s UserDataStore) *UserService {
	return &UserService{cache: c, store: s}
}

func (svc *UserService) GetUser(id string) (*User, error) {
	cacheKey := "user::" + id
	
	// 1. Check cache
	if val, found := svc.cache.Get(cacheKey); found {
		fmt.Printf("*> [Cache] HIT for key '%s'\n", cacheKey)
		return val.(*User), nil
	}
	fmt.Printf("*> [Cache] MISS for key '%s'\n", cacheKey)

	// 2. Fetch from store
	user, err := svc.store.FindUserByID(id)
	if err != nil {
		return nil, err
	}

	// 3. Populate cache
	fmt.Printf("*> [Cache] SET for key '%s'\n", cacheKey)
	svc.cache.Set(cacheKey, user, 10*time.Second)
	return user, nil
}

func (svc *UserService) UpdateUser(user *User) error {
	// 1. Update data store
	if err := svc.store.SaveUser(user); err != nil {
		return err
	}

	// 2. Invalidate cache
	cacheKey := "user::" + user.ID
	fmt.Printf("*> [Cache] DELETE for key '%s'\n", cacheKey)
	svc.cache.Delete(cacheKey)
	return nil
}

func main() {
	fmt.Println("--- Variation 2: Functional & Interface-Driven ---")
	
	// Dependencies
	db := newMockDB()
	cache := NewLRUCache(5)
	
	// Service
	userService := NewUserService(cache, db)
	
	userID := "user-abc-123"

	// First call -> MISS
	fmt.Println("\n[1] First call to GetUser...")
	u, _ := userService.GetUser(userID)
	fmt.Printf("    Got user: %s\n", u.Email)

	// Second call -> HIT
	fmt.Println("\n[2] Second call to GetUser...")
	u, _ = userService.GetUser(userID)
	fmt.Printf("    Got user: %s\n", u.Email)

	// Update and Invalidate
	fmt.Println("\n[3] Updating user...")
	u.Email = "updated.user@domain.com"
	userService.UpdateUser(u)

	// Third call -> MISS
	fmt.Println("\n[4] Third call to GetUser (post-update)...")
	u, _ = userService.GetUser(userID)
	fmt.Printf("    Got user: %s\n", u.Email)
}
</pre>