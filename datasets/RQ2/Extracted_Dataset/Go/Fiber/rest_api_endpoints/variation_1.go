package main

import (
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
	PasswordHash string    `json:"-"` // Don't expose password hash
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

// --- Mock Database ---

var (
	// Using a map as an in-memory database
	userStore = make(map[uuid.UUID]User)
	// Mutex to handle concurrent access
	storeMutex = &sync.RWMutex{}
)

// --- Main Application ---

func main() {
	// Pre-populate the database with some mock data
	seedData()

	app := fiber.New()
	app.Use(logger.New())

	// --- Route Definitions ---
	app.Post("/users", createUser)
	app.Get("/users", listUsers)
	app.Get("/users/:id", getUserByID)
	app.Put("/users/:id", updateUser)
	app.Patch("/users/:id", patchUser)
	app.Delete("/users/:id", deleteUser)

	log.Println("Server starting on port 3000...")
	log.Fatal(app.Listen(":3000"))
}

// --- Route Handlers (Functional Style) ---

func createUser(c *fiber.Ctx) error {
	// 1. Parse request body
	req := new(struct {
		Email    string `json:"email"`
		Password string `json:"password"`
		Role     Role   `json:"role"`
	})

	if err := c.BodyParser(req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "cannot parse JSON"})
	}

	// 2. Basic Validation
	if req.Email == "" || req.Password == "" {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "email and password are required"})
	}
	if req.Role != AdminRole && req.Role != UserRole {
		req.Role = UserRole // Default to USER role
	}

	// 3. Create User entity
	newUser := User{
		ID:           uuid.New(),
		Email:        req.Email,
		PasswordHash: fmt.Sprintf("hashed_%s", req.Password), // Mock hashing
		Role:         req.Role,
		IsActive:     true,
		CreatedAt:    time.Now().UTC(),
	}

	// 4. Store user
	storeMutex.Lock()
	defer storeMutex.Unlock()

	// Check for duplicate email
	for _, u := range userStore {
		if u.Email == newUser.Email {
			return c.Status(fiber.StatusConflict).JSON(fiber.Map{"error": "email already exists"})
		}
	}
	userStore[newUser.ID] = newUser

	return c.Status(fiber.StatusCreated).JSON(newUser)
}

func listUsers(c *fiber.Ctx) error {
	// 1. Parse query parameters for filtering and pagination
	roleFilter := c.Query("role")
	isActiveFilter := c.Query("is_active")
	limit, _ := strconv.Atoi(c.Query("limit", "10"))
	offset, _ := strconv.Atoi(c.Query("offset", "0"))

	storeMutex.RLock()
	defer storeMutex.RUnlock()

	// 2. Filter users
	var filteredUsers []User
	for _, user := range userStore {
		match := true
		if roleFilter != "" && string(user.Role) != strings.ToUpper(roleFilter) {
			match = false
		}
		if isActiveFilter != "" {
			isActive, err := strconv.ParseBool(isActiveFilter)
			if err == nil && user.IsActive != isActive {
				match = false
			}
		}
		if match {
			filteredUsers = append(filteredUsers, user)
		}
	}

	// 3. Apply pagination
	start := offset
	end := offset + limit
	if start > len(filteredUsers) {
		start = len(filteredUsers)
	}
	if end > len(filteredUsers) {
		end = len(filteredUsers)
	}

	paginatedUsers := filteredUsers[start:end]

	return c.JSON(fiber.Map{
		"total": len(filteredUsers),
		"limit": limit,
		"offset": offset,
		"data": paginatedUsers,
	})
}

func getUserByID(c *fiber.Ctx) error {
	id, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "invalid UUID format"})
	}

	storeMutex.RLock()
	defer storeMutex.RUnlock()

	user, found := userStore[id]
	if !found {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": "user not found"})
	}

	return c.JSON(user)
}

func updateUser(c *fiber.Ctx) error { // PUT - full update
	id, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "invalid UUID format"})
	}

	req := new(struct {
		Email    string `json:"email"`
		Role     Role   `json:"role"`
		IsActive bool   `json:"is_active"`
	})

	if err := c.BodyParser(req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "cannot parse JSON"})
	}

	storeMutex.Lock()
	defer storeMutex.Unlock()

	user, found := userStore[id]
	if !found {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": "user not found"})
	}

	// Full update
	user.Email = req.Email
	user.Role = req.Role
	user.IsActive = req.IsActive
	userStore[id] = user

	return c.JSON(user)
}

func patchUser(c *fiber.Ctx) error { // PATCH - partial update
	id, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "invalid UUID format"})
	}

	var req map[string]interface{}
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "cannot parse JSON"})
	}

	storeMutex.Lock()
	defer storeMutex.Unlock()

	user, found := userStore[id]
	if !found {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": "user not found"})
	}

	// Partial update
	if email, ok := req["email"].(string); ok {
		user.Email = email
	}
	if role, ok := req["role"].(string); ok {
		user.Role = Role(role)
	}
	if isActive, ok := req["is_active"].(bool); ok {
		user.IsActive = isActive
	}

	userStore[id] = user
	return c.JSON(user)
}

func deleteUser(c *fiber.Ctx) error {
	id, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "invalid UUID format"})
	}

	storeMutex.Lock()
	defer storeMutex.Unlock()

	if _, found := userStore[id]; !found {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": "user not found"})
	}

	delete(userStore, id)
	return c.SendStatus(fiber.StatusNoContent)
}

// --- Helper Functions ---

func seedData() {
	user1 := User{ID: uuid.New(), Email: "admin@example.com", PasswordHash: "hashed_adminpass", Role: AdminRole, IsActive: true, CreatedAt: time.Now().UTC()}
	user2 := User{ID: uuid.New(), Email: "user1@example.com", PasswordHash: "hashed_user1pass", Role: UserRole, IsActive: true, CreatedAt: time.Now().UTC()}
	user3 := User{ID: uuid.New(), Email: "user2@example.com", PasswordHash: "hashed_user2pass", Role: UserRole, IsActive: false, CreatedAt: time.Now().UTC()}
	
	userStore[user1.ID] = user1
	userStore[user2.ID] = user2
	userStore[user3.ID] = user3
}