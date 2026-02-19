package main

import (
	"context"
	"encoding/gob"
	"errors"
	"log"
	"net/http"
	"os"
	"time"

	"github.comcom/gin-contrib/sessions"
	"github.com/gin-contrib/sessions/cookie"
	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v4"
	"github.com/google/uuid"
	"golang.org/x/crypto/bcrypt"
	"golang.org/x/oauth2"
	"golang.org/x/oauth2/google"
)

// --- Domain Models & Enums ---

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

// --- Mock Database ---

var usersDB = make(map[string]*User)
var postsDB = make(map[uuid.UUID]*Post)

// --- JWT & Auth Configuration ---

var jwtSecret = []byte("my_super_secret_key")
var googleOauthConfig *oauth2.Config

type Claims struct {
	UserID uuid.UUID `json:"user_id"`
	Role   Role      `json:"role"`
	jwt.RegisteredClaims
}

// --- Utility & Helper Functions ---

func hashPassword(password string) (string, error) {
	bytes, err := bcrypt.GenerateFromPassword([]byte(password), 14)
	return string(bytes), err
}

func checkPasswordHash(password, hash string) bool {
	err := bcrypt.CompareHashAndPassword([]byte(hash), []byte(password))
	return err == nil
}

func generateJWT(user *User) (string, error) {
	expirationTime := time.Now().Add(24 * time.Hour)
	claims := &Claims{
		UserID: user.ID,
		Role:   user.Role,
		RegisteredClaims: jwt.RegisteredClaims{
			ExpiresAt: jwt.NewNumericDate(expirationTime),
			Issuer:    "my-app",
		},
	}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	return token.SignedString(jwtSecret)
}

func validateJWT(tokenStr string) (*Claims, error) {
	claims := &Claims{}
	token, err := jwt.ParseWithClaims(tokenStr, claims, func(token *jwt.Token) (interface{}, error) {
		return jwtSecret, nil
	})
	if err != nil {
		return nil, err
	}
	if !token.Valid {
		return nil, errors.New("invalid token")
	}
	return claims, nil
}

// --- Middleware ---

func authMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		authHeader := c.GetHeader("Authorization")
		if authHeader == "" {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "Authorization header required"})
			return
		}

		tokenString := authHeader[len("Bearer "):]
		claims, err := validateJWT(tokenString)
		if err != nil {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "Invalid token"})
			return
		}

		c.Set("userID", claims.UserID)
		c.Set("userRole", claims.Role)
		c.Next()
	}
}

func rbacMiddleware(requiredRole Role) gin.HandlerFunc {
	return func(c *gin.Context) {
		role, exists := c.Get("userRole")
		if !exists {
			c.AbortWithStatusJSON(http.StatusForbidden, gin.H{"error": "User role not found in context"})
			return
		}

		userRole, ok := role.(Role)
		if !ok {
			c.AbortWithStatusJSON(http.StatusInternalServerError, gin.H{"error": "Invalid role type in context"})
			return
		}

		if userRole != requiredRole {
			c.AbortWithStatusJSON(http.StatusForbidden, gin.H{"error": "Insufficient permissions"})
			return
		}
		c.Next()
	}
}

// --- Handlers ---

func handleLogin(c *gin.Context) {
	var credentials struct {
		Email    string `json:"email" binding:"required"`
		Password string `json:"password" binding:"required"`
	}

	if err := c.ShouldBindJSON(&credentials); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid request body"})
		return
	}

	user, exists := usersDB[credentials.Email]
	if !exists || !user.IsActive {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "Invalid credentials"})
		return
	}

	if !checkPasswordHash(credentials.Password, user.PasswordHash) {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "Invalid credentials"})
		return
	}

	token, err := generateJWT(user)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Could not generate token"})
		return
	}

	c.JSON(http.StatusOK, gin.H{"token": token})
}

func handleGoogleLogin(c *gin.Context) {
	url := googleOauthConfig.AuthCodeURL("state-token", oauth2.AccessTypeOffline)
	c.Redirect(http.StatusTemporaryRedirect, url)
}

func handleGoogleCallback(c *gin.Context) {
	// In a real app, you'd validate the state and exchange the code for a token,
	// then fetch user info from Google. Here, we'll simulate it.
	code := c.Query("code")
	if code == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Code not found"})
		return
	}

	// Simulated user lookup/creation
	email := "user.from.google@example.com"
	user, exists := usersDB[email]
	if !exists {
		// Create a new user if they don't exist
		newUser, _ := createMockUser("user.from.google@example.com", "somepassword", USER)
		user = newUser
	}

	session := sessions.Default(c)
	session.Set("userID", user.ID.String())
	if err := session.Save(); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to save session"})
		return
	}

	c.JSON(http.StatusOK, gin.H{"message": "Successfully logged in with Google", "user_id": user.ID})
}

func handleGetPosts(c *gin.Context) {
	// In a real app, you'd filter posts based on the user or other criteria
	allPosts := make([]*Post, 0, len(postsDB))
	for _, post := range postsDB {
		allPosts = append(allPosts, post)
	}
	c.JSON(http.StatusOK, allPosts)
}

func handleCreatePost(c *gin.Context) {
	var newPostData struct {
		Title   string `json:"title" binding:"required"`
		Content string `json:"content" binding:"required"`
	}

	if err := c.ShouldBindJSON(&newPostData); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	userID, _ := c.Get("userID")
	post := &Post{
		ID:      uuid.New(),
		UserID:  userID.(uuid.UUID),
		Title:   newPostData.Title,
		Content: newPostData.Content,
		Status:  DRAFT,
	}
	postsDB[post.ID] = post

	c.JSON(http.StatusCreated, post)
}

func handleAdminDashboard(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{"message": "Welcome to the Admin Dashboard!", "user_count": len(usersDB)})
}

// --- Main Application Setup ---

func createMockUser(email, password string, role Role) (*User, error) {
	hash, err := hashPassword(password)
	if err != nil {
		return nil, err
	}
	user := &User{
		ID:           uuid.New(),
		Email:        email,
		PasswordHash: hash,
		Role:         role,
		IsActive:     true,
		CreatedAt:    time.Now(),
	}
	usersDB[email] = user
	return user, nil
}

func setupMockData() {
	_, err := createMockUser("admin@example.com", "admin123", ADMIN)
	if err != nil {
		log.Fatalf("Failed to create admin user: %v", err)
	}
	user, err := createMockUser("user@example.com", "user123", USER)
	if err != nil {
		log.Fatalf("Failed to create regular user: %v", err)
	}

	post := &Post{
		ID:      uuid.New(),
		UserID:  user.ID,
		Title:   "First Post",
		Content: "This is the content of the first post.",
		Status:  PUBLISHED,
	}
	postsDB[post.ID] = post
}

func setupOAuthConfig() {
	// In a real app, these would come from environment variables
	googleOauthConfig = &oauth2.Config{
		RedirectURL:  "http://localhost:8080/auth/google/callback",
		ClientID:     os.Getenv("GOOGLE_CLIENT_ID"), // Placeholder
		ClientSecret: os.Getenv("GOOGLE_CLIENT_SECRET"), // Placeholder
		Scopes:       []string{"https://www.googleapis.com/auth/userinfo.email"},
		Endpoint:     google.Endpoint,
	}
}

func main() {
	// Gob registration for session serialization
	gob.Register(uuid.UUID{})

	setupMockData()
	setupOAuthConfig()

	r := gin.Default()

	// Session Middleware Setup
	store := cookie.NewStore([]byte("secret-session-key"))
	r.Use(sessions.Sessions("mysession", store))

	// Public Routes
	r.POST("/login", handleLogin)
	r.GET("/auth/google/login", handleGoogleLogin)
	r.GET("/auth/google/callback", handleGoogleCallback)

	// Authenticated Routes
	authorized := r.Group("/")
	authorized.Use(authMiddleware())
	{
		authorized.GET("/posts", handleGetPosts)
		authorized.POST("/posts", handleCreatePost)

		// RBAC-protected Routes
		admin := authorized.Group("/admin")
		admin.Use(rbacMiddleware(ADMIN))
		{
			admin.GET("/dashboard", handleAdminDashboard)
		}
	}

	log.Println("Server starting on port 8080...")
	if err := r.Run(":8080"); err != nil {
		log.Fatalf("Failed to run server: %v", err)
	}
}