package main

import (
	"context"
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

// VARIATION 1: "The Classic Layered Approach"
// This variation demonstrates a clean, layered architecture common in larger applications.
// Responsibilities are separated into:
// - Models: Data structures.
// - Repository: Data access layer, abstracting the database.
// - Service: Business logic layer, orchestrates repositories and transactions.
// - Handler: API layer, handles HTTP requests and responses.

// --- 1. MODELS ---

type User struct {
	ID           uuid.UUID `gorm:"type:uuid;primary_key;"`
	Email        string    `gorm:"uniqueIndex;not null"`
	PasswordHash string    `gorm:"not null"`
	IsActive     bool      `gorm:"default:true"`
	CreatedAt    time.Time
	Posts        []Post `gorm:"foreignKey:UserID"`
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

// --- 2. REPOSITORY LAYER (Data Access) ---

type UserRepository interface {
	Create(ctx context.Context, user *User) error
	FindByID(ctx context.Context, id uuid.UUID) (*User, error)
	FindWithFilters(ctx context.Context, isActive *bool) ([]User, error)
	AssignRole(ctx context.Context, user *User, role *Role) error
}

type gormUserRepository struct {
	db *gorm.DB
}

func NewGormUserRepository(db *gorm.DB) UserRepository {
	return &gormUserRepository{db: db}
}

func (r *gormUserRepository) Create(ctx context.Context, user *User) error {
	return r.db.WithContext(ctx).Create(user).Error
}

func (r *gormUserRepository) FindByID(ctx context.Context, id uuid.UUID) (*User, error) {
	var user User
	err := r.db.WithContext(ctx).Preload("Posts").Preload("Roles").First(&user, "id = ?", id).Error
	return &user, err
}

func (r *gormUserRepository) FindWithFilters(ctx context.Context, isActive *bool) ([]User, error) {
	var users []User
	query := r.db.WithContext(ctx)
	if isActive != nil {
		query = query.Where("is_active = ?", *isActive)
	}
	err := query.Find(&users).Error
	return users, err
}

func (r *gormUserRepository) AssignRole(ctx context.Context, user *User, role *Role) error {
	return r.db.WithContext(ctx).Model(user).Association("Roles").Append(role)
}

// --- 3. SERVICE LAYER (Business Logic) ---

type UserService interface {
	RegisterUserWithPost(ctx context.Context, email, password, postTitle, postContent string) (*User, error)
	GetUser(ctx context.Context, id uuid.UUID) (*User, error)
	ListUsers(ctx context.Context, isActive *bool) ([]User, error)
}

type userService struct {
	db             *gorm.DB // For transactions
	userRepository UserRepository
}

func NewUserService(db *gorm.DB, userRepo UserRepository) UserService {
	return &userService{db: db, userRepository: userRepo}
}

func (s *userService) RegisterUserWithPost(ctx context.Context, email, password, postTitle, postContent string) (*User, error) {
	var createdUser *User
	// Transaction and Rollback example
	err := s.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		// Create a new repository instance with the transaction
		txUserRepo := NewGormUserRepository(tx)

		user := &User{
			ID:           uuid.New(),
			Email:        email,
			PasswordHash: "hashed_" + password, // In real app, use bcrypt
			IsActive:     true,
		}

		if err := txUserRepo.Create(ctx, user); err != nil {
			return err
		}

		post := &Post{
			ID:      uuid.New(),
			UserID:  user.ID,
			Title:   postTitle,
			Content: postContent,
			Status:  "DRAFT",
		}

		if err := tx.Create(&post).Error; err != nil {
			// This error will cause the transaction to rollback
			return err
		}

		// Simulate another error that would cause a rollback
		if postTitle == "fail_transaction" {
			return errors.New("simulated failure after post creation")
		}
		
		createdUser = user
		return nil // Commit transaction
	})

	return createdUser, err
}

func (s *userService) GetUser(ctx context.Context, id uuid.UUID) (*User, error) {
	return s.userRepository.FindByID(ctx, id)
}

func (s *userService) ListUsers(ctx context.Context, isActive *bool) ([]User, error) {
	return s.userRepository.FindWithFilters(ctx, isActive)
}

// --- 4. HANDLER LAYER (API) ---

type UserHandler struct {
	userService UserService
}

func NewUserHandler(userService UserService) *UserHandler {
	return &UserHandler{userService: userService}
}

func (h *UserHandler) CreateUser(c *gin.Context) {
	var req struct {
		Email       string `json:"email"`
		Password    string `json:"password"`
		PostTitle   string `json:"post_title"`
		PostContent string `json:"post_content"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	user, err := h.userService.RegisterUserWithPost(c.Request.Context(), req.Email, req.Password, req.PostTitle, req.PostContent)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusCreated, user)
}

func (h *UserHandler) GetUser(c *gin.Context) {
	userID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid UUID"})
		return
	}
	user, err := h.userService.GetUser(c.Request.Context(), userID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "User not found"})
		return
	}
	c.JSON(http.StatusOK, user)
}

func (h *UserHandler) ListUsers(c *gin.Context) {
	var isActive *bool
	if val, ok := c.GetQuery("is_active"); ok {
		b := val == "true"
		isActive = &b
	}

	users, err := h.userService.ListUsers(c.Request.Context(), isActive)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, users)
}

// --- 5. MAIN (Setup and Routing) ---

func main() {
	// Database Setup (In-memory SQLite)
	db, err := gorm.Open(sqlite.Open("file::memory:?cache=shared"), &gorm.Config{
		Logger: logger.Default.LogMode(logger.Silent),
	})
	if err != nil {
		log.Fatal("Failed to connect to database:", err)
	}

	// Migrations
	db.AutoMigrate(&User{}, &Post{}, &Role{})

	// Seeding Data
	adminRole := &Role{ID: uuid.New(), Name: "ADMIN"}
	userRole := &Role{ID: uuid.New(), Name: "USER"}
	db.Create(&adminRole)
	db.Create(&userRole)

	// Dependency Injection
	userRepo := NewGormUserRepository(db)
	userService := NewUserService(db, userRepo)
	userHandler := NewUserHandler(userService)

	// Gin Router
	r := gin.Default()
	
	userRoutes := r.Group("/users")
	{
		userRoutes.POST("", userHandler.CreateUser)
		userRoutes.GET("", userHandler.ListUsers)
		userRoutes.GET("/:id", userHandler.GetUser)
	}

	log.Println("Server starting on port 8080")
	// In a real app, use r.Run(":8080"). For this example, we don't block.
	// r.Run(":8080")
}