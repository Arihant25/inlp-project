package main

import (
	"fmt"
	"log"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

// --- Domain Models ---

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

// GORM Hooks
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

// --- Data Store ---

type DataStore struct {
	DB *gorm.DB
}

func NewDataStore() (*DataStore, error) {
	db, err := gorm.Open(sqlite.Open("file::memory:?cache=shared"), &gorm.Config{
		Logger: logger.Default.LogMode(logger.Silent),
	})
	if err != nil {
		return nil, fmt.Errorf("failed to connect to database: %w", err)
	}

	// Migration
	err = db.AutoMigrate(&User{}, &Post{}, &Role{})
	if err != nil {
		return nil, fmt.Errorf("database migration failed: %w", err)
	}
	
	// Seeding
	if db.First(&Role{}).RowsAffected == 0 {
		db.Create(&[]Role{{Name: "ADMIN"}, {Name: "USER"}})
	}

	return &DataStore{DB: db}, nil
}

// --- Modular Route Handlers ---

// UserModule encapsulates all user-related handlers and dependencies.
type UserModule struct {
	Store *DataStore
}

func (m *UserModule) Register(router fiber.Router) {
	router.Post("/", m.createUser)
	router.Get("/", m.findUsersWithQueryBuilder)
	router.Get("/:id", m.findUser)
	router.Post("/:id/posts", m.createPostForUser)
	router.Post("/:id/roles", m.assignRoleToUser)
}

func (m *UserModule) createUser(c *fiber.Ctx) error {
	var input struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if err := c.BodyParser(&input); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "bad request"})
	}
	user := User{Email: input.Email, PasswordHash: "hashed_" + input.Password}
	if err := m.Store.DB.Create(&user).Error; err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": err.Error()})
	}
	return c.Status(fiber.StatusCreated).JSON(user)
}

func (m *UserModule) findUsersWithQueryBuilder(c *fiber.Ctx) error {
	var users []User
	
	// Dynamic Query Building
	query := m.Store.DB.Model(&User{})
	
	if isActive := c.Query("is_active"); isActive != "" {
		query = query.Where("is_active = ?", isActive == "true")
	}
	
	if roleName := c.Query("role"); roleName != "" {
		// Subquery to filter users by role name
		query = query.Joins("JOIN user_roles on user_roles.user_id = users.id").
			Joins("JOIN roles on roles.id = user_roles.role_id").
			Where("roles.name = ?", roleName)
	}

	if err := query.Preload("Roles").Distinct().Find(&users).Error; err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": err.Error()})
	}
	
	return c.JSON(users)
}

func (m *UserModule) findUser(c *fiber.Ctx) error {
	var user User
	if err := m.Store.DB.Preload("Posts").Preload("Roles").First(&user, "id = ?", c.Params("id")).Error; err != nil {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": "user not found"})
	}
	return c.JSON(user)
}

func (m *UserModule) createPostForUser(c *fiber.Ctx) error {
	userID, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "invalid user ID"})
	}

	var postInput struct {
		Title   string `json:"title"`
		Content string `json:"content"`
	}
	if err := c.BodyParser(&postInput); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "bad request"})
	}

	post := Post{
		UserID:  userID,
		Title:   postInput.Title,
		Content: postInput.Content,
		Status:  Draft,
	}

	// Transaction to ensure user exists before creating post
	txErr := m.Store.DB.Transaction(func(tx *gorm.DB) error {
		var userCount int64
		if err := tx.Model(&User{}).Where("id = ?", userID).Count(&userCount).Error; err != nil {
			return err
		}
		if userCount == 0 {
			return fmt.Errorf("user not found")
		}
		if err := tx.Create(&post).Error; err != nil {
			return err
		}
		return nil
	})

	if txErr != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": txErr.Error()})
	}

	return c.Status(fiber.StatusCreated).JSON(post)
}

func (m *UserModule) assignRoleToUser(c *fiber.Ctx) error {
	var user User
	if err := m.Store.DB.First(&user, "id = ?", c.Params("id")).Error; err != nil {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": "user not found"})
	}

	var roleInput struct {
		RoleID string `json:"role_id"`
	}
	if err := c.BodyParser(&roleInput); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "bad request"})
	}

	var role Role
	if err := m.Store.DB.First(&role, "id = ?", roleInput.RoleID).Error; err != nil {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": "role not found"})
	}

	if err := m.Store.DB.Model(&user).Association("Roles").Append(&role); err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": "failed to assign role"})
	}

	m.Store.DB.Preload("Roles").First(&user)
	return c.JSON(user)
}

// --- Main Application Setup ---

func main() {
	// Initialize dependencies
	store, err := NewDataStore()
	if err != nil {
		log.Fatalf("Initialization failed: %v", err)
	}

	// Initialize modules
	userModule := &UserModule{Store: store}

	// Setup Fiber app and routing
	app := fiber.New()
	
	apiV1 := app.Group("/api/v1")
	
	// Register modules to route groups
	userModule.Register(apiV1.Group("/users"))

	fmt.Println("Server routes are configured. This is a runnable example.")
	// In a real app, you would run:
	// log.Fatal(app.Listen(":3000"))
}