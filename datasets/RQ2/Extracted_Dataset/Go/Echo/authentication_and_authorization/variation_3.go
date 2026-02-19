package main

import (
	"context"
	"encoding/gob"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"sync"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
	"github.com/gorilla/sessions"
	"github.com/labstack/echo-contrib/session"
	"github.com/labstack/echo/v4"
	"github.com/labstack/echo/v4/middleware"
	"golang.org/x/crypto/bcrypt"
	"golang.org/x/oauth2"
	"golang.org/x/oauth2/google"
)

// --- Domain Layer ---

type Role string
const (
	ADMIN Role = "ADMIN"
	USER  Role = "USER"
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
	Status  string    `json:"status"`
}

type JWTClaims struct {
	UserID string `json:"user_id"`
	Email  string `json:"email"`
	Role   Role   `json:"role"`
	jwt.RegisteredClaims
}

// --- Repository Layer (Interfaces & Implementations) ---

type IUserRepository interface {
	FindByEmail(email string) (*User, error)
	Save(user *User) error
}

type InMemoryUserRepository struct {
	mu    sync.RWMutex
	users map[string]*User
}

func NewInMemoryUserRepository() IUserRepository {
	repo := &InMemoryUserRepository{users: make(map[string]*User)}
	adminPassword, _ := bcrypt.GenerateFromPassword([]byte("adminpass"), bcrypt.DefaultCost)
	userPassword, _ := bcrypt.GenerateFromPassword([]byte("userpass"), bcrypt.DefaultCost)
	repo.Save(&User{ID: uuid.New(), Email: "admin@example.com", PasswordHash: string(adminPassword), Role: ADMIN, IsActive: true, CreatedAt: time.Now()})
	repo.Save(&User{ID: uuid.New(), Email: "user@example.com", PasswordHash: string(userPassword), Role: USER, IsActive: true, CreatedAt: time.Now()})
	return repo
}

func (r *InMemoryUserRepository) FindByEmail(email string) (*User, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	user, ok := r.users[email]
	if !ok {
		return nil, errors.New("repository: user not found")
	}
	return user, nil
}

func (r *InMemoryUserRepository) Save(user *User) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.users[user.Email] = user
	return nil
}

// --- Service Layer (Interfaces & Implementations) ---

type IAuthService interface {
	Authenticate(email, password string) (*User, error)
	GenerateToken(user *User) (string, error)
	GetOAuth2Config() *oauth2.Config
	ProcessOAuth2Callback(email string) (*User, error)
}

type AuthService struct {
	userRepo  IUserRepository
	jwtSecret string
	oauthConf *oauth2.Config
}

func NewAuthService(userRepo IUserRepository, jwtSecret string) IAuthService {
	return &AuthService{
		userRepo:  userRepo,
		jwtSecret: jwtSecret,
		oauthConf: &oauth2.Config{
			ClientID:     os.Getenv("GOOGLE_CLIENT_ID"),
			ClientSecret: os.Getenv("GOOGLE_CLIENT_SECRET"),
			RedirectURL:  "http://localhost:1323/auth/google/callback",
			Scopes:       []string{"https://www.googleapis.com/auth/userinfo.email"},
			Endpoint:     google.Endpoint,
		},
	}
}

func (s *AuthService) Authenticate(email, password string) (*User, error) {
	user, err := s.userRepo.FindByEmail(email)
	if err != nil {
		return nil, err
	}
	if err := bcrypt.CompareHashAndPassword([]byte(user.PasswordHash), []byte(password)); err != nil {
		return nil, errors.New("service: invalid credentials")
	}
	if !user.IsActive {
		return nil, errors.New("service: user inactive")
	}
	return user, nil
}

func (s *AuthService) GenerateToken(user *User) (string, error) {
	claims := &JWTClaims{
		UserID: user.ID.String(),
		Email:  user.Email,
		Role:   user.Role,
		RegisteredClaims: jwt.RegisteredClaims{ExpiresAt: jwt.NewNumericDate(time.Now().Add(time.Hour * 72))},
	}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	return token.SignedString([]byte(s.jwtSecret))
}

func (s *AuthService) GetOAuth2Config() *oauth2.Config {
	return s.oauthConf
}

func (s *AuthService) ProcessOAuth2Callback(email string) (*User, error) {
	user, err := s.userRepo.FindByEmail(email)
	if err != nil { // User not found, create them
		newUser := &User{
			ID:        uuid.New(),
			Email:     email,
			Role:      USER,
			IsActive:  true,
			CreatedAt: time.Now(),
		}
		if err := s.userRepo.Save(newUser); err != nil {
			return nil, err
		}
		return newUser, nil
	}
	return user, nil
}

// --- Controller Layer ---

type AuthController struct {
	authService IAuthService
}

func NewAuthController(authService IAuthService) *AuthController {
	return &AuthController{authService: authService}
}

func (ctrl *AuthController) Login(c echo.Context) error {
	var creds struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if err := c.Bind(&creds); err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "Invalid request body"})
	}
	user, err := ctrl.authService.Authenticate(creds.Email, creds.Password)
	if err != nil {
		return c.JSON(http.StatusUnauthorized, map[string]string{"error": "Authentication failed"})
	}
	token, err := ctrl.authService.GenerateToken(user)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": "Could not create token"})
	}
	return c.JSON(http.StatusOK, map[string]string{"token": token})
}

func (ctrl *AuthController) OAuthLogin(c echo.Context) error {
	state := uuid.NewString()
	sess, _ := session.Get("session", c)
	sess.Values["state"] = state
	sess.Save(c.Request(), c.Response())
	url := ctrl.authService.GetOAuth2Config().AuthCodeURL(state)
	return c.Redirect(http.StatusTemporaryRedirect, url)
}

func (ctrl *AuthController) OAuthCallback(c echo.Context) error {
	sess, _ := session.Get("session", c)
	if c.QueryParam("state") != sess.Values["state"] {
		return c.JSON(http.StatusUnauthorized, map[string]string{"error": "Invalid OAuth state"})
	}
	// Mocking the part where we get the user's email from the provider
	mockEmail := "oauth.user.oop@example.com"
	user, err := ctrl.authService.ProcessOAuth2Callback(mockEmail)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": "Failed to process OAuth user"})
	}
	token, err := ctrl.authService.GenerateToken(user)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": "Could not create token"})
	}
	return c.JSON(http.StatusOK, map[string]string{"token": token})
}

// --- Middleware Factory ---
func AuthMiddleware(jwtSecret string, requiredRole ...Role) echo.MiddlewareFunc {
	return func(next echo.HandlerFunc) echo.HandlerFunc {
		return func(c echo.Context) error {
			// First, run the standard JWT middleware
			jwtMiddleware := middleware.JWT([]byte(jwtSecret))
			err := jwtMiddleware(next)(c)
			if err != nil {
				// If JWT middleware fails, it sets the error on the context.
				// We just return nil to let Echo's error handler deal with it.
				return nil
			}

			// If no specific role is required, we're done.
			if len(requiredRole) == 0 {
				return next(c)
			}

			// Role check
			userToken, ok := c.Get("user").(*jwt.Token)
			if !ok {
				return echo.NewHTTPError(http.StatusUnauthorized, "JWT token missing or invalid")
			}
			claims := userToken.Claims.(jwt.MapClaims)
			role := Role(claims["role"].(string))

			for _, r := range requiredRole {
				if role == r {
					return next(c)
				}
			}

			return echo.NewHTTPError(http.StatusForbidden, "Insufficient permissions")
		}
	}
}

// --- Main / DI Container ---
func main() {
	gob.Register(map[string]interface{}{})
	e := echo.New()
	e.Use(middleware.Logger())
	e.Use(session.Middleware(sessions.NewCookieStore([]byte("oop-style-session-secret"))))

	// Config
	jwtSecret := "oop_secret"

	// Dependency Injection
	userRepo := NewInMemoryUserRepository()
	authService := NewAuthService(userRepo, jwtSecret)
	authController := NewAuthController(authService)

	// Routing
	e.POST("/login", authController.Login)
	e.GET("/auth/google/login", authController.OAuthLogin)
	e.GET("/auth/google/callback", authController.OAuthCallback)

	api := e.Group("/api")
	api.Use(middleware.JWT([]byte(jwtSecret)))

	api.POST("/posts", func(c echo.Context) error {
		return c.JSON(http.StatusOK, "post created")
	}, AuthMiddleware(jwtSecret, USER, ADMIN))

	admin := api.Group("/admin")
	admin.Use(AuthMiddleware(jwtSecret, ADMIN))
	admin.GET("/dashboard", func(c echo.Context) error {
		return c.JSON(http.StatusOK, "welcome to the admin dashboard")
	})

	log.Println("Starting OOP-style server on :1323")
	e.Logger.Fatal(e.Start(":1323"))
}