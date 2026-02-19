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

// VARIATION 4: "The All-in-One/Monolithic Handler with Dependency Injection"
// This pattern uses a central `Server` or `API` struct to hold all application
// dependencies, such as the database connection and the router.
// Handlers are methods on this `Server` struct, giving them easy access to
// dependencies via the `s.` receiver. It's a pragmatic and popular approach
// in Go for managing state and dependencies without full-blown layering.

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

// --- Server ---
// The Server struct holds all dependencies for the application.
type Server struct {
	db     *gorm.DB
	router *gin.Engine
}

// NewServer creates a new server instance and sets up routes.
func NewServer(db *gorm.DB) *Server {
	server := &Server{
		db:     db,
		router: gin.Default(),
	}
	server.setupRoutes()
	return server
}

// setupRoutes defines all the API endpoints for the application.
func (s *Server) setupRoutes() {
	api := s.router.Group("/api")
	{
		api.POST("/users", s.handleCreateUser)
		api.GET("/users", s.handleListUsers)
		api.GET("/users/:id", s.handleGetUser)
		api.PUT("/users/:id", s.handleUpdateUser)
		api.DELETE("/users/:id", s.handleDeleteUser)
		api.POST("/posts/transactional", s.handleCreateUserAndPost) // Transactional endpoint
	}
}

// Run starts the HTTP server.
func (s *Server) Run(addr string) error {
	return s.router.Run(addr)
}

// --- Handlers (methods on Server) ---

func (s *Server) handleCreateUser(c *gin.Context) {
	var req struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	user := User{
		ID:           uuid.New(),
		Email:        req.Email,
		PasswordHash: "hashed_" + req.Password,
	}
	if err := s.db.Create(&user).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Could not create user"})
		return
	}
	c.JSON(http.StatusCreated, user)
}

func (s *Server) handleListUsers(c *gin.Context) {
	// Query building with filters
	dbQuery := s.db.Model(&User{})
	if isActive := c.Query("is_active"); isActive != "" {
		dbQuery = dbQuery.Where("is_active = ?", isActive == "true")
	}

	var users []User
	if err := dbQuery.Find(&users).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to list users"})
		return
	}
	c.JSON(http.StatusOK, users)
}

func (s *Server) handleGetUser(c *gin.Context) {
	id := c.Param("id")
	var user User
	if err := s.db.Preload("Posts").Preload("Roles").First(&user, "id = ?", id).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "User not found"})
		return
	}
	c.JSON(http.StatusOK, user)
}

func (s *Server) handleUpdateUser(c *gin.Context) {
	id := c.Param("id")
	var user User
	if err := s.db.First(&user, "id = ?", id).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "User not found"})
		return
	}

	var req struct {
		Email    string `json:"email"`
		IsActive *bool  `json:"is_active"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if req.Email != "" {
		user.Email = req.Email
	}
	if req.IsActive != nil {
		user.IsActive = *req.IsActive
	}

	s.db.Save(&user)
	c.JSON(http.StatusOK, user)
}

func (s *Server) handleDeleteUser(c *gin.Context) {
	id := c.Param("id")
	// Using unscoped delete to permanently remove
	result := s.db.Unscoped().Delete(&User{}, "id = ?", id)
	if result.Error != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to delete user"})
		return
	}
	if result.RowsAffected == 0 {
		c.JSON(http.StatusNotFound, gin.H{"error": "User not found"})
		return
	}
	c.Status(http.StatusNoContent)
}

// handleCreateUserAndPost demonstrates a transaction with rollback.
func (s *Server) handleCreateUserAndPost(c *gin.Context) {
	var req struct {
		Email   string `json:"email"`
		Title   string `json:"title"`
		Content string `json:"content"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	var createdUser User
	err := s.db.Transaction(func(tx *gorm.DB) error {
		user := User{ID: uuid.New(), Email: req.Email, PasswordHash: "default"}
		if err := tx.Create(&user).Error; err != nil {
			return err
		}

		post := Post{ID: uuid.New(), UserID: user.ID, Title: req.Title, Content: req.Content}
		if err := tx.Create(&post).Error; err != nil {
			return err // This will trigger a rollback
		}
		
		createdUser = user
		return nil // Commit
	})

	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Transaction failed: " + err.Error()})
		return
	}
	c.JSON(http.StatusCreated, gin.H{"user_id": createdUser.ID})
}

// --- Main ---
func main() {
	// Database Setup
	db, err := gorm.Open(sqlite.Open("file::memory:?cache=shared"), &gorm.Config{
		Logger: logger.Default.LogMode(logger.Silent),
	})
	if err != nil {
		log.Fatalf("cannot connect to database: %v", err)
	}

	// Migrations
	if err := db.AutoMigrate(&User{}, &Post{}, &Role{}); err != nil {
		log.Fatalf("migration failed: %v", err)
	}

	// Create and run server
	server := NewServer(db)
	log.Println("Server starting on port 8080")
	// if err := server.Run(":8080"); err != nil {
	// 	log.Fatalf("cannot start server: %v", err)
	// }
}