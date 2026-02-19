package main

import (
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

// --- MODELS ---

type RoleName string
const (
	RoleAdmin RoleName = "ADMIN"
	RoleUser  RoleName = "USER"
)

type PostStatus string
const (
	StatusDraft     PostStatus = "DRAFT"
	StatusPublished PostStatus = "PUBLISHED"
)

type User struct {
	ID           uuid.UUID `gorm:"type:uuid;primary_key;" json:"id"`
	Email        string    `gorm:"uniqueIndex;not null" json:"email"`
	PasswordHash string    `gorm:"not null" json:"-"`
	IsActive     bool      `gorm:"default:true" json:"is_active"`
	CreatedAt    time.Time `json:"created_at"`
	Posts        []Post    `gorm:"foreignKey:UserID" json:"posts"`
	Roles        []*Role   `gorm:"many2many:user_roles;" json:"roles"`
}

type Role struct {
	ID   uint     `gorm:"primaryKey" json:"id"`
	Name RoleName `gorm:"uniqueIndex;not null" json:"name"`
}

type Post struct {
	ID        uuid.UUID  `gorm:"type:uuid;primary_key;" json:"id"`
	UserID    uuid.UUID  `gorm:"type:uuid;not null" json:"user_id"`
	Title     string     `gorm:"not null" json:"title"`
	Content   string     `json:"content"`
	Status    PostStatus `gorm:"default:'DRAFT'" json:"status"`
	CreatedAt time.Time  `json:"created_at"`
}

// GORM Hooks
func (u *User) BeforeCreate(tx *gorm.DB) (err error) {
	u.ID = uuid.New()
	return
}
func (p *Post) BeforeCreate(tx *gorm.DB) (err error) {
	p.ID = uuid.New()
	return
}

// --- HANDLER-CENTRIC RESOURCE ---

type UserResource struct {
	DB *gorm.DB
}

func (ur *UserResource) createUser(c echo.Context) error {
	// This handler combines business logic (transaction) and data access.
	var payload struct {
		Email    string   `json:"email"`
		Password string   `json:"password"`
		Role     RoleName `json:"role"`
	}
	if err := c.Bind(&payload); err != nil {
		return c.JSON(http.StatusBadRequest, echo.Map{"message": "Invalid input"})
	}

	var createdUser User
	err := ur.DB.Transaction(func(tx *gorm.DB) error {
		// 1. Find the role
		var role Role
		if err := tx.Where("name = ?", payload.Role).First(&role).Error; err != nil {
			return err // Role not found, rollback
		}

		// 2. Create the user
		newUser := User{
			Email:        payload.Email,
			PasswordHash: "hashed_" + payload.Password, // Use bcrypt in production
			IsActive:     true,
			Roles:        []*Role{&role},
		}

		if err := tx.Create(&newUser).Error; err != nil {
			return err // User creation failed, rollback
		}

		createdUser = newUser
		return nil // Commit
	})

	if err != nil {
		log.Printf("Transaction failed: %v", err)
		return c.JSON(http.StatusInternalServerError, echo.Map{"message": "Could not create user"})
	}

	return c.JSON(http.StatusCreated, createdUser)
}

func (ur *UserResource) listUsers(c echo.Context) error {
	// Query building directly in the handler
	var users []User
	query := ur.DB.Model(&User{}).Preload("Roles").Preload("Posts")

	if isActiveParam := c.QueryParam("is_active"); isActiveParam != "" {
		isActive := isActiveParam == "true"
		query = query.Where("is_active = ?", isActive)
	}

	if err := query.Find(&users).Error; err != nil {
		return c.JSON(http.StatusInternalServerError, echo.Map{"message": "Failed to fetch users"})
	}

	return c.JSON(http.StatusOK, users)
}

func (ur *UserResource) getUser(c echo.Context) error {
	userID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		return c.JSON(http.StatusBadRequest, echo.Map{"message": "Invalid user ID"})
	}

	var user User
	if err := ur.DB.Preload("Roles").Preload("Posts").First(&user, "id = ?", userID).Error; err != nil {
		if err == gorm.ErrRecordNotFound {
			return c.JSON(http.StatusNotFound, echo.Map{"message": "User not found"})
		}
		return c.JSON(http.StatusInternalServerError, echo.Map{"message": "Database error"})
	}

	return c.JSON(http.StatusOK, user)
}

func (ur *UserResource) deleteUser(c echo.Context) error {
	userID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		return c.JSON(http.StatusBadRequest, echo.Map{"message": "Invalid user ID"})
	}

	// GORM's soft delete could be used here if the model has gorm.DeletedAt
	result := ur.DB.Delete(&User{}, "id = ?", userID)
	if result.Error != nil {
		return c.JSON(http.StatusInternalServerError, echo.Map{"message": "Failed to delete user"})
	}
	if result.RowsAffected == 0 {
		return c.JSON(http.StatusNotFound, echo.Map{"message": "User not found"})
	}

	return c.NoContent(http.StatusNoContent)
}

// --- MAIN ---

func main() {
	// --- DB Init ---
	db, err := gorm.Open(sqlite.Open("file::memory:?cache=shared"), &gorm.Config{
		Logger: logger.Default.LogMode(logger.Silent),
	})
	if err != nil {
		log.Fatalf("DB connection failed: %v", err)
	}

	// --- Migrations ---
	log.Println("Migrating database schema...")
	if err := db.AutoMigrate(&User{}, &Post{}, &Role{}); err != nil {
		log.Fatalf("Migration failed: %v", err)
	}

	// --- Seed Data ---
	log.Println("Seeding initial data...")
	db.Create(&Role{Name: RoleAdmin})
	db.Create(&Role{Name: RoleUser})

	// --- Resource and Echo Setup ---
	userRes := &UserResource{DB: db}
	// A PostResource would be similarly instantiated

	e := echo.New()
	e.Use(middleware.Recover())
	e.Use(middleware.LoggerWithConfig(middleware.LoggerConfig{
		Format: "method=${method}, uri=${uri}, status=${status}\n",
	}))

	// --- Routes ---
	api := e.Group("/api")
	api.POST("/users", userRes.createUser)
	api.GET("/users", userRes.listUsers)
	api.GET("/users/:id", userRes.getUser)
	api.DELETE("/users/:id", userRes.deleteUser)
	// Other CRUD routes (PUT) and Post routes would be added here

	log.Println("Server starting on http://localhost:8080")
	if err := e.Start(":8080"); err != nil {
		log.Fatal(err)
	}
}