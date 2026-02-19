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

	"github.com/go-playground/validator/v10"
	"github.com/google/uuid"
	"github.comcom/labstack/echo/v4"
	"github.com/labstack/echo/v4/middleware"
)

// --- Domain Models ---
// (Simulating package: domain)

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
	Status  Status
}

// --- DTOs (Data Transfer Objects) ---
// (Simulating package: dto)

type CreateUserRequest struct {
	Email    string `json:"email" validate:"required,email"`
	Password string `json:"password" validate:"required,min=8"`
	Role     Role   `json:"role" validate:"required,oneof=ADMIN USER"`
}

type UpdateUserRequest struct {
	Email    *string `json:"email" validate:"omitempty,email"`
	Role     *Role   `json:"role" validate:"omitempty,oneof=ADMIN USER"`
	IsActive *bool   `json:"is_active"`
}

type UserResponse struct {
	ID        uuid.UUID `json:"id"`
	Email     string    `json:"email"`
	Role      Role      `json:"role"`
	IsActive  bool      `json:"is_active"`
	CreatedAt time.Time `json:"created_at"`
}

type PaginatedUsersResponse struct {
	Users      []UserResponse `json:"users"`
	TotalCount int            `json:"total_count"`
	Page       int            `json:"page"`
	PageSize   int            `json:"page_size"`
}

func toUserResponse(u *User) UserResponse {
	return UserResponse{
		ID:        u.ID,
		Email:     u.Email,
		Role:      u.Role,
		IsActive:  u.IsActive,
		CreatedAt: u.CreatedAt,
	}
}

// --- Custom Errors ---
// (Simulating package: service)

var (
	ErrUserNotFound      = errors.New("user not found")
	ErrEmailInUse        = errors.New("email is already in use")
	ErrInvalidInput      = errors.New("invalid input provided")
	ErrInternalServer    = errors.New("internal server error")
)

// --- Repository Layer ---
// (Simulating package: repository)

type UserRepository interface {
	Save(ctx context.Context, user *User) error
	FindByID(ctx context.Context, id uuid.UUID) (*User, error)
	FindByEmail(ctx context.Context, email string) (*User, error)
	Delete(ctx context.Context, id uuid.UUID) error
	FindAll(ctx context.Context, roleFilter *Role, activeFilter *bool, limit, offset int) ([]User, int, error)
}

type InMemoryUserRepository struct {
	mu    sync.RWMutex
	users map[uuid.UUID]*User
}

func NewInMemoryUserRepository() *InMemoryUserRepository {
	return &InMemoryUserRepository{users: make(map[uuid.UUID]*User)}
}

func (r *InMemoryUserRepository) Save(ctx context.Context, user *User) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.users[user.ID] = user
	return nil
}

func (r *InMemoryUserRepository) FindByID(ctx context.Context, id uuid.UUID) (*User, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	user, ok := r.users[id]
	if !ok {
		return nil, ErrUserNotFound
	}
	return user, nil
}

func (r *InMemoryUserRepository) FindByEmail(ctx context.Context, email string) (*User, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	for _, u := range r.users {
		if u.Email == email {
			return u, nil
		}
	}
	return nil, ErrUserNotFound
}

func (r *InMemoryUserRepository) Delete(ctx context.Context, id uuid.UUID) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, ok := r.users[id]; !ok {
		return ErrUserNotFound
	}
	delete(r.users, id)
	return nil
}

func (r *InMemoryUserRepository) FindAll(ctx context.Context, roleFilter *Role, activeFilter *bool, limit, offset int) ([]User, int, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	
	var filtered []*User
	for _, u := range r.users {
		if roleFilter != nil && u.Role != *roleFilter { continue }
		if activeFilter != nil && u.IsActive != *activeFilter { continue }
		filtered = append(filtered, u)
	}

	sort.Slice(filtered, func(i, j int) bool { return filtered[i].CreatedAt.Before(filtered[j].CreatedAt) })
	
	totalCount := len(filtered)
	if offset >= totalCount {
		return []User{}, totalCount, nil
	}
	end := offset + limit
	if end > totalCount {
		end = totalCount
	}

	result := make([]User, len(filtered[offset:end]))
	for i, u := range filtered[offset:end] {
		result[i] = *u
	}

	return result, totalCount, nil
}

// --- Service Layer ---
// (Simulating package: service)

type UserService struct {
	repo UserRepository
}

func NewUserService(repo UserRepository) *UserService {
	return &UserService{repo: repo}
}

func (s *UserService) CreateUser(ctx context.Context, req CreateUserRequest) (*User, error) {
	if _, err := s.repo.FindByEmail(ctx, req.Email); err == nil {
		return nil, ErrEmailInUse
	}

	user := &User{
		ID:           uuid.New(),
		Email:        req.Email,
		PasswordHash: "hashed_" + req.Password, // Use bcrypt in production
		Role:         req.Role,
		IsActive:     true,
		CreatedAt:    time.Now().UTC(),
	}

	if err := s.repo.Save(ctx, user); err != nil {
		return nil, ErrInternalServer
	}
	return user, nil
}

// ... other service methods would follow a similar pattern ...

// --- API/Handler Layer ---
// (Simulating package: api)

type UserAPIHandler struct {
	service *UserService
}

func NewUserAPIHandler(s *UserService) *UserAPIHandler {
	return &UserAPIHandler{service: s}
}

func (h *UserAPIHandler) Create(c echo.Context) error {
	var req CreateUserRequest
	if err := c.Bind(&req); err != nil {
		return echo.NewHTTPError(http.StatusBadRequest, "Invalid request body")
	}
	if err := c.Validate(&req); err != nil {
		return err // Let the custom error handler format this
	}

	user, err := h.service.CreateUser(c.Request().Context(), req)
	if err != nil {
		return err // Let the custom error handler handle this
	}

	return c.JSON(http.StatusCreated, toUserResponse(user))
}

func (h *UserAPIHandler) GetByID(c echo.Context) error {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		return ErrInvalidInput
	}
	user, err := h.service.repo.FindByID(c.Request().Context(), id)
	if err != nil {
		return err
	}
	return c.JSON(http.StatusOK, toUserResponse(user))
}

func (h *UserAPIHandler) Update(c echo.Context) error {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		return ErrInvalidInput
	}
	
	var req UpdateUserRequest
	if err := c.Bind(&req); err != nil {
		return echo.NewHTTPError(http.StatusBadRequest, "Invalid request body")
	}
	if err := c.Validate(&req); err != nil {
		return err
	}

	user, err := h.service.repo.FindByID(c.Request().Context(), id)
	if err != nil {
		return err
	}

	if req.Email != nil { user.Email = *req.Email }
	if req.Role != nil { user.Role = *req.Role }
	if req.IsActive != nil { user.IsActive = *req.IsActive }

	if err := h.service.repo.Save(c.Request().Context(), user); err != nil {
		return ErrInternalServer
	}

	return c.JSON(http.StatusOK, toUserResponse(user))
}

func (h *UserAPIHandler) Delete(c echo.Context) error {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		return ErrInvalidInput
	}
	if err := h.service.repo.Delete(c.Request().Context(), id); err != nil {
		return err
	}
	return c.NoContent(http.StatusNoContent)
}

func (h *UserAPIHandler) List(c echo.Context) error {
	page, _ := strconv.Atoi(c.QueryParam("page"))
	if page < 1 { page = 1 }
	pageSize, _ := strconv.Atoi(c.QueryParam("pageSize"))
	if pageSize < 1 || pageSize > 100 { pageSize = 10 }
	offset := (page - 1) * pageSize

	var roleFilter *Role
	if r := c.QueryParam("role"); r != "" {
		role := Role(strings.ToUpper(r))
		roleFilter = &role
	}
	var activeFilter *bool
	if a := c.QueryParam("is_active"); a != "" {
		b, err := strconv.ParseBool(a)
		if err == nil { activeFilter = &b }
	}

	users, total, err := h.service.repo.FindAll(c.Request().Context(), roleFilter, activeFilter, pageSize, offset)
	if err != nil {
		return err
	}

	resp := PaginatedUsersResponse{
		Users:      make([]UserResponse, len(users)),
		TotalCount: total,
		Page:       page,
		PageSize:   pageSize,
	}
	for i, u := range users {
		resp.Users[i] = toUserResponse(&u)
	}

	return c.JSON(http.StatusOK, resp)
}

// --- Custom Validator ---

type CustomValidator struct {
	validator *validator.Validate
}

func (cv *CustomValidator) Validate(i interface{}) error {
	if err := cv.validator.Struct(i); err != nil {
		// Optionally, you can return a custom error format here
		return echo.NewHTTPError(http.StatusBadRequest, err.Error())
	}
	return nil
}

// --- Custom HTTP Error Handler ---

func httpErrorHandler(err error, c echo.Context) {
	if c.Response().Committed {
		return
	}

	code := http.StatusInternalServerError
	message := "Internal Server Error"

	var he *echo.HTTPError
	if errors.As(err, &he) {
		code = he.Code
		message = he.Message.(string)
	} else if errors.Is(err, ErrUserNotFound) {
		code = http.StatusNotFound
		message = err.Error()
	} else if errors.Is(err, ErrEmailInUse) {
		code = http.StatusConflict
		message = err.Error()
	} else if errors.Is(err, ErrInvalidInput) {
		code = http.StatusBadRequest
		message = err.Error()
	}

	if !c.Response().Committed {
		if err := c.JSON(code, map[string]string{"error": message}); err != nil {
			c.Logger().Error(err)
		}
	}
}

func main() {
	e := echo.New()
	e.Validator = &CustomValidator{validator: validator.New()}
	e.HTTPErrorHandler = httpErrorHandler

	e.Use(middleware.Logger())
	e.Use(middleware.Recover())

	// DI Container
	repo := NewInMemoryUserRepository()
	service := NewUserService(repo)
	handler := NewUserAPIHandler(service)

	// Seed data
	repo.Save(context.Background(), &User{ID: uuid.New(), Email: "admin@example.com", Role: RoleAdmin, IsActive: true, CreatedAt: time.Now()})
	repo.Save(context.Background(), &User{ID: uuid.New(), Email: "user@example.com", Role: RoleUser, IsActive: false, CreatedAt: time.Now()})

	// Routes
	g := e.Group("/users")
	g.POST("", handler.Create)
	g.GET("/:id", handler.GetByID)
	g.PUT("/:id", handler.Update)
	g.DELETE("/:id", handler.Delete)
	g.GET("", handler.List)

	e.Logger.Fatal(e.Start(":8080"))
}