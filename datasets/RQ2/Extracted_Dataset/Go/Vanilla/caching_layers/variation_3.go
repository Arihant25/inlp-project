<pre>
package main

import (
	"container/list"
	"fmt"
	"sync"
	"time"
)

// --- Domain Models ---
type UserRole int
const ( USER_ROLE_ADMIN UserRole = 1; USER_ROLE_USER UserRole = 2 )
type PostStatus int
const ( POST_STATUS_DRAFT PostStatus = 1; POST_STATUS_PUBLISHED PostStatus = 2 )

type User struct {
	Id          string
	Email       string
	PasswordHash string
	Role        UserRole
	IsActive    bool
	CreatedAt   time.Time
}
type Post struct {
	Id      string
	UserId  string
	Title   string
	Content string
	Status  PostStatus
}

// --- Mock Datastore (Global) ---
var (
	mockDataStore = map[string]interface{}{
		"user:user-001": &User{
			Id: "user-001", Email: "singleton.user@service.net", PasswordHash: "hash123",
			Role: USER_ROLE_ADMIN, IsActive: true, CreatedAt: time.Now(),
		},
		"post:post-abc": &Post{
			Id: "post-abc", UserId: "user-001", Title: "Singleton Pattern",
			Content: "A global cache example.", Status: POST_STATUS_PUBLISHED,
		},
	}
	dbLock = sync.RWMutex{}
)

// --- Global Singleton Cache ---
type cacheEntry struct {
	key       string
	val       interface{}
	expiresAt int64
}

type GlobalLRUCache struct {
	capacity int
	items    map[string]*list.Element
	queue    *list.List
	lock     sync.Mutex
}

var (
	globalCache *GlobalLRUCache
	once        sync.Once
)

func GetGlobalCache() *GlobalLRUCache {
	once.Do(func() {
		fmt.Println("[SYSTEM] Initializing global singleton cache...")
		globalCache = &GlobalLRUCache{
			capacity: 100,
			items:    make(map[string]*list.Element),
			queue:    list.New(),
		}
	})
	return globalCache
}

func (c *GlobalLRUCache) Put(key string, val interface{}, ttl time.Duration) {
	c.lock.Lock()
	defer c.lock.Unlock()

	if elem, exists := c.items[key]; exists {
		c.queue.MoveToFront(elem)
		elem.Value.(*cacheEntry).val = val
		elem.Value.(*cacheEntry).expiresAt = time.Now().Add(ttl).UnixNano()
		return
	}

	if c.queue.Len() >= c.capacity {
		oldest := c.queue.Back()
		if oldest != nil {
			removedEntry := c.queue.Remove(oldest).(*cacheEntry)
			delete(c.items, removedEntry.key)
		}
	}

	entry := &cacheEntry{
		key:       key,
		val:       val,
		expiresAt: time.Now().Add(ttl).UnixNano(),
	}
	elem := c.queue.PushFront(entry)
	c.items[key] = elem
}

func (c *GlobalLRUCache) Read(key string) (interface{}, bool) {
	c.lock.Lock()
	defer c.lock.Unlock()

	if elem, exists := c.items[key]; exists {
		entry := elem.Value.(*cacheEntry)
		if time.Now().UnixNano() > entry.expiresAt {
			c.queue.Remove(elem)
			delete(c.items, key)
			return nil, false
		}
		c.queue.MoveToFront(elem)
		return entry.val, true
	}
	return nil, false
}

func (c *GlobalLRUCache) Purge(key string) {
	c.lock.Lock()
	defer c.lock.Unlock()
	if elem, exists := c.items[key]; exists {
		c.queue.Remove(elem)
		delete(c.items, key)
	}
}

// --- Service Functions using Global Cache (Cache-Aside) ---

func FindUserById(id string) (*User, error) {
	cacheKey := "user:" + id
	cache := GetGlobalCache()

	// 1. Try cache
	if data, found := cache.Read(cacheKey); found {
		fmt.Printf("... Cache HIT for %s\n", cacheKey)
		return data.(*User), nil
	}
	fmt.Printf("... Cache MISS for %s\n", cacheKey)

	// 2. On miss, query "DB"
	dbLock.RLock()
	time.Sleep(40 * time.Millisecond) // Simulate I/O
	data, exists := mockDataStore[cacheKey]
	dbLock.RUnlock()

	if !exists {
		return nil, fmt.Errorf("user not found in datastore")
	}
	user := data.(*User)

	// 3. Populate cache
	fmt.Printf("... Populating cache for %s\n", cacheKey)
	cache.Put(cacheKey, user, 5*time.Second)

	return user, nil
}

func UpdateUser(user *User) error {
	cacheKey := "user:" + user.Id
	
	// 1. Update "DB"
	dbLock.Lock()
	fmt.Printf("... Updating datastore for %s\n", cacheKey)
	time.Sleep(20 * time.Millisecond) // Simulate I/O
	mockDataStore[cacheKey] = user
	dbLock.Unlock()

	// 2. Invalidate cache
	fmt.Printf("... Purging cache for %s\n", cacheKey)
	GetGlobalCache().Purge(cacheKey)
	return nil
}

func main() {
	fmt.Println("--- Variation 3: All-in-One Singleton ---")
	
	userId := "user-001"

	fmt.Println("\n>>> First request for user")
	user, _ := FindUserById(userId)
	fmt.Printf("    Result: %s\n", user.Email)

	fmt.Println("\n>>> Second request for user (should be cached)")
	user, _ = FindUserById(userId)
	fmt.Printf("    Result: %s\n", user.Email)

	fmt.Println("\n>>> Updating user email and invalidating cache")
	user.Email = "new.singleton.user@service.net"
	UpdateUser(user)

	fmt.Println("\n>>> Third request for user (should be a miss)")
	user, _ = FindUserById(userId)
	fmt.Printf("    Result: %s\n", user.Email)

	fmt.Println("\n>>> Fourth request for user (should be a hit)")
	user, _ = FindUserById(userId)
	fmt.Printf("    Result: %s\n", user.Email)

	fmt.Println("\n>>> Waiting for cache to expire (5s)...")
	time.Sleep(6 * time.Second)

	fmt.Println("\n>>> Fifth request for user (should be a miss after TTL)")
	user, _ = FindUserById(userId)
	fmt.Printf("    Result: %s\n", user.Email)
}
</pre>