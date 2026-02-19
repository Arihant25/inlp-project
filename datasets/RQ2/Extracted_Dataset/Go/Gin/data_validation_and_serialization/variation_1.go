package main

import (
	"encoding/xml"
	"net/http"
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
	PasswordHash string    `json:"-"` // Omit from JSON responses
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

// --- Data Transfer Objects (DTOs) with Validation ---

type CreateUserRequest struct {
	Email    string   `json:"email" binding:"required,email"`
	Password string   `json:"password" binding:"required,min=8"`
	Phone    string   `json:"phone" binding:"required,e164"` // Example: +12125552368
	Role     UserRole `json:"role" binding:"required,is_valid_role"`
}

type CreatePostRequest struct {
	XMLName xml.Name   `xml:"CreatePostRequest"`
	UserID  uuid.UUID  `xml:"user_id" binding:"required"`
	Title   string     `xml:"title" binding:"required,min=5,max=100"`
	Content string     `xml:"content" binding:"required"`
	Status  PostStatus `xml:"status" binding:"required,is_valid_status"`
}

// --- Custom Validators ---

var isValidRole validator.Func = func(fl validator.FieldLevel) bool {
	role := UserRole(fl.Field().String())
	switch role {
	case RoleAdmin, RoleUser:
		return true
	}
	return false
}

var isValidStatus validator.Func = func(fl validator.FieldLevel) bool {
	status := PostStatus(fl.Field().String())
	switch status {
	case StatusDraft, StatusPublished:
		return true
	}
	return false
}

// --- Error Formatting Utility ---

func formatValidationErrors(err error) gin.H {
	errors := gin.H{}
	if validationErrs, ok := err.(validator.ValidationErrors); ok {
		for _, e := range validationErrs {
			errors[e.Field()] = "Validation failed on tag: " + e.Tag()
		}
	} else {
		errors["error"] = err.Error()
	}
	return gin.H{"errors": errors}
}

// --- API Handlers (Procedural Style) ---

func createUserHandler(c *gin.Context) {
	var req CreateUserRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, formatValidationErrors(err))
		return
	}

	// Type coercion/conversion happens implicitly during binding.
	// Now, create the domain model from the DTO.
	newUser := User{
		ID:           uuid.New(),
		Email:        req.Email,
		PasswordHash: "hashed_" + req.Password, // In a real app, hash this properly
		Role:         req.Role,
		IsActive:     true,
		CreatedAt:    time.Now().UTC(),
	}

	// JSON Serialization
	c.JSON(http.StatusCreated, newUser)
}

func createPostHandler(c *gin.Context) {
	var req CreatePostRequest
	// XML Deserialization
	if err := c.ShouldBindXML(&req); err != nil {
		c.XML(http.StatusBadRequest, formatValidationErrors(err))
		return
	}

	newPost := Post{
		ID:      uuid.New(),
		UserID:  req.UserID,
		Title:   req.Title,
		Content: req.Content,
		Status:  req.Status,
	}

	// XML Serialization
	c.XML(http.StatusCreated, newPost)
}

func getMockPostsForUserHandler(c *gin.Context) {
	userID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid user ID format"})
		return
	}

	// Mock data for demonstration
	mockPosts := []Post{
		{ID: uuid.New(), UserID: userID, Title: "First Post", Content: "Content of the first post.", Status: StatusPublished},
		{ID: uuid.New(), UserID: userID, Title: "Second Post", Content: "Content of the second post.", Status: StatusDraft},
	}

	c.JSON(http.StatusOK, mockPosts)
}

// --- Main Application Setup ---

func main() {
	router := gin.Default()

	// Register custom validators with Gin's default validator engine
	if v, ok := binding.Validator.Engine().(*validator.Validate); ok {
		v.RegisterValidation("is_valid_role", isValidRole)
		v.RegisterValidation("is_valid_status", isValidStatus)
	}

	// Route definitions
	userRoutes := router.Group("/users")
	{
		userRoutes.POST("", createUserHandler)
		userRoutes.GET("/:id/posts", getMockPostsForUserHandler)
	}

	postRoutes := router.Group("/posts")
	{
		postRoutes.POST("", createPostHandler)
	}

	// To run this example, you would typically use:
	// router.Run(":8080")
	// For this self-contained example, we won't start the server.
	// The code is complete and compilable.
}