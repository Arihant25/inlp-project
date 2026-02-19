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

// --- DOMAIN: USER ---

// user/model.go
type RoleName string
const (
	AdminRole RoleName = "ADMIN"
	UserRole  RoleName = "USER"
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

func (u *User) BeforeCreate(tx *gorm.DB) (err error) {
	u.ID = uuid.New()
	return
}

// user/repository.go
type IUserRepository interface {
	FindWithFilters(ctx context.Context, filters map[string]interface{}) ([]User, error)
	CreateInTx(ctx context.Context, tx *gorm.DB, user *User, roleNames []RoleName) error
}

type UserRepository struct {
	db *gorm.DB
}

func NewUserRepository(db *gorm.DB) *UserRepository {
	return &UserRepository{db: db}
}

func (r *UserRepository) FindWithFilters(ctx context.Context, filters map[string]interface{}) ([]User, error) {
	var users []User
	query := r.db.WithContext(ctx).Preload("Roles").Preload("Posts")
	if isActive, ok := filters["is_active"]; ok {
		query = query.Where("is_active = ?", isActive)
	}
	return users, query.Find(&users).Error
}

func (r *UserRepository) CreateInTx(ctx context.Context, tx *gorm.DB, user *User, roleNames []RoleName) error {
	var roles []*Role
	if err := tx.WithContext(ctx).Where("name IN ?", roleNames).Find(&roles).Error; err != nil {
		return err
	}
	user.Roles = roles
	return tx.WithContext(ctx).Create(user).Error
}

// user/service.go
type UserService struct {
	db   *gorm.DB
	repo IUserRepository
}

func NewUserService(db *gorm.DB, repo IUserRepository) *UserService {
	return &UserService{db: db, repo: repo}
}

func (s *UserService) CreateUser(ctx context.Context, email, password string) (*User, error) {
	user := &User{Email: email, PasswordHash: "hashed_" + password}
	err := s.db.Transaction(func(tx *gorm.DB) error {
		return s.repo.CreateInTx(ctx, tx, user, []RoleName{UserRole})
	})
	return user, err
}

// user/handler.go
type UserHandler struct {
	svc *UserService
}

func NewUserHandler(svc *UserService) *UserHandler {
	return &UserHandler{svc: svc}
}

func (h *UserHandler) RegisterRoutes(g *echo.Group) {
	g.POST("", h.handleCreateUser)
	g.GET("", h.handleGetUsers)
}

func (h *UserHandler) handleCreateUser(c echo.Context) error {
	var req struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if err := c.Bind(&req); err != nil {
		return c.JSON(http.StatusBadRequest, echo.Map{"error": "invalid_request"})
	}
	user, err := h.svc.CreateUser(c.Request().Context(), req.Email, req.Password)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, echo.Map{"error": err.Error()})
	}
	return c.JSON(http.StatusCreated, user)
}

func (h *UserHandler) handleGetUsers(c echo.Context) error {
	filters := make(map[string]interface{})
	if c.QueryParam("is_active") == "true" {
		filters["is_active"] = true
	} else if c.QueryParam("is_active") == "false" {
		filters["is_active"] = false
	}
	users, err := h.svc.repo.FindWithFilters(c.Request().Context(), filters)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, echo.Map{"error": err.Error()})
	}
	return c.JSON(http.StatusOK, users)
}

// --- DOMAIN: POST ---

// post/model.go
type PostStatus string
const (
	DraftStatus     PostStatus = "DRAFT"
	PublishedStatus PostStatus = "PUBLISHED"
)

type Post struct {
	ID        uuid.UUID  `gorm:"type:uuid;primary_key;"`
	UserID    uuid.UUID  `gorm:"type:uuid;not null"`
	Title     string     `gorm:"not null"`
	Content   string
	Status    PostStatus `gorm:"default:'DRAFT'"`
	CreatedAt time.Time
}

func (p *Post) BeforeCreate(tx *gorm.DB) (err error) {
	p.ID = uuid.New()
	return
}

// Other Post components (repo, service, handler) would be defined similarly.

// --- MAIN (Application Wiring) ---

func main() {
	// --- Init DB ---
	db, err := gorm.Open(sqlite.Open("file::memory:?cache=shared"), &gorm.Config{
		Logger: logger.Default.LogMode(logger.Silent),
	})
	if err != nil {
		log.Fatalf("Cannot connect to DB: %v", err)
	}

	// --- Migrations ---
	log.Println("Executing migrations...")
	allModels := []interface{}{&User{}, &Post{}, &Role{}}
	if err := db.AutoMigrate(allModels...); err != nil {
		log.Fatalf("Migration failed: %v", err)
	}

	// --- Seed Data ---
	log.Println("Seeding database...")
	db.Create(&[]Role{{Name: AdminRole}, {Name: UserRole}})

	// --- Init Echo ---
	e := echo.New()
	e.Use(middleware.Logger())
	e.Use(middleware.Recover())

	// --- Dependency Injection & Route Registration per Domain ---
	apiGroup := e.Group("/api")

	// User Domain
	userRepo := NewUserRepository(db)
	userSvc := NewUserService(db, userRepo)
	userHandler := NewUserHandler(userSvc)
	userHandler.RegisterRoutes(apiGroup.Group("/users"))

	// Post Domain (would be set up here)
	// postRepo := NewPostRepository(db)
	// ... and so on

	log.Println("Server is running on port 8080")
	e.Start(":8080")
}