package main

import (
	"context"
	"encoding/gob"
	"errors"
	"log"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/gin-contrib/sessions"
	"github.com/gin-contrib/sessions/cookie"
	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v4"
	"github.com/google/uuid"
	"golang.org/x/crypto/bcrypt"
	"golang.org/x/oauth2"
	"golang.org/x/oauth2/google"
)

// --- package: domain ---

type Role string
type PostStatus string

const (
	RoleAdmin Role = "ADMIN"
	RoleUser  Role = "USER"
)
const (
	StatusDraft     PostStatus = "DRAFT"
	StatusPublished PostStatus = "PUBLISHED"
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
	ID      uuid.UUID  `json:"id"`
	UserID  uuid.UUID  `json:"user_id"`
	Title   string     `json:"title"`
	Content string     `json:"content"`
	Status  PostStatus `json:"status"`
}

// --- package: user (data access) ---

type UserStore struct {
	mu    sync.RWMutex
	users map[string]*User
}

func NewUserStore() *UserStore {
	return &UserStore{users: make(map[string]*User)}
}

func (s *UserStore) FindByEmail(email string) (*User, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	user, ok := s.users[email]
	if !ok {
		return nil, errors.New("user not found")
	}
	return user, nil
}

func (s *UserStore) Add(user *User) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.users[user.Email] = user
}

// --- package: auth (business logic & http) ---

type AuthService struct {
	userStore *UserStore
	jwtSecret string
}

func NewAuthService(us *UserStore, secret string) *AuthService {
	return &AuthService{userStore: us, jwtSecret: secret}
}

func (s *AuthService) GenerateToken(email, password string) (string, error) {
	user, err := s.userStore.FindByEmail(email)
	if err != nil {
		return "", err
	}
	if err := bcrypt.CompareHashAndPassword([]byte(user.PasswordHash), []byte(password)); err != nil {
		return "", errors.New("invalid password")
	}

	claims := jwt.MapClaims{
		"sub":  user.ID,
		"role": user.Role,
		"exp":  time.Now().Add(time.Hour * 72).Unix(),
	}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	return token.SignedString([]byte(s.jwtSecret))
}

type AuthController struct {
	authSvc           *AuthService
	googleOauthConfig *oauth2.Config
}

func NewAuthController(as *AuthService, goc *oauth2.Config) *AuthController {
	return &AuthController{authSvc: as, googleOauthConfig: goc}
}

func (ctrl *AuthController) Login(c *gin.Context) {
	var body struct {
		Email    string `json:"email" binding:"required"`
		Password string `json:"password" binding:"required"`
	}
	if err := c.BindJSON(&body); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid request"})
		return
	}
	token, err := ctrl.authSvc.GenerateToken(body.Email, body.Password)
	if err != nil {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "failed to authenticate"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"token": token})
}

func (ctrl *AuthController) RedirectToGoogle(c *gin.Context) {
	url := ctrl.googleOauthConfig.AuthCodeURL("state")
	c.Redirect(http.StatusFound, url)
}

func (ctrl *AuthController) HandleGoogleCallback(c *gin.Context) {
	// Simplified flow for demonstration
	session := sessions.Default(c)
	session.Set("provider", "google")
	session.Set("authenticated_at", time.Now().Unix())
	if err := session.Save(); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "could not save session"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"message": "session established via oauth"})
}

// --- package: middleware ---

func JWTAuthMiddleware(jwtSecret string) gin.HandlerFunc {
	return func(c *gin.Context) {
		tokenString := strings.TrimPrefix(c.GetHeader("Authorization"), "Bearer ")
		if tokenString == "" {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "request does not contain an access token"})
			return
		}
		token, err := jwt.Parse(tokenString, func(token *jwt.Token) (interface{}, error) {
			return []byte(jwtSecret), nil
		})
		if err != nil || !token.Valid {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid access token"})
			return
		}
		claims := token.Claims.(jwt.MapClaims)
		c.Set("userID", claims["sub"])
		c.Set("userRole", claims["role"])
		c.Next()
	}
}

func RBACMiddleware(requiredRole Role) gin.HandlerFunc {
	return func(c *gin.Context) {
		roleVal, ok := c.Get("userRole")
		if !ok {
			c.AbortWithStatusJSON(http.StatusForbidden, gin.H{"error": "user role not available in context"})
			return
		}
		role, ok := roleVal.(string)
		if !ok || Role(role) != requiredRole {
			c.AbortWithStatusJSON(http.StatusForbidden, gin.H{"error": "permission denied"})
			return
		}
		c.Next()
	}
}

// --- package: post (http) ---

type PostController struct {
	// In a real app, this would have a PostService/PostStore
}

func NewPostController() *PostController {
	return &PostController{}
}

func (ctrl *PostController) GetPosts(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{"message": "Fetching all posts"})
}

func (ctrl *PostController) CreatePost(c *gin.Context) {
	userID, _ := c.Get("userID")
	c.JSON(http.StatusCreated, gin.H{"message": "Post created", "author_id": userID})
}

// --- package: main (application root) ---

func main() {
	gob.Register(uuid.UUID{})

	// --- Configuration ---
	const JWT_SECRET = "modular_secret_key_!@#"

	// --- Dependency Instantiation ---
	userStore := NewUserStore()
	authService := NewAuthService(userStore, JWT_SECRET)
	googleOauthConfig := &oauth2.Config{
		RedirectURL:  "http://localhost:8080/v1/auth/google/callback",
		ClientID:     os.Getenv("GOOGLE_CLIENT_ID"),
		ClientSecret: os.Getenv("GOOGLE_CLIENT_SECRET"),
		Scopes:       []string{"openid", "email"},
		Endpoint:     google.Endpoint,
	}
	authController := NewAuthController(authService, googleOauthConfig)
	postController := NewPostController()

	// --- Data Seeding ---
	adminPass, _ := bcrypt.GenerateFromPassword([]byte("modularAdminPass"), bcrypt.DefaultCost)
	userStore.Add(&User{ID: uuid.New(), Email: "admin.mod@example.com", PasswordHash: string(adminPass), Role: RoleAdmin, IsActive: true})
	userPass, _ := bcrypt.GenerateFromPassword([]byte("modularUserPass"), bcrypt.DefaultCost)
	userStore.Add(&User{ID: uuid.New(), Email: "user.mod@example.com", PasswordHash: string(userPass), Role: RoleUser, IsActive: true})

	// --- Router Setup ---
	engine := gin.New()
	engine.Use(gin.Recovery())
	engine.Use(gin.LoggerWithFormatter(func(param gin.LogFormatterParams) string {
		return "" // Suppress logs for cleaner output
	}))

	// Session Middleware
	sessionStore := cookie.NewStore([]byte("session-key-for-oauth"))
	engine.Use(sessions.Sessions("oauth_session", sessionStore))

	// --- Route Registration ---
	v1 := engine.Group("/v1")
	{
		authGroup := v1.Group("/auth")
		{
			authGroup.POST("/login", authController.Login)
			authGroup.GET("/google/login", authController.RedirectToGoogle)
			authGroup.GET("/google/callback", authController.HandleGoogleCallback)
		}

		// All routes below require a valid JWT
		apiGroup := v1.Group("/api")
		apiGroup.Use(JWTAuthMiddleware(JWT_SECRET))
		{
			apiGroup.GET("/posts", postController.GetPosts)
			apiGroup.POST("/posts", postController.CreatePost)

			adminGroup := apiGroup.Group("/admin")
			adminGroup.Use(RBACMiddleware(RoleAdmin))
			{
				adminGroup.GET("/health", func(c *gin.Context) {
					c.JSON(http.StatusOK, gin.H{"status": "healthy", "message": "Admin services are running"})
				})
			}
		}
	}

	log.Println("Modular server starting on http://localhost:8080")
	if err := engine.Run(":8080"); err != nil {
		log.Fatalf("Failed to run server: %v", err)
	}
}