package main

import (
	"container/list"
	"context"
	"fmt"
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/labstack/echo/v4"
)

// --- Domain Models ---

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

// --- Mock Data Source ---

type PostRepository struct {
	posts map[uuid.UUID]Post
	mu    sync.RWMutex
}

func createPostRepository() *PostRepository {
	repo := &PostRepository{
		posts: make(map[uuid.UUID]Post),
	}
	postID := uuid.New()
	repo.posts[postID] = Post{
		ID:      postID,
		UserID:  uuid.New(),
		Title:   "First Post",
		Content: "This is the content of the first post.",
		Status:  PublishedStatus,
	}
	return repo
}

func (r *PostRepository) fetchByID(ctx context.Context, id uuid.UUID) (*Post, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	time.Sleep(120 * time.Millisecond) // Simulate I/O
	if post, found := r.posts[id]; found {
		return &post, nil
	}
	return nil, fmt.Errorf("post not found")
}

func (r *PostRepository) deleteByID(ctx context.Context, id uuid.UUID) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	time.Sleep(60 * time.Millisecond) // Simulate I/O
	if _, found := r.posts[id]; !found {
		return fmt.Errorf("post not found")
	}
	delete(r.posts, id)
	return nil
}

// --- Caching Layer (From-Scratch LRU Implementation) ---

type lruEntry struct {
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

func newLruCache(capacity int) *LruCache {
	return &LruCache{
		capacity: capacity,
		items:    make(map[string]*list.Element),
		queue:    list.New(),
	}
}

func (c *LruCache) retrieve(key string) (interface{}, bool) {
	c.lock.Lock()
	defer c.lock.Unlock()

	element, exists := c.items[key]
	if !exists {
		return nil, false
	}

	entry := element.Value.(*lruEntry)
	if time.Now().After(entry.expiresAt) {
		c.removeElement(element)
		return nil, false
	}

	c.queue.MoveToFront(element)
	return entry.value, true
}

func (c *LruCache) store(key string, value interface{}, ttl time.Duration) {
	c.lock.Lock()
	defer c.lock.Unlock()

	if element, exists := c.items[key]; exists {
		c.queue.MoveToFront(element)
		element.Value.(*lruEntry).value = value
		element.Value.(*lruEntry).expiresAt = time.Now().Add(ttl)
		return
	}

	if c.queue.Len() >= c.capacity {
		c.evict()
	}

	entry := &lruEntry{
		key:       key,
		value:     value,
		expiresAt: time.Now().Add(ttl),
	}
	element := c.queue.PushFront(entry)
	c.items[key] = element
}

func (c *LruCache) purge(key string) {
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
	delete(c.items, e.Value.(*lruEntry).key)
}

// --- Handlers (Functional Style) ---

type postHandlers struct {
	repo  *PostRepository
	cache *LruCache
}

func registerPostRoutes(e *echo.Echo, repo *PostRepository, cache *LruCache) {
	h := &postHandlers{repo, cache}
	e.GET("/posts/:id", h.getPostHandler)
	e.DELETE("/posts/:id", h.deletePostHandler)
}

func (h *postHandlers) getPostHandler(c echo.Context) error {
	postID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"message": "Invalid post ID"})
	}

	cacheKey := "post:" + postID.String()

	// 1. Check cache
	if cached, found := h.cache.retrieve(cacheKey); found {
		log.Printf("CACHE HIT: post %s", postID)
		return c.JSON(http.StatusOK, cached)
	}

	log.Printf("CACHE MISS: post %s", postID)

	// 2. Fetch from repository on miss
	post, err := h.repo.fetchByID(c.Request().Context(), postID)
	if err != nil {
		return c.JSON(http.StatusNotFound, map[string]string{"message": err.Error()})
	}

	// 3. Store in cache
	h.cache.store(cacheKey, post, 2*time.Minute)
	log.Printf("CACHE SET: post %s", postID)

	return c.JSON(http.StatusOK, post)
}

func (h *postHandlers) deletePostHandler(c echo.Context) error {
	postID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"message": "Invalid post ID"})
	}

	// 1. Delete from repository
	if err := h.repo.deleteByID(c.Request().Context(), postID); err != nil {
		return c.JSON(http.StatusNotFound, map[string]string{"message": err.Error()})
	}

	// 2. Invalidate cache
	cacheKey := "post:" + postID.String()
	h.cache.purge(cacheKey)
	log.Printf("CACHE PURGED: post %s", postID)

	return c.NoContent(http.StatusNoContent)
}

func main() {
	app := echo.New()

	// --- Dependency Setup ---
	postRepo := createPostRepository()
	lruCache := newLruCache(256) // Capacity of 256 items

	// --- Route Registration ---
	registerPostRoutes(app, postRepo, lruCache)

	// Log the seeded post ID for testing
	for id := range postRepo.posts {
		log.Printf("Server running. Test with post ID: %s\n", id)
		log.Printf("GET http://localhost:1323/posts/%s\n", id)
		log.Printf("DELETE http://localhost:1323/posts/%s\n", id)
		break
	}

	app.Logger.Fatal(app.Start(":1323"))
}