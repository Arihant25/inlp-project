package main

import (
	"context"
	"encoding/gob"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"time"

	"github.com/google/uuid"
	"github.com/gorilla/sessions"
	"github.com/labstack/echo-contrib/session"
	"github.com/labstack/echo/v4"
	"github.com/labstack/echo/v4/middleware"
	"golang.org/x/crypto/bcrypt"
	"golang.org/x/oauth2"
	"golang.org/x/oauth2/google"

	"github.com/golang-jwt/jwt/v5"
)

// --- Domain Models ---

type Role string

const (
	ADMIN Role = "ADMIN"
	USER  Role = "USER"
)

type User struct {
	ID           uuid.UUID `json:"id"`
	Email        string    `json:"email"`
	PasswordHash string    `json:"-"`
	UserRole     Role      `json:"role"`
	IsActive     bool      `json:"is_active"`
	CreatedAt    time.Time `json:"created_at"`
}

type PostStatus string

const (
	DRAFT     PostStatus = "DRAFT"
	PUBLISHED PostStatus = "PUBLISHED"
)

type Post struct {
	ID      uuid.UUID  `json:"id"`
	UserID  uuid.UUID  `json:"user_id"`
	Title   string     `json:"title"`
	Content string     `json:"content"`
	Status  PostStatus `json:"status"`
}

// --- JWT Claims ---

type JwtCustomClaims struct {
	UserID string `json:"user_id"`
	Email  string `json:"email"`
	Role   Role   `json:"role"`
	jwt.RegisteredClaims
}

// --- Storage Layer (In-Memory Mock) ---

type UserStorage struct {
	users map[string]*User
}

func NewUserStorage() *UserStorage {
	storage := &UserStorage{users: make(map[string]*User)}
	adminPassword, _ := bcrypt.GenerateFromPassword([]byte("adminpass"), bcrypt.DefaultCost)
	userPassword, _ := bcrypt.GenerateFromPassword([]byte("userpass"), bcrypt.DefaultCost)

	adminID := uuid.New()
	storage.users["admin@example.com"] = &User{
		ID:           adminID,
		Email:        "admin@example.com",
		PasswordHash: string(adminPassword),
		UserRole:     ADMIN,
		IsActive:     true,
		CreatedAt:    time.Now(),
	}
	userID := uuid.New()
	storage.users["user@example.com"] = &User{
		ID:           userID,
		Email:        "user@example.com",
		PasswordHash: string(userPassword),
		UserRole:     USER,
		IsActive:     true,
		CreatedAt:    time.Now(),
	}
	return storage
}

func (s *UserStorage) FindByEmail(email string) (*User, error) {
	user, exists := s.users[email]
	if !exists {
		return nil, errors.New("user not found")
	}
	return user, nil
}

// --- Service Layer ---

type AuthService struct {
	jwtSecret    []byte
	oauth2Config *oauth2.Config
}

func NewAuthService(jwtSecret string) *AuthService {
	return &AuthService{
		jwtSecret: []byte(jwtSecret),
		oauth2Config: &oauth2.Config{
			ClientID:     os.Getenv("GOOGLE_CLIENT_ID"), // Mocked
			ClientSecret: os.Getenv("GOOGLE_CLIENT_SECRET"), // Mocked
			RedirectURL:  "http://localhost:1323/auth/google/callback",
			Scopes:       []string{"https://www.googleapis.com/auth/userinfo.email"},
			Endpoint:     google.Endpoint,
		},
	}
}

func (s *AuthService) GenerateJWT(user *User) (string, error) {
	claims := &JwtCustomClaims{
		user.ID.String(),
		user.Email,
		user.UserRole,
		jwt.RegisteredClaims{
			ExpiresAt: jwt.NewNumericDate(time.Now().Add(time.Hour * 72)),
			IssuedAt:  jwt.NewNumericDate(time.Now()),
		},
	}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	return token.SignedString(s.jwtSecret)
}

type UserService struct {
	storage *UserStorage
}

func NewUserService(storage *UserStorage) *UserService {
	return &UserService{storage: storage}
}

func (s *UserService) Authenticate(email, password string) (*User, error) {
	user, err := s.storage.FindByEmail(email)
	if err != nil {
		return nil, err
	}
	if !user.IsActive {
		return nil, errors.New("user is not active")
	}
	err = bcrypt.CompareHashAndPassword([]byte(user.PasswordHash), []byte(password))
	if err != nil {
		return nil, errors.New("invalid credentials")
	}
	return user, nil
}

// --- Handler/Controller Layer ---

type AuthHandler struct {
	userService *UserService
	authService *AuthService
}

func NewAuthHandler(us *UserService, as *AuthService) *AuthHandler {
	return &AuthHandler{userService: us, authService: as}
}

func (h *AuthHandler) Login(c echo.Context) error {
	req := new(struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	})
	if err := c.Bind(req); err != nil {
		return echo.NewHTTPError(http.StatusBadRequest, "Invalid input")
	}

	user, err := h.userService.Authenticate(req.Email, req.Password)
	if err != nil {
		return echo.NewHTTPError(http.StatusUnauthorized, "Invalid credentials")
	}

	token, err := h.authService.GenerateJWT(user)
	if err != nil {
		return echo.NewHTTPError(http.StatusInternalServerError, "Could not generate token")
	}

	return c.JSON(http.StatusOK, echo.Map{"token": token})
}

func (h *AuthHandler) GoogleLogin(c echo.Context) error {
	state := uuid.New().String()
	sess, _ := session.Get("session", c)
	sess.Values["state"] = state
	sess.Save(c.Request(), c.Response())
	url := h.authService.oauth2Config.AuthCodeURL(state)
	return c.Redirect(http.StatusTemporaryRedirect, url)
}

func (h *AuthHandler) GoogleCallback(c echo.Context) error {
	sess, _ := session.Get("session", c)
	if c.QueryParam("state") != sess.Values["state"] {
		return echo.NewHTTPError(http.StatusUnauthorized, "Invalid oauth state")
	}
	// In a real app, you'd exchange the code for a token and get user info.
	// We'll mock this part.
	mockEmail := "oauth.user@example.com"
	user, err := h.userService.storage.FindByEmail(mockEmail)
	if err != nil {
		// User doesn't exist, create one (or handle as needed)
		user = &User{
			ID:        uuid.New(),
			Email:     mockEmail,
			UserRole:  USER,
			IsActive:  true,
			CreatedAt: time.Now(),
		}
		h.userService.storage.users[mockEmail] = user
	}

	token, err := h.authService.GenerateJWT(user)
	if err != nil {
		return echo.NewHTTPError(http.StatusInternalServerError, "Could not generate token")
	}
	return c.JSON(http.StatusOK, echo.Map{"token": token})
}

type PostHandler struct {
	// In a real app, this would have a PostService dependency
}

func NewPostHandler() *PostHandler {
	return &PostHandler{}
}

func (h *PostHandler) GetAdminDashboard(c echo.Context) error {
	user := c.Get("user").(*jwt.Token)
	claims := user.Claims.(*JwtCustomClaims)
	return c.JSON(http.StatusOK, echo.Map{
		"message": fmt.Sprintf("Welcome to the admin dashboard, %s!", claims.Email),
	})
}

func (h *PostHandler) CreatePost(c echo.Context) error {
	user := c.Get("user").(*jwt.Token)
	claims := user.Claims.(*JwtCustomClaims)
	return c.JSON(http.StatusCreated, echo.Map{
		"message": fmt.Sprintf("Post created by user %s", claims.UserID),
	})
}

// --- Middleware ---

type MiddlewareManager struct {
	jwtSecret []byte
}

func NewMiddlewareManager(jwtSecret string) *MiddlewareManager {
	return &MiddlewareManager{jwtSecret: []byte(jwtSecret)}
}

func (m *MiddlewareManager) JWT() echo.MiddlewareFunc {
	return middleware.JWTWithConfig(middleware.JWTConfig{
		Claims:     &JwtCustomClaims{},
		SigningKey: m.jwtSecret,
	})
}

func (m *MiddlewareManager) RoleCheck(requiredRole Role) echo.MiddlewareFunc {
	return func(next echo.HandlerFunc) echo.HandlerFunc {
		return func(c echo.Context) error {
			userToken, ok := c.Get("user").(*jwt.Token)
			if !ok {
				return echo.NewHTTPError(http.StatusUnauthorized, "JWT token missing or invalid")
			}
			claims, ok := userToken.Claims.(*JwtCustomClaims)
			if !ok {
				return echo.NewHTTPError(http.StatusUnauthorized, "Invalid JWT claims")
			}
			if claims.Role != requiredRole {
				return echo.NewHTTPError(http.StatusForbidden, "Insufficient permissions")
			}
			return next(c)
		}
	}
}

// --- Main Application Setup ---

func main() {
	gob.Register(map[string]interface{}{})
	e := echo.New()
	e.Use(middleware.Logger())
	e.Use(middleware.Recover())
	e.Use(session.Middleware(sessions.NewCookieStore([]byte("secret-session-key"))))

	// Configuration
	jwtSecret := "a-very-secret-key"

	// Dependency Injection
	userStorage := NewUserStorage()
	userService := NewUserService(userStorage)
	authService := NewAuthService(jwtSecret)
	authHandler := NewAuthHandler(userService, authService)
	postHandler := NewPostHandler()
	middlewareManager := NewMiddlewareManager(jwtSecret)

	// Public Routes
	e.POST("/login", authHandler.Login)
	e.GET("/auth/google/login", authHandler.GoogleLogin)
	e.GET("/auth/google/callback", authHandler.GoogleCallback)

	// Authenticated Routes
	api := e.Group("/api")
	api.Use(middlewareManager.JWT())

	// User-specific routes
	api.POST("/posts", postHandler.CreatePost)

	// Admin-specific routes
	admin := api.Group("/admin")
	admin.Use(middlewareManager.RoleCheck(ADMIN))
	admin.GET("/dashboard", postHandler.GetAdminDashboard)

	log.Println("Starting server on :1323")
	e.Logger.Fatal(e.Start(":1323"))
}