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

// --- Models & Data Store ---

type Role string
const (
	ADMIN Role = "ADMIN"
	USER  Role = "USER"
)

type User struct {
	ID           uuid.UUID
	Email        string
	PasswordHash string
	Role         Role
	IsActive     bool
	CreatedAt    time.Time
}

type Post struct {
	ID      uuid.UUID
	UserID  uuid.UUID
	Title   string
	Content string
	Status  string
}

type JWTClaims struct {
	UserID string `json:"user_id"`
	Email  string `json:"email"`
	Role   Role   `json:"role"`
	jwt.RegisteredClaims
}

var (
	// In-memory database
	userDB = make(map[string]*User)
	postDB = make(map[uuid.UUID]*Post)
	// Config
	jwtSecret    = []byte("procedural_secret_key")
	oauth2Config *oauth2.Config
)

func seedData() {
	adminPassword, _ := bcrypt.GenerateFromPassword([]byte("adminpass"), bcrypt.DefaultCost)
	userPassword, _ := bcrypt.GenerateFromPassword([]byte("userpass"), bcrypt.DefaultCost)

	userDB["admin@example.com"] = &User{
		ID:           uuid.New(),
		Email:        "admin@example.com",
		PasswordHash: string(adminPassword),
		Role:         ADMIN,
		IsActive:     true,
		CreatedAt:    time.Now(),
	}
	userDB["user@example.com"] = &User{
		ID:           uuid.New(),
		Email:        "user@example.com",
		PasswordHash: string(userPassword),
		Role:         USER,
		IsActive:     true,
		CreatedAt:    time.Now(),
	}
	log.Println("Data seeded.")
}

// --- Authentication Logic ---

func findUserByEmail(email string) (*User, error) {
	user, ok := userDB[email]
	if !ok {
		return nil, errors.New("user not found")
	}
	return user, nil
}

func verifyPassword(hash, password string) bool {
	err := bcrypt.CompareHashAndPassword([]byte(hash), []byte(password))
	return err == nil
}

func createJWT(user *User) (string, error) {
	claims := &JWTClaims{
		UserID: user.ID.String(),
		Email:  user.Email,
		Role:   user.Role,
		RegisteredClaims: jwt.RegisteredClaims{
			ExpiresAt: jwt.NewNumericDate(time.Now().Add(time.Hour * 24)),
		},
	}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	return token.SignedString(jwtSecret)
}

// --- Middleware ---

func buildJwtMiddleware() echo.MiddlewareFunc {
	return middleware.JWTWithConfig(middleware.JWTConfig{
		Claims:     &JWTClaims{},
		SigningKey: jwtSecret,
	})
}

func requireRole(requiredRole Role) echo.MiddlewareFunc {
	return func(next echo.HandlerFunc) echo.HandlerFunc {
		return func(c echo.Context) error {
			token, ok := c.Get("user").(*jwt.Token)
			if !ok {
				return c.JSON(http.StatusUnauthorized, "Missing token")
			}
			claims, ok := token.Claims.(*JWTClaims)
			if !ok || claims.Role != requiredRole {
				return c.JSON(http.StatusForbidden, "Access denied")
			}
			return next(c)
		}
	}
}

// --- Handlers ---

func handleLogin(c echo.Context) error {
	type loginRequest struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	req := new(loginRequest)
	if err := c.Bind(req); err != nil {
		return c.JSON(http.StatusBadRequest, "Bad request")
	}

	user, err := findUserByEmail(req.Email)
	if err != nil || !verifyPassword(user.PasswordHash, req.Password) || !user.IsActive {
		return c.JSON(http.StatusUnauthorized, "Invalid credentials")
	}

	token, err := createJWT(user)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, "Failed to create token")
	}

	return c.JSON(http.StatusOK, map[string]string{"token": token})
}

func handleGoogleLogin(c echo.Context) error {
	state := uuid.NewString()
	sess, _ := session.Get("session", c)
	sess.Values["oauth_state"] = state
	sess.Save(c.Request(), c.Response())
	url := oauth2Config.AuthCodeURL(state)
	return c.Redirect(http.StatusTemporaryRedirect, url)
}

func handleGoogleCallback(c echo.Context) error {
	sess, _ := session.Get("session", c)
	if c.QueryParam("state") != sess.Values["oauth_state"] {
		return c.JSON(http.StatusUnauthorized, "Invalid state")
	}

	// Mocking user info retrieval from Google
	mockEmail := "oauth.user.procedural@example.com"
	user, err := findUserByEmail(mockEmail)
	if err != nil { // User not found, create a new one
		user = &User{
			ID:        uuid.New(),
			Email:     mockEmail,
			Role:      USER,
			IsActive:  true,
			CreatedAt: time.Now(),
		}
		userDB[mockEmail] = user
	}

	token, err := createJWT(user)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, "Failed to create token")
	}
	return c.JSON(http.StatusOK, map[string]string{"token": token})
}

func handleCreatePost(c echo.Context) error {
	claims := c.Get("user").(*jwt.Token).Claims.(*JWTClaims)
	return c.JSON(http.StatusCreated, map[string]string{
		"message": fmt.Sprintf("Post created by user %s", claims.UserID),
	})
}

func handleAdminData(c echo.Context) error {
	claims := c.Get("user").(*jwt.Token).Claims.(*JWTClaims)
	return c.JSON(http.StatusOK, map[string]string{
		"message": fmt.Sprintf("Hello admin %s, here is your secret data.", claims.Email),
	})
}

// --- Main ---

func main() {
	gob.Register(map[string]interface{}{})
	seedData()

	oauth2Config = &oauth2.Config{
		ClientID:     os.Getenv("GOOGLE_CLIENT_ID"),
		ClientSecret: os.Getenv("GOOGLE_CLIENT_SECRET"),
		RedirectURL:  "http://localhost:1323/auth/google/callback",
		Scopes:       []string{"https://www.googleapis.com/auth/userinfo.email"},
		Endpoint:     google.Endpoint,
	}

	e := echo.New()
	e.Use(middleware.Recover())
	e.Use(middleware.Logger())
	e.Use(session.Middleware(sessions.NewCookieStore([]byte("procedural-session-secret"))))

	// Public routes
	e.POST("/login", handleLogin)
	e.GET("/auth/google/login", handleGoogleLogin)
	e.GET("/auth/google/callback", handleGoogleCallback)

	// Group for authenticated routes
	g := e.Group("/v1")
	g.Use(buildJwtMiddleware())

	// Routes for any authenticated user
	g.POST("/posts", handleCreatePost)

	// Routes for admins only
	adminGroup := g.Group("/admin")
	adminGroup.Use(requireRole(ADMIN))
	adminGroup.GET("/data", handleAdminData)

	log.Println("Starting procedural-style server on :1323")
	e.Logger.Fatal(e.Start(":1323"))
}