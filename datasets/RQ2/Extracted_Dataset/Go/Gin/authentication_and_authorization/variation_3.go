package main

import (
	"context"
	"encoding/gob"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"strings"
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

// --- Domain Layer ---

type Role string
type PostStatus string

const (
	ADMIN Role = "ADMIN"
	USER  Role = "USER"
)
const (
	DRAFT     PostStatus = "DRAFT"
	PUBLISHED PostStatus = "PUBLISHED"
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

// --- Repository Layer (Data Access) ---

type UserRepository struct {
	users map[string]*User
}

func NewUserRepository() *UserRepository {
	return &UserRepository{users: make(map[string]*User)}
}
func (r *UserRepository) FindByEmail(email string) (*User, error) {
	user, ok := r.users[email]
	if !ok {
		return nil, errors.New("user not found")
	}
	return user, nil
}
func (r *UserRepository) Save(user *User) {
	r.users[user.Email] = user
}

// --- Service Layer (Business Logic) ---

type AuthService struct {
	userRepo *UserRepository
	jwtKey   []byte
}

func NewAuthService(userRepo *UserRepository, jwtKey []byte) *AuthService {
	return &AuthService{userRepo: userRepo, jwtKey: jwtKey}
}

func (s *AuthService) Login(email, password string) (string, error) {
	user, err := s.userRepo.FindByEmail(email)
	if err != nil || !user.IsActive {
		return "", errors.New("invalid credentials")
	}

	if err := bcrypt.CompareHashAndPassword([]byte(user.PasswordHash), []byte(password)); err != nil {
		return "", errors.New("invalid credentials")
	}

	expirationTime := time.Now().Add(1 * time.Hour)
	claims := &jwt.MapClaims{
		"exp":     expirationTime.Unix(),
		"iat":     time.Now().Unix(),
		"user_id": user.ID,
		"role":    user.Role,
	}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	return token.SignedString(s.jwtKey)
}

func (s *AuthService) ValidateToken(tokenString string) (*jwt.MapClaims, error) {
	token, err := jwt.Parse(tokenString, func(token *jwt.Token) (interface{}, error) {
		if _, ok := token.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, fmt.Errorf("unexpected signing method: %v", token.Header["alg"])
		}
		return s.jwtKey, nil
	})

	if err != nil {
		return nil, err
	}

	if claims, ok := token.Claims.(jwt.MapClaims); ok && token.Valid {
		return &claims, nil
	}
	return nil, errors.New("invalid token")
}

// --- Handler Layer (HTTP Interface) ---

type AuthHandler struct {
	authService       *AuthService
	googleOauthConfig *oauth2.Config
}

func NewAuthHandler(authSvc *AuthService, oauthCfg *oauth2.Config) *AuthHandler {
	return &AuthHandler{authService: authSvc, googleOauthConfig: oauthCfg}
}

func (h *AuthHandler) Login(c *gin.Context) {
	var loginRequest struct {
		Email    string `json:"email" binding:"required,email"`
		Password string `json:"password" binding:"required"`
	}
	if err := c.ShouldBindJSON(&loginRequest); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	token, err := h.authService.Login(loginRequest.Email, loginRequest.Password)
	if err != nil {
		c.JSON(http.StatusUnauthorized, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"token": token})
}

func (h *AuthHandler) HandleGoogleLogin(c *gin.Context) {
	url := h.googleOauthConfig.AuthCodeURL("pseudo-random")
	c.Redirect(http.StatusTemporaryRedirect, url)
}

func (h *AuthHandler) HandleGoogleCallback(c *gin.Context) {
	// Simplified: In reality, you'd exchange the code for a token and get user info.
	// Here we just set a session to show the flow.
	session := sessions.Default(c)
	session.Set("oauth_user", "simulated_google_user_id")
	session.Set("is_authenticated", true)
	err := session.Save()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to save session"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"message": "OAuth login successful, session created."})
}

// --- Middleware ---

func AuthMiddleware(authService *AuthService) gin.HandlerFunc {
	return func(c *gin.Context) {
		authHeader := c.GetHeader("Authorization")
		if authHeader == "" {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "Authorization header is required"})
			return
		}
		tokenString := strings.TrimPrefix(authHeader, "Bearer ")
		claims, err := authService.ValidateToken(tokenString)
		if err != nil {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "Invalid or expired token"})
			return
		}
		c.Set("userID", (*claims)["user_id"])
		c.Set("userRole", (*claims)["role"])
		c.Next()
	}
}

func RoleMiddleware(requiredRole Role) gin.HandlerFunc {
	return func(c *gin.Context) {
		role, exists := c.Get("userRole")
		if !exists {
			c.AbortWithStatusJSON(http.StatusForbidden, gin.H{"error": "Role not found in token"})
			return
		}
		if Role(role.(string)) != requiredRole {
			c.AbortWithStatusJSON(http.StatusForbidden, gin.H{"error": "You do not have permission to access this resource"})
			return
		}
		c.Next()
	}
}

// --- Main Application Setup ---

func main() {
	gob.Register(uuid.UUID{})

	// --- Dependency Injection ---
	userRepo := NewUserRepository()
	authService := NewAuthService(userRepo, []byte("service_layer_secret"))
	googleOauthConfig := &oauth2.Config{
		RedirectURL:  "http://localhost:8080/auth/google/callback",
		ClientID:     os.Getenv("GOOGLE_CLIENT_ID"),
		ClientSecret: os.Getenv("GOOGLE_CLIENT_SECRET"),
		Scopes:       []string{"https://www.googleapis.com/auth/userinfo.profile"},
		Endpoint:     google.Endpoint,
	}
	authHandler := NewAuthHandler(authService, googleOauthConfig)

	// --- Seed Data ---
	adminPass, _ := bcrypt.GenerateFromPassword([]byte("secureadmin"), 12)
	userRepo.Save(&User{ID: uuid.New(), Email: "admin@service.com", PasswordHash: string(adminPass), Role: ADMIN, IsActive: true})
	userPass, _ := bcrypt.GenerateFromPassword([]byte("secureuser"), 12)
	userRepo.Save(&User{ID: uuid.New(), Email: "user@service.com", PasswordHash: string(userPass), Role: USER, IsActive: true})

	// --- Router Setup ---
	r := gin.New()
	r.Use(gin.Logger(), gin.Recovery())

	// Session setup
	store := cookie.NewStore([]byte("session_secret_key"))
	r.Use(sessions.Sessions("app_session", store))

	// --- Routes ---
	authRoutes := r.Group("/auth")
	{
		authRoutes.POST("/login", authHandler.Login)
		authRoutes.GET("/google/login", authHandler.HandleGoogleLogin)
		authRoutes.GET("/google/callback", authHandler.HandleGoogleCallback)
	}

	api := r.Group("/api")
	api.Use(AuthMiddleware(authService))
	{
		api.GET("/posts", func(c *gin.Context) {
			c.JSON(http.StatusOK, gin.H{"message": "List of posts for any authenticated user"})
		})
		api.POST("/posts", func(c *gin.Context) {
			userID, _ := c.Get("userID")
			c.JSON(http.StatusCreated, gin.H{"message": "Post created successfully", "creator_id": userID})
		})

		adminApi := api.Group("/admin")
		adminApi.Use(RoleMiddleware(ADMIN))
		{
			adminApi.GET("/users", func(c *gin.Context) {
				c.JSON(http.StatusOK, gin.H{"message": "List of all users for admin"})
			})
		}
	}

	log.Println("Server with Service Layer pattern is running on port 8080")
	if err := r.Run(":8080"); err != nil {
		log.Fatalf("Failed to start server: %v", err)
	}
}