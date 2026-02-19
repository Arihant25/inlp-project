package main

import (
	"fmt"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/labstack/echo/v4"
	"github.com/labstack/echo/v4/middleware"
)

// --- Domain Models ---

type Role string

const (
	RoleAdmin Role = "ADMIN"
	RoleUser  Role = "USER"
)

type Status string

const (
	StatusDraft     Status = "DRAFT"
	StatusPublished Status = "PUBLISHED"
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
	Status  Status    `json:"status"`
}

// --- Data Store Layer (Repository Pattern) ---

type UserStore interface {
	Create(user User) (User, error)
	FindByID(id uuid.UUID) (User, error)
	Update(id uuid.UUID, user User) (User, error)
	Delete(id uuid.UUID) error
	FindAll(filters map[string]string, limit, offset int) ([]User, error)
}

type InMemoryUserStore struct {
	mu    sync.RWMutex
	users map[uuid.UUID]User
}

func NewInMemoryUserStore() *InMemoryUserStore {
	return &InMemoryUserStore{
		users: make(map[uuid.UUID]User),
	}
}

func (s *InMemoryUserStore) Create(user User) (User, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, u := range s.users {
		if u.Email == user.Email {
			return User{}, fmt.Errorf("user with email %s already exists", user.Email)
		}
	}
	s.users[user.ID] = user
	return user, nil
}

func (s *InMemoryUserStore) FindByID(id uuid.UUID) (User, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	user, ok := s.users[id]
	if !ok {
		return User{}, fmt.Errorf("user with id %s not found", id)
	}
	return user, nil
}

func (s *InMemoryUserStore) Update(id uuid.UUID, updatedUser User) (User, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, ok := s.users[id]
	if !ok {
		return User{}, fmt.Errorf("user with id %s not found", id)
	}
	s.users[id] = updatedUser
	return updatedUser, nil
}

func (s *InMemoryUserStore) Delete(id uuid.UUID) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.users[id]; !ok {
		return fmt.Errorf("user with id %s not found", id)
	}
	delete(s.users, id)
	return nil
}

func (s *InMemoryUserStore) FindAll(filters map[string]string, limit, offset int) ([]User, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	var result []User
	for _, user := range s.users {
		match := true
		if role, ok := filters["role"]; ok && strings.ToUpper(string(user.Role)) != strings.ToUpper(role) {
			match = false
		}
		if isActiveStr, ok := filters["is_active"]; ok {
			isActive, err := strconv.ParseBool(isActiveStr)
			if err == nil && user.IsActive != isActive {
				match = false
			}
		}
		if match {
			result = append(result, user)
		}
	}

	sort.Slice(result, func(i, j int) bool {
		return result[i].CreatedAt.Before(result[j].CreatedAt)
	})

	if offset >= len(result) {
		return []User{}, nil
	}
	end := offset + limit
	if end > len(result) {
		end = len(result)
	}
	return result[offset:end], nil
}

// --- Handler Layer (OOP Style) ---

type UserHandler struct {
	store UserStore
}

func NewUserHandler(s UserStore) *UserHandler {
	return &UserHandler{store: s}
}

func (h *UserHandler) CreateUser(c echo.Context) error {
	var input struct {
		Email    string `json:"email"`
		Password string `json:"password"`
		Role     Role   `json:"role"`
	}
	if err := c.Bind(&input); err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "Invalid request body"})
	}

	newUser := User{
		ID:           uuid.New(),
		Email:        input.Email,
		PasswordHash: "hashed_" + input.Password,
		Role:         input.Role,
		IsActive:     true,
		CreatedAt:    time.Now().UTC(),
	}

	createdUser, err := h.store.Create(newUser)
	if err != nil {
		return c.JSON(http.StatusConflict, map[string]string{"error": err.Error()})
	}
	return c.JSON(http.StatusCreated, createdUser)
}

func (h *UserHandler) GetUser(c echo.Context) error {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "Invalid user ID"})
	}

	user, err := h.store.FindByID(id)
	if err != nil {
		return c.JSON(http.StatusNotFound, map[string]string{"error": err.Error()})
	}
	return c.JSON(http.StatusOK, user)
}

func (h *UserHandler) UpdateUser(c echo.Context) error {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "Invalid user ID"})
	}

	user, err := h.store.FindByID(id)
	if err != nil {
		return c.JSON(http.StatusNotFound, map[string]string{"error": err.Error()})
	}

	var input struct {
		Email    *string `json:"email"`
		Role     *Role   `json:"role"`
		IsActive *bool   `json:"is_active"`
	}
	if err := c.Bind(&input); err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "Invalid request body"})
	}

	if input.Email != nil {
		user.Email = *input.Email
	}
	if input.Role != nil {
		user.Role = *input.Role
	}
	if input.IsActive != nil {
		user.IsActive = *input.IsActive
	}

	updatedUser, err := h.store.Update(id, user)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": "Failed to update user"})
	}
	return c.JSON(http.StatusOK, updatedUser)
}

func (h *UserHandler) DeleteUser(c echo.Context) error {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "Invalid user ID"})
	}

	if err := h.store.Delete(id); err != nil {
		return c.JSON(http.StatusNotFound, map[string]string{"error": err.Error()})
	}
	return c.NoContent(http.StatusNoContent)
}

func (h *UserHandler) ListUsers(c echo.Context) error {
	filters := make(map[string]string)
	if role := c.QueryParam("role"); role != "" {
		filters["role"] = role
	}
	if isActive := c.QueryParam("is_active"); isActive != "" {
		filters["is_active"] = isActive
	}

	limit, _ := strconv.Atoi(c.QueryParam("limit"))
	if limit <= 0 {
		limit = 10
	}
	offset, _ := strconv.Atoi(c.QueryParam("offset"))
	if offset < 0 {
		offset = 0
	}

	users, err := h.store.FindAll(filters, limit, offset)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": "Failed to retrieve users"})
	}
	return c.JSON(http.StatusOK, users)
}

func main() {
	e := echo.New()
	e.Use(middleware.Logger())
	e.Use(middleware.Recover())

	store := NewInMemoryUserStore()
	// Seed data
	adminUser, _ := store.Create(User{ID: uuid.New(), Email: "admin@example.com", Role: RoleAdmin, IsActive: true, CreatedAt: time.Now().UTC()})
	store.Create(User{ID: uuid.New(), Email: "user@example.com", Role: RoleUser, IsActive: false, CreatedAt: time.Now().UTC().Add(time.Second)})
	
	// This is just to satisfy the domain schema requirement
	_ = Post{ID: uuid.New(), UserID: adminUser.ID, Title: "First Post", Content: "Hello World", Status: StatusPublished}


	handler := NewUserHandler(store)

	g := e.Group("/users")
	g.POST("", handler.CreateUser)
	g.GET("", handler.ListUsers)
	g.GET("/:id", handler.GetUser)
	g.PUT("/:id", handler.UpdateUser)
	g.DELETE("/:id", handler.DeleteUser)

	e.Logger.Fatal(e.Start(":8080"))
}