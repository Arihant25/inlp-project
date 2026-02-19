package main

import (
	"context"
	"fmt"
	"log"
	"sync"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/gofiber/fiber/v2/middleware/logger"
	"github.com/google/uuid"
	lru "github.com/hashicorp/lru/v2"
)

// --- DOMAIN MODELS ---

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

// --- REPOSITORY LAYER ---

// UserRepository defines the contract for user data access.
type UserRepository interface {
	FindByID(ctx context.Context, id uuid.UUID) (User, error)
	Update(ctx context.Context, user User) error
}

// dbUserRepository is the concrete implementation that talks to the database.
type dbUserRepository struct {
	db *sync.Map
}

func NewDBUserRepository() UserRepository {
	db := &sync.Map{}
	// Seed data
	adminID := uuid.New()
	db.Store(adminID, User{ID: adminID, Email: "admin@example.com", Role: AdminRole, IsActive: true, CreatedAt: time.Now()})
	fmt.Println("Database seeded.")
	fmt.Printf("Admin User ID: %s\n", adminID)
	return &dbUserRepository{db: db}
}

func (r *dbUserRepository) FindByID(ctx context.Context, id uuid.UUID) (User, error) {
	// Simulate DB latency
	time.Sleep(50 * time.Millisecond)
	if val, ok := r.db.Load(id); ok {
		return val.(User), nil
	}
	return User{}, fmt.Errorf("user %s not found in DB", id)
}

func (r *dbUserRepository) Update(ctx context.Context, user User) error {
	time.Sleep(20 * time.Millisecond)
	r.db.Store(user.ID, user)
	return nil
}

// cachedUserRepository is a Decorator that adds caching to another UserRepository.
type cachedUserRepository struct {
	nextRepo UserRepository
	cache    *lru.Cache[uuid.UUID, User]
}

func NewCachedUserRepository(next UserRepository) UserRepository {
	cache, err := lru.New[uuid.UUID, User](100)
	if err != nil {
		log.Fatalf("failed to create cache: %v", err)
	}
	return &cachedUserRepository{
		nextRepo: next,
		cache:    cache,
	}
}

// FindByID implements the cache-aside pattern.
func (r *cachedUserRepository) FindByID(ctx context.Context, id uuid.UUID) (User, error) {
	// 1. Check cache
	if user, ok := r.cache.Get(id); ok {
		fmt.Printf("REPO CACHE HIT for user %s\n", id)
		return user, nil
	}

	fmt.Printf("REPO CACHE MISS for user %s\n", id)
	// 2. On miss, call the next repository in the chain (the DB one)
	user, err := r.nextRepo.FindByID(ctx, id)
	if err != nil {
		return User{}, err
	}

	// 3. Store the result in the cache
	r.cache.Add(id, user)
	return user, nil
}

// Update implements cache invalidation.
func (r *cachedUserRepository) Update(ctx context.Context, user User) error {
	// 1. Update the underlying database first
	err := r.nextRepo.Update(ctx, user)
	if err != nil {
		return err
	}

	// 2. Invalidate the cache
	fmt.Printf("INVALIDATING REPO CACHE for user %s\n", user.ID)
	r.cache.Remove(user.ID)
	return nil
}

// --- SERVICE LAYER ---

// The service layer is completely unaware of caching. It just uses the interface.
type UserService struct {
	userRepo UserRepository
}

func NewUserService(repo UserRepository) *UserService {
	return &UserService{userRepo: repo}
}

func (s *UserService) GetUser(ctx context.Context, id uuid.UUID) (User, error) {
	return s.userRepo.FindByID(ctx, id)
}

func (s *UserService) UpdateUserActivity(ctx context.Context, id uuid.UUID, isActive bool) (User, error) {
	user, err := s.userRepo.FindByID(ctx, id)
	if err != nil {
		return User{}, err
	}
	user.IsActive = isActive
	err = s.userRepo.Update(ctx, user)
	return user, err
}

// --- HANDLER LAYER ---

type UserHandler struct {
	userService *UserService
}

func NewUserHandler(service *UserService) *UserHandler {
	return &UserHandler{userService: service}
}

func (h *UserHandler) GetUser(c *fiber.Ctx) error {
	id, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "invalid user ID"})
	}

	user, err := h.userService.GetUser(c.Context(), id)
	if err != nil {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": err.Error()})
	}
	return c.JSON(user)
}

func (h *UserHandler) UpdateUser(c *fiber.Ctx) error {
	id, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "invalid user ID"})
	}

	var payload struct {
		IsActive bool `json:"is_active"`
	}
	if err := c.BodyParser(&payload); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "invalid request body"})
	}

	user, err := h.userService.UpdateUserActivity(c.Context(), id, payload.IsActive)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": err.Error()})
	}
	return c.JSON(user)
}

// --- MAIN APPLICATION ---
// To run this:
// 1. go mod init example.com/cache
// 2. go get github.com/gofiber/fiber/v2
// 3. go get github.com/google/uuid
// 4. go get github.com/hashicorp/lru/v2
// 5. go run .
func main() {
	// --- Dependency Injection Setup ---
	// 1. Create the base DB repository
	dbRepo := NewDBUserRepository()
	// 2. Decorate it with the caching repository
	cachedRepo := NewCachedUserRepository(dbRepo)
	// 3. Inject the decorated (cached) repository into the service.
	// The service doesn't know or care that it's cached.
	userService := NewUserService(cachedRepo)
	// 4. Inject the service into the handler.
	userHandler := NewUserHandler(userService)

	app := fiber.New()
	app.Use(logger.New())

	app.Get("/users/:id", userHandler.GetUser)
	app.Patch("/users/:id", userHandler.UpdateUser)

	log.Fatal(app.Listen(":3000"))
}