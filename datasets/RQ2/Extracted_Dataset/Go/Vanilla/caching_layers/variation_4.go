<pre>
package main

import (
	"container/list"
	"context"
	"fmt"
	"sync"
	"time"
)

// --- Domain Schema ---
type UserRole int
const (ROLE_ADMIN UserRole = 0; ROLE_USER UserRole = 1)
type PostStatus int
const (STATUS_DRAFT PostStatus = 0; STATUS_PUBLISHED PostStatus = 1)

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

// --- Mock Data Source ---
type DataSource struct {
	users map[string]*User
	posts map[string]*Post
	mu    sync.RWMutex
}

func NewDataSource() *DataSource {
	uid := "user-con-curr-ent"
	return &DataSource{
		users: map[string]*User{
			uid: {ID: uid, Email: "concurrent@example.org", PasswordHash: "hash", Role: ROLE_ADMIN, IsActive: true},
		},
		posts: make(map[string]*Post),
	}
}

func (ds *DataSource) FetchUser(id string) (*User, error) {
	ds.mu.RLock()
	defer ds.mu.RUnlock()
	fmt.Printf("[DB] >> Fetching user %s\n", id)
	time.Sleep(30 * time.Millisecond) // Simulate network latency
	if u, ok := ds.users[id]; ok {
		return u, nil
	}
	return nil, fmt.Errorf("not found")
}

func (ds *DataSource) PersistUser(u *User) error {
	ds.mu.Lock()
	defer ds.mu.Unlock()
	fmt.Printf("[DB] >> Persisting user %s\n", u.ID)
	time.Sleep(15 * time.Millisecond)
	ds.users[u.ID] = u
	return nil
}

// --- Concurrency-Focused Caching Layer ---
type cacheNode struct {
	key       string
	value     any
	expiresAt time.Time
}

type ConcurrentLRUCache struct {
	capacity  int
	lookup    map[string]*list.Element
	evictList *list.List
	mu        sync.RWMutex
	stopChan  chan struct{}
}

func NewConcurrentLRUCache(ctx context.Context, capacity int, cleanupInterval time.Duration) *ConcurrentLRUCache {
	c := &ConcurrentLRUCache{
		capacity:  capacity,
		lookup:    make(map[string]*list.Element),
		evictList: list.New(),
		stopChan:  make(chan struct{}),
	}
	go c.startCleanupTicker(ctx, cleanupInterval)
	return c
}

func (c *ConcurrentLRUCache) Get(key string) (any, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if elem, ok := c.lookup[key]; ok {
		node := elem.Value.(*cacheNode)
		if time.Now().After(node.expiresAt) {
			c.removeElement(elem)
			return nil, false
		}
		c.evictList.MoveToFront(elem)
		return node.value, true
	}
	return nil, false
}

func (c *ConcurrentLRUCache) Set(key string, value any, ttl time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if elem, ok := c.lookup[key]; ok {
		c.evictList.MoveToFront(elem)
		node := elem.Value.(*cacheNode)
		node.value = value
		node.expiresAt = time.Now().Add(ttl)
		return
	}

	if c.evictList.Len() >= c.capacity {
		oldest := c.evictList.Back()
		if oldest != nil {
			c.removeElement(oldest)
		}
	}

	node := &cacheNode{
		key:       key,
		value:     value,
		expiresAt: time.Now().Add(ttl),
	}
	elem := c.evictList.PushFront(node)
	c.lookup[key] = elem
}

func (c *ConcurrentLRUCache) Delete(key string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if elem, ok := c.lookup[key]; ok {
		c.removeElement(elem)
	}
}

func (c *ConcurrentLRUCache) removeElement(e *list.Element) {
	// Must be called within a write lock
	c.evictList.Remove(e)
	node := e.Value.(*cacheNode)
	delete(c.lookup, node.key)
}

func (c *ConcurrentLRUCache) startCleanupTicker(ctx context.Context, interval time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ticker.C:
			c.mu.Lock()
			for key, elem := range c.lookup {
				node := elem.Value.(*cacheNode)
				if time.Now().After(node.expiresAt) {
					fmt.Printf("[CACHE-CLEANUP] Purging expired key: %s\n", key)
					c.removeElement(elem)
				}
			}
			c.mu.Unlock()
		case <-c.stopChan:
			return
		case <-ctx.Done():
			return
		}
	}
}

func (c *ConcurrentLRUCache) StopCleanup() {
	close(c.stopChan)
}

// --- Data Service with Cache-Aside ---
type DataService struct {
	db    *DataSource
	cache *ConcurrentLRUCache
}

func (s *DataService) GetUser(id string) (*User, error) {
	key := "user/" + id
	if val, ok := s.cache.Get(key); ok {
		fmt.Printf("[CACHE] HIT: %s\n", key)
		return val.(*User), nil
	}
	fmt.Printf("[CACHE] MISS: %s\n", key)

	user, err := s.db.FetchUser(id)
	if err != nil {
		return nil, err
	}

	s.cache.Set(key, user, 2*time.Second)
	return user, nil
}

func (s *DataService) UpdateUser(u *User) error {
	if err := s.db.PersistUser(u); err != nil {
		return err
	}
	key := "user/" + u.ID
	fmt.Printf("[CACHE] INVALIDATE: %s\n", key)
	s.cache.Delete(key)
	return nil
}

func main() {
	fmt.Println("--- Variation 4: Concurrency-Focused ---")
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	db := NewDataSource()
	cache := NewConcurrentLRUCache(ctx, 10, 5*time.Second)
	defer cache.StopCleanup()
	service := &DataService{db: db, cache: cache}
	userID := "user-con-curr-ent"

	var wg sync.WaitGroup
	// Simulate 5 concurrent readers
	for i := 0; i < 5; i++ {
		wg.Add(1)
		go func(workerID int) {
			defer wg.Done()
			fmt.Printf("[Worker %d] Fetching user...\n", workerID)
			user, err := service.GetUser(userID)
			if err != nil {
				fmt.Printf("[Worker %d] Error: %v\n", workerID, err)
			} else {
				fmt.Printf("[Worker %d] Got user: %s\n", workerID, user.Email)
			}
		}(i)
	}
	wg.Wait()

	fmt.Println("\n--- Second batch of reads (should be all hits) ---")
	for i := 0; i < 3; i++ {
		wg.Add(1)
		go func(workerID int) {
			defer wg.Done()
			user, _ := service.GetUser(userID)
			fmt.Printf("[Worker %d] Got user again: %s\n", workerID, user.Email)
		}(i)
	}
	wg.Wait()

	fmt.Println("\n--- Updating user ---")
	user, _ := service.GetUser(userID)
	user.Email = "updated.concurrent@example.org"
	service.UpdateUser(user)

	fmt.Println("\n--- Final read after invalidation (should be a miss) ---")
	finalUser, _ := service.GetUser(userID)
	fmt.Printf("Final user email: %s\n", finalUser.Email)
}
</pre>