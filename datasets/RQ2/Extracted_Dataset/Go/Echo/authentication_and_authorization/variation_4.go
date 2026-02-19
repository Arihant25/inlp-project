package main

import (
	"context"
	"encoding/gob"
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

// --- Types ---
type (
	Role string
	User struct {
		ID       uuid.UUID
		Email    string
		PassHash string
		Role     Role
		Active   bool
	}
	Post struct {
		ID     uuid.UUID
		UserID uuid.UUID
		Title  string
	}
	JwtClaims struct {
		UID  string `json:"uid"`
		Role Role   `json:"role"`
		jwt.RegisteredClaims
	}
)

const (
	ADMIN Role = "ADMIN"
	USER  Role = "USER"
)

// --- In-memory DB & Config ---
var (
	userStore = make(map[string]*User)
	jwtKey    = []byte("minimalist_secret")
	oauthConf *oauth2.Config
)

func main() {
	// --- Seeding Data ---
	adminPass, _ := bcrypt.GenerateFromPassword([]byte("admin123"), bcrypt.DefaultCost)
	userPass, _ := bcrypt.GenerateFromPassword([]byte("user123"), bcrypt.DefaultCost)
	userStore["admin@test.com"] = &User{ID: uuid.New(), Email: "admin@test.com", PassHash: string(adminPass), Role: ADMIN, Active: true}
	userStore["user@test.com"] = &User{ID: uuid.New(), Email: "user@test.com", PassHash: string(userPass), Role: USER, Active: true}

	// --- OAuth2 Config ---
	oauthConf = &oauth2.Config{
		ClientID:     os.Getenv("GOOGLE_CLIENT_ID"),
		ClientSecret: os.Getenv("GOOGLE_CLIENT_SECRET"),
		RedirectURL:  "http://localhost:1323/oauth/callback",
		Scopes:       []string{"https://www.googleapis.com/auth/userinfo.email"},
		Endpoint:     google.Endpoint,
	}

	// --- Echo Setup ---
	e := echo.New()
	gob.Register(map[string]interface{}{})
	e.Use(middleware.Logger())
	e.Use(session.Middleware(sessions.NewCookieStore([]byte("minimalist-session-key"))))

	// --- Handlers (defined as closures) ---
	loginHandler := func(c echo.Context) error {
		var req struct {
			Email string `json:"email"`
			Pass  string `json:"password"`
		}
		if err := c.Bind(&req); err != nil {
			return c.NoContent(http.StatusBadRequest)
		}
		u, ok := userStore[req.Email]
		if !ok || bcrypt.CompareHashAndPassword([]byte(u.PassHash), []byte(req.Pass)) != nil || !u.Active {
			return c.NoContent(http.StatusUnauthorized)
		}
		claims := &JwtClaims{
			UID:  u.ID.String(),
			Role: u.Role,
			RegisteredClaims: jwt.RegisteredClaims{ExpiresAt: jwt.NewNumericDate(time.Now().Add(time.Hour * 24))},
		}
		token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
		t, err := token.SignedString(jwtKey)
		if err != nil {
			return c.NoContent(http.StatusInternalServerError)
		}
		return c.JSON(http.StatusOK, map[string]string{"token": t})
	}

	oauthLoginHandler := func(c echo.Context) error {
		state := uuid.NewString()
		sess, _ := session.Get("session", c)
		sess.Values["state"] = state
		sess.Save(c.Request(), c.Response())
		return c.Redirect(http.StatusTemporaryRedirect, oauthConf.AuthCodeURL(state))
	}

	oauthCallbackHandler := func(c echo.Context) error {
		sess, _ := session.Get("session", c)
		if c.QueryParam("state") != sess.Values["state"] {
			return c.NoContent(http.StatusUnauthorized)
		}
		// Mock getting user from provider
		email := "oauth.minimal@example.com"
		u, ok := userStore[email]
		if !ok {
			u = &User{ID: uuid.New(), Email: email, Role: USER, Active: true}
			userStore[email] = u
		}
		claims := &JwtClaims{
			UID:  u.ID.String(),
			Role: u.Role,
			RegisteredClaims: jwt.RegisteredClaims{ExpiresAt: jwt.NewNumericDate(time.Now().Add(time.Hour * 24))},
		}
		token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
		t, err := token.SignedString(jwtKey)
		if err != nil {
			return c.NoContent(http.StatusInternalServerError)
		}
		return c.JSON(http.StatusOK, map[string]string{"token": t})
	}

	// --- Middleware (defined as closures) ---
	jwtAuth := middleware.JWT(jwtKey)

	checkRole := func(role Role) echo.MiddlewareFunc {
		return func(next echo.HandlerFunc) echo.HandlerFunc {
			return func(c echo.Context) error {
				token, ok := c.Get("user").(*jwt.Token)
				if !ok {
					return c.NoContent(http.StatusUnauthorized)
				}
				claims := token.Claims.(jwt.MapClaims)
				if Role(claims["role"].(string)) != role {
					return c.NoContent(http.StatusForbidden)
				}
				return next(c)
			}
		}
	}

	// --- Routes ---
	e.POST("/login", loginHandler)
	e.GET("/login/oauth", oauthLoginHandler)
	e.GET("/oauth/callback", oauthCallbackHandler)

	// Protected group
	p := e.Group("/p")
	p.Use(jwtAuth)

	p.POST("/post", func(c echo.Context) error {
		claims := c.Get("user").(*jwt.Token).Claims.(jwt.MapClaims)
		return c.JSON(http.StatusOK, fmt.Sprintf("Post created by user %s", claims["uid"]))
	})

	p.GET("/admin/info", func(c echo.Context) error {
		return c.JSON(http.StatusOK, "Secret admin info")
	}, checkRole(ADMIN))

	log.Fatal(e.Start(":1323"))
}