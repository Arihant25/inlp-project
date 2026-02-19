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
	PasswordHash string    `json:"-" xml:"-"`
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
	XMLName xml.Name   `xml:"Post"`
	ID      uuid.UUID  `json:"id" xml:"id"`
	UserID  uuid.UUID  `json:"user_id" xml:"user_id"`
	Title   string     `json:"title" xml:"title"`
	Content string     `json:"content" xml:"content"`
	Status  PostStatus `json:"status" xml:"status"`
}

// --- DTOs ---

type CreateUserDTO struct {
	Email    string `json:"email" validate:"required,email"`
	Password string `json:"password" validate:"required,min=8"`
	Role     string `json:"role" validate:"required,oneof=ADMIN USER"`
}

type CreatePostDTO struct {
	UserID  uuid.UUID  `json:"user_id" validate:"required"`
	Title   string     `json:"title" validate:"required,min=5"`
	Content string     `json:"content" validate:"required"`
	Status  PostStatus `json:"status" validate:"is-status"` // Custom validator
}

// --- Custom Validator Setup ---

type AppValidator struct {
	validator *validator.Validate
}

func (v *AppValidator) Validate(i interface{}) error {
	return v.validator.Struct(i)
}

func validatePostStatus(fl validator.FieldLevel) bool {
	status := PostStatus(fl.Field().String())
	switch status {
	case StatusDraft, StatusPublished:
		return true
	case "": // Allow empty status to be handled by 'required' tag if needed
		return true
	}
	return false
}

// --- Error Handling ---

type ErrorResponse struct {
	Message string            `json:"message"`
	Details map[string]string `json:"details,omitempty"`
}

func httpErrorHandler(err error, c echo.Context) {
	if c.Response().Committed {
		return
	}

	code := http.StatusInternalServerError
	resp := ErrorResponse{Message: "Internal Server Error"}

	if he, ok := err.(*echo.HTTPError); ok {
		code = he.Code
		resp.Message = he.Message.(string)
	} else if ve, ok := err.(validator.ValidationErrors); ok {
		code = http.StatusBadRequest
		resp.Message = "Input validation failed"
		details := make(map[string]string)
		for _, fe := range ve {
			details[fe.Field()] = "Failed on validation rule: " + fe.Tag()
		}
		resp.Details = details
	} else {
		c.Logger().Error(err)
	}

	if err := c.JSON(code, resp); err != nil {
		c.Logger().Error(err)
	}
}

// --- Handlers (OOP Style) ---

type UserHandler struct {
	// Dependencies like a database connection would go here
}

func NewUserHandler() *UserHandler {
	return &UserHandler{}
}

func (h *UserHandler) Create(c echo.Context) error {
	dto := new(CreateUserDTO)
	if err := c.Bind(dto); err != nil {
		return echo.NewHTTPError(http.StatusBadRequest, "Invalid request format")
	}
	if err := c.Validate(dto); err != nil {
		return err // Let the central error handler format it
	}

	user := &User{
		ID:           uuid.New(),
		Email:        dto.Email,
		PasswordHash: "some_secure_hash",
		Role:         UserRole(dto.Role), // Type conversion
		IsActive:     false,
		CreatedAt:    time.Now(),
	}

	return c.JSON(http.StatusCreated, user)
}

type PostHandler struct{}

func NewPostHandler() *PostHandler {
	return &PostHandler{}
}

func (h *PostHandler) GetAsXML(c echo.Context) error {
	// Mock data
	post := &Post{
		ID:      uuid.New(),
		UserID:  uuid.New(),
		Title:   "A Post Title for XML",
		Content: "This content is rendered as XML.",
		Status:  StatusPublished,
	}
	return c.XMLPretty(http.StatusOK, post, "  ")
}

// --- Main Application ---

func main() {
	e := echo.New()
	e.Use(middleware.Recover())

	// Setup custom validator
	v := validator.New()
	v.RegisterValidation("is-status", validatePostStatus)
	e.Validator = &AppValidator{validator: v}

	// Setup custom error handler
	e.HTTPErrorHandler = httpErrorHandler

	// --- Register Routes ---
	userHandler := NewUserHandler()
	postHandler := NewPostHandler()

	apiV2 := e.Group("/v2")
	apiV2.POST("/users", userHandler.Create)
	apiV2.GET("/posts/:id/xml", postHandler.GetAsXML)

	e.Logger.Fatal(e.Start(":1324"))
}
</pre>