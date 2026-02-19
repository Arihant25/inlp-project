package main

import (
	"log"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

// VARIATION 2: "The Functional/Handler-Centric Approach"
// This style is common in smaller projects or microservices.
// It avoids formal layers like repository or service, placing logic directly
// within handler functions. The database connection is passed as a dependency.
// It's straightforward but can become harder to maintain as complexity grows.

// --- Models ---
type User struct {
	ID           uuid.UUID `gorm:"type:uuid;primary_key;" json:"id"`
	Email        string    `gorm:"uniqueIndex;not null" json:"email"`
	PasswordHash string    `gorm:"not null" json:"-"` // Hide password hash
	IsActive     bool      `gorm:"default:true" json:"is_active"`
	CreatedAt    time.Time `json:"created_at"`
	Posts        []Post    `gorm:"foreignKey:UserID" json:"posts"`
	Roles        []*Role   `gorm:"many2many:user_roles;" json:"roles"`
}

type Post struct {
	ID      uuid.UUID `gorm:"type:uuid;primary_key;" json:"id"`
	UserID  uuid.UUID `gorm:"type:uuid;not null" json:"user_id"`
	Title   string    `gorm:"not null" json:"title"`
	Content string    `json:"content"`
	Status  string    `gorm:"default:'DRAFT'" json:"status"`
}

type Role struct {
	ID   uuid.UUID `gorm:"type:uuid;primary_key;" json:"id"`
	Name string    `gorm:"uniqueIndex;not null" json:"name"`
}

// --- Database Setup ---
func setupDatabase() *gorm.DB {
	db, err := gorm.Open(sqlite.Open("file::memory:?cache=shared"), &gorm.Config{
		Logger: logger.Default.LogMode(logger.Silent),
	})
	if err != nil {
		log.Fatal("Failed to connect to database:", err)
	}
	return db
}

// --- Migrations ---
func runMigrations(db *gorm.DB) {
	db.AutoMigrate(&User{}, &Post{}, &Role{})
}

// --- Seeding ---
func seedData(db *gorm.DB) {
	adminRole := Role{ID: uuid.New(), Name: "ADMIN"}
	userRole := Role{ID: uuid.New(), Name: "USER"}
	db.Create(&[]Role{adminRole, userRole})

	user1 := User{
		ID:           uuid.New(),
		Email:        "test@example.com",
		PasswordHash: "hashed_password",
		IsActive:     true,
		Roles:        []*Role{&adminRole, &userRole},
	}
	db.Create(&user1)

	db.Create(&Post{
		ID:     uuid.New(),
		UserID: user1.ID,
		Title:  "First Post",
		Status: "PUBLISHED",
	})
}

// --- Handlers ---

func handleCreateUser(c *gin.Context, db *gorm.DB) {
	var req struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	newUser := User{
		ID:           uuid.New(),
		Email:        req.Email,
		PasswordHash: "hashed_" + req.Password, // Use bcrypt in production
		IsActive:     true,
	}

	if result := db.Create(&newUser); result.Error != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": result.Error.Error()})
		return
	}

	c.JSON(http.StatusCreated, newUser)
}

func handleGetUser(c *gin.Context, db *gorm.DB) {
	userID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid user ID format"})
		return
	}

	var user User
	// Eager load relationships
	result := db.Preload("Posts").Preload("Roles").First(&user, "id = ?", userID)
	if result.Error != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "User not found"})
		return
	}

	c.JSON(http.StatusOK, user)
}

func handleListUsers(c *gin.Context, db *gorm.DB) {
	// Query building with filters
	query := db.Model(&User{})

	if status := c.Query("is_active"); status != "" {
		isActive := status == "true"
		query = query.Where("is_active = ?", isActive)
	}

	var users []User
	if err := query.Find(&users).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, users)
}

func handleCreatePostForUser(c *gin.Context, db *gorm.DB) {
	userID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid user ID format"})
		return
	}

	var req struct {
		Title   string `json:"title"`
		Content string `json:"content"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	newPost := Post{
		ID:      uuid.New(),
		UserID:  userID,
		Title:   req.Title,
		Content: req.Content,
		Status:  "DRAFT",
	}

	// Transaction and Rollback example
	err = db.Transaction(func(tx *gorm.DB) error {
		// 1. Check if user exists
		var userCount int64
		if err := tx.Model(&User{}).Where("id = ?", userID).Count(&userCount).Error; err != nil {
			return err
		}
		if userCount == 0 {
			return gorm.ErrRecordNotFound
		}

		// 2. Create the post
		if err := tx.Create(&newPost).Error; err != nil {
			return err
		}

		// If we return an error here, the transaction rolls back.
		return nil
	})

	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": "User not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, newPost)
}

// --- Main ---
func main() {
	db := setupDatabase()
	runMigrations(db)
	seedData(db)

	r := gin.Default()

	// Pass db instance to handlers using a closure
	r.POST("/users", func(c *gin.Context) { handleCreateUser(c, db) })
	r.GET("/users", func(c *gin.Context) { handleListUsers(c, db) })
	r.GET("/users/:id", func(c *gin.Context) { handleGetUser(c, db) })
	r.POST("/users/:id/posts", func(c *gin.Context) { handleCreatePostForUser(c, db) })

	log.Println("Server starting on port 8080")
	// r.Run(":8080")
}