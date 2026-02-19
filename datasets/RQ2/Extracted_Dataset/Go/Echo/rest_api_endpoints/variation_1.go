package main

import (
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
	PasswordHash string    `json:"-"` // Omit from JSON responses
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

// --- In-Memory Datastore ---

var (
	users = make(map[uuid.UUID]User)
	mu    = &sync.RWMutex{}
)

// --- API Handlers (Functional Style) ---

func createUser(c echo.Context) error {
	u := new(struct {
		Email    string `json:"email"`
		Password string `json:"password"`
		Role     Role   `json:"role"`
	})

	if err := c.Bind(u); err != nil {
		return echo.NewHTTPError(http.StatusBadRequest, err.Error())
	}

	// Basic validation
	if u.Email == "" || u.Password == "" {
		return echo.NewHTTPError(http.StatusBadRequest, "Email and password are required")
	}
	if u.Role != RoleAdmin && u.Role != RoleUser {
		u.Role = RoleUser // Default role
	}

	mu.Lock()
	defer mu.Unlock()

	for _, existingUser := range users {
		if existingUser.Email == u.Email {
			return echo.NewHTTPError(http.StatusConflict, "Email already exists")
		}
	}

	newUser := User{
		ID:           uuid.New(),
		Email:        u.Email,
		PasswordHash: "hashed_" + u.Password, // In a real app, use bcrypt
		Role:         u.Role,
		IsActive:     true,
		CreatedAt:    time.Now().UTC(),
	}

	users[newUser.ID] = newUser
	return c.JSON(http.StatusCreated, newUser)
}

func getUserByID(c echo.Context) error {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		return echo.NewHTTPError(http.StatusBadRequest, "Invalid user ID format")
	}

	mu.RLock()
	defer mu.RUnlock()

	user, found := users[id]
	if !found {
		return echo.NewHTTPError(http.StatusNotFound, "User not found")
	}

	return c.JSON(http.StatusOK, user)
}

func updateUser(c echo.Context) error {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		return echo.NewHTTPError(http.StatusBadRequest, "Invalid user ID format")
	}

	updateData := new(struct {
		Email    *string `json:"email"`
		Role     *Role   `json:"role"`
		IsActive *bool   `json:"is_active"`
	})

	if err := c.Bind(updateData); err != nil {
		return echo.NewHTTPError(http.StatusBadRequest, err.Error())
	}

	mu.Lock()
	defer mu.Unlock()

	user, found := users[id]
	if !found {
		return echo.NewHTTPError(http.StatusNotFound, "User not found")
	}

	if updateData.Email != nil {
		user.Email = *updateData.Email
	}
	if updateData.Role != nil {
		user.Role = *updateData.Role
	}
	if updateData.IsActive != nil {
		user.IsActive = *updateData.IsActive
	}

	users[id] = user
	return c.JSON(http.StatusOK, user)
}

func deleteUser(c echo.Context) error {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		return echo.NewHTTPError(http.StatusBadRequest, "Invalid user ID format")
	}

	mu.Lock()
	defer mu.Unlock()

	if _, found := users[id]; !found {
		return echo.NewHTTPError(http.StatusNotFound, "User not found")
	}

	delete(users, id)
	return c.NoContent(http.StatusNoContent)
}

func listUsers(c echo.Context) error {
	// Filtering
	roleFilter := c.QueryParam("role")
	isActiveFilter := c.QueryParam("is_active")

	mu.RLock()
	allUsers := make([]User, 0, len(users))
	for _, user := range users {
		// Apply filters
		if roleFilter != "" && strings.ToUpper(string(user.Role)) != strings.ToUpper(roleFilter) {
			continue
		}
		if isActiveFilter != "" {
			isActive, err := strconv.ParseBool(isActiveFilter)
			if err == nil && user.IsActive != isActive {
				continue
			}
		}
		allUsers = append(allUsers, user)
	}
	mu.RUnlock()

	// Sort by creation time for consistent pagination
	sort.Slice(allUsers, func(i, j int) bool {
		return allUsers[i].CreatedAt.Before(allUsers[j].CreatedAt)
	})

	// Pagination
	page, _ := strconv.Atoi(c.QueryParam("page"))
	if page < 1 {
		page = 1
	}
	pageSize, _ := strconv.Atoi(c.QueryParam("pageSize"))
	if pageSize < 1 || pageSize > 100 {
		pageSize = 10
	}

	offset := (page - 1) * pageSize
	if offset >= len(allUsers) {
		return c.JSON(http.StatusOK, []User{})
	}

	end := offset + pageSize
	if end > len(allUsers) {
		end = len(allUsers)
	}

	return c.JSON(http.StatusOK, allUsers[offset:end])
}

func main() {
	// Seed data
	adminID := uuid.New()
	users[adminID] = User{
		ID:           adminID,
		Email:        "admin@example.com",
		PasswordHash: "hashed_admin_pass",
		Role:         RoleAdmin,
		IsActive:     true,
		CreatedAt:    time.Now().UTC().Add(-time.Hour),
	}
	userID := uuid.New()
	users[userID] = User{
		ID:           userID,
		Email:        "user@example.com",
		PasswordHash: "hashed_user_pass",
		Role:         RoleUser,
		IsActive:     false,
		CreatedAt:    time.Now().UTC(),
	}

	e := echo.New()
	e.Use(middleware.Logger())
	e.Use(middleware.Recover())

	// Routes
	userGroup := e.Group("/users")
	userGroup.POST("", createUser)
	userGroup.GET("", listUsers)
	userGroup.GET("/:id", getUserByID)
	userGroup.PUT("/:id", updateUser)
	userGroup.DELETE("/:id", deleteUser)

	e.Logger.Fatal(e.Start(":8080"))
}