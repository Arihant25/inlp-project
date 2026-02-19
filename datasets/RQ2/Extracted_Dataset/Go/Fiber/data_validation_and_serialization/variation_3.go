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

// This variation simulates a layered architecture within a single file.
// Comments are used to denote package/file boundaries.

// --- file: models/enums.go ---
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

// --- file: models/user.go ---
type User struct {
	ID          uuid.UUID `json:"id"`
	Email       string    `json:"email"`
	PasswordHash string    `json:"-"`
	Role        UserRole  `json:"role"`
	IsActive    bool      `json:"is_active"`
	CreatedAt   time.Time `json:"created_at"`
}

// --- file: models/post.go ---
type Post struct {
	XMLName xml.Name   `json:"-" xml:"post"`
	ID      uuid.UUID  `json:"id" xml:"id"`
	UserID  uuid.UUID  `json:"user_id" xml:"user_id"`
	Title   string     `json:"title" xml:"title"`
	Content string     `json:"content" xml:"content"`
	Status  PostStatus `json:"status" xml:"status"`
}

// --- file: dtos/requests.go ---
type UserCreationRequest struct {
	Email    string   `json:"email" validate:"required,email"`
	Password string   `json:"password" validate:"required,min=8,max=72"`
	Phone    string   `json:"phone" validate:"required,e164"`
	Role     UserRole `json:"role" validate:"required,user_role"`
}

type PostCreationRequest struct {
	UserID  uuid.UUID  `json:"user_id" validate:"required"`
	Title   string     `json:"title" validate:"required,min=3"`
	Content string     `json:"content" validate:"required"`
	Status  PostStatus `json:"status" validate:"required,post_status"`
}

// --- file: validation/validator.go ---
type AppValidator struct {
	Validator *validator.Validate
}

func (v *AppValidator) Validate(data interface{}) map[string]string {
	err := v.Validator.Struct(data)
	if err == nil {
		return nil
	}
	validationErrors := err.(validator.ValidationErrors)
	errorMessages := make(map[string]string)
	for _, e := range validationErrors {
		errorMessages[e.StructField()] = fmt.Sprintf("Invalid value for %s, failed on '%s' validation", e.Field(), e.Tag())
	}
	return errorMessages
}

func NewAppValidator() *AppValidator {
	v := validator.New()
	_ = v.RegisterValidation("user_role", func(fl validator.FieldLevel) bool {
		return fl.Field().String() == string(RoleAdmin) || fl.Field().String() == string(RoleUser)
	})
	_ = v.RegisterValidation("post_status", func(fl validator.FieldLevel) bool {
		return fl.Field().String() == string(StatusDraft) || fl.Field().String() == string(StatusPublished)
	})
	return &AppValidator{Validator: v}
}

// --- file: services/user_service.go ---
type UserServiceProvider interface {
	CreateUser(req UserCreationRequest) (*User, error)
}

type MockUserService struct{}

func (s *MockUserService) CreateUser(req UserCreationRequest) (*User, error) {
	// Mock service logic: hashing password, creating user record
	return &User{
		ID:          uuid.New(),
		Email:       req.Email,
		PasswordHash: fmt.Sprintf("hashed(%s)", req.Password),
		Role:        req.Role,
		IsActive:    true,
		CreatedAt:   time.Now().UTC(),
	}, nil
}

// --- file: services/post_service.go ---
type PostServiceProvider interface {
	CreatePost(req PostCreationRequest) (*Post, error)
}

type MockPostService struct{}

func (s *MockPostService) CreatePost(req PostCreationRequest) (*Post, error) {
	return &Post{
		ID:      uuid.New(),
		UserID:  req.UserID,
		Title:   req.Title,
		Content: req.Content,
		Status:  req.Status,
	}, nil
}

// --- file: handlers/post_handler.go ---
type PostHandler struct {
	Service   PostServiceProvider
	Validator *AppValidator
}

func NewPostHandler(service PostServiceProvider, validator *AppValidator) *PostHandler {
	return &PostHandler{Service: service, Validator: validator}
}

func (h *PostHandler) CreatePostXML(c *fiber.Ctx) error {
	req := new(PostCreationRequest)
	if err := c.BodyParser(req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Cannot parse XML"})
	}

	if errs := h.Validator.Validate(req); errs != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"validation_errors": errs})
	}

	post, err := h.Service.CreatePost(*req)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"message": "Failed to create post"})
	}

	return c.Status(fiber.StatusCreated).XML(post)
}

// --- file: handlers/user_handler.go ---
type UserHandler struct {
	Service   UserServiceProvider
	Validator *AppValidator
}

func NewUserHandler(service UserServiceProvider, validator *AppValidator) *UserHandler {
	return &UserHandler{Service: service, Validator: validator}
}

func (h *UserHandler) CreateUserJSON(c *fiber.Ctx) error {
	req := new(UserCreationRequest)
	if err := c.BodyParser(req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"message": "Cannot parse JSON"})
	}

	if errs := h.Validator.Validate(req); errs != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"validation_errors": errs})
	}

	user, err := h.Service.CreateUser(*req)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"message": "Failed to create user"})
	}

	return c.Status(fiber.StatusCreated).JSON(user)
}

// --- file: main.go ---
func main() {
	// Dependencies
	appValidator := NewAppValidator()
	mockUserService := &MockUserService{}
	mockPostService := &MockPostService{}

	// Handlers
	userHandler := NewUserHandler(mockUserService, appValidator)
	postHandler := NewPostHandler(mockPostService, appValidator)

	// Fiber App
	app := fiber.New()

	// Routes
	app.Post("/users", userHandler.CreateUserJSON)
	app.Post("/posts", postHandler.CreatePostXML)

	fmt.Println("Layered Architecture Server listening on :3000")
	log.Fatal(app.Listen(":3000"))
}
</pre>