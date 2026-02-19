package main

import (
	"errors"
	"log"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

// VARIATION 3: "The OOP/Method-Based Approach"
// This variation uses structs with methods to encapsulate related logic.
// A `UserController` and `PostController` hold a reference to the database
// and provide methods that act as Gin handlers. This organizes code by
// domain entity and is a common pattern for developers coming from
// object-oriented backgrounds.

// --- Models ---
type User struct {
	ID           uuid.UUID `gorm:"type:uuid;primary_key;"`
	Email        string    `gorm:"uniqueIndex;not null"`
	PasswordHash string    `gorm:"not null"`
	IsActive     bool      `gorm:"default:true"`
	CreatedAt    time.Time
	Posts        []Post  `gorm:"foreignKey:UserID"`
	Roles        []*Role `gorm:"many2many:user_roles;"`
}

type Post struct {
	ID      uuid.UUID `gorm:"type:uuid;primary_key;"`
	UserID  uuid.UUID `gorm:"type:uuid;not null"`
	Title   string    `gorm:"not null"`
	Content string
	Status  string `gorm:"default:'DRAFT'"`
}

type Role struct {
	ID   uuid.UUID `gorm:"type:uuid;primary_key;"`
	Name string    `gorm:"uniqueIndex;not null"`
}

// --- Controllers ---

// UserController encapsulates all user-related handlers and dependencies.
type UserController struct {
	DB *gorm.DB
}

func NewUserController(db *gorm.DB) *UserController {
	return &UserController{DB: db}
}

func (uc *UserController) Create(c *gin.Context) {
	var input struct {
		Email    string `json:"email" binding:"required,email"`
		Password string `json:"password" binding:"required"`
	}
	if err := c.ShouldBindJSON(&input); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	user := User{
		ID:           uuid.New(),
		Email:        input.Email,
		PasswordHash: "hashed_" + input.Password, // Use bcrypt in prod
		IsActive:     true,
	}

	if err := uc.DB.Create(&user).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to create user"})
		return
	}
	c.JSON(http.StatusCreated, user)
}

func (uc *UserController) GetByID(c *gin.Context) {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid ID"})
		return
	}

	var user User
	if err := uc.DB.Preload("Posts").Preload("Roles").First(&user, id).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "User not found"})
		return
	}
	c.JSON(http.StatusOK, user)
}

func (uc *UserController) List(c *gin.Context) {
	// Query building with filters
	query := uc.DB.Model(&User{})
	if isActive, ok := c.GetQuery("is_active"); ok {
		query = query.Where("is_active = ?", isActive == "true")
	}

	var users []User
	if err := query.Find(&users).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Could not fetch users"})
		return
	}
	c.JSON(http.StatusOK, users)
}

func (uc *UserController) AssignRole(c *gin.Context) {
	userID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid User ID"})
		return
	}

	var input struct {
		RoleName string `json:"role_name" binding:"required"`
	}
	if err := c.ShouldBindJSON(&input); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	// Transaction and Rollback Example
	err = uc.DB.Transaction(func(tx *gorm.DB) error {
		var user User
		if err := tx.First(&user, userID).Error; err != nil {
			return errors.New("user not found") // Will cause rollback
		}

		var role Role
		if err := tx.Where("name = ?", input.RoleName).First(&role).Error; err != nil {
			return errors.New("role not found") // Will cause rollback
		}

		// GORM's Association mode handles the many-to-many join table
		return tx.Model(&user).Association("Roles").Append(&role)
	})

	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"status": "Role assigned successfully"})
}

// --- Main Application Setup ---
func main() {
	// Database Setup
	db, err := gorm.Open(sqlite.Open("file::memory:?cache=shared"), &gorm.Config{
		Logger: logger.Default.LogMode(logger.Silent),
	})
	if err != nil {
		log.Fatal("Failed to connect to database:", err)
	}

	// Migrations
	db.AutoMigrate(&User{}, &Post{}, &Role{})

	// Seed Data
	db.Create(&Role{ID: uuid.New(), Name: "ADMIN"})
	db.Create(&Role{ID: uuid.New(), Name: "USER"})

	// Initialize Controllers
	userController := NewUserController(db)

	// Setup Gin Router
	router := gin.Default()

	// User Routes
	userGroup := router.Group("/users")
	{
		userGroup.POST("", userController.Create)
		userGroup.GET("", userController.List)
		userGroup.GET("/:id", userController.GetByID)
		userGroup.POST("/:id/roles", userController.AssignRole)
	}

	log.Println("Server starting on port 8080")
	// router.Run(":8080")
}