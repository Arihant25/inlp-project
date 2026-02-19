package main

import (
	"context"
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	lru "github.com/golang-lru/lru/v2"
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

// --- Mock Database ---

var (
	userDatabase = make(map[uuid.UUID]User)
	dbLock       = &sync.RWMutex{}
)

func init() {
	userID := uuid.New()
	userDatabase[userID] = User{
		ID:           userID,
		Email:        "repo.user@example.com",
		PasswordHash: "hashed_password_repo",
		Role:         UserRole,
		IsActive:     true,
		CreatedAt:    time.Now(),
	}
}

// --- Data Layer (Repository Pattern) ---

type UserRepository interface {
	FindByID(ctx context.Context, id uuid.UUID) (User, error)
	Update(ctx context.Context, user User) error
	Delete(ctx context.Context, id uuid.UUID) error
}

// userMemoryRepository is the "real" repository talking to the database
type userMemoryRepository struct{}

func NewUserMemoryRepository() UserRepository {
	return &userMemoryRepository{}
}

func (r *userMemoryRepository) FindByID(ctx context.Context, id uuid.UUID) (User, error) {
	log.Printf("DATABASE: Querying for user %s", id)
	time.Sleep(50 * time.Millisecond) // Simulate DB latency
	dbLock.RLock()
	defer dbLock.RUnlock()
	user, ok := userDatabase[id]
	if !ok {
		return User{}, http.ErrNoCookie
	}
	return user, nil
}

func (r *userMemoryRepository) Update(ctx context.Context, user User) error {
	log.Printf("DATABASE: Updating user %s", user.ID)
	dbLock.Lock()
	defer dbLock.Unlock()
	userDatabase[user.ID] = user
	return nil
}

func (r *userMemoryRepository) Delete(ctx context.Context, id uuid.UUID) error {
	log.Printf("DATABASE: Deleting user %s", id)
	dbLock.Lock()
	defer dbLock.Unlock()
	delete(userDatabase, id)
	return nil
}

// userCacheRepository is a decorator that adds caching to another UserRepository
type userCacheRepository struct {
	next UserRepository
	lru  *lru.Cache[uuid.UUID, User]
}

func NewUserCacheRepository(nextRepo UserRepository, cache *lru.Cache[uuid.UUID, User]) UserRepository {
	return &userCacheRepository{
		next: nextRepo,
		lru:  cache,
	}
}

func (r *userCacheRepository) FindByID(ctx context.Context, id uuid.UUID) (User, error) {
	// 1. Check cache
	if user, ok := r.lru.Get(id); ok {
		log.Printf("CACHE HIT for user %s", id)
		return user, nil
	}
	log.Printf("CACHE MISS for user %s", id)

	// 2. On miss, call the next repository in the chain (the real DB)
	user, err := r.next.FindByID(ctx, id)
	if err != nil {
		return User{}, err
	}

	// 3. Store result in cache
	r.lru.Add(id, user)
	log.Printf("CACHE SET for user %s", id)
	return user, nil
}

func (r *userCacheRepository) Update(ctx context.Context, user User) error {
	// 1. Update the database first
	if err := r.next.Update(ctx, user); err != nil {
		return err
	}
	// 2. Invalidate cache
	r.lru.Remove(user.ID)
	log.Printf("CACHE INVALIDATED for user %s", user.ID)
	return nil
}

func (r *userCacheRepository) Delete(ctx context.Context, id uuid.UUID) error {
	if err := r.next.Delete(ctx, id); err != nil {
		return err
	}
	r.lru.Remove(id)
	log.Printf("CACHE INVALIDATED for user %s", id)
	return nil
}

// --- Service Layer ---

type AppService struct {
	UserRepo UserRepository
}

// --- Handler Layer ---

type UserAPIHandler struct {
	Service *AppService
}

func (h *UserAPIHandler) FindUser(c *gin.Context) {
	userID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid user ID"})
		return
	}

	user, err := h.Service.UserRepo.FindByID(c.Request.Context(), userID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "user not found"})
		return
	}
	c.JSON(http.StatusOK, user)
}

func (h *UserAPIHandler) ModifyUser(c *gin.Context) {
	userID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid user ID"})
		return
	}
	var user User
	if err := c.ShouldBindJSON(&user); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	user.ID = userID // Ensure ID from URL is authoritative

	if err := h.Service.UserRepo.Update(c.Request.Context(), user); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "could not update user"})
		return
	}
	c.JSON(http.StatusOK, user)
}

// --- Main Application ---

func main() {
	// 1. Setup Cache
	userCache, err := lru.New[uuid.UUID, User](256)
	if err != nil {
		log.Fatalf("Could not create LRU cache: %v", err)
	}

	// 2. Wire up repositories (Decorator Pattern)
	dbRepo := NewUserMemoryRepository()
	cacheRepo := NewUserCacheRepository(dbRepo, userCache)

	// 3. Wire up service and handler
	appService := &AppService{UserRepo: cacheRepo}
	userHandler := &UserAPIHandler{Service: appService}

	// 4. Setup Gin
	r := gin.Default()
	r.GET("/users/:id", userHandler.FindUser)
	r.PUT("/users/:id", userHandler.ModifyUser)

	log.Println("Server starting on port 8080...")
	// To test, run the server and use a tool like curl:
	// 1. First request (cache miss): curl http://localhost:8080/users/<user_id>
	// 2. Second request (cache hit): curl http://localhost:8080/users/<user_id>
	// 3. Update (invalidate): curl -X PUT -H "Content-Type: application/json" -d '{"email":"new.email.repo@example.com"}' http://localhost:8080/users/<user_id>
	// 4. Get again (cache miss): curl http://localhost:8080/users/<user_id>
	r.Run(":8080")
}