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

// --- Handler with Dependencies (OOP Style) ---

// UserHandler encapsulates the dependencies for user-related handlers.
type UserHandler struct {
	// Using a map for O(1) lookups, which is more efficient for Get/Update/Delete.
	db    map[uuid.UUID]*User
	mutex *sync.RWMutex
}

// NewUserHandler creates and initializes a UserHandler.
func NewUserHandler() *UserHandler {
	handler := &UserHandler{
		db:    make(map[uuid.UUID]*User),
		mutex: &sync.RWMutex{},
	}
	handler.seed()
	return handler
}

func (h *UserHandler) seed() {
	h.mutex.Lock()
	defer h.mutex.Unlock()

	users := []*User{
		{ID: uuid.New(), Email: "admin@example.com", PasswordHash: "hash1", Role: RoleAdmin, IsActive: true, CreatedAt: time.Now().UTC()},
		{ID: uuid.New(), Email: "user1@example.com", PasswordHash: "hash2", Role: RoleUser, IsActive: true, CreatedAt: time.Now().UTC()},
		{ID: uuid.New(), Email: "user2@example.com", PasswordHash: "hash3", Role: RoleUser, IsActive: false, CreatedAt: time.Now().UTC()},
	}

	for _, u := range users {
		h.db[u.ID] = u
	}
}

// --- Main Application ---

func main() {
	// Instantiate the handler which contains our "database" connection
	userHandler := NewUserHandler()

	router := gin.Default()

	// Setup routes and bind them to the handler's methods
	router.POST("/users", userHandler.CreateUser)
	router.GET("/users", userHandler.ListUsers)
	router.GET("/users/:id", userHandler.GetUserByID)
	router.PUT("/users/:id", userHandler.UpdateUser)
	router.PATCH("/users/:id", userHandler.PatchUser)
	router.DELETE("/users/:id", userHandler.DeleteUser)

	log.Println("Server starting on port 8080...")
	if err := router.Run(":8080"); err != nil {
		log.Fatalf("Failed to run server: %v", err)
	}
}

// --- Handler Methods ---

// CreateUser handles POST /users
func (h *UserHandler) CreateUser(c *gin.Context) {
	var req struct {
		Email    string `json:"email" binding:"required,email"`
		Password string `json:"password" binding:"required,min=8"`
		Role     Role   `json:"role" binding:"required"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	h.mutex.Lock()
	defer h.mutex.Unlock()

	for _, u := range h.db {
		if u.Email == req.Email {
			c.JSON(http.StatusConflict, gin.H{"error": "Email already in use"})
			return
		}
	}

	user := &User{
		ID:           uuid.New(),
		Email:        req.Email,
		PasswordHash: "hashed_" + req.Password, // Use bcrypt in production
		Role:         req.Role,
		IsActive:     true,
		CreatedAt:    time.Now().UTC(),
	}

	h.db[user.ID] = user
	c.JSON(http.StatusCreated, user)
}

// ListUsers handles GET /users with pagination and filtering
func (h *UserHandler) ListUsers(c *gin.Context) {
	h.mutex.RLock()
	defer h.mutex.RUnlock()

	allUsers := make([]*User, 0, len(h.db))
	for _, u := range h.db {
		allUsers = append(allUsers, u)
	}

	// Filtering logic
	roleFilter := c.Query("role")
	activeFilter := c.Query("is_active")
	searchQuery := c.Query("search")

	var filteredUsers []*User
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

	// Pagination logic
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("pageSize", "10"))
	start := (page - 1) * pageSize
	if start >= len(filteredUsers) {
		c.JSON(http.StatusOK, gin.H{"total": len(filteredUsers), "page": page, "pageSize": pageSize, "data": []*User{}})
		return
	}
	end := start + pageSize
	if end > len(filteredUsers) {
		end = len(filteredUsers)
	}

	c.JSON(http.StatusOK, gin.H{
		"total":    len(filteredUsers),
		"page":     page,
		"pageSize": pageSize,
		"data":     filteredUsers[start:end],
	})
}

// GetUserByID handles GET /users/:id
func (h *UserHandler) GetUserByID(c *gin.Context) {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid ID"})
		return
	}

	h.mutex.RLock()
	defer h.mutex.RUnlock()

	user, exists := h.db[id]
	if !exists {
		c.JSON(http.StatusNotFound, gin.H{"error": "User not found"})
		return
	}

	c.JSON(http.StatusOK, user)
}

// UpdateUser handles PUT /users/:id (full update)
func (h *UserHandler) UpdateUser(c *gin.Context) {
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

	h.mutex.Lock()
	defer h.mutex.Unlock()

	user, exists := h.db[id]
	if !exists {
		c.JSON(http.StatusNotFound, gin.H{"error": "User not found"})
		return
	}

	user.Email = req.Email
	user.Role = req.Role
	user.IsActive = *req.IsActive
	h.db[id] = user

	c.JSON(http.StatusOK, user)
}

// PatchUser handles PATCH /users/:id (partial update)
func (h *UserHandler) PatchUser(c *gin.Context) {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid ID"})
		return
	}

	var req struct {
		Email    *string `json:"email,omitempty"`
		Role     *Role   `json:"role,omitempty"`
		IsActive *bool   `json:"is_active,omitempty"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	h.mutex.Lock()
	defer h.mutex.Unlock()

	user, exists := h.db[id]
	if !exists {
		c.JSON(http.StatusNotFound, gin.H{"error": "User not found"})
		return
	}

	if req.Email != nil {
		user.Email = *req.Email
	}
	if req.Role != nil {
		user.Role = *req.Role
	}
	if req.IsActive != nil {
		user.IsActive = *req.IsActive
	}
	h.db[id] = user

	c.JSON(http.StatusOK, user)
}

// DeleteUser handles DELETE /users/:id
func (h *UserHandler) DeleteUser(c *gin.Context) {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid ID"})
		return
	}

	h.mutex.Lock()
	defer h.mutex.Unlock()

	if _, exists := h.db[id]; !exists {
		c.JSON(http.StatusNotFound, gin.H{"error": "User not found"})
		return
	}

	delete(h.db, id)
	c.Status(http.StatusNoContent)
}