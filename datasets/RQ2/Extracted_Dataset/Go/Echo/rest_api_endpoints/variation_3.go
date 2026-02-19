package main

import (
	"context"
	"errors"
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

var (
	ErrNotFound      = errors.New("resource not found")
	ErrAlreadyExists = errors.New("resource already exists")
)

// --- Repository Layer ---

type UserRepository interface {
	Create(ctx context.Context, user *User) error
	GetByID(ctx context.Context, id uuid.UUID) (*User, error)
	Update(ctx context.Context, user *User) error
	Delete(ctx context.Context, id uuid.UUID) error
	List(ctx context.Context, params ListUserParams) ([]User, error)
}

type ListUserParams struct {
	Role     *Role
	IsActive *bool
	Limit    int
	Offset   int
}

type memoryUserRepository struct {
	mu    sync.RWMutex
	users map[uuid.UUID]User
}

func NewMemoryUserRepository() UserRepository {
	return &memoryUserRepository{users: make(map[uuid.UUID]User)}
}

func (r *memoryUserRepository) Create(ctx context.Context, user *User) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	for _, u := range r.users {
		if u.Email == user.Email {
			return ErrAlreadyExists
		}
	}
	r.users[user.ID] = *user
	return nil
}

func (r *memoryUserRepository) GetByID(ctx context.Context, id uuid.UUID) (*User, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	user, ok := r.users[id]
	if !ok {
		return nil, ErrNotFound
	}
	return &user, nil
}

func (r *memoryUserRepository) Update(ctx context.Context, user *User) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, ok := r.users[user.ID]; !ok {
		return ErrNotFound
	}
	r.users[user.ID] = *user
	return nil
}

func (r *memoryUserRepository) Delete(ctx context.Context, id uuid.UUID) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, ok := r.users[id]; !ok {
		return ErrNotFound
	}
	delete(r.users, id)
	return nil
}

func (r *memoryUserRepository) List(ctx context.Context, params ListUserParams) ([]User, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	var result []User
	for _, user := range r.users {
		if params.Role != nil && user.Role != *params.Role {
			continue
		}
		if params.IsActive != nil && user.IsActive != *params.IsActive {
			continue
		}
		result = append(result, user)
	}

	sort.Slice(result, func(i, j int) bool { return result[i].CreatedAt.Before(result[j].CreatedAt) })

	if params.Offset >= len(result) {
		return []User{}, nil
	}
	end := params.Offset + params.Limit
	if end > len(result) {
		end = len(result)
	}
	return result[params.Offset:end], nil
}

// --- Service Layer ---

type UserService interface {
	Create(ctx context.Context, email, password string, role Role) (*User, error)
	Get(ctx context.Context, id uuid.UUID) (*User, error)
	Update(ctx context.Context, id uuid.UUID, email *string, role *Role, isActive *bool) (*User, error)
	Delete(ctx context.Context, id uuid.UUID) error
	List(ctx context.Context, params ListUserParams) ([]User, error)
}

type userService struct {
	userRepo UserRepository
}

func NewUserService(repo UserRepository) UserService {
	return &userService{userRepo: repo}
}

func (s *userService) Create(ctx context.Context, email, password string, role Role) (*User, error) {
	newUser := &User{
		ID:           uuid.New(),
		Email:        email,
		PasswordHash: "hashed_" + password, // Use bcrypt in production
		Role:         role,
		IsActive:     true,
		CreatedAt:    time.Now().UTC(),
	}
	if err := s.userRepo.Create(ctx, newUser); err != nil {
		return nil, err
	}
	return newUser, nil
}

func (s *userService) Get(ctx context.Context, id uuid.UUID) (*User, error) {
	return s.userRepo.GetByID(ctx, id)
}

func (s *userService) Update(ctx context.Context, id uuid.UUID, email *string, role *Role, isActive *bool) (*User, error) {
	user, err := s.userRepo.GetByID(ctx, id)
	if err != nil {
		return nil, err
	}
	if email != nil {
		user.Email = *email
	}
	if role != nil {
		user.Role = *role
	}
	if isActive != nil {
		user.IsActive = *isActive
	}
	if err := s.userRepo.Update(ctx, user); err != nil {
		return nil, err
	}
	return user, nil
}

func (s *userService) Delete(ctx context.Context, id uuid.UUID) error {
	return s.userRepo.Delete(ctx, id)
}

func (s *userService) List(ctx context.Context, params ListUserParams) ([]User, error) {
	return s.userRepo.List(ctx, params)
}

// --- Controller/Handler Layer ---

type UserController struct {
	userService UserService
}

func NewUserController(svc UserService) *UserController {
	return &UserController{userService: svc}
}

func (ctrl *UserController) RegisterRoutes(g *echo.Group) {
	g.POST("", ctrl.Create)
	g.GET("/:id", ctrl.Get)
	g.PUT("/:id", ctrl.Update)
	g.DELETE("/:id", ctrl.Delete)
	g.GET("", ctrl.List)
}

func (ctrl *UserController) Create(c echo.Context) error {
	var req struct {
		Email    string `json:"email"`
		Password string `json:"password"`
		Role     Role   `json:"role"`
	}
	if err := c.Bind(&req); err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"message": err.Error()})
	}
	user, err := ctrl.userService.Create(c.Request().Context(), req.Email, req.Password, req.Role)
	if errors.Is(err, ErrAlreadyExists) {
		return c.JSON(http.StatusConflict, map[string]string{"message": "Email already in use"})
	} else if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"message": "Could not create user"})
	}
	return c.JSON(http.StatusCreated, user)
}

func (ctrl *UserController) Get(c echo.Context) error {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"message": "Invalid ID"})
	}
	user, err := ctrl.userService.Get(c.Request().Context(), id)
	if errors.Is(err, ErrNotFound) {
		return c.JSON(http.StatusNotFound, map[string]string{"message": "User not found"})
	} else if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"message": "Could not retrieve user"})
	}
	return c.JSON(http.StatusOK, user)
}

func (ctrl *UserController) Update(c echo.Context) error {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"message": "Invalid ID"})
	}
	var req struct {
		Email    *string `json:"email"`
		Role     *Role   `json:"role"`
		IsActive *bool   `json:"is_active"`
	}
	if err := c.Bind(&req); err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"message": err.Error()})
	}
	user, err := ctrl.userService.Update(c.Request().Context(), id, req.Email, req.Role, req.IsActive)
	if errors.Is(err, ErrNotFound) {
		return c.JSON(http.StatusNotFound, map[string]string{"message": "User not found"})
	} else if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"message": "Could not update user"})
	}
	return c.JSON(http.StatusOK, user)
}

func (ctrl *UserController) Delete(c echo.Context) error {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"message": "Invalid ID"})
	}
	err = ctrl.userService.Delete(c.Request().Context(), id)
	if errors.Is(err, ErrNotFound) {
		return c.JSON(http.StatusNotFound, map[string]string{"message": "User not found"})
	} else if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"message": "Could not delete user"})
	}
	return c.NoContent(http.StatusNoContent)
}

func (ctrl *UserController) List(c echo.Context) error {
	params := ListUserParams{}
	if roleStr := c.QueryParam("role"); roleStr != "" {
		role := Role(strings.ToUpper(roleStr))
		params.Role = &role
	}
	if isActiveStr := c.QueryParam("is_active"); isActiveStr != "" {
		isActive, err := strconv.ParseBool(isActiveStr)
		if err == nil {
			params.IsActive = &isActive
		}
	}
	limit, _ := strconv.Atoi(c.QueryParam("limit"))
	if limit <= 0 {
		limit = 20
	}
	params.Limit = limit
	offset, _ := strconv.Atoi(c.QueryParam("offset"))
	if offset < 0 {
		offset = 0
	}
	params.Offset = offset

	users, err := ctrl.userService.List(c.Request().Context(), params)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"message": "Could not list users"})
	}
	return c.JSON(http.StatusOK, users)
}

func main() {
	e := echo.New()
	e.Use(middleware.Logger())
	e.Use(middleware.Recover())

	// Dependency Injection
	userRepo := NewMemoryUserRepository()
	userService := NewUserService(userRepo)
	userController := NewUserController(userService)

	// Seed data
	ctx := context.Background()
	userService.Create(ctx, "admin@example.com", "adminpass", RoleAdmin)
	userService.Create(ctx, "user@example.com", "userpass", RoleUser)

	// Register routes
	userController.RegisterRoutes(e.Group("/users"))

	e.Logger.Fatal(e.Start(":8080"))
}