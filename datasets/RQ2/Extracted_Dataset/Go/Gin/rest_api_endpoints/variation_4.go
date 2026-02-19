package main

import (
	"log"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

// --- package: models ---
// This section simulates the models package, defining domain entities and DTOs.

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

type PostStatus string

const (
	StatusDraft     PostStatus = "DRAFT"
	StatusPublished PostStatus = "PUBLISHED"
)

type Post struct {
	ID      uuid.UUID
	UserID  uuid.UUID
	Title   string
	Content string
	Status  PostStatus
}

// Data Transfer Objects (DTOs) for API contracts
type CreateUserRequest struct {
	Email    string `json:"email" binding:"required,email"`
	Password string `json:"password" binding:"required,min=8"`
	Role     Role   `json:"role" binding:"required,oneof=ADMIN USER"`
}

type UpdateUserRequest struct {
	Email    *string `json:"email,omitempty" binding:"omitempty,email"`
	Role     *Role   `json:"role,omitempty" binding:"omitempty,oneof=ADMIN USER"`
	IsActive *bool   `json:"is_active,omitempty"`
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

// --- package: store ---
// This section simulates a data store package.

type UserStore struct {
	data  map[uuid.UUID]*User
	mutex sync.RWMutex
}

func NewUserStore() *UserStore {
	store := &UserStore{
		data: make(map[uuid.UUID]*User),
	}
	store.seed()
	return store
}

func (s *UserStore) seed() {
	users := []*User{
		{ID: uuid.New(), Email: "admin@example.com", PasswordHash: "hash1", Role: RoleAdmin, IsActive: true, CreatedAt: time.Now().UTC()},
		{ID: uuid.New(), Email: "user1@example.com", PasswordHash: "hash2", Role: RoleUser, IsActive: true, CreatedAt: time.Now().UTC()},
		{ID: uuid.New(), Email: "user2@example.com", PasswordHash: "hash3", Role: RoleUser, IsActive: false, CreatedAt: time.Now().UTC()},
	}
	for _, u := range users {
		s.data[u.ID] = u
	}
}

// --- package: user (module) ---
// This section simulates a self-contained user module with its own handlers and route registration.

type UserAPI struct {
	Store *UserStore
}

func (api *UserAPI) CreateUser(c *gin.Context) {
	var req CreateUserRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	api.Store.mutex.Lock()
	defer api.Store.mutex.Unlock()

	for _, u := range api.Store.data {
		if u.Email == req.Email {
			c.JSON(http.StatusConflict, gin.H{"error": "email already registered"})
			return
		}
	}

	newUser := &User{
		ID:           uuid.New(),
		Email:        req.Email,
		PasswordHash: "hashed_" + req.Password, // Use bcrypt in production
		Role:         req.Role,
		IsActive:     true,
		CreatedAt:    time.Now().UTC(),
	}
	api.Store.data[newUser.ID] = newUser

	c.JSON(http.StatusCreated, toUserResponse(newUser))
}

func (api *UserAPI) GetUser(c *gin.Context) {
	userID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid user ID"})
		return
	}

	api.Store.mutex.RLock()
	defer api.Store.mutex.RUnlock()

	user, ok := api.Store.data[userID]
	if !ok {
		c.JSON(http.StatusNotFound, gin.H{"error": "user not found"})
		return
	}

	c.JSON(http.StatusOK, toUserResponse(user))
}

func (api *UserAPI) ListUsers(c *gin.Context) {
	api.Store.mutex.RLock()
	defer api.Store.mutex.RUnlock()

	// Convert map to slice for filtering and sorting
	allUsers := make([]*User, 0, len(api.Store.data))
	for _, u := range api.Store.data {
		allUsers = append(allUsers, u)
	}

	// Filtering
	var filteredUsers []*User
	roleFilter := c.Query("role")
	activeFilter := c.Query("is_active")
	searchQuery := c.Query("search")

	for _, u := range allUsers {
		if roleFilter != "" && string(u.Role) != roleFilter {
			continue
		}
		if activeFilter != "" {
			isActive, err := strconv.ParseBool(activeFilter)
			if err == nil && u.IsActive != isActive {
				continue
			}
		}
		if searchQuery != "" && !strings.Contains(strings.ToLower(u.Email), strings.ToLower(searchQuery)) {
			continue
		}
		filteredUsers = append(filteredUsers, u)
	}

	// Pagination
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("pageSize", "10"))
	start := (page - 1) * pageSize
	if start >= len(filteredUsers) {
		c.JSON(http.StatusOK, gin.H{"total": len(filteredUsers), "page": page, "pageSize": pageSize, "data": []UserResponse{}})
		return
	}
	end := start + pageSize
	if end > len(filteredUsers) {
		end = len(filteredUsers)
	}

	paginatedUsers := filteredUsers[start:end]
	responseDTOs := make([]UserResponse, len(paginatedUsers))
	for i, u := range paginatedUsers {
		responseDTOs[i] = toUserResponse(u)
	}

	c.JSON(http.StatusOK, gin.H{"total": len(filteredUsers), "page": page, "pageSize": pageSize, "data": responseDTOs})
}

func (api *UserAPI) UpdateUser(c *gin.Context) {
	userID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid user ID"})
		return
	}

	var req UpdateUserRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	api.Store.mutex.Lock()
	defer api.Store.mutex.Unlock()

	user, ok := api.Store.data[userID]
	if !ok {
		c.JSON(http.StatusNotFound, gin.H{"error": "user not found"})
		return
	}

	// This logic is for PATCH, but can be used for PUT if all fields are required in the DTO
	if req.Email != nil {
		user.Email = *req.Email
	}
	if req.Role != nil {
		user.Role = *req.Role
	}
	if req.IsActive != nil {
		user.IsActive = *req.IsActive
	}

	c.JSON(http.StatusOK, toUserResponse(user))
}

func (api *UserAPI) DeleteUser(c *gin.Context) {
	userID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid user ID"})
		return
	}

	api.Store.mutex.Lock()
	defer api.Store.mutex.Unlock()

	if _, ok := api.Store.data[userID]; !ok {
		c.JSON(http.StatusNotFound, gin.H{"error": "user not found"})
		return
	}

	delete(api.Store.data, userID)
	c.Status(http.StatusNoContent)
}

// RegisterUserRoutes encapsulates the routing for the user module.
func RegisterUserRoutes(rg *gin.RouterGroup, store *UserStore) {
	api := &UserAPI{Store: store}

	rg.POST("", api.CreateUser)
	rg.GET("", api.ListUsers)
	rg.GET("/:id", api.GetUser)
	rg.PUT("/:id", api.UpdateUser)   // Can be used for full updates
	rg.PATCH("/:id", api.UpdateUser) // Or partial updates due to pointer fields in DTO
	rg.DELETE("/:id", api.DeleteUser)
}

// --- package: main ---
// The main package wires everything together.

func main() {
	// Initialize shared dependencies, like the data store
	userStore := NewUserStore()

	// Setup Gin router
	router := gin.Default()

	// Create a versioned API group
	v1 := router.Group("/api/v1")
	{
		// Register the user module's routes within the v1 group
		userRoutes := v1.Group("/users")
		RegisterUserRoutes(userRoutes, userStore)
	}

	log.Println("Server starting on port 8080...")
	if err := router.Run(":8080"); err != nil {
		log.Fatalf("Failed to run server: %v", err)
	}
}