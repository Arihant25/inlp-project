package main

import (
	"context"
	"errors"
	"fmt"
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

// --- Data Access Layer (Repository) ---

type UserRepository struct {
	mu    sync.RWMutex
	users map[string]*User
}

func NewUserRepository() *UserRepository {
	repo := &UserRepository{users: make(map[string]*User)}
	repo.seed()
	return repo
}

func (r *UserRepository) seed() {
	hashedPasswordAdmin, _ := bcrypt.GenerateFromPassword([]byte("adminpass"), bcrypt.DefaultCost)
	admin := &User{ID: uuid.New(), Email: "admin@corp.com", PasswordHash: string(hashedPasswordAdmin), Role: RoleAdmin, IsActive: true, CreatedAt: time.Now()}
	r.users[admin.Email] = admin

	hashedPasswordUser, _ := bcrypt.GenerateFromPassword([]byte("userpass"), bcrypt.DefaultCost)
	user := &User{ID: uuid.New(), Email: "user@corp.com", PasswordHash: string(hashedPasswordUser), Role: RoleUser, IsActive: true, CreatedAt: time.Now()}
	r.users[user.Email] = user
}

func (r *UserRepository) FindByEmail(email string) (*User, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	user, ok := r.users[email]
	if !ok {
		return nil, errors.New("user not found")
	}
	return user, nil
}

func (r *UserRepository) Save(user *User) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.users[user.Email] = user
}

// --- Service Layer ---

type AuthService struct {
	userRepo  *UserRepository
	jwtSecret []byte
}

func NewAuthService(userRepo *UserRepository, secret string) *AuthService {
	return &AuthService{userRepo: userRepo, jwtSecret: []byte(secret)}
}

func (s *AuthService) Login(email, password string) (string, error) {
	user, err := s.userRepo.FindByEmail(email)
	if err != nil || !user.IsActive {
		return "", errors.New("invalid credentials")
	}

	if err := bcrypt.CompareHashAndPassword([]byte(user.PasswordHash), []byte(password)); err != nil {
		return "", errors.New("invalid credentials")
	}

	return s.GenerateJWT(user)
}

func (s *AuthService) GenerateJWT(user *User) (string, error) {
	claims := jwt.MapClaims{
		"user_id": user.ID.String(),
		"email":   user.Email,
		"role":    user.Role,
		"exp":     time.Now().Add(time.Hour * 24).Unix(),
	}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	return token.SignedString(s.jwtSecret)
}

// --- Handler/Controller Layer ---

type AuthHandler struct {
	authSvc      *AuthService
	userRepo     *UserRepository
	oauth2Config *oauth2.Config
	sessionStore *session.Store
}

func NewAuthHandler(authSvc *AuthService, userRepo *UserRepository, oauthCfg *oauth2.Config, sessionStore *session.Store) *AuthHandler {
	return &AuthHandler{
		authSvc:      authSvc,
		userRepo:     userRepo,
		oauth2Config: oauthCfg,
		sessionStore: sessionStore,
	}
}

func (h *AuthHandler) Login(c *fiber.Ctx) error {
	var req struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "bad request"})
	}

	token, err := h.authSvc.Login(req.Email, req.Password)
	if err != nil {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{"error": err.Error()})
	}

	return c.JSON(fiber.Map{"access_token": token})
}

func (h *AuthHandler) OAuthLogin(c *fiber.Ctx) error {
	sess, _ := h.sessionStore.Get(c)
	defer sess.Save()
	state := uuid.NewString()
	sess.Set("oauth_state", state)
	url := h.oauth2Config.AuthCodeURL(state)
	return c.Redirect(url, http.StatusTemporaryRedirect)
}

func (h *AuthHandler) OAuthCallback(c *fiber.Ctx) error {
	sess, _ := h.sessionStore.Get(c)
	defer sess.Save()

	if c.Query("state") != sess.Get("oauth_state") {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{"error": "invalid oauth state"})
	}

	code := c.Query("code")
	token, err := h.oauth2Config.Exchange(context.Background(), code)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": "oauth token exchange failed"})
	}

	// Mocking user info fetch
	oauthUserEmail := "new.oauth.user@example.com"
	
	user, err := h.userRepo.FindByEmail(oauthUserEmail)
	if err != nil { // User doesn't exist, create them
		user = &User{
			ID:        uuid.New(),
			Email:     oauthUserEmail,
			Role:      RoleUser,
			IsActive:  true,
			CreatedAt: time.Now(),
		}
		h.userRepo.Save(user)
	}

	appToken, err := h.authSvc.GenerateJWT(user)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": "failed to generate token"})
	}

	return c.JSON(fiber.Map{"access_token": appToken})
}

// --- Middleware ---

func Protected(jwtSecret string) fiber.Handler {
	return jwtware.New(jwtware.Config{
		SigningKey: []byte(jwtSecret),
		ErrorHandler: func(c *fiber.Ctx, err error) error {
			return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{"error": "unauthorized"})
		},
	})
}

func HasRole(role Role) fiber.Handler {
	return func(c *fiber.Ctx) error {
		user := c.Locals("user").(*jwt.Token)
		claims := user.Claims.(jwt.MapClaims)
		userRole := claims["role"].(string)
		if Role(userRole) != role {
			return c.Status(fiber.StatusForbidden).JSON(fiber.Map{"error": "forbidden"})
		}
		return c.Next()
	}
}

// --- Main Application Setup ---

func main() {
	// Config
	jwtSecret := "my-super-duper-secret-for-variation-2"

	// Dependencies
	userRepo := NewUserRepository()
	authSvc := NewAuthService(userRepo, jwtSecret)
	sessionStore := session.New()
	oauthCfg := &oauth2.Config{
		ClientID:     "MOCK_GOOGLE_CLIENT_ID",
		ClientSecret: "MOCK_GOOGLE_CLIENT_SECRET",
		RedirectURL:  "http://localhost:3001/v1/auth/google/callback",
		Scopes:       []string{"openid", "email", "profile"},
		Endpoint:     google.Endpoint,
	}
	authHandler := NewAuthHandler(authSvc, userRepo, oauthCfg, sessionStore)

	// Fiber App
	app := fiber.New()
	app.Use(logger.New())

	// Routing
	v1 := app.Group("/v1")
	
	authRoutes := v1.Group("/auth")
	authRoutes.Post("/login", authHandler.Login)
	authRoutes.Get("/google/login", authHandler.OAuthLogin)
	authRoutes.Get("/google/callback", authHandler.OAuthCallback)

	// Mock Post creation handler
	createPostHandler := func(c *fiber.Ctx) error {
		userToken := c.Locals("user").(*jwt.Token)
		claims := userToken.Claims.(jwt.MapClaims)
		userID := claims["user_id"].(string)
		return c.JSON(fiber.Map{
			"message": "Post created successfully",
			"user_id": userID,
		})
	}

	// Mock Admin data handler
	adminDataHandler := func(c *fiber.Ctx) error {
		return c.JSON(fiber.Map{"data": "top secret admin data"})
	}

	api := v1.Group("/api", Protected(jwtSecret))
	api.Post("/posts", createPostHandler)
	api.Get("/admin/data", HasRole(RoleAdmin), adminDataHandler)

	log.Fatal(app.Listen(":3001"))
}