package main

import (
	"context"
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

// --- package: config ---
type AppConfig struct {
	JWTSecret    string
	OAuthConfig  *oauth2.Config
	SessionStore *session.Store
}

func LoadConfig() *AppConfig {
	return &AppConfig{
		JWTSecret: "a-different-secret-for-modular-approach",
		OAuthConfig: &oauth2.Config{
			ClientID:     "MOCK_CLIENT_ID_V3",
			ClientSecret: "MOCK_CLIENT_SECRET_V3",
			RedirectURL:  "http://localhost:3002/auth/callback",
			Scopes:       []string{"https://www.googleapis.com/auth/userinfo.email"},
			Endpoint:     google.Endpoint,
		},
		SessionStore: session.New(),
	}
}

// --- package: model ---
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

// --- package: store ---
type DataStore struct {
	mu    sync.RWMutex
	Users map[string]*User
	Posts map[uuid.UUID]*Post
}

func NewDataStore() *DataStore {
	ds := &DataStore{
		Users: make(map[string]*User),
		Posts: make(map[uuid.UUID]*Post),
	}
	ds.init()
	return ds
}

func (ds *DataStore) init() {
	adminPass, _ := bcrypt.GenerateFromPassword([]byte("admin!@#"), bcrypt.DefaultCost)
	ds.Users["admin@domain.com"] = &User{ID: uuid.New(), Email: "admin@domain.com", PasswordHash: string(adminPass), Role: ADMIN, IsActive: true}
	userPass, _ := bcrypt.GenerateFromPassword([]byte("user!@#"), bcrypt.DefaultCost)
	ds.Users["user@domain.com"] = &User{ID: uuid.New(), Email: "user@domain.com", PasswordHash: string(userPass), Role: USER, IsActive: true}
}

// --- package: middleware ---
func JWTProtected(cfg *AppConfig) fiber.Handler {
	return jwtware.New(jwtware.Config{
		SigningKey: []byte(cfg.JWTSecret),
		ContextKey: "jwt",
	})
}

func RBAC(role Role) fiber.Handler {
	return func(c *fiber.Ctx) error {
		token, ok := c.Locals("jwt").(*jwt.Token)
		if !ok {
			return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{"message": "Auth token not found"})
		}
		claims := token.Claims.(jwt.MapClaims)
		userRole := claims["role"].(string)
		if Role(userRole) != role {
			return c.Status(fiber.StatusForbidden).JSON(fiber.Map{"message": "Access denied"})
		}
		return c.Next()
	}
}

// --- package: auth ---
type AuthModule struct {
	DB  *DataStore
	Cfg *AppConfig
}

func (m *AuthModule) Login(c *fiber.Ctx) error {
	var body struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if err := c.BodyParser(&body); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Invalid request"})
	}

	m.DB.mu.RLock()
	user, exists := m.DB.Users[body.Email]
	m.DB.mu.RUnlock()

	if !exists || !user.IsActive {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{"message": "Invalid credentials"})
	}

	if err := bcrypt.CompareHashAndPassword([]byte(user.PasswordHash), []byte(body.Password)); err != nil {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{"message": "Invalid credentials"})
	}

	claims := jwt.MapClaims{
		"uid":   user.ID,
		"email": user.Email,
		"role":  user.Role,
		"exp":   time.Now().Add(time.Hour * 8).Unix(),
	}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	signedToken, err := token.SignedString([]byte(m.Cfg.JWTSecret))
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"message": "Could not sign token"})
	}
	return c.JSON(fiber.Map{"token": signedToken})
}

func (m *AuthModule) OAuthRedirect(c *fiber.Ctx) error {
	sess, _ := m.Cfg.SessionStore.Get(c)
	defer sess.Save()
	state := uuid.NewString()
	sess.Set("state", state)
	return c.Redirect(m.Cfg.OAuthConfig.AuthCodeURL(state))
}

func (m *AuthModule) OAuthCallback(c *fiber.Ctx) error {
	sess, _ := m.Cfg.SessionStore.Get(c)
	defer sess.Save()
	if c.Query("state") != sess.Get("state") {
		return c.Status(http.StatusUnauthorized).SendString("Invalid state")
	}
	// In a real app, exchange code for token and get user info
	// For this mock, we'll just create/find a user and issue a JWT
	oauthEmail := "oauth.user.v3@example.com"
	m.DB.mu.Lock()
	user, exists := m.DB.Users[oauthEmail]
	if !exists {
		user = &User{ID: uuid.New(), Email: oauthEmail, Role: USER, IsActive: true}
		m.DB.Users[oauthEmail] = user
	}
	m.DB.mu.Unlock()

	claims := jwt.MapClaims{"uid": user.ID, "email": user.Email, "role": user.Role, "exp": time.Now().Add(time.Hour * 8).Unix()}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	signedToken, _ := token.SignedString([]byte(m.Cfg.JWTSecret))
	return c.JSON(fiber.Map{"token": signedToken, "source": "oauth"})
}

func (m *AuthModule) RegisterRoutes(router fiber.Router) {
	router.Post("/login", m.Login)
	router.Get("/redirect", m.OAuthRedirect)
	router.Get("/callback", m.OAuthCallback)
}

// --- package: post ---
type PostModule struct {
	DB *DataStore
}

func (m *PostModule) CreatePost(c *fiber.Ctx) error {
	token := c.Locals("jwt").(*jwt.Token)
	claims := token.Claims.(jwt.MapClaims)
	userID, _ := uuid.Parse(claims["uid"].(string))

	var newPost Post
	if err := c.BodyParser(&newPost); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Invalid post data"})
	}

	newPost.ID = uuid.New()
	newPost.UserID = userID
	newPost.Status = DRAFT

	m.DB.mu.Lock()
	m.DB.Posts[newPost.ID] = &newPost
	m.DB.mu.Unlock()

	return c.Status(fiber.StatusCreated).JSON(newPost)
}

func (m *PostModule) RegisterRoutes(router fiber.Router) {
	router.Post("/posts", m.CreatePost)
}

// --- package: main ---
func main() {
	// Initialization
	config := LoadConfig()
	dataStore := NewDataStore()
	app := fiber.New()
	app.Use(logger.New())

	// Module Instantiation
	authModule := &AuthModule{DB: dataStore, Cfg: config}
	postModule := &PostModule{DB: dataStore}

	// Route Registration
	authRouter := app.Group("/auth")
	authModule.RegisterRoutes(authRouter)

	apiRouter := app.Group("/api", JWTProtected(config))
	postModule.RegisterRoutes(apiRouter)

	adminRouter := apiRouter.Group("/admin", RBAC(ADMIN))
	adminRouter.Get("/stats", func(c *fiber.Ctx) error {
		dataStore.mu.RLock()
		defer dataStore.mu.RUnlock()
		return c.JSON(fiber.Map{
			"user_count": len(dataStore.Users),
			"post_count": len(dataStore.Posts),
		})
	})

	log.Fatal(app.Listen(":3002"))
}