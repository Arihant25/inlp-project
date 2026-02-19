package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/gofiber/fiber/v2/middleware/logger"
	"github.com/google/uuid"
)

// --- Domain Layer ---

type Role string

const (
	RoleAdmin Role = "ADMIN"
	RoleUser  Role = "USER"
)

type User struct {
	ID           uuid.UUID
	Email        string
	PasswordHash string
	Role         Role
	IsActive     bool
	CreatedAt    time.Time
}

type Post struct {
	ID      uuid.UUID
	UserID  uuid.UUID
	Title   string
	Content string
	Status  string // DRAFT, PUBLISHED
}

// --- DTOs (Data Transfer Objects) ---

type CreateUserRequest struct {
	Email    string `json:"email"`
	Password string `json:"password"`
	Role     Role   `json:"role"`
}

type UpdateUserRequest struct {
	Email    string `json:"email"`
	Role     Role   `json:"role"`
	IsActive bool   `json:"is_active"`
}

type UserResponse struct {
	ID        uuid.UUID `json:"id"`
	Email     string    `json:"email"`
	Role      Role      `json:"role"`
	IsActive  bool      `json:"is_active"`
	CreatedAt time.Time `json:"created_at"`
}

func toUserResponse(user *User) UserResponse {
	return UserResponse{
		ID:        user.ID,
		Email:     user.Email,
		Role:      user.Role,
		IsActive:  user.IsActive,
		CreatedAt: user.CreatedAt,
	}
}

// --- Repository Layer ---

type UserRepository interface {
	Save(ctx context.Context, user *User) error
	FindByID(ctx context.Context, id uuid.UUID) (*User, error)
	FindByEmail(ctx context.Context, email string) (*User, error)
	FindAll(ctx context.Context, offset, limit int, filters map[string]interface{}) ([]*User, int, error)
	Delete(ctx context.Context, id uuid.UUID) error
}

type memoryUserRepository struct {
	users map[uuid.UUID]*User
	mu    sync.RWMutex
}

func NewMemoryUserRepository() UserRepository {
	repo := &memoryUserRepository{users: make(map[uuid.UUID]*User)}
	// Seed
	admin := &User{ID: uuid.New(), Email: "admin@example.com", PasswordHash: "hash1", Role: RoleAdmin, IsActive: true, CreatedAt: time.Now()}
	user := &User{ID: uuid.New(), Email: "user@example.com", PasswordHash: "hash2", Role: RoleUser, IsActive: false, CreatedAt: time.Now()}
	repo.users[admin.ID] = admin
	repo.users[user.ID] = user
	return repo
}

func (r *memoryUserRepository) Save(ctx context.Context, user *User) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.users[user.ID] = user
	return nil
}

func (r *memoryUserRepository) FindByID(ctx context.Context, id uuid.UUID) (*User, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	user, ok := r.users[id]
	if !ok {
		return nil, errors.New("user not found")
	}
	return user, nil
}

func (r *memoryUserRepository) FindByEmail(ctx context.Context, email string) (*User, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	for _, u := range r.users {
		if u.Email == email {
			return u, nil
		}
	}
	return nil, errors.New("user not found")
}

func (r *memoryUserRepository) FindAll(ctx context.Context, offset, limit int, filters map[string]interface{}) ([]*User, int, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	
	var allUsers []*User
	for _, u := range r.users {
		allUsers = append(allUsers, u)
	}

	// Filtering logic
	var filteredUsers []*User
	for _, user := range allUsers {
		match := true
		if role, ok := filters["role"].(Role); ok && user.Role != role {
			match = false
		}
		if isActive, ok := filters["is_active"].(bool); ok && user.IsActive != isActive {
			match = false
		}
		if match {
			filteredUsers = append(filteredUsers, user)
		}
	}

	total := len(filteredUsers)
	start := offset
	end := offset + limit
	if start > total { start = total }
	if end > total { end = total }

	return filteredUsers[start:end], total, nil
}

func (r *memoryUserRepository) Delete(ctx context.Context, id uuid.UUID) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, ok := r.users[id]; !ok {
		return errors.New("user not found")
	}
	delete(r.users, id)
	return nil
}

// --- Service Layer ---

type UserService interface {
	CreateUser(ctx context.Context, req CreateUserRequest) (*User, error)
	GetUser(ctx context.Context, id uuid.UUID) (*User, error)
	ListUsers(ctx context.Context, offset, limit int, filters map[string]interface{}) ([]*User, int, error)
	UpdateUser(ctx context.Context, id uuid.UUID, req UpdateUserRequest) (*User, error)
	DeleteUser(ctx context.Context, id uuid.UUID) error
}

type userService struct {
	repo UserRepository
}

func NewUserService(repo UserRepository) UserService {
	return &userService{repo: repo}
}

func (s *userService) CreateUser(ctx context.Context, req CreateUserRequest) (*User, error) {
	if _, err := s.repo.FindByEmail(ctx, req.Email); err == nil {
		return nil, errors.New("email is already taken")
	}

	user := &User{
		ID:           uuid.New(),
		Email:        req.Email,
		PasswordHash: fmt.Sprintf("hashed:%s", req.Password),
		Role:         req.Role,
		IsActive:     true,
		CreatedAt:    time.Now().UTC(),
	}
	if user.Role == "" {
		user.Role = RoleUser
	}

	if err := s.repo.Save(ctx, user); err != nil {
		return nil, err
	}
	return user, nil
}

func (s *userService) GetUser(ctx context.Context, id uuid.UUID) (*User, error) {
	return s.repo.FindByID(ctx, id)
}

func (s *userService) ListUsers(ctx context.Context, offset, limit int, filters map[string]interface{}) ([]*User, int, error) {
	return s.repo.FindAll(ctx, offset, limit, filters)
}

func (s *userService) UpdateUser(ctx context.Context, id uuid.UUID, req UpdateUserRequest) (*User, error) {
	user, err := s.repo.FindByID(ctx, id)
	if err != nil {
		return nil, err
	}
	user.Email = req.Email
	user.Role = req.Role
	user.IsActive = req.IsActive
	
	if err := s.repo.Save(ctx, user); err != nil {
		return nil, err
	}
	return user, nil
}

func (s *userService) DeleteUser(ctx context.Context, id uuid.UUID) error {
	return s.repo.Delete(ctx, id)
}

// --- Handler/Transport Layer ---

type UserHandler struct {
	service UserService
}

func NewUserHandler(service UserService) *UserHandler {
	return &UserHandler{service: service}
}

func (h *UserHandler) RegisterRoutes(app *fiber.App) {
	users := app.Group("/users")
	users.Post("/", h.handleCreateUser)
	users.Get("/", h.handleListUsers)
	users.Get("/:id", h.handleGetUser)
	users.Put("/:id", h.handleUpdateUser) // PUT/PATCH are combined in service for this example
	users.Patch("/:id", h.handleUpdateUser)
	users.Delete("/:id", h.handleDeleteUser)
}

func (h *UserHandler) handleCreateUser(c *fiber.Ctx) error {
	var req CreateUserRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "invalid request"})
	}
	user, err := h.service.CreateUser(c.Context(), req)
	if err != nil {
		return c.Status(fiber.StatusConflict).JSON(fiber.Map{"error": err.Error()})
	}
	return c.Status(fiber.StatusCreated).JSON(toUserResponse(user))
}

func (h *UserHandler) handleGetUser(c *fiber.Ctx) error {
	id, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "invalid id"})
	}
	user, err := h.service.GetUser(c.Context(), id)
	if err != nil {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": err.Error()})
	}
	return c.JSON(toUserResponse(user))
}

func (h *UserHandler) handleListUsers(c *fiber.Ctx) error {
	limit, _ := strconv.Atoi(c.Query("limit", "20"))
	offset, _ := strconv.Atoi(c.Query("offset", "0"))
	
	filters := make(map[string]interface{})
	if role := c.Query("role"); role != "" {
		filters["role"] = Role(strings.ToUpper(role))
	}
	if isActiveStr := c.Query("is_active"); isActiveStr != "" {
		if isActive, err := strconv.ParseBool(isActiveStr); err == nil {
			filters["is_active"] = isActive
		}
	}

	users, total, err := h.service.ListUsers(c.Context(), offset, limit, filters)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": "could not fetch users"})
	}
	
	respUsers := make([]UserResponse, len(users))
	for i, u := range users {
		respUsers[i] = toUserResponse(u)
	}

	return c.JSON(fiber.Map{"data": respUsers, "total": total})
}

func (h *UserHandler) handleUpdateUser(c *fiber.Ctx) error {
	id, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "invalid id"})
	}
	var req UpdateUserRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "invalid request"})
	}
	user, err := h.service.UpdateUser(c.Context(), id, req)
	if err != nil {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": err.Error()})
	}
	return c.JSON(toUserResponse(user))
}

func (h *UserHandler) handleDeleteUser(c *fiber.Ctx) error {
	id, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "invalid id"})
	}
	if err := h.service.DeleteUser(c.Context(), id); err != nil {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": err.Error()})
	}
	return c.SendStatus(fiber.StatusNoContent)
}

// --- Main ---

func main() {
	// Dependency Injection
	userRepo := NewMemoryUserRepository()
	userService := NewUserService(userRepo)
	userHandler := NewUserHandler(userService)

	app := fiber.New()
	app.Use(logger.New())

	// Register routes
	userHandler.RegisterRoutes(app)

	log.Println("Server starting on port 3000...")
	log.Fatal(app.Listen(":3000"))
}