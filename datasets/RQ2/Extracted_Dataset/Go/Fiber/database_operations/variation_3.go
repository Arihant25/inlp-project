package main

import (
	"fmt"
	"log"
	"os"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
)

// --- Domain Models ---

type Role struct {
	ID   uuid.UUID `gorm:"type:uuid;primary_key;"`
	Name string    `gorm:"uniqueIndex;not null"`
}

type User struct {
	ID           uuid.UUID `gorm:"type:uuid;primary_key;"`
	Email        string    `gorm:"uniqueIndex;not null"`
	PasswordHash string    `gorm:"not null"`
	IsActive     bool      `gorm:"default:true"`
	CreatedAt    time.Time `gorm:"autoCreateTime"`
	Posts        []Post    `gorm:"foreignKey:UserID"`
	Roles        []*Role   `gorm:"many2many:user_roles;"`
}

type PostStatus string

const (
	Draft     PostStatus = "DRAFT"
	Published PostStatus = "PUBLISHED"
)

type Post struct {
	ID      uuid.UUID  `gorm:"type:uuid;primary_key;"`
	UserID  uuid.UUID  `gorm:"type:uuid;not null"`
	Title   string     `gorm:"not null"`
	Content string     `gorm:"type:text"`
	Status  PostStatus `gorm:"type:varchar(20);default:'DRAFT'"`
}

// GORM Hooks
func (base *User) BeforeCreate(tx *gorm.DB) (err error) {
	base.ID = uuid.New()
	return
}
func (base *Post) BeforeCreate(tx *gorm.DB) (err error) {
	base.ID = uuid.New()
	return
}
func (base *Role) BeforeCreate(tx *gorm.DB) (err error) {
	base.ID = uuid.New()
	return
}

// --- Application Container ---

type AppConfig struct {
	DB     *gorm.DB
	Logger *log.Logger
}

type Application struct {
	Config AppConfig
	Web    *fiber.App
}

func NewApplication() *Application {
	// Setup Logger
	logger := log.New(os.Stdout, "", log.Ldate|log.Ltime)

	// Setup Database
	db, err := gorm.Open(sqlite.Open("file::memory:?cache=shared"), &gorm.Config{})
	if err != nil {
		logger.Fatalf("Cannot connect to database: %v", err)
	}

	// Run Migrations
	db.AutoMigrate(&User{}, &Post{}, &Role{})
	
	// Seed Data
	if db.First(&Role{}).RowsAffected == 0 {
		db.Create(&[]Role{{Name: "ADMIN"}, {Name: "USER"}})
	}

	// Setup Fiber
	web := fiber.New()

	app := &Application{
		Config: AppConfig{
			DB:     db,
			Logger: logger,
		},
		Web: web,
	}

	app.registerRoutes()

	return app
}

func (app *Application) registerRoutes() {
	app.Web.Post("/users", app.handleCreateUser)
	app.Web.Get("/users", app.handleGetUsers)
	app.Web.Get("/users/:id", app.handleGetUserByID)
	app.Web.Put("/users/:id/status", app.handleUpdateUserStatus)
	app.Web.Delete("/users/:id", app.handleDeleteUser)
	app.Web.Post("/users/transactional", app.handleTransactionalCreate)
}

// --- Handlers (Methods on Application struct) ---

func (app *Application) handleCreateUser(c *fiber.Ctx) error {
	type Request struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	var body Request
	if err := c.BodyParser(&body); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"status": "fail", "message": err.Error()})
	}

	user := User{
		Email:        body.Email,
		PasswordHash: "hashed_" + body.Password, // Use bcrypt in production
	}

	result := app.Config.DB.Create(&user)
	if result.Error != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"status": "error", "message": "Failed to create user"})
	}

	app.Config.Logger.Printf("New user created: %s", user.Email)
	return c.Status(fiber.StatusCreated).JSON(user)
}

func (app *Application) handleGetUsers(c *fiber.Ctx) error {
	var users []User
	
	// Query Builder based on query params
	query := app.Config.DB
	if status := c.Query("status"); status == "active" {
		query = query.Where("is_active = ?", true)
	} else if status == "inactive" {
		query = query.Where("is_active = ?", false)
	}

	if err := query.Preload("Roles").Find(&users).Error; err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"status": "error", "message": "Could not retrieve users"})
	}
	return c.JSON(users)
}

func (app *Application) handleGetUserByID(c *fiber.Ctx) error {
	id := c.Params("id")
	var user User
	if err := app.Config.DB.Preload("Posts").Preload("Roles").First(&user, "id = ?", id).Error; err != nil {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"status": "fail", "message": "User not found"})
	}
	return c.JSON(user)
}

func (app *Application) handleUpdateUserStatus(c *fiber.Ctx) error {
	id := c.Params("id")
	var user User
	if err := app.Config.DB.First(&user, "id = ?", id).Error; err != nil {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"status": "fail", "message": "User not found"})
	}

	type Request struct {
		IsActive bool `json:"is_active"`
	}
	var body Request
	if err := c.BodyParser(&body); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"status": "fail", "message": err.Error()})
	}

	user.IsActive = body.IsActive
	app.Config.DB.Save(&user)
	return c.JSON(user)
}

func (app *Application) handleDeleteUser(c *fiber.Ctx) error {
	id := c.Params("id")
	result := app.Config.DB.Delete(&User{}, "id = ?", id)
	if result.RowsAffected == 0 {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"status": "fail", "message": "User not found"})
	}
	return c.SendStatus(fiber.StatusNoContent)
}

func (app *Application) handleTransactionalCreate(c *fiber.Ctx) error {
	type Request struct {
		Email string `json:"email"`
	}
	var body Request
	if err := c.BodyParser(&body); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"status": "fail", "message": err.Error()})
	}

	var createdUser User
	err := app.Config.DB.Transaction(func(tx *gorm.DB) error {
		// Step 1: Create User
		user := User{Email: body.Email, PasswordHash: "default_pass"}
		if err := tx.Create(&user).Error; err != nil {
			return err // Rollback
		}

		// Step 2: Assign ADMIN and USER roles
		var roles []*Role
		if err := tx.Where("name IN ?", []string{"ADMIN", "USER"}).Find(&roles).Error; err != nil {
			return err // Rollback
		}
		if err := tx.Model(&user).Association("Roles").Append(roles); err != nil {
			return err // Rollback
		}
		
		// Step 3: Create an initial "DRAFT" post
		post := Post{UserID: user.ID, Title: "My first draft", Content: "...", Status: Draft}
		if err := tx.Create(&post).Error; err != nil {
			return err // Rollback
		}

		createdUser = user
		return nil // Commit
	})

	if err != nil {
		app.Config.Logger.Printf("Transaction failed: %v", err)
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"status": "error", "message": "Transactional creation failed"})
	}

	// Reload to show associations in response
	app.Config.DB.Preload("Posts").Preload("Roles").First(&createdUser, "id = ?", createdUser.ID)
	return c.Status(fiber.StatusCreated).JSON(createdUser)
}

// --- Main Entry Point ---

func main() {
	app := NewApplication()
	fmt.Println("Server routes are configured. This is a runnable example.")
	// In a real app, you would run:
	// app.Web.Listen(":3000")
}