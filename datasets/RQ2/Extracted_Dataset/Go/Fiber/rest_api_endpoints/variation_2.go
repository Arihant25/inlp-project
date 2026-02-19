package main

import (
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

// --- Domain Model ---

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

type Post struct {
	ID      uuid.UUID `json:"id"`
	UserID  uuid.UUID `json:"user_id"`
	Title   string    `json:"title"`
	Content string    `json:"content"`
	Status  string    `json:"status"` // DRAFT, PUBLISHED
}

// --- Repository Layer (Data Access) ---

type UserRepository interface {
	Create(user User) (User, error)
	FindByID(id uuid.UUID) (User, error)
	FindAll(params ListUserParams) ([]User, int)
	Update(id uuid.UUID, user User) (User, error)
	Delete(id uuid.UUID) error
}

type ListUserParams struct {
	Limit  int
	Offset int
	Role   Role
	IsActive *bool
}

type inMemoryUserRepository struct {
	users map[uuid.UUID]User
	mu    sync.RWMutex
}

func NewInMemoryUserRepository() UserRepository {
	repo := &inMemoryUserRepository{
		users: make(map[uuid.UUID]User),
	}
	// Seed data
	user1 := User{ID: uuid.New(), Email: "admin@example.com", PasswordHash: "hashed_adminpass", Role: AdminRole, IsActive: true, CreatedAt: time.Now().UTC()}
	user2 := User{ID: uuid.New(), Email: "user1@example.com", PasswordHash: "hashed_user1pass", Role: UserRole, IsActive: true, CreatedAt: time.Now().UTC()}
	repo.users[user1.ID] = user1
	repo.users[user2.ID] = user2
	return repo
}

func (r *inMemoryUserRepository) Create(user User) (User, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	for _, u := range r.users {
		if u.Email == user.Email {
			return User{}, errors.New("email already exists")
		}
	}
	r.users[user.ID] = user
	return user, nil
}

func (r *inMemoryUserRepository) FindByID(id uuid.UUID) (User, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	user, ok := r.users[id]
	if !ok {
		return User{}, errors.New("user not found")
	}
	return user, nil
}

func (r *inMemoryUserRepository) FindAll(params ListUserParams) ([]User, int) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	
	var filtered []User
	for _, user := range r.users {
		match := true
		if params.Role != "" && user.Role != params.Role {
			match = false
		}
		if params.IsActive != nil && user.IsActive != *params.IsActive {
			match = false
		}
		if match {
			filtered = append(filtered, user)
		}
	}
	
	total := len(filtered)
	start := params.Offset
	end := params.Offset + params.Limit
	if start > total { start = total }
	if end > total { end = total }

	return filtered[start:end], total
}

func (r *inMemoryUserRepository) Update(id uuid.UUID, user User) (User, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, ok := r.users[id]; !ok {
		return User{}, errors.New("user not found")
	}
	user.ID = id // Ensure ID is not changed
	r.users[id] = user
	return user, nil
}

func (r *inMemoryUserRepository) Delete(id uuid.UUID) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, ok := r.users[id]; !ok {
		return errors.New("user not found")
	}
	delete(r.users, id)
	return nil
}

// --- Handler Layer (Controller) ---

type UserHandler struct {
	Repo UserRepository
}

func NewUserHandler(repo UserRepository) *UserHandler {
	return &UserHandler{Repo: repo}
}

func (h *UserHandler) Create(c *fiber.Ctx) error {
	var req struct {
		Email    string `json:"email"`
		Password string `json:"password"`
		Role     Role   `json:"role"`
	}
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "invalid request body"})
	}

	newUser := User{
		ID:           uuid.New(),
		Email:        req.Email,
		PasswordHash: fmt.Sprintf("hashed_%s", req.Password),
		Role:         req.Role,
		IsActive:     true,
		CreatedAt:    time.Now().UTC(),
	}

	createdUser, err := h.Repo.Create(newUser)
	if err != nil {
		return c.Status(fiber.StatusConflict).JSON(fiber.Map{"error": err.Error()})
	}
	return c.Status(fiber.StatusCreated).JSON(createdUser)
}

func (h *UserHandler) GetByID(c *fiber.Ctx) error {
	id, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "invalid user ID"})
	}

	user, err := h.Repo.FindByID(id)
	if err != nil {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": err.Error()})
	}
	return c.JSON(user)
}

func (h *UserHandler) List(c *fiber.Ctx) error {
	params := ListUserParams{
		Limit:  c.QueryInt("limit", 10),
		Offset: c.QueryInt("offset", 0),
		Role:   Role(strings.ToUpper(c.Query("role"))),
	}
	if isActiveStr := c.Query("is_active"); isActiveStr != "" {
		isActive, err := strconv.ParseBool(isActiveStr)
		if err == nil {
			params.IsActive = &isActive
		}
	}

	users, total := h.Repo.FindAll(params)
	return c.JSON(fiber.Map{
		"data":   users,
		"total":  total,
		"limit":  params.Limit,
		"offset": params.Offset,
	})
}

func (h *UserHandler) Update(c *fiber.Ctx) error { // Handles PUT
	id, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "invalid user ID"})
	}

	var req User
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "invalid request body"})
	}

	// Preserve original creation date and password hash
	originalUser, err := h.Repo.FindByID(id)
	if err != nil {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": "user not found"})
	}
	req.CreatedAt = originalUser.CreatedAt
	req.PasswordHash = originalUser.PasswordHash

	updatedUser, err := h.Repo.Update(id, req)
	if err != nil {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": err.Error()})
	}
	return c.JSON(updatedUser)
}

func (h *UserHandler) Patch(c *fiber.Ctx) error { // Handles PATCH
	id, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "invalid user ID"})
	}

	user, err := h.Repo.FindByID(id)
	if err != nil {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": "user not found"})
	}

	var updates map[string]interface{}
	if err := c.BodyParser(&updates); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "invalid request body"})
	}

	if email, ok := updates["email"].(string); ok {
		user.Email = email
	}
	if role, ok := updates["role"].(string); ok {
		user.Role = Role(role)
	}
	if isActive, ok := updates["is_active"].(bool); ok {
		user.IsActive = isActive
	}

	updatedUser, err := h.Repo.Update(id, user)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": "could not update user"})
	}
	return c.JSON(updatedUser)
}

func (h *UserHandler) Delete(c *fiber.Ctx) error {
	id, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "invalid user ID"})
	}

	if err := h.Repo.Delete(id); err != nil {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": err.Error()})
	}
	return c.SendStatus(fiber.StatusNoContent)
}

// --- Main Application ---

func main() {
	// 1. Initialize dependencies
	userRepository := NewInMemoryUserRepository()
	userHandler := NewUserHandler(userRepository)

	// 2. Setup Fiber app
	app := fiber.New()
	app.Use(logger.New())

	// 3. Group routes
	api := app.Group("/api/v1")
	userRoutes := api.Group("/users")

	// 4. Register routes
	userRoutes.Post("/", userHandler.Create)
	userRoutes.Get("/", userHandler.List)
	userRoutes.Get("/:id", userHandler.GetByID)
	userRoutes.Put("/:id", userHandler.Update)
	userRoutes.Patch("/:id", userHandler.Patch)
	userRoutes.Delete("/:id", userHandler.Delete)

	log.Println("Server starting on port 3000...")
	log.Fatal(app.Listen(":3000"))
}