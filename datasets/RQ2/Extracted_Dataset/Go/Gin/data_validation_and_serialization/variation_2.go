package main

import (
	"encoding/xml"
	"errors"
	"net/http"
	"reflect"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/gin-gonic/gin/binding"
	"github.com/go-playground/validator/v10"
	"github.com/google/uuid"
)

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
	ID           uuid.UUID `json:"id"`
	Email        string    `json:"email"`
	PasswordHash string    `json:"-"`
	Role         UserRole  `json:"role"`
	IsActive     bool      `json:"is_active"`
	CreatedAt    time.Time `json:"created_at"`
}

type Post struct {
	XMLName xml.Name   `xml:"Post"`
	ID      uuid.UUID  `xml:"id"`
	UserID  uuid.UUID  `xml:"user_id"`
	Title   string     `xml:"title"`
	Content string     `xml:"content"`
	Status  PostStatus `xml:"status"`
}

// --- Data Transfer Objects (DTOs) ---

type UserCreationDTO struct {
	Email    string   `json:"email" binding:"required,email" validate:"required,email"`
	Password string   `json:"password" binding:"required,min=8" validate:"required,min=8"`
	Phone    string   `json:"phone" binding:"required,e164" validate:"required,e164"`
	Role     UserRole `json:"role" binding:"required,role_check" validate:"required,role_check"`
}

type PostCreationDTO struct {
	XMLName xml.Name   `xml:"PostCreationDTO"`
	UserID  string     `xml:"user_id" binding:"required,uuid4" validate:"required,uuid4"`
	Title   string     `xml:"title" binding:"required,min=5" validate:"required,min=5"`
	Content string     `xml:"content" binding:"required" validate:"required"`
	Status  PostStatus `xml:"status" binding:"required,status_check" validate:"required,status_check"`
}

// --- Structured Error Response ---

type APIError struct {
	Code    int               `json:"code"`
	Message string            `json:"message"`
	Details map[string]string `json:"details,omitempty"`
}

func NewAPIError(code int, message string, err error) *APIError {
	apiErr := &APIError{Code: code, Message: message, Details: make(map[string]string)}
	var validationErrs validator.ValidationErrors
	if errors.As(err, &validationErrs) {
		for _, fieldErr := range validationErrs {
			fieldName := fieldErr.Field()
			// Try to get the json tag name for a more user-friendly field name
			// This is a simplified example of field name mapping
			if t, ok := reflect.TypeOf(&UserCreationDTO{}).Elem().FieldByName(fieldName); ok {
				jsonTag := t.Tag.Get("json")
				if jsonTag != "" {
					fieldName = strings.Split(jsonTag, ",")[0]
				}
			}
			apiErr.Details[fieldName] = "Failed on validation rule: " + fieldErr.Tag()
		}
	}
	return apiErr
}

// --- Handlers (OOP Style) ---

type UserHandler struct {
	// Dependencies like a database service would go here
}

func NewUserHandler() *UserHandler {
	return &UserHandler{}
}

func (h *UserHandler) Create(c *gin.Context) {
	var dto UserCreationDTO
	if err := c.ShouldBindJSON(&dto); err != nil {
		apiErr := NewAPIError(http.StatusBadRequest, "Invalid user data provided", err)
		c.JSON(apiErr.Code, apiErr)
		return
	}

	user := &User{
		ID:           uuid.New(),
		Email:        dto.Email,
		PasswordHash: "super_secret_hash_for_" + dto.Password,
		Role:         dto.Role,
		IsActive:     false,
		CreatedAt:    time.Now().UTC(),
	}

	c.JSON(http.StatusCreated, user)
}

type PostHandler struct{}

func NewPostHandler() *PostHandler {
	return &PostHandler{}
}

func (h *PostHandler) Create(c *gin.Context) {
	var dto PostCreationDTO
	if err := c.ShouldBindXML(&dto); err != nil {
		apiErr := NewAPIError(http.StatusBadRequest, "Invalid post data provided", err)
		// Note: Gin's c.XML doesn't work well with complex structs like APIError.
		// For consistency, we'll respond with JSON even for XML input errors.
		c.JSON(apiErr.Code, apiErr)
		return
	}

	userID, _ := uuid.Parse(dto.UserID) // Error ignored due to 'uuid4' validation
	post := &Post{
		ID:      uuid.New(),
		UserID:  userID,
		Title:   dto.Title,
		Content: dto.Content,
		Status:  dto.Status,
	}

	c.XML(http.StatusCreated, post)
}

func (h *PostHandler) ListByUser(c *gin.Context) {
	userIDStr := c.Param("userId")
	userID, err := uuid.Parse(userIDStr)
	if err != nil {
		c.JSON(http.StatusBadRequest, APIError{Code: http.StatusBadRequest, Message: "Invalid user ID"})
		return
	}

	mockPosts := []Post{
		{ID: uuid.New(), UserID: userID, Title: "OOP Post 1", Content: "Content here.", Status: StatusPublished},
		{ID: uuid.New(), UserID: userID, Title: "OOP Post 2", Content: "More content.", Status: StatusDraft},
	}

	c.JSON(http.StatusOK, mockPosts)
}

// --- Custom Validator Registration ---

func registerCustomValidations() {
	if v, ok := binding.Validator.Engine().(*validator.Validate); ok {
		v.RegisterValidation("role_check", func(fl validator.FieldLevel) bool {
			return fl.Field().String() == string(RoleAdmin) || fl.Field().String() == string(RoleUser)
		})
		v.RegisterValidation("status_check", func(fl validator.FieldLevel) bool {
			return fl.Field().String() == string(StatusDraft) || fl.Field().String() == string(StatusPublished)
		})
	}
}

// --- Main Application Setup ---

func main() {
	registerCustomValidations()

	router := gin.Default()

	userHandler := NewUserHandler()
	postHandler := NewPostHandler()

	v1 := router.Group("/v1")
	{
		userGroup := v1.Group("/users")
		{
			userGroup.POST("", userHandler.Create)
			userGroup.GET("/:userId/posts", postHandler.ListByUser)
		}
		postGroup := v1.Group("/posts")
		{
			postGroup.POST("", postHandler.Create)
		}
	}

	// Server would be started here in a real application.
	// router.Run()
}