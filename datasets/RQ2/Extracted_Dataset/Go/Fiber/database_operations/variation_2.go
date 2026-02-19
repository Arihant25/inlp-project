package main

import (
	"context"
	"fmt"
	"log"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

// --- Models ---

type Role struct {
	ID   uuid.UUID `gorm:"type:uuid;primary_key;" json:"id"`
	Name string    `gorm:"uniqueIndex;not null" json:"name"`
}

type User struct {
	ID           uuid.UUID `gorm:"type:uuid;primary_key;" json:"id"`
	Email        string    `gorm:"uniqueIndex;not null" json:"email"`
	PasswordHash string    `gorm:"not null" json:"-"`
	IsActive     bool      `gorm:"default:true" json:"is_active"`
	CreatedAt    time.Time `gorm:"autoCreateTime" json:"created_at"`
	Posts        []Post    `gorm:"foreignKey:UserID" json:"posts,omitempty"`
	Roles        []*Role   `gorm:"many2many:user_roles;" json:"roles,omitempty"`
}

type PostStatus string

const (
	Draft     PostStatus = "DRAFT"
	Published PostStatus = "PUBLISHED"
)

type Post struct {
	ID      uuid.UUID  `gorm:"type:uuid;primary_key;" json:"id"`
	UserID  uuid.UUID  `gorm:"type:uuid;not null" json:"user_id"`
	Title   string     `gorm:"not null" json:"title"`
	Content string     `gorm:"type:text" json:"content"`
	Status  PostStatus `gorm:"type:varchar(20);default:'DRAFT'" json:"status"`
}

func (u *User) BeforeCreate(tx *gorm.DB) (err error) {
	u.ID = uuid.New()
	return
}
func (p *Post) BeforeCreate(tx *gorm.DB) (err error) {
	p.ID = uuid.New()
	return
}
func (r *Role) BeforeCreate(tx *gorm.DB) (err error) {
	r.ID = uuid.New()
	return
}

// --- Repository Layer ---

type UserRepository interface {
	Create(ctx context.Context, user *User) error
	FindAll(ctx context.Context, filters map[string]interface{}) ([]User, error)
	FindByID(ctx context.Context, id uuid.UUID) (*User, error)
	Update(ctx context.Context, user *User) error
	Delete(ctx context.Context, id uuid.UUID) error
	CreateUserWithPostInTx(ctx context.Context, user *User, post *Post) error
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

func (r *gormUserRepository) FindAll(ctx context.Context, filters map[string]interface{}) ([]User, error) {
	var users []User
	query := r.db.WithContext(ctx).Model(&User{})
	if isActive, ok := filters["is_active"]; ok {
		query = query.Where("is_active = ?", isActive)
	}
	err := query.Preload("Roles").Find(&users).Error
	return users, err
}

func (r *gormUserRepository) FindByID(ctx context.Context, id uuid.UUID) (*User, error) {
	var user User
	err := r.db.WithContext(ctx).Preload("Posts").Preload("Roles").First(&user, "id = ?", id).Error
	return &user, err
}

func (r *gormUserRepository) Update(ctx context.Context, user *User) error {
	return r.db.WithContext(ctx).Save(user).Error
}

func (r *gormUserRepository) Delete(ctx context.Context, id uuid.UUID) error {
	return r.db.WithContext(ctx).Delete(&User{}, "id = ?", id).Error
}

func (r *gormUserRepository) CreateUserWithPostInTx(ctx context.Context, user *User, post *Post) error {
	return r.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		if err := tx.Create(user).Error; err != nil {
			return err
		}
		post.UserID = user.ID
		if err := tx.Create(post).Error; err != nil {
			return err
		}
		return nil
	})
}

// --- Service Layer ---

type UserService interface {
	RegisterUser(ctx context.Context, email, password string) (*User, error)
	GetUsers(ctx context.Context, isActive *bool) ([]User, error)
	GetUser(ctx context.Context, id string) (*User, error)
	RemoveUser(ctx context.Context, id string) error
	OnboardUser(ctx context.Context, email, password, postTitle, postContent string) (*User, error)
}

type userService struct {
	repo UserRepository
}

func NewUserService(repo UserRepository) UserService {
	return &userService{repo: repo}
}

func (s *userService) RegisterUser(ctx context.Context, email, password string) (*User, error) {
	user := &User{
		Email:        email,
		PasswordHash: "hashed_" + password, // Use bcrypt in production
	}
	err := s.repo.Create(ctx, user)
	return user, err
}

func (s *userService) GetUsers(ctx context.Context, isActive *bool) ([]User, error) {
	filters := make(map[string]interface{})
	if isActive != nil {
		filters["is_active"] = *isActive
	}
	return s.repo.FindAll(ctx, filters)
}

func (s *userService) GetUser(ctx context.Context, id string) (*User, error) {
	uid, err := uuid.Parse(id)
	if err != nil {
		return nil, fmt.Errorf("invalid user ID")
	}
	return s.repo.FindByID(ctx, uid)
}

func (s *userService) RemoveUser(ctx context.Context, id string) error {
	uid, err := uuid.Parse(id)
	if err != nil {
		return fmt.Errorf("invalid user ID")
	}
	return s.repo.Delete(ctx, uid)
}

func (s *userService) OnboardUser(ctx context.Context, email, password, postTitle, postContent string) (*User, error) {
	user := &User{
		Email:        email,
		PasswordHash: "hashed_" + password,
	}
	post := &Post{
		Title:   postTitle,
		Content: postContent,
		Status:  Published,
	}
	err := s.repo.CreateUserWithPostInTx(ctx, user, post)
	if err != nil {
		return nil, err
	}
	return s.repo.FindByID(ctx, user.ID)
}

// --- Handler/Controller Layer ---

type UserHandler struct {
	service UserService
}

func NewUserHandler(service UserService) *UserHandler {
	return &UserHandler{service: service}
}

func (h *UserHandler) Create(c *fiber.Ctx) error {
	var req struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "invalid request"})
	}
	user, err := h.service.RegisterUser(c.Context(), req.Email, req.Password)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": err.Error()})
	}
	return c.Status(fiber.StatusCreated).JSON(user)
}

func (h *UserHandler) GetAll(c *fiber.Ctx) error {
	var isActive *bool
	if val := c.Query("is_active"); val != "" {
		b := val == "true"
		isActive = &b
	}
	users, err := h.service.GetUsers(c.Context(), isActive)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": err.Error()})
	}
	return c.JSON(users)
}

func (h *UserHandler) GetByID(c *fiber.Ctx) error {
	user, err := h.service.GetUser(c.Context(), c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": err.Error()})
	}
	return c.JSON(user)
}

func (h *UserHandler) Delete(c *fiber.Ctx) error {
	if err := h.service.RemoveUser(c.Context(), c.Params("id")); err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": err.Error()})
	}
	return c.SendStatus(fiber.StatusNoContent)
}

func (h *UserHandler) Onboard(c *fiber.Ctx) error {
	var req struct {
		Email   string `json:"email"`
		Password string `json:"password"`
	}
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "invalid request"})
	}
	user, err := h.service.OnboardUser(c.Context(), req.Email, req.Password, "Welcome!", "Your new account is ready.")
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": err.Error()})
	}
	return c.Status(fiber.StatusCreated).JSON(user)
}

// --- Main ---

func main() {
	// Database Setup
	db, err := gorm.Open(sqlite.Open("file::memory:?cache=shared"), &gorm.Config{
		Logger: logger.Default.LogMode(logger.Silent),
	})
	if err != nil {
		log.Fatalf("DB connection failed: %v", err)
	}
	db.AutoMigrate(&User{}, &Post{}, &Role{})

	// Dependency Injection
	userRepo := NewGormUserRepository(db)
	userService := NewUserService(userRepo)
	userHandler := NewUserHandler(userService)

	// Fiber App
	app := fiber.New()
	api := app.Group("/api")
	
	userRoutes := api.Group("/users")
	userRoutes.Post("/", userHandler.Create)
	userRoutes.Get("/", userHandler.GetAll)
	userRoutes.Get("/:id", userHandler.GetByID)
	userRoutes.Delete("/:id", userHandler.Delete)
	userRoutes.Post("/onboard", userHandler.Onboard)

	fmt.Println("Server routes are configured. This is a runnable example.")
	// log.Fatal(app.Listen(":3000"))
}