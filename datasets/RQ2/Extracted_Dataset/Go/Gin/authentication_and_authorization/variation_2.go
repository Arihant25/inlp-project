package main

import (
	"context"
	"encoding/gob"
	"errors"
	"log"
	"net/http"
	"os"
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

// --- Domain Models & Enums ---

type UserRole string
type PostStatus string

const (
	RoleAdmin UserRole = "ADMIN"
	RoleUser  UserRole = "USER"
)

const (
	StatusDraft     PostStatus = "DRAFT"
	StatusPublished PostStatus = "PUBLISHED"
)

type User struct {
	ID           uuid.UUID `json:"id"`
	Email        string    `json:"email"`
	PasswordHash string    `json:"-"`
	Role         UserRole  `json:"role"`
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

// --- Mock Data Store ---

type MockDataStore struct {
	users map[string]*User
	posts map[uuid.UUID]*Post
}

func NewMockDataStore() *MockDataStore {
	return &MockDataStore{
		users: make(map[string]*User),
		posts: make(map[uuid.UUID]*Post),
	}
}

// --- JWT & Auth Configuration ---

var jwtSecretKey = []byte("a_different_secret_key")

type JWTClaims struct {
	UserID uuid.UUID `json:"user_id"`
	Role   UserRole  `json:"role"`
	jwt.RegisteredClaims
}

// --- Handlers (OOP Style) ---

type AuthHandler struct {
	db                *MockDataStore
	googleOauthConfig *oauth2.Config
}

func NewAuthHandler(db *MockDataStore, oauthConfig *oauth2.Config) *AuthHandler {
	return &AuthHandler{db: db, googleOauthConfig: oauthConfig}
}

func (h *AuthHandler) Login(c *gin.Context) {
	var req struct {
		Email    string `json:"email" binding:"required"`
		Password string `json:"password" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid input"})
		return
	}

	user, exists := h.db.users[req.Email]
	if !exists || !user.IsActive || bcrypt.CompareHashAndPassword([]byte(user.PasswordHash), []byte(req.Password)) != nil {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "authentication failed"})
		return
	}

	expirationTime := time.Now().Add(24 * time.Hour)
	claims := &JWTClaims{
		UserID: user.ID,
		Role:   user.Role,
		RegisteredClaims: jwt.RegisteredClaims{ExpiresAt: jwt.NewNumericDate(expirationTime)},
	}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	tokenString, err := token.SignedString(jwtSecretKey)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to create token"})
		return
	}

	c.JSON(http.StatusOK, gin.H{"access_token": tokenString})
}

func (h *AuthHandler) GoogleLogin(c *gin.Context) {
	url := h.googleOauthConfig.AuthCodeURL("random-state")
	c.Redirect(http.StatusTemporaryRedirect, url)
}

func (h *AuthHandler) GoogleCallback(c *gin.Context) {
	// Simulate fetching user from Google and creating/finding them in our DB
	simulatedEmail := "oauth.user@example.com"
	user, exists := h.db.users[simulatedEmail]
	if !exists {
		c.JSON(http.StatusNotFound, gin.H{"error": "OAuth user not found, please register first"})
		return
	}

	session := sessions.Default(c)
	session.Set("profile_id", user.ID)
	if err := session.Save(); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "could not save session"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"status": "google login successful", "user_id": user.ID})
}

type PostHandler struct {
	db *MockDataStore
}

func NewPostHandler(db *MockDataStore) *PostHandler {
	return &PostHandler{db: db}
}

func (h *PostHandler) GetAllPosts(c *gin.Context) {
	var postList []*Post
	for _, p := range h.db.posts {
		postList = append(postList, p)
	}
	c.JSON(http.StatusOK, gin.H{"posts": postList})
}

func (h *PostHandler) CreatePost(c *gin.Context) {
	var req struct {
		Title   string `json:"title" binding:"required"`
		Content string `json:"content" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid input"})
		return
	}

	userIDVal, _ := c.Get("userID")
	userID, ok := userIDVal.(uuid.UUID)
	if !ok {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "user ID not found in context"})
		return
	}

	newPost := &Post{
		ID:      uuid.New(),
		UserID:  userID,
		Title:   req.Title,
		Content: req.Content,
		Status:  StatusDraft,
	}
	h.db.posts[newPost.ID] = newPost
	c.JSON(http.StatusCreated, newPost)
}

// --- Middleware ---

func JWTMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		authHeader := c.GetHeader("Authorization")
		if authHeader == "" || len(authHeader) < 8 || authHeader[:7] != "Bearer " {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "missing or malformed token"})
			return
		}
		tokenStr := authHeader[7:]
		claims := &JWTClaims{}
		token, err := jwt.ParseWithClaims(tokenStr, claims, func(token *jwt.Token) (interface{}, error) {
			return jwtSecretKey, nil
		})

		if err != nil || !token.Valid {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid token"})
			return
		}

		c.Set("userID", claims.UserID)
		c.Set("userRole", claims.Role)
		c.Next()
	}
}

func RBACMiddleware(allowedRoles ...UserRole) gin.HandlerFunc {
	return func(c *gin.Context) {
		roleVal, exists := c.Get("userRole")
		if !exists {
			c.AbortWithStatusJSON(http.StatusForbidden, gin.H{"error": "access denied"})
			return
		}
		userRole := roleVal.(UserRole)
		for _, allowedRole := range allowedRoles {
			if userRole == allowedRole {
				c.Next()
				return
			}
		}
		c.AbortWithStatusJSON(http.StatusForbidden, gin.H{"error": "insufficient permissions"})
	}
}

// --- Main Application Setup ---

func seedData(store *MockDataStore) {
	hashedPassAdmin, _ := bcrypt.GenerateFromPassword([]byte("adminpass"), bcrypt.DefaultCost)
	adminUser := &User{ID: uuid.New(), Email: "admin@test.com", PasswordHash: string(hashedPassAdmin), Role: RoleAdmin, IsActive: true, CreatedAt: time.Now()}
	store.users[adminUser.Email] = adminUser

	hashedPassUser, _ := bcrypt.GenerateFromPassword([]byte("userpass"), bcrypt.DefaultCost)
	regularUser := &User{ID: uuid.New(), Email: "user@test.com", PasswordHash: string(hashedPassUser), Role: RoleUser, IsActive: true, CreatedAt: time.Now()}
	store.users[regularUser.Email] = regularUser
	
	oauthUser := &User{ID: uuid.New(), Email: "oauth.user@example.com", PasswordHash: "", Role: RoleUser, IsActive: true, CreatedAt: time.Now()}
	store.users[oauthUser.Email] = oauthUser

	post1 := &Post{ID: uuid.New(), UserID: regularUser.ID, Title: "Hello World", Content: "My first post.", Status: StatusPublished}
	store.posts[post1.ID] = post1
}

func main() {
	gob.Register(uuid.UUID{})

	dataStore := NewMockDataStore()
	seedData(dataStore)

	googleOauthConfig := &oauth2.Config{
		RedirectURL:  "http://localhost:8080/auth/google/callback",
		ClientID:     os.Getenv("GOOGLE_CLIENT_ID"),
		ClientSecret: os.Getenv("GOOGLE_CLIENT_SECRET"),
		Scopes:       []string{"https://www.googleapis.com/auth/userinfo.email"},
		Endpoint:     google.Endpoint,
	}

	authHandler := NewAuthHandler(dataStore, googleOauthConfig)
	postHandler := NewPostHandler(dataStore)

	router := gin.Default()

	cookieStore := cookie.NewStore([]byte("session-secret"))
	router.Use(sessions.Sessions("app_session", cookieStore))

	// Public routes
	router.POST("/login", authHandler.Login)
	router.GET("/auth/google/login", authHandler.GoogleLogin)
	router.GET("/auth/google/callback", authHandler.GoogleCallback)

	// Protected routes
	api := router.Group("/api")
	api.Use(JWTMiddleware())
	{
		// Routes for any authenticated user (USER or ADMIN)
		posts := api.Group("/posts")
		posts.Use(RBACMiddleware(RoleUser, RoleAdmin))
		{
			posts.GET("", postHandler.GetAllPosts)
			posts.POST("", postHandler.CreatePost)
		}

		// Routes for ADMIN only
		admin := api.Group("/admin")
		admin.Use(RBACMiddleware(RoleAdmin))
		{
			admin.GET("/status", func(c *gin.Context) {
				c.JSON(http.StatusOK, gin.H{"status": "ok", "message": "Welcome Admin!"})
			})
		}
	}

	log.Println("Starting server on :8080")
	if err := router.Run(":8080"); err != nil {
		log.Fatalf("Server failed to start: %v", err)
	}
}