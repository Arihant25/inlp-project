package main

import (
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	lru "github.com/hashicorp/golang-lru"
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
	PasswordHash string    `json:"-"` // Omit from JSON responses
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

// --- Mock Database ---

var (
	mockUserDB = make(map[uuid.UUID]User)
	mockPostDB = make(map[uuid.UUID]Post)
	dbMutex    = &sync.RWMutex{}
)

func init() {
	// Pre-populate mock database
	userID := uuid.New()
	mockUserDB[userID] = User{
		ID:           userID,
		Email:        "test.user@example.com",
		PasswordHash: "hashed_password",
		Role:         UserRole,
		IsActive:     true,
		CreatedAt:    time.Now(),
	}
	postID := uuid.New()
	mockPostDB[postID] = Post{
		ID:      postID,
		UserID:  userID,
		Title:   "My First Post",
		Content: "This is the content of the first post.",
		Status:  PublishedStatus,
	}
}

// --- Caching Layer (Service) ---

type CacheService struct {
	userCache *lru.Cache
	postCache *lru.Cache
}

func NewCacheService(size int) (*CacheService, error) {
	userCache, err := lru.New(size)
	if err != nil {
		return nil, err
	}
	postCache, err := lru.New(size)
	if err != nil {
		return nil, err
	}
	return &CacheService{userCache: userCache, postCache: postCache}, nil
}

// --- Business Logic (Services) ---

type UserService struct {
	cache *CacheService
}

func NewUserService(cache *CacheService) *UserService {
	return &UserService{cache: cache}
}

// GetUser implements the cache-aside pattern
func (s *UserService) GetUser(id uuid.UUID) (User, bool) {
	// 1. Try to get from cache
	if cachedUser, ok := s.cache.userCache.Get(id); ok {
		log.Printf("CACHE HIT for user %s", id)
		return cachedUser.(User), true
	}
	log.Printf("CACHE MISS for user %s", id)

	// 2. On miss, get from "database"
	dbMutex.RLock()
	user, ok := mockUserDB[id]
	dbMutex.RUnlock()

	if !ok {
		return User{}, false
	}

	// 3. Add to cache
	s.cache.userCache.Add(id, user)
	log.Printf("CACHE SET for user %s", id)

	return user, true
}

func (s *UserService) UpdateUser(user User) {
	dbMutex.Lock()
	mockUserDB[user.ID] = user
	dbMutex.Unlock()

	// Invalidation strategy: remove from cache on update
	s.cache.userCache.Remove(user.ID)
	log.Printf("CACHE INVALIDATED for user %s", user.ID)
}

func (s *UserService) DeleteUser(id uuid.UUID) {
	dbMutex.Lock()
	delete(mockUserDB, id)
	dbMutex.Unlock()

	// Invalidation strategy: remove from cache on delete
	s.cache.userCache.Remove(id)
	log.Printf("CACHE INVALIDATED for user %s", id)
}

// --- API Layer (Handlers) ---

type UserHandler struct {
	service *UserService
}

func NewUserHandler(service *UserService) *UserHandler {
	return &UserHandler{service: service}
}

func (h *UserHandler) GetUserByID(c *gin.Context) {
	idStr := c.Param("id")
	id, err := uuid.Parse(idStr)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid UUID format"})
		return
	}

	user, found := h.service.GetUser(id)
	if !found {
		c.JSON(http.StatusNotFound, gin.H{"error": "User not found"})
		return
	}

	c.JSON(http.StatusOK, user)
}

func (h *UserHandler) UpdateUser(c *gin.Context) {
	idStr := c.Param("id")
	id, err := uuid.Parse(idStr)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid UUID format"})
		return
	}

	var updatedUser User
	if err := c.ShouldBindJSON(&updatedUser); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	// Ensure the ID from the path is used
	updatedUser.ID = id
	h.service.UpdateUser(updatedUser)

	c.JSON(http.StatusOK, updatedUser)
}

func (h *UserHandler) DeleteUser(c *gin.Context) {
	idStr := c.Param("id")
	id, err := uuid.Parse(idStr)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid UUID format"})
		return
	}

	h.service.DeleteUser(id)
	c.Status(http.StatusNoContent)
}

// --- Main Application Setup ---

func main() {
	// Initialize cache
	cacheService, err := NewCacheService(128) // LRU cache with capacity 128
	if err != nil {
		log.Fatalf("Failed to create cache service: %v", err)
	}

	// Initialize services
	userService := NewUserService(cacheService)

	// Initialize handlers
	userHandler := NewUserHandler(userService)

	// Setup Gin router
	router := gin.Default()
	userRoutes := router.Group("/users")
	{
		userRoutes.GET("/:id", userHandler.GetUserByID)
		userRoutes.PUT("/:id", userHandler.UpdateUser)
		userRoutes.DELETE("/:id", userHandler.DeleteUser)
	}

	log.Println("Server starting on port 8080...")
	// To test, run the server and use a tool like curl:
	// 1. First request (cache miss): curl http://localhost:8080/users/<user_id>
	// 2. Second request (cache hit): curl http://localhost:8080/users/<user_id>
	// 3. Update (invalidate): curl -X PUT -H "Content-Type: application/json" -d '{"email":"new.email@example.com"}' http://localhost:8080/users/<user_id>
	// 4. Get again (cache miss): curl http://localhost:8080/users/<user_id>
	router.Run(":8080")
}