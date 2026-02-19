package main

import (
	"context"
	"log"
	"net/http"
	"time"

	"github.com/google/uuid"
	"github.com/labstack/echo/v4"
	"github.com/labstack/echo/v4/middleware"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

// --- Domain Models ---

type RoleName string
const (
	ADMIN RoleName = "ADMIN"
	USER  RoleName = "USER"
)

type PostStatus string
const (
	DRAFT     PostStatus = "DRAFT"
	PUBLISHED PostStatus = "PUBLISHED"
)

type User struct {
	ID           uuid.UUID `gorm:"type:uuid;primary_key;"`
	Email        string    `gorm:"uniqueIndex;not null"`
	PasswordHash string    `gorm:"not null"`
	IsActive     bool      `gorm:"default:true"`
	CreatedAt    time.Time
	Posts        []Post  `gorm:"foreignKey:UserID"`
	Roles        []*Role `gorm:"many2many:user_roles;"`
}

type Role struct {
	ID   uint     `gorm:"primaryKey"`
	Name RoleName `gorm:"uniqueIndex;not null"`
}

type Post struct {
	ID        uuid.UUID  `gorm:"type:uuid;primary_key;"`
	UserID    uuid.UUID  `gorm:"type:uuid;not null"`
	Title     string     `gorm:"not null"`
	Content   string
	Status    PostStatus `gorm:"default:'DRAFT'"`
	CreatedAt time.Time
}

// GORM Hooks for UUID generation
func (u *User) BeforeCreate(tx *gorm.DB) (err error) {
	u.ID = uuid.New()
	return
}
func (p *Post) BeforeCreate(tx *gorm.DB) (err error) {
	p.ID = uuid.New()
	return
}

// --- Data Access Functions ---

type DBStore struct {
	*gorm.DB
}

func (s *DBStore) CreateUserWithDefaultRoleInTx(ctx context.Context, email, password string) (*User, error) {
	var createdUser *User
	err := s.DB.Transaction(func(tx *gorm.DB) error {
		var userRole Role
		if err := tx.WithContext(ctx).Where("name = ?", USER).First(&userRole).Error; err != nil {
			return err
		}

		newUser := &User{
			Email:        email,
			PasswordHash: "hashed_" + password, // Use bcrypt in production
			IsActive:     true,
			Roles:        []*Role{&userRole},
		}

		if err := tx.WithContext(ctx).Create(newUser).Error; err != nil {
			return err
		}
		createdUser = newUser
		return nil
	})
	return createdUser, err
}

func (s *DBStore) FindUsers(ctx context.Context, isActive *bool) ([]User, error) {
	var users []User
	query := s.DB.WithContext(ctx).Preload("Roles").Preload("Posts")
	if isActive != nil {
		query = query.Where("is_active = ?", *isActive)
	}
	err := query.Find(&users).Error
	return users, err
}

func (s *DBStore) FindUserByID(ctx context.Context, id uuid.UUID) (*User, error) {
	var user User
	err := s.DB.WithContext(ctx).Preload("Roles").Preload("Posts").First(&user, "id = ?", id).Error
	return &user, err
}

// --- Functional Handlers ---

func handleCreateUser(db *DBStore) echo.HandlerFunc {
	return func(c echo.Context) error {
		var body struct {
			Email    string `json:"email"`
			Password string `json:"password"`
		}
		if err := c.Bind(&body); err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "bad request"})
		}

		user, err := db.CreateUserWithDefaultRoleInTx(c.Request().Context(), body.Email, body.Password)
		if err != nil {
			return c.JSON(http.StatusInternalServerError, map[string]string{"error": "could not create user"})
		}
		return c.JSON(http.StatusCreated, user)
	}
}

func handleGetUsers(db *DBStore) echo.HandlerFunc {
	return func(c echo.Context) error {
		var isActive *bool
		if isActiveParam := c.QueryParam("is_active"); isActiveParam != "" {
			val := isActiveParam == "true"
			isActive = &val
		}

		users, err := db.FindUsers(c.Request().Context(), isActive)
		if err != nil {
			return c.JSON(http.StatusInternalServerError, map[string]string{"error": "could not fetch users"})
		}
		return c.JSON(http.StatusOK, users)
	}
}

func handleGetUser(db *DBStore) echo.HandlerFunc {
	return func(c echo.Context) error {
		userID, err := uuid.Parse(c.Param("id"))
		if err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid id format"})
		}

		user, err := db.FindUserByID(c.Request().Context(), userID)
		if err != nil {
			if err == gorm.ErrRecordNotFound {
				return c.JSON(http.StatusNotFound, map[string]string{"error": "user not found"})
			}
			return c.JSON(http.StatusInternalServerError, map[string]string{"error": "database error"})
		}
		return c.JSON(http.StatusOK, user)
	}
}

// --- Main Application ---

func main() {
	// Database Connection
	gormDB, err := gorm.Open(sqlite.Open("file::memory:?cache=shared"), &gorm.Config{
		Logger: logger.Default.LogMode(logger.Silent),
	})
	if err != nil {
		log.Fatalf("Failed to connect to database: %v", err)
	}
	dbStore := &DBStore{gormDB}

	// Migrations
	log.Println("Running migrations...")
	if err := dbStore.AutoMigrate(&User{}, &Post{}, &Role{}); err != nil {
		log.Fatalf("Migration failed: %v", err)
	}

	// Seed Data
	log.Println("Seeding data...")
	dbStore.Create(&Role{Name: ADMIN})
	dbStore.Create(&Role{Name: USER})

	// Echo Instance
	e := echo.New()
	e.Use(middleware.Logger())
	e.Use(middleware.Recover())

	// Routes
	e.POST("/users", handleCreateUser(dbStore))
	e.GET("/users", handleGetUsers(dbStore))
	e.GET("/users/:id", handleGetUser(dbStore))
	// Other CRUD and Post routes would be added here

	log.Println("Server starting on port 8080")
	e.Start(":8080")
}