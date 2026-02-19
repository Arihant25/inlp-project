package main

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/hashicorp/lru/v2"
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

// --- Generic Interfaces ---

type Identifiable interface {
	GetID() uuid.UUID
}

func (p Post) GetID() uuid.UUID {
	return p.ID
}

type Repository[T Identifiable] interface {
	FindByID(ctx context.Context, id uuid.UUID) (*T, error)
	Save(ctx context.Context, entity T) error
	Delete(ctx context.Context, id uuid.UUID) error
}

// --- Mock Concrete Repository ---

type MockPostRepository struct {
	posts map[uuid.UUID]Post
	mu    sync.RWMutex
}

func NewMockPostRepository() *MockPostRepository {
	repo := &MockPostRepository{posts: make(map[uuid.UUID]Post)}
	postID := uuid.New()
	repo.posts[postID] = Post{
		ID:      postID,
		UserID:  uuid.New(),
		Title:   "Generic Caching",
		Content: "A post about generic repository patterns.",
		Status:  PublishedStatus,
	}
	return repo
}

func (r *MockPostRepository) FindByID(ctx context.Context, id uuid.UUID) (*Post, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	time.Sleep(100 * time.Millisecond) // Simulate latency
	if post, ok := r.posts[id]; ok {
		return &post, nil
	}
	return nil, fmt.Errorf("post %s not found", id)
}

func (r *MockPostRepository) Save(ctx context.Context, entity Post) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	time.Sleep(50 * time.Millisecond)
	r.posts[entity.ID] = entity
	return nil
}

func (r *MockPostRepository) Delete(ctx context.Context, id uuid.UUID) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	delete(r.posts, id)
	return nil
}

// --- Generic Caching Decorator ---

type cacheEntry[V any] struct {
	value     V
	expiresAt time.Time
}

type CachedRepository[T Identifiable] struct {
	primaryRepo Repository[T]
	cache       *lru.Cache[string, cacheEntry[T]]
	ttl         time.Duration
	keyPrefix   string
}

func NewCachedRepository[T Identifiable](repo Repository[T], cache *lru.Cache[string, cacheEntry[T]], ttl time.Duration, keyPrefix string) Repository[T] {
	return &CachedRepository[T]{
		primaryRepo: repo,
		cache:       cache,
		ttl:         ttl,
		keyPrefix:   keyPrefix,
	}
}

func (r *CachedRepository[T]) FindByID(ctx context.Context, id uuid.UUID) (*T, error) {
	cacheKey := fmt.Sprintf("%s:%s", r.keyPrefix, id)

	// 1. Check cache
	if entry, ok := r.cache.Get(cacheKey); ok && time.Now().Before(entry.expiresAt) {
		log.Printf("CACHE HIT for key %s", cacheKey)
		// Return a pointer to a copy to avoid race conditions if the caller modifies it
		valCopy := entry.value
		return &valCopy, nil
	}

	log.Printf("CACHE MISS for key %s", cacheKey)
	// 2. On miss, call primary repository
	entity, err := r.primaryRepo.FindByID(ctx, id)
	if err != nil {
		return nil, err
	}

	// 3. Store in cache
	newEntry := cacheEntry[T]{
		value:     *entity,
		expiresAt: time.Now().Add(r.ttl),
	}
	r.cache.Add(cacheKey, newEntry)
	log.Printf("CACHE SET for key %s", cacheKey)

	return entity, nil
}

func (r *CachedRepository[T]) Save(ctx context.Context, entity T) error {
	// 1. Save to primary repository first
	if err := r.primaryRepo.Save(ctx, entity); err != nil {
		return err
	}

	// 2. Invalidate cache
	cacheKey := fmt.Sprintf("%s:%s", r.keyPrefix, entity.GetID())
	r.cache.Remove(cacheKey)
	log.Printf("CACHE INVALIDATED for key %s", cacheKey)
	return nil
}

func (r *CachedRepository[T]) Delete(ctx context.Context, id uuid.UUID) error {
	if err := r.primaryRepo.Delete(ctx, id); err != nil {
		return err
	}
	cacheKey := fmt.Sprintf("%s:%s", r.keyPrefix, id)
	r.cache.Remove(cacheKey)
	log.Printf("CACHE INVALIDATED for key %s", cacheKey)
	return nil
}

// --- Echo Handlers ---

type PostController struct {
	repo Repository[Post]
}

func (ctrl *PostController) getPost(c echo.Context) error {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		return c.JSON(http.StatusBadRequest, echo.Map{"error": "Invalid ID"})
	}

	post, err := ctrl.repo.FindByID(c.Request().Context(), id)
	if err != nil {
		return c.JSON(http.StatusNotFound, echo.Map{"error": err.Error()})
	}
	return c.JSON(http.StatusOK, post)
}

func (ctrl *PostController) updatePostStatus(c echo.Context) error {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		return c.JSON(http.StatusBadRequest, echo.Map{"error": "Invalid ID"})
	}

	post, err := ctrl.repo.FindByID(c.Request().Context(), id)
	if err != nil {
		return c.JSON(http.StatusNotFound, echo.Map{"error": err.Error()})
	}

	post.Status = DraftStatus // Change status for demonstration
	if err := ctrl.repo.Save(c.Request().Context(), *post); err != nil {
		return c.JSON(http.StatusInternalServerError, echo.Map{"error": "Failed to save post"})
	}

	return c.JSON(http.StatusOK, post)
}

func main() {
	e := echo.New()

	// --- Dependency Setup ---
	// Create a generic LRU cache instance
	genericCache, err := lru.New[string, cacheEntry[Post]](1000)
	if err != nil {
		log.Fatalf("Failed to create LRU cache: %v", err)
	}

	// Create the base repository
	postDB := NewMockPostRepository()

	// Decorate the base repository with the caching layer
	cachedPostRepo := NewCachedRepository[Post](postDB, genericCache, 5*time.Minute, "post")

	// Inject the decorated repository into the controller
	postController := &PostController{repo: cachedPostRepo}

	// --- Routes ---
	e.GET("/posts/:id", postController.getPost)
	e.PUT("/posts/:id/draft", postController.updatePostStatus)

	// Log the seeded post ID for testing
	for id := range postDB.posts {
		log.Printf("Server is up. Test with post ID: %s\n", id)
		log.Printf("GET http://localhost:1323/posts/%s\n", id)
		log.Printf("PUT http://localhost:1323/posts/%s/draft\n", id)
		break
	}

	e.Logger.Fatal(e.Start(":1323"))
}