package main

import (
	"errors"
	"fmt"
	"log"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.comcom/google/uuid"
)

// --- Domain Models ---

type Role string

const (
	RoleAdmin Role = "ADMIN"
	RoleUser  Role = "USER"
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
	StatusDraft     PostStatus = "DRAFT"
	StatusPublished PostStatus = "PUBLISHED"
)

type Post struct {
	ID      uuid.UUID  `json:"id"`
	UserID  uuid.UUID  `json:"user_id"`
	Title   string     `json:"title"`
	Content string     `json:"content"`
	Status  PostStatus `json:"status"`
}

var (
	ErrUserNotFound      = errors.New("user not found")
	ErrEmailAlreadyExists = errors.New("email already exists")
)

// --- Repository Layer (Data Access) ---

type UserRepository interface {
	Create(user *User) error
	FindByID(id uuid.UUID) (*User, error)
	FindAll(filters map[string]string) ([]*User, error)
	Update(user *User) error
	Delete(id uuid.UUID) error
}

type inMemoryUserRepository struct {
	users map[uuid.UUID]*User
	mutex *sync.RWMutex
}

func NewInMemoryUserRepository() UserRepository {
	repo := &inMemoryUserRepository{
		users: make(map[uuid.UUID]*User),
		mutex: &sync.RWMutex{},
	}
	// Seed data
	usersToSeed := []*User{
		{ID: uuid.New(), Email: "admin@example.com", PasswordHash: "hash1", Role: RoleAdmin, IsActive: true, CreatedAt: time.Now().UTC()},
		{ID: uuid.New(), Email: "user1@example.com", PasswordHash: "hash2", Role: RoleUser, IsActive: true, CreatedAt: time.Now().UTC()},
	}
	for _, u := range usersToSeed {
		repo.users[u.ID] = u
	}
	return repo
}

func (r *inMemoryUserRepository) Create(user *User) error {
	r.mutex.Lock()
	defer r.mutex.Unlock()
	for _, u := range r.users {
		if u.Email == user.Email {
			return ErrEmailAlreadyExists
		}
	}
	r.users[user.ID] = user
	return nil
}

func (r *inMemoryUserRepository) FindByID(id uuid.UUID) (*User, error) {
	r.mutex.RLock()
	defer r.mutex.RUnlock()
	user, ok := r.users[id]
	if !ok {
		return nil, ErrUserNotFound
	}
	return user, nil
}

func (r *inMemoryUserRepository) FindAll(filters map[string]string) ([]*User, error) {
	r.mutex.RLock()
	defer r.mutex.RUnlock()
	
	result := make([]*User, 0, len(r.users))
	for _, user := range r.users {
		match := true
		if role, ok := filters["role"]; ok && string(user.Role) != role {
			match = false
		}
		if active, ok := filters["is_active"]; ok {
			isActive, _ := strconv.ParseBool(active)
			if user.IsActive != isActive {
				match = false
			}
		}
		if search, ok := filters["search"]; ok && !strings.Contains(user.Email, search) {
			match = false
		}
		if match {
			result = append(result, user)
		}
	}
	return result, nil
}

func (r *inMemoryUserRepository) Update(user *User) error {
	r.mutex.Lock()
	defer r.mutex.Unlock()
	if _, ok := r.users[user.ID]; !ok {
		return ErrUserNotFound
	}
	r.users[user.ID] = user
	return nil
}

func (r *inMemoryUserRepository) Delete(id uuid.UUID) error {
	r.mutex.Lock()
	defer r.mutex.Unlock()
	if _, ok := r.users[id]; !ok {
		return ErrUserNotFound
	}
	delete(r.users, id)
	return nil
}

// --- Service Layer (Business Logic) ---

type UserService interface {
	CreateUser(email, password string, role Role) (*User, error)
	GetUser(id uuid.UUID) (*User, error)
	ListUsers(filters map[string]string, page, pageSize int) ([]*User, int, error)
	UpdateUser(id uuid.UUID, email string, role Role, isActive bool) (*User, error)
	DeleteUser(id uuid.UUID) error
}

type userServiceImpl struct {
	repo UserRepository
}

func NewUserService(repo UserRepository) UserService {
	return &userServiceImpl{repo: repo}
}

func (s *userServiceImpl) CreateUser(email, password string, role Role) (*User, error) {
	user := &User{
		ID:           uuid.New(),
		Email:        email,
		PasswordHash: fmt.Sprintf("hashed_%s", password), // Use bcrypt
		Role:         role,
		IsActive:     true,
		CreatedAt:    time.Now().UTC(),
	}
	if err := s.repo.Create(user); err != nil {
		return nil, err
	}
	return user, nil
}

func (s *userServiceImpl) GetUser(id uuid.UUID) (*User, error) {
	return s.repo.FindByID(id)
}

func (s *userServiceImpl) ListUsers(filters map[string]string, page, pageSize int) ([]*User, int, error) {
	users, err := s.repo.FindAll(filters)
	if err != nil {
		return nil, 0, err
	}
	total := len(users)
	start := (page - 1) * pageSize
	if start >= total {
		return []*User{}, total, nil
	}
	end := start + pageSize
	if end > total {
		end = total
	}
	return users[start:end], total, nil
}

func (s *userServiceImpl) UpdateUser(id uuid.UUID, email string, role Role, isActive bool) (*User, error) {
	user, err := s.repo.FindByID(id)
	if err != nil {
		return nil, err
	}
	user.Email = email
	user.Role = role
	user.IsActive = isActive
	if err := s.repo.Update(user); err != nil {
		return nil, err
	}
	return user, nil
}

func (s *userServiceImpl) DeleteUser(id uuid.UUID) error {
	return s.repo.Delete(id)
}

// --- Controller Layer (HTTP Handlers) ---

type UserController struct {
	service UserService
}

func NewUserController(service UserService) *UserController {
	return &UserController{service: service}
}

func (ctrl *UserController) RegisterRoutes(router *gin.Engine) {
	userRoutes := router.Group("/users")
	{
		userRoutes.POST("", ctrl.Create)
		userRoutes.GET("", ctrl.List)
		userRoutes.GET("/:id", ctrl.Get)
		userRoutes.PUT("/:id", ctrl.Update)
		userRoutes.DELETE("/:id", ctrl.Delete)
	}
}

func (ctrl *UserController) Create(c *gin.Context) {
	var req struct {
		Email    string `json:"email" binding:"required,email"`
		Password string `json:"password" binding:"required,min=8"`
		Role     Role   `json:"role" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	user, err := ctrl.service.CreateUser(req.Email, req.Password, req.Role)
	if err != nil {
		if errors.Is(err, ErrEmailAlreadyExists) {
			c.JSON(http.StatusConflict, gin.H{"error": err.Error()})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to create user"})
		return
	}
	c.JSON(http.StatusCreated, user)
}

func (ctrl *UserController) Get(c *gin.Context) {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid ID"})
		return
	}
	user, err := ctrl.service.GetUser(id)
	if err != nil {
		if errors.Is(err, ErrUserNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to get user"})
		return
	}
	c.JSON(http.StatusOK, user)
}

func (ctrl *UserController) List(c *gin.Context) {
	filters := make(map[string]string)
	if role := c.Query("role"); role != "" {
		filters["role"] = role
	}
	if isActive := c.Query("is_active"); isActive != "" {
		filters["is_active"] = isActive
	}
	if search := c.Query("search"); search != "" {
		filters["search"] = search
	}

	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("pageSize", "10"))

	users, total, err := ctrl.service.ListUsers(filters, page, pageSize)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to list users"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"data": users, "total": total, "page": page, "pageSize": pageSize})
}

func (ctrl *UserController) Update(c *gin.Context) {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid ID"})
		return
	}
	var req struct {
		Email    string `json:"email" binding:"required,email"`
		Role     Role   `json:"role" binding:"required"`
		IsActive *bool  `json:"is_active" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	user, err := ctrl.service.UpdateUser(id, req.Email, req.Role, *req.IsActive)
	if err != nil {
		if errors.Is(err, ErrUserNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to update user"})
		return
	}
	c.JSON(http.StatusOK, user)
}

func (ctrl *UserController) Delete(c *gin.Context) {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid ID"})
		return
	}
	if err := ctrl.service.DeleteUser(id); err != nil {
		if errors.Is(err, ErrUserNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to delete user"})
		return
	}
	c.Status(http.StatusNoContent)
}

// --- Main Application ---

func main() {
	// Dependency Injection Wire-up
	userRepo := NewInMemoryUserRepository()
	userService := NewUserService(userRepo)
	userController := NewUserController(userService)

	router := gin.Default()
	userController.RegisterRoutes(router)

	log.Println("Server starting on port 8080...")
	if err := router.Run(":8080"); err != nil {
		log.Fatalf("Failed to run server: %v", err)
	}
}