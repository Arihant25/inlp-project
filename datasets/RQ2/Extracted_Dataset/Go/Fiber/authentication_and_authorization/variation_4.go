package main

import (
	"context"
	"errors"
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/gofiber/fiber/v2/middleware/logger"
	"github.com/gofiber/jwt/v3"
	"github.com/gofiber/session/v2"
	"github.com/google/uuid"
	"golang.org/x/crypto/bcrypt"
	"golang.org/x/oauth2"
	"golang.org/x/oauth2/google"
)

// --- Domain & Mock DB ---
type Role string
const (
	ADMIN Role = "ADMIN"
	USER  Role = "USER"
)
type Status string
const (
	DRAFT     Status = "DRAFT"
	PUBLISHED Status = "PUBLISHED"
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
	users      = make(map[uuid.UUID]*User)
	usersByEmail = make(map[string]*User)
	posts      = make(map[uuid.UUID]*Post)
	dbMutex    = &sync.RWMutex{}
	jwtSecret  = []byte("yet-another-secret-for-handler-centric-style")
)

func seedData() {
	dbMutex.Lock()
	defer dbMutex.Unlock()
	
	adminPass, _ := bcrypt.GenerateFromPassword([]byte("securepass1"), bcrypt.DefaultCost)
	adminID := uuid.New()
	admin := &User{ID: adminID, Email: "admin.boss@example.com", PasswordHash: string(adminPass), Role: ADMIN, IsActive: true, CreatedAt: time.Now()}
	users[adminID] = admin
	usersByEmail[admin.Email] = admin

	userPass, _ := bcrypt.GenerateFromPassword([]byte("securepass2"), bcrypt.DefaultCost)
	userID := uuid.New()
	user := &User{ID: userID, Email: "regular.joe@example.com", PasswordHash: string(userPass), Role: USER, IsActive: true, CreatedAt: time.Now()}
	users[userID] = user
	usersByEmail[user.Email] = user
}

// --- Middleware Chain ---

// 1. JWT Validator Middleware
var jwtMiddleware = jwtware.New(jwtware.Config{
	SigningKey: jwtSecret,
	ErrorHandler: func(c *fiber.Ctx, err error) error {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{"status": "error", "message": "Invalid or expired token"})
	},
})

// 2. User Loader Middleware (runs after JWT validation)
func loadUserFromJWT(c *fiber.Ctx) error {
	token := c.Locals("user").(*jwt.Token)
	claims := token.Claims.(jwt.MapClaims)
	userIDStr, ok := claims["sub"].(string)
	if !ok {
		return c.Status(fiber.StatusForbidden).JSON(fiber.Map{"status": "error", "message": "Invalid token claims"})
	}

	userID, err := uuid.Parse(userIDStr)
	if err != nil {
		return c.Status(fiber.StatusForbidden).JSON(fiber.Map{"status": "error", "message": "Malformed user ID in token"})
	}

	dbMutex.RLock()
	user, exists := users[userID]
	dbMutex.RUnlock()

	if !exists || !user.IsActive {
		return c.Status(fiber.StatusForbidden).JSON(fiber.Map{"status": "error", "message": "User not found or inactive"})
	}

	c.Locals("currentUser", user)
	return c.Next()
}

// 3. Role Checker Middleware (factory function)
func requireRole(r Role) fiber.Handler {
	return func(c *fiber.Ctx) error {
		currentUser, ok := c.Locals("currentUser").(*User)
		if !ok {
			return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"status": "error", "message": "User context not found"})
		}

		if currentUser.Role != r {
			return c.Status(fiber.StatusForbidden).JSON(fiber.Map{"status": "error", "message": "Insufficient permissions"})
		}
		return c.Next()
	}
}

// --- Handlers ---

func loginHandler(c *fiber.Ctx) error {
	var req struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"status": "error", "message": "Cannot parse request"})
	}

	dbMutex.RLock()
	user, exists := usersByEmail[req.Email]
	dbMutex.RUnlock()

	if !exists || bcrypt.CompareHashAndPassword([]byte(user.PasswordHash), []byte(req.Password)) != nil {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{"status": "error", "message": "Invalid email or password"})
	}

	claims := jwt.MapClaims{
		"sub":   user.ID.String(),
		"role":  user.Role,
		"email": user.Email,
		"iat":   time.Now().Unix(),
		"exp":   time.Now().Add(time.Hour * 24).Unix(),
	}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	signedToken, err := token.SignedString(jwtSecret)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"status": "error", "message": "Failed to create token"})
	}

	return c.JSON(fiber.Map{"status": "success", "token": signedToken})
}

func createPostHandler(c *fiber.Ctx) error {
	currentUser := c.Locals("currentUser").(*User)
	var req struct {
		Title   string `json:"title"`
		Content string `json:"content"`
	}
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"status": "error", "message": "Invalid post format"})
	}

	postID := uuid.New()
	newPost := &Post{
		ID:      postID,
		UserID:  currentUser.ID,
		Title:   req.Title,
		Content: req.Content,
		Status:  DRAFT,
	}

	dbMutex.Lock()
	posts[postID] = newPost
	dbMutex.Unlock()

	return c.Status(fiber.StatusCreated).JSON(newPost)
}

func adminDashboardHandler(c *fiber.Ctx) error {
	currentUser := c.Locals("currentUser").(*User)
	dbMutex.RLock()
	userCount := len(users)
	postCount := len(posts)
	dbMutex.RUnlock()

	return c.JSON(fiber.Map{
		"message":      "Welcome, " + currentUser.Email,
		"user_count":   userCount,
		"post_count":   postCount,
		"access_level": currentUser.Role,
	})
}

// --- Main Application ---
func main() {
	seedData()
	app := fiber.New()
	app.Use(logger.New())

	sessionStore := session.New()
	oauthCfg := &oauth2.Config{
		ClientID:     "MOCK_CLIENT_ID_V4",
		ClientSecret: "MOCK_CLIENT_SECRET_V4",
		RedirectURL:  "http://localhost:3003/oauth/callback",
		Scopes:       []string{"profile", "email"},
		Endpoint:     google.Endpoint,
	}

	// --- Public Routes ---
	app.Post("/login", loginHandler)
	
	// OAuth2 Flow
	app.Get("/oauth/login", func(c *fiber.Ctx) error {
		sess, _ := sessionStore.Get(c)
		defer sess.Save()
		state := uuid.NewString()
		sess.Set("state", state)
		return c.Redirect(oauthCfg.AuthCodeURL(state), http.StatusTemporaryRedirect)
	})

	app.Get("/oauth/callback", func(c *fiber.Ctx) error {
		sess, _ := sessionStore.Get(c)
		defer sess.Save()
		if c.Query("state") != sess.Get("state") {
			return c.Status(http.StatusUnauthorized).SendString("Invalid state")
		}
		// Mock user creation/login after successful OAuth
		oauthEmail := "oauth.user.v4@example.com"
		dbMutex.Lock()
		user, exists := usersByEmail[oauthEmail]
		if !exists {
			userID := uuid.New()
			user = &User{ID: userID, Email: oauthEmail, Role: USER, IsActive: true, CreatedAt: time.Now()}
			users[userID] = user
			usersByEmail[oauthEmail] = user
		}
		dbMutex.Unlock()

		claims := jwt.MapClaims{"sub": user.ID.String(), "role": user.Role, "exp": time.Now().Add(time.Hour * 24).Unix()}
		token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
		signedToken, _ := token.SignedString(jwtSecret)
		return c.JSON(fiber.Map{"status": "success", "token": signedToken})
	})

	// --- Protected Routes with Middleware Chaining ---
	api := app.Group("/api", jwtMiddleware, loadUserFromJWT)
	
	// Any authenticated user can access this
	api.Post("/posts", createPostHandler)

	// Only admins can access this
	admin := api.Group("/admin", requireRole(ADMIN))
	admin.Get("/dashboard", adminDashboardHandler)

	log.Fatal(app.Listen(":3003"))
}