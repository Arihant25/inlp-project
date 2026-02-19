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
	PasswordHash string    `json:"-"` // Omit from JSON responses
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

// --- In-Memory Datastore (Procedural/Global) ---

var (
	// Using a slice as a simple in-memory DB
	users = make([]User, 0)
	// Mutex to handle concurrent access
	userMutex = &sync.RWMutex{}
)

// --- Main Application ---

func main() {
	// Seed the database with some initial data
	seedData()

	router := gin.Default()

	// Group user routes
	userRoutes := router.Group("/users")
	{
		userRoutes.POST("", createUser)
		userRoutes.GET("", listUsers)
		userRoutes.GET("/:id", getUserByID)
		userRoutes.PUT("/:id", updateUser)
		userRoutes.DELETE("/:id", deleteUser)
	}

	log.Println("Server starting on port 8080...")
	if err := router.Run(":8080"); err != nil {
		log.Fatalf("Failed to run server: %v", err)
	}
}

func seedData() {
	userMutex.Lock()
	defer userMutex.Unlock()

	users = append(users,
		User{
			ID:           uuid.New(),
			Email:        "admin@example.com",
			PasswordHash: "hashed_password_1",
			Role:         RoleAdmin,
			IsActive:     true,
			CreatedAt:    time.Now().UTC(),
		},
		User{
			ID:           uuid.New(),
			Email:        "user1@example.com",
			PasswordHash: "hashed_password_2",
			Role:         RoleUser,
			IsActive:     true,
			CreatedAt:    time.Now().UTC(),
		},
		User{
			ID:           uuid.New(),
			Email:        "user2@example.com",
			PasswordHash: "hashed_password_3",
			Role:         RoleUser,
			IsActive:     false,
			CreatedAt:    time.Now().UTC(),
		},
	)
}

// --- Route Handlers (Functional Style) ---

// createUser handles POST /users
func createUser(c *gin.Context) {
	var newUser struct {
		Email    string `json:"email" binding:"required,email"`
		Password string `json:"password" binding:"required,min=8"`
		Role     Role   `json:"role" binding:"required"`
	}

	if err := c.ShouldBindJSON(&newUser); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	userMutex.Lock()
	defer userMutex.Unlock()

	for _, u := range users {
		if u.Email == newUser.Email {
			c.JSON(http.StatusConflict, gin.H{"error": "User with this email already exists"})
			return
		}
	}

	user := User{
		ID:           uuid.New(),
		Email:        newUser.Email,
		PasswordHash: "hashed_" + newUser.Password, // In a real app, use bcrypt
		Role:         newUser.Role,
		IsActive:     true,
		CreatedAt:    time.Now().UTC(),
	}

	users = append(users, user)
	c.JSON(http.StatusCreated, user)
}

// listUsers handles GET /users with pagination and filtering
func listUsers(c *gin.Context) {
	userMutex.RLock()
	defer userMutex.RUnlock()

	// Filtering
	filteredUsers := make([]User, 0, len(users))
	roleFilter := c.Query("role")
	activeFilter := c.Query("is_active")
	searchQuery := c.Query("search")

	for _, u := range users {
		match := true
		if roleFilter != "" && string(u.Role) != roleFilter {
			match = false
		}
		if activeFilter != "" {
			isActive, err := strconv.ParseBool(activeFilter)
			if err == nil && u.IsActive != isActive {
				match = false
			}
		}
		if searchQuery != "" && !strings.Contains(strings.ToLower(u.Email), strings.ToLower(searchQuery)) {
			match = false
		}

		if match {
			filteredUsers = append(filteredUsers, u)
		}
	}

	// Pagination
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("pageSize", "10"))
	if page < 1 {
		page = 1
	}
	if pageSize < 1 {
		pageSize = 10
	}

	start := (page - 1) * pageSize
	end := start + pageSize
	if start > len(filteredUsers) {
		c.JSON(http.StatusOK, gin.H{"total": len(filteredUsers), "page": page, "pageSize": pageSize, "data": []User{}})
		return
	}
	if end > len(filteredUsers) {
		end = len(filteredUsers)
	}

	paginatedUsers := filteredUsers[start:end]

	c.JSON(http.StatusOK, gin.H{
		"total":    len(filteredUsers),
		"page":     page,
		"pageSize": pageSize,
		"data":     paginatedUsers,
	})
}

// getUserByID handles GET /users/:id
func getUserByID(c *gin.Context) {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid user ID format"})
		return
	}

	userMutex.RLock()
	defer userMutex.RUnlock()

	for _, u := range users {
		if u.ID == id {
			c.JSON(http.StatusOK, u)
			return
		}
	}

	c.JSON(http.StatusNotFound, gin.H{"error": "User not found"})
}

// updateUser handles PUT /users/:id
func updateUser(c *gin.Context) {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid user ID format"})
		return
	}

	var updatedUser struct {
		Email    string `json:"email" binding:"required,email"`
		Role     Role   `json:"role" binding:"required"`
		IsActive *bool  `json:"is_active" binding:"required"`
	}

	if err := c.ShouldBindJSON(&updatedUser); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	userMutex.Lock()
	defer userMutex.Unlock()

	for i, u := range users {
		if u.ID == id {
			users[i].Email = updatedUser.Email
			users[i].Role = updatedUser.Role
			users[i].IsActive = *updatedUser.IsActive
			c.JSON(http.StatusOK, users[i])
			return
		}
	}

	c.JSON(http.StatusNotFound, gin.H{"error": "User not found"})
}

// deleteUser handles DELETE /users/:id
func deleteUser(c *gin.Context) {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid user ID format"})
		return
	}

	userMutex.Lock()
	defer userMutex.Unlock()

	for i, u := range users {
		if u.ID == id {
			users = append(users[:i], users[i+1:]...)
			c.Status(http.StatusNoContent)
			return
		}
	}

	c.JSON(http.StatusNotFound, gin.H{"error": "User not found"})
}