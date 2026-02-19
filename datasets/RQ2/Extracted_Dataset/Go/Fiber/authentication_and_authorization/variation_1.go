package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
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

// --- Domain Schema ---

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

// --- Mock Database ---

var usersDB = make(map[string]*User)
var postsDB = make(map[uuid.UUID]*Post)
var adminUserID uuid.UUID
var regularUserID uuid.UUID

// --- Configuration ---

const jwtSecret = "a-very-secret-key-that-is-long-enough"

var oauth2Config *oauth2.Config
var sessionStore *session.Store

// --- Helper Functions ---

func setupMockData() {
	adminID := uuid.New()
	userID := uuid.New()
	adminUserID = adminID
	regularUserID = userID

	hashedPassword, _ := bcrypt.GenerateFromPassword([]byte("admin123"), bcrypt.DefaultCost)
	usersDB["admin@example.com"] = &User{
		ID:           adminID,
		Email:        "admin@example.com",
		PasswordHash: string(hashedPassword),
		Role:         ADMIN,
		IsActive:     true,
		CreatedAt:    time.Now(),
	}

	hashedPassword, _ = bcrypt.GenerateFromPassword([]byte("user123"), bcrypt.DefaultCost)
	usersDB["user@example.com"] = &User{
		ID:           userID,
		Email:        "user@example.com",
		PasswordHash: string(hashedPassword),
		Role:         USER,
		IsActive:     true,
		CreatedAt:    time.Now(),
	}

	postsDB[uuid.New()] = &Post{
		ID:      uuid.New(),
		UserID:  userID,
		Title:   "User's First Post",
		Content: "This is a draft.",
		Status:  DRAFT,
	}
}

func checkPasswordHash(password, hash string) bool {
	err := bcrypt.CompareHashAndPassword([]byte(hash), []byte(password))
	return err == nil
}

// --- Middleware ---

func jwtAuthMiddleware() fiber.Handler {
	return jwtware.New(jwtware.Config{
		SigningKey: []byte(jwtSecret),
		ErrorHandler: func(c *fiber.Ctx, err error) error {
			return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{
				"error": "Unauthorized",
				"message": "Invalid or missing token",
			})
		},
	})
}

func roleBasedAccessControl(requiredRole Role) fiber.Handler {
	return func(c *fiber.Ctx) error {
		token := c.Locals("user").(*jwt.Token)
		claims := token.Claims.(jwt.MapClaims)
		role := claims["role"].(string)

		if Role(role) != requiredRole {
			return c.Status(fiber.StatusForbidden).JSON(fiber.Map{
				"error": "Forbidden",
				"message": "Insufficient permissions",
			})
		}
		return c.Next()
	}
}

// --- Handlers ---

func handleLogin(c *fiber.Ctx) error {
	type LoginRequest struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	var req LoginRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "Cannot parse JSON"})
	}

	user, ok := usersDB[req.Email]
	if !ok || !user.IsActive || !checkPasswordHash(req.Password, user.PasswordHash) {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{"error": "Invalid credentials"})
	}

	claims := jwt.MapClaims{
		"user_id": user.ID.String(),
		"email":   user.Email,
		"role":    user.Role,
		"exp":     time.Now().Add(time.Hour * 72).Unix(),
	}

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	t, err := token.SignedString([]byte(jwtSecret))
	if err != nil {
		return c.SendStatus(fiber.StatusInternalServerError)
	}

	return c.JSON(fiber.Map{"token": t})
}

func handleCreatePost(c *fiber.Ctx) error {
	token := c.Locals("user").(*jwt.Token)
	claims := token.Claims.(jwt.MapClaims)
	userIDStr := claims["user_id"].(string)
	userID, _ := uuid.Parse(userIDStr)

	type CreatePostRequest struct {
		Title   string `json:"title"`
		Content string `json:"content"`
	}
	var req CreatePostRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "Cannot parse JSON"})
	}

	postID := uuid.New()
	newPost := &Post{
		ID:      postID,
		UserID:  userID,
		Title:   req.Title,
		Content: req.Content,
		Status:  DRAFT,
	}
	postsDB[postID] = newPost

	return c.Status(fiber.StatusCreated).JSON(newPost)
}

func handleAdminDashboard(c *fiber.Ctx) error {
	token := c.Locals("user").(*jwt.Token)
	claims := token.Claims.(jwt.MapClaims)
	email := claims["email"].(string)

	return c.JSON(fiber.Map{
		"message": fmt.Sprintf("Welcome to the admin dashboard, %s!", email),
		"user_count": len(usersDB),
		"post_count": len(postsDB),
	})
}

func handleGoogleLogin(c *fiber.Ctx) error {
	sess, err := sessionStore.Get(c)
	if err != nil {
		return c.Status(http.StatusInternalServerError).SendString(err.Error())
	}
	defer sess.Save()

	oauthStateString := uuid.New().String()
	sess.Set("state", oauthStateString)

	url := oauth2Config.AuthCodeURL(oauthStateString)
	return c.Redirect(url)
}

func handleGoogleCallback(c *fiber.Ctx) error {
	sess, err := sessionStore.Get(c)
	if err != nil {
		return c.Status(http.StatusInternalServerError).SendString(err.Error())
	}
	defer sess.Save()

	state := c.Query("state")
	sessionState := sess.Get("state")
	if state != sessionState {
		return c.Status(http.StatusUnauthorized).SendString("Invalid OAuth state")
	}

	code := c.Query("code")
	token, err := oauth2Config.Exchange(context.Background(), code)
	if err != nil {
		return c.Status(http.StatusInternalServerError).SendString("Failed to exchange token: " + err.Error())
	}

	// Mocking user info fetch from Google
	// In a real app, you'd use the token to call Google's user info endpoint
	client := oauth2Config.Client(context.Background(), token)
	resp, err := client.Get("https://www.googleapis.com/oauth2/v2/userinfo?access_token=" + token.AccessToken)
	if err != nil {
		// This will fail without a real call, so we mock the response
	}
	if resp != nil {
		defer resp.Body.Close()
	}
	
	// Mocked user data
	googleUser := struct {
		Email string `json:"email"`
		ID    string `json:"id"`
	}{
		Email: "oauthuser@gmail.com",
		ID:    "123456789",
	}

	// Find or create user in our DB
	user, ok := usersDB[googleUser.Email]
	if !ok {
		newID := uuid.New()
		user = &User{
			ID:        newID,
			Email:     googleUser.Email,
			Role:      USER,
			IsActive:  true,
			CreatedAt: time.Now(),
		}
		usersDB[googleUser.Email] = user
	}

	// Generate our own JWT for the user
	claims := jwt.MapClaims{
		"user_id": user.ID.String(),
		"email":   user.Email,
		"role":    user.Role,
		"exp":     time.Now().Add(time.Hour * 72).Unix(),
	}
	jwtToken := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	t, err := jwtToken.SignedString([]byte(jwtSecret))
	if err != nil {
		return c.SendStatus(fiber.StatusInternalServerError)
	}

	return c.JSON(fiber.Map{"token": t, "message": "Successfully logged in via OAuth"})
}


// --- Main Application ---

func main() {
	setupMockData()

	app := fiber.New()
	app.Use(logger.New())

	// Session store for OAuth
	sessionStore = session.New()

	// OAuth2 Config
	oauth2Config = &oauth2.Config{
		ClientID:     "YOUR_GOOGLE_CLIENT_ID",
		ClientSecret: "YOUR_GOOGLE_CLIENT_SECRET",
		RedirectURL:  "http://localhost:3000/auth/google/callback",
		Scopes:       []string{"https://www.googleapis.com/auth/userinfo.email"},
		Endpoint:     google.Endpoint,
	}

	// --- Routes ---
	api := app.Group("/api")

	// Auth routes
	auth := api.Group("/auth")
	auth.Post("/login", handleLogin)
	auth.Get("/google/login", handleGoogleLogin)
	auth.Get("/google/callback", handleGoogleCallback)

	// User routes (protected)
	userRoutes := api.Group("/user")
	userRoutes.Use(jwtAuthMiddleware())
	userRoutes.Post("/posts", handleCreatePost)

	// Admin routes (protected with RBAC)
	adminRoutes := api.Group("/admin")
	adminRoutes.Use(jwtAuthMiddleware(), roleBasedAccessControl(ADMIN))
	adminRoutes.Get("/dashboard", handleAdminDashboard)

	log.Fatal(app.Listen(":3000"))
}