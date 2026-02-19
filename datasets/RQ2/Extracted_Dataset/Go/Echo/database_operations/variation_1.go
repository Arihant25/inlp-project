package main

import (
	"context"
	"fmt"
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

// --- 1. MODELS (Domain Layer) ---

type RoleName string

const (
	AdminRole RoleName = "ADMIN"
	UserRole  RoleName = "USER"
)

type PostStatus string

const (
	DraftStatus     PostStatus = "DRAFT"
	PublishedStatus PostStatus = "PUBLISHED"
)

type User struct {
	ID           uuid.UUID `gorm:"type:uuid;primary_key;"`
	Email        string    `gorm:"uniqueIndex;not null"`
	PasswordHash string    `gorm:"not null"`
	IsActive     bool      `gorm:"default:true"`
	CreatedAt    time.Time
	Posts        []Post `gorm:"foreignKey:UserID"`
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

// GORM hook to generate UUID before creating a record
func (u *User) BeforeCreate(tx *gorm.DB) (err error) {
	u.ID = uuid.New()
	return
}

func (p *Post) BeforeCreate(tx *gorm.DB) (err error) {
	p.ID = uuid.New()
	return
}

// --- 2. REPOSITORY (Data Access Layer) ---

type UserRepository struct {
	db *gorm.DB
}

func NewUserRepository(db *gorm.DB) *UserRepository {
	return &UserRepository{db: db}
}

func (r *UserRepository) FindAll(ctx context.Context, filters map[string]interface{}) ([]User, error) {
	var users []User
	query := r.db.WithContext(ctx).Model(&User{}).Preload("Roles").Preload("Posts")
	if isActive, ok := filters["is_active"]; ok {
		query = query.Where("is_active = ?", isActive)
	}
	err := query.Find(&users).Error
	return users, err
}

func (r *UserRepository) FindByID(ctx context.Context, id uuid.UUID) (*User, error) {
	var user User
	err := r.db.WithContext(ctx).Preload("Roles").Preload("Posts").First(&user, "id = ?", id).Error
	if err != nil {
		return nil, err
	}
	return &user, nil
}

func (r *UserRepository) CreateInTx(ctx context.Context, tx *gorm.DB, user *User) error {
	return tx.WithContext(ctx).Create(user).Error
}

func (r *UserRepository) FindRoleByName(ctx context.Context, name RoleName) (*Role, error) {
	var role Role
	err := r.db.WithContext(ctx).Where("name = ?", name).First(&role).Error
	return &role, err
}

type PostRepository struct {
	db *gorm.DB
}

func NewPostRepository(db *gorm.DB) *PostRepository {
	return &PostRepository{db: db}
}

func (r *PostRepository) Create(ctx context.Context, post *Post) error {
	return r.db.WithContext(ctx).Create(post).Error
}

// --- 3. SERVICE (Business Logic Layer) ---

type UserService struct {
	db             *gorm.DB
	userRepository *UserRepository
}

func NewUserService(db *gorm.DB, userRepo *UserRepository) *UserService {
	return &UserService{db: db, userRepository: userRepo}
}

func (s *UserService) CreateUserWithRole(ctx context.Context, email, password string, roleName RoleName) (*User, error) {
	var user *User
	err := s.db.Transaction(func(tx *gorm.DB) error {
		// Find the role
		role, err := s.userRepository.FindRoleByName(ctx, roleName)
		if err != nil {
			return fmt.Errorf("role '%s' not found: %w", roleName, err)
		}

		// Create the user
		newUser := &User{
			Email:        email,
			PasswordHash: "hashed_" + password, // In real app, use bcrypt
			IsActive:     true,
			Roles:        []*Role{role},
		}

		if err := s.userRepository.CreateInTx(ctx, tx, newUser); err != nil {
			// Transaction will be rolled back automatically
			return err
		}
		
		user = newUser
		return nil // Commit
	})

	if err != nil {
		return nil, err
	}
	return user, nil
}

// --- 4. HANDLER (Presentation Layer) ---

type UserHandler struct {
	userService    *UserService
	userRepository *UserRepository
}

func NewUserHandler(userService *UserService, userRepo *UserRepository) *UserHandler {
	return &UserHandler{userService: userService, userRepository: userRepo}
}

func (h *UserHandler) CreateUser(c echo.Context) error {
	type request struct {
		Email    string   `json:"email"`
		Password string   `json:"password"`
		Role     RoleName `json:"role"`
	}
	req := new(request)
	if err := c.Bind(req); err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "Invalid request body"})
	}

	user, err := h.userService.CreateUserWithRole(c.Request().Context(), req.Email, req.Password, req.Role)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	return c.JSON(http.StatusCreated, user)
}

func (h *UserHandler) GetUsers(c echo.Context) error {
	filters := make(map[string]interface{})
	if isActive := c.QueryParam("is_active"); isActive != "" {
		filters["is_active"] = (isActive == "true")
	}

	users, err := h.userRepository.FindAll(c.Request().Context(), filters)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	return c.JSON(http.StatusOK, users)
}

func (h *UserHandler) GetUserByID(c echo.Context) error {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "Invalid UUID format"})
	}

	user, err := h.userRepository.FindByID(c.Request().Context(), id)
	if err != nil {
		if err == gorm.ErrRecordNotFound {
			return c.JSON(http.StatusNotFound, map[string]string{"error": "User not found"})
		}
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	return c.JSON(http.StatusOK, user)
}

// --- 5. MAIN (Application Setup) ---

func main() {
	// --- Database Setup ---
	db, err := gorm.Open(sqlite.Open("file::memory:?cache=shared"), &gorm.Config{
		Logger: logger.Default.LogMode(logger.Silent),
	})
	if err != nil {
		log.Fatal("Failed to connect to database:", err)
	}

	// --- Migrations ---
	log.Println("Running database migrations...")
	db.AutoMigrate(&User{}, &Post{}, &Role{})

	// --- Seed Data ---
	log.Println("Seeding data...")
	roles := []*Role{{Name: AdminRole}, {Name: UserRole}}
	db.Create(&roles)

	// --- Dependency Injection ---
	userRepo := NewUserRepository(db)
	postRepo := NewPostRepository(db)
	userService := NewUserService(db, userRepo)
	userHandler := NewUserHandler(userService, userRepo)
	// Post handler would be set up similarly
	_ = postRepo // To avoid unused variable error

	// --- Echo Setup ---
	e := echo.New()
	e.Use(middleware.Logger())
	e.Use(middleware.Recover())

	// --- Routing ---
	userGroup := e.Group("/users")
	userGroup.POST("", userHandler.CreateUser)
	userGroup.GET("", userHandler.GetUsers)
	userGroup.GET("/:id", userHandler.GetUserByID)
	// Other CRUD routes (PUT, DELETE) and Post routes would be added here

	log.Println("Starting server on :8080")
	e.Start(":8080")
}