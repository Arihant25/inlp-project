<pre>
package main

import (
	"encoding/xml"
	"net/http"
	"time"

	"github.com/go-playground/validator/v10"
	"github.com/google/uuid"
	"github.com/labstack/echo/v4"
	"github.com/labstack/echo/v4/middleware"
)

// --- Domain Models ---

type UserRole string

const (
	RoleAdmin UserRole = "ADMIN"
	RoleUser  UserRole = "USER"
)

type User struct {
	ID           uuid.UUID `json:"id" xml:"id"`
	Email        string    `json:"email" xml:"email"`
	PasswordHash string    `json:"-" xml:"-"` // Exclude from serialization
	Role         UserRole  `json:"role" xml:"role"`
	IsActive     bool      `json:"is_active" xml:"is_active"`
	CreatedAt    time.Time `json:"created_at" xml:"created_at"`
}

type PostStatus string

const (
	StatusDraft     PostStatus = "DRAFT"
	StatusPublished PostStatus = "PUBLISHED"
)

type Post struct {
	XMLName xml.Name   `xml:"Post"` // Root element for XML
	ID      uuid.UUID  `json:"id" xml:"id"`
	UserID  uuid.UUID  `json:"user_id" xml:"user_id"`
	Title   string     `json:"title" xml:"title"`
	Content string     `json:"content" xml:"content"`
	Status  PostStatus `json:"status" xml:"status"`
}

// --- DTOs (Data Transfer Objects) ---

type CreateUserRequest struct {
	Email    string   `json:"email" validate:"required,email"`
	Password string   `json:"password" validate:"required,min=8"`
	Role     UserRole `json:"role" validate:"required,is-role"`
}

type CreatePostRequest struct {
	UserID  string `json:"user_id" validate:"required,uuid"`
	Title   string `json:"title" validate:"required,min=5,max=100"`
	Content string `json:"content" validate:"required"`
}

// --- Custom Validator ---

type CustomValidator struct {
	validator *validator.Validate
}

func (cv *CustomValidator) Validate(i interface{}) error {
	if err := cv.validator.Struct(i); err != nil {
		// Optionally, you could return a custom error format here
		return err
	}
	return nil
}

// Custom validation function for UserRole
func validateUserRole(fl validator.FieldLevel) bool {
	role := UserRole(fl.Field().String())
	switch role {
	case RoleAdmin, RoleUser:
		return true
	}
	return false
}

func NewValidator() *CustomValidator {
	v := validator.New()
	v.RegisterValidation("is-role", validateUserRole)
	return &CustomValidator{validator: v}
}

// --- Error Formatting ---

func formatValidationErrors(err error) map[string]string {
	errors := make(map[string]string)
	if validationErrors, ok := err.(validator.ValidationErrors); ok {
		for _, fieldErr := range validationErrors {
			errors[fieldErr.Field()] = "Validation failed on tag: " + fieldErr.Tag()
		}
	}
	return errors
}

// --- Handlers (Functional Style) ---

func createUserHandler(c echo.Context) error {
	req := new(CreateUserRequest)
	if err := c.Bind(req); err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "Invalid request body"})
	}

	if err := c.Validate(req); err != nil {
		return c.JSON(http.StatusBadRequest, map[string]interface{}{
			"message": "Validation failed",
			"details": formatValidationErrors(err),
		})
	}

	// In a real app, you'd hash the password and save the user
	newUser := &User{
		ID:           uuid.New(),
		Email:        req.Email,
		PasswordHash: "hashed_" + req.Password, // Mock hashing
		Role:         req.Role,
		IsActive:     true,
		CreatedAt:    time.Now().UTC(),
	}

	return c.JSON(http.StatusCreated, newUser)
}

func createPostHandler(c echo.Context) error {
	req := new(CreatePostRequest)
	if err := c.Bind(req); err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "Invalid request body"})
	}
	if err := c.Validate(req); err != nil {
		return c.JSON(http.StatusBadRequest, map[string]interface{}{
			"message": "Validation failed",
			"details": formatValidationErrors(err),
		})
	}

	// Type Coercion from string to UUID
	userID, err := uuid.Parse(req.UserID)
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "Invalid user_id format"})
	}

	newPost := &Post{
		ID:      uuid.New(),
		UserID:  userID,
		Title:   req.Title,
		Content: req.Content,
		Status:  StatusDraft, // Default status
	}

	return c.JSON(http.StatusCreated, newPost)
}

func getPostAsXMLHandler(c echo.Context) error {
	postID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "Invalid post ID format"})
	}

	// Mock fetching a post from a database
	mockPost := &Post{
		ID:      postID,
		UserID:  uuid.New(),
		Title:   "XML Generation Example",
		Content: "This is the content that will be serialized into XML.",
		Status:  StatusPublished,
	}

	return c.XML(http.StatusOK, mockPost)
}

// --- Main Application ---

func main() {
	e := echo.New()
	e.Use(middleware.Logger())
	e.Use(middleware.Recover())

	// Register the custom validator
	e.Validator = NewValidator()

	// --- Routes ---
	e.POST("/users", createUserHandler)
	e.POST("/posts", createPostHandler)
	e.GET("/posts/:id/xml", getPostAsXMLHandler)

	e.Logger.Fatal(e.Start(":1323"))
}
</pre>