<pre>
package main

import (
	"encoding/xml"
	"fmt"
	"log"
	"time"

	"github.com/go-playground/validator/v10"
	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
)

// To run this code:
// 1. go mod init your_module_name
// 2. go get github.com/gofiber/fiber/v2
// 3. go get github.com/go-playground/validator/v10
// 4. go get github.com/google/uuid
// 5. go run .

// --- Domain Enums & Models ---

type UserRole string
type PostStatus string

const (
	ADMIN UserRole = "ADMIN"
	USER  UserRole = "USER"
)
const (
	DRAFT     PostStatus = "DRAFT"
	PUBLISHED PostStatus = "PUBLISHED"
)

type User struct {
	ID          uuid.UUID `json:"id" xml:"id,attr"`
	Email       string    `json:"email" xml:"email"`
	PasswordHash string    `json:"-" xml:"-"`
	Role        UserRole  `json:"role" xml:"role"`
	IsActive    bool      `json:"is_active" xml:"is_active"`
	CreatedAt   time.Time `json:"created_at" xml:"created_at"`
}

type Post struct {
	ID      uuid.UUID  `json:"id"`
	UserID  uuid.UUID  `json:"user_id"`
	Title   string     `json:"title"`
	Content string     `json:"content"`
	Status  PostStatus `json:"status"`
}

// --- DTOs for Data Transfer ---

type UserCreateDTO struct {
	XMLName  xml.Name `json:"-" xml:"UserCreateDTO"`
	Email    string   `json:"email" xml:"Email" validate:"required,email"`
	Password string   `json:"password" xml:"Password" validate:"required,min=8"`
	Phone    string   `json:"phone" xml:"Phone" validate:"required,e164"`
	Role     UserRole `json:"role" xml:"Role" validate:"required,role_enum"`
}

type PostCreateDTO struct {
	UserID  uuid.UUID  `json:"user_id" validate:"required"`
	Title   string     `json:"title" validate:"required,min=5"`
	Content string     `json:"content" validate:"required"`
	Status  PostStatus `json:"status" validate:"required,status_enum"`
}

// --- Validation Utility ---

type ValidatorUtil struct {
	validator *validator.Validate
}

func NewValidator() *ValidatorUtil {
	v := validator.New()
	_ = v.RegisterValidation("role_enum", func(fl validator.FieldLevel) bool {
		return fl.Field().String() == string(ADMIN) || fl.Field().String() == string(USER)
	})
	_ = v.RegisterValidation("status_enum", func(fl validator.FieldLevel) bool {
		return fl.Field().String() == string(DRAFT) || fl.Field().String() == string(PUBLISHED)
	})
	return &ValidatorUtil{validator: v}
}

func (v *ValidatorUtil) Validate(payload interface{}) map[string]string {
	err := v.validator.Struct(payload)
	if err == nil {
		return nil
	}
	errors := make(map[string]string)
	for _, err := range err.(validator.ValidationErrors) {
		errors[err.Field()] = fmt.Sprintf("Field validation for '%s' failed on the '%s' tag", err.Field(), err.Tag())
	}
	return errors
}

// --- Handlers (OOP Approach) ---

type UserHandler struct {
	Validator *ValidatorUtil
}

func (h *UserHandler) CreateUserFromJSON(c *fiber.Ctx) error {
	dto := new(UserCreateDTO)
	if err := c.BodyParser(dto); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "Invalid JSON body"})
	}

	if errs := h.Validator.Validate(dto); errs != nil {
		return c.Status(fiber.StatusUnprocessableEntity).JSON(fiber.Map{"errors": errs})
	}

	// Mock persistence
	user := &User{
		ID:          uuid.New(),
		Email:       dto.Email,
		PasswordHash: "super_secret_hash_for_" + dto.Password,
		Role:        dto.Role,
		IsActive:    false,
		CreatedAt:   time.Now(),
	}
	return c.Status(fiber.StatusCreated).JSON(user)
}

func (h *UserHandler) CreateUserFromXML(c *fiber.Ctx) error {
	dto := new(UserCreateDTO)
	// Fiber's BodyParser can handle XML if Content-Type is set correctly
	if err := c.BodyParser(dto); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "Invalid XML body"})
	}

	if errs := h.Validator.Validate(dto); errs != nil {
		return c.Status(fiber.StatusUnprocessableEntity).JSON(fiber.Map{"errors": errs})
	}

	user := &User{
		ID:          uuid.New(),
		Email:       dto.Email,
		PasswordHash: "super_secret_hash_for_" + dto.Password,
		Role:        dto.Role,
		IsActive:    false,
		CreatedAt:   time.Now(),
	}
	return c.Status(fiber.StatusCreated).JSON(user)
}

func (h *UserHandler) GetUserAsXML(c *fiber.Ctx) error {
	userID, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "Invalid user ID"})
	}

	// Mock retrieval
	user := &User{
		ID:        userID,
		Email:     "retrieved.user@example.com",
		Role:      ADMIN,
		IsActive:  true,
		CreatedAt: time.Now().Add(-48 * time.Hour),
	}
	return c.XML(user)
}

type PostHandler struct {
	Validator *ValidatorUtil
}

func (h *PostHandler) CreatePost(c *fiber.Ctx) error {
	dto := new(PostCreateDTO)
	if err := c.BodyParser(dto); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "Invalid JSON body"})
	}

	if errs := h.Validator.Validate(dto); errs != nil {
		return c.Status(fiber.StatusUnprocessableEntity).JSON(fiber.Map{"errors": errs})
	}

	post := &Post{
		ID:      uuid.New(),
		UserID:  dto.UserID,
		Title:   dto.Title,
		Content: dto.Content,
		Status:  dto.Status,
	}
	return c.Status(fiber.StatusCreated).JSON(post)
}

// --- Main Application Setup ---

func main() {
	app := fiber.New()
	validator := NewValidator()

	// Instantiate handlers with dependencies
	userHandler := &UserHandler{Validator: validator}
	postHandler := &PostHandler{Validator: validator}

	// Setup routes
	userRoutes := app.Group("/users")
	userRoutes.Post("/json", userHandler.CreateUserFromJSON)
	userRoutes.Post("/xml", userHandler.CreateUserFromXML)
	userRoutes.Get("/:id/xml", userHandler.GetUserAsXML)

	postRoutes := app.Group("/posts")
	postRoutes.Post("/", postHandler.CreatePost)

	fmt.Println("Server starting on :3000")
	log.Fatal(app.Listen(":3000"))
}
</pre>