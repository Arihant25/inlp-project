<pre>
package main

import (
	"encoding/xml"
	"fmt"
	"log"
	"strings"
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

// --- Domain Models ---

type UserRole string
type PostStatus string

const (
	RoleAdmin UserRole = "ADMIN"
	RoleUser  UserRole = "USER"
)

const (
	StatusDraft     PostStatus = "DRAFT"
	StatusPublished PostStatus = "PUBLISHED"
)

type User struct {
	XMLName     xml.Name  `json:"-" xml:"user"`
	ID          uuid.UUID `json:"id" xml:"id"`
	Email       string    `json:"email" xml:"email"`
	PasswordHash string    `json:"-" xml:"-"` // Never expose password hash
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

// --- DTOs and Validation ---

type CreateUserRequest struct {
	Email    string   `json:"email" xml:"email" validate:"required,email"`
	Password string   `json:"password" xml:"password" validate:"required,min=8"`
	Phone    string   `json:"phone" xml:"phone" validate:"required,e164"` // E.164 phone number format
	Role     UserRole `json:"role" xml:"role" validate:"required,userrole"`
}

type CreatePostRequest struct {
	UserID  uuid.UUID  `json:"user_id" validate:"required"`
	Title   string     `json:"title" validate:"required,min=5,max=100"`
	Content string     `json:"content" validate:"required"`
	Status  PostStatus `json:"status" validate:"required,poststatus"`
}

// --- Validation Logic ---

type ErrorResponse struct {
	FailedField string `json:"failed_field"`
	Tag         string `json:"tag"`
	Value       string `json:"value"`
}

var validate = validator.New()

func validateStruct(s interface{}) []*ErrorResponse {
	var errors []*ErrorResponse
	err := validate.Struct(s)
	if err != nil {
		for _, err := range err.(validator.ValidationErrors) {
			var element ErrorResponse
			element.FailedField = err.StructNamespace()
			element.Tag = err.Tag()
			element.Value = err.Param()
			errors = append(errors, &element)
		}
	}
	return errors
}

// Custom validator for UserRole
func validateUserRole(fl validator.FieldLevel) bool {
	role := UserRole(fl.Field().String())
	switch role {
	case RoleAdmin, RoleUser:
		return true
	default:
		return false
	}
}

// Custom validator for PostStatus
func validatePostStatus(fl validator.FieldLevel) bool {
	status := PostStatus(fl.Field().String())
	switch status {
	case StatusDraft, StatusPublished:
		return true
	default:
		return false
	}
}

// --- Handlers (Functional Approach) ---

func handleCreateUser(c *fiber.Ctx) error {
	req := new(CreateUserRequest)

	// This single BodyParser handles JSON, form, and XML based on Content-Type
	if err := c.BodyParser(req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "Cannot parse request body"})
	}

	if errors := validateStruct(req); errors != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"errors": errors})
	}

	// In a real app, you'd hash the password and save to DB
	newUser := &User{
		ID:          uuid.New(),
		Email:       req.Email,
		PasswordHash: "hashed_" + req.Password, // Mock hashing
		Role:        req.Role,
		IsActive:    true,
		CreatedAt:   time.Now().UTC(),
	}

	// Respond with the correct content type
	if strings.Contains(c.Get("Accept"), "application/xml") {
		return c.XML(newUser)
	}
	return c.Status(fiber.StatusCreated).JSON(newUser)
}

func handleCreatePost(c *fiber.Ctx) error {
	req := new(CreatePostRequest)

	if err := c.BodyParser(req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "Cannot parse JSON"})
	}

	if errors := validateStruct(req); errors != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"errors": errors})
	}

	newPost := &Post{
		ID:      uuid.New(),
		UserID:  req.UserID,
		Title:   req.Title,
		Content: req.Content,
		Status:  req.Status,
	}

	return c.Status(fiber.StatusCreated).JSON(newPost)
}

func handleGetUser(c *fiber.Ctx) error {
	id, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "Invalid UUID format"})
	}

	// Mock user retrieval
	mockUser := &User{
		ID:        id,
		Email:     "test.user@example.com",
		Role:      RoleUser,
		IsActive:  true,
		CreatedAt: time.Now().UTC().Add(-24 * time.Hour),
	}

	// Respond based on Accept header
	if strings.Contains(c.Get("Accept"), "application/xml") {
		c.Set("Content-Type", "application/xml")
		return c.XML(mockUser)
	}
	return c.JSON(mockUser)
}

func main() {
	// Register custom validators
	_ = validate.RegisterValidation("userrole", validateUserRole)
	_ = validate.RegisterValidation("poststatus", validatePostStatus)

	app := fiber.New()

	api := app.Group("/api/v1")

	// User routes
	api.Post("/users", handleCreateUser) // Accepts JSON or XML
	api.Get("/users/:id", handleGetUser) // Responds with JSON or XML

	// Post routes
	api.Post("/posts", handleCreatePost) // Accepts JSON

	fmt.Println("Server running on port 3000")
	log.Fatal(app.Listen(":3000"))
}
</pre>