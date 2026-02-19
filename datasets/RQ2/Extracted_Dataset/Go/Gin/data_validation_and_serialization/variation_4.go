package main

import (
	"context"
	"encoding/xml"
	"errors"
	"fmt"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/gin-gonic/gin/binding"
	"github.com/go-playground/validator/v10"
	"github.com/google/uuid"
)

// --- models/domain.go ---

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

// --- models/dto.go ---

type CreateUserRequestDTO struct {
	Email    string   `json:"email" binding:"required,email"`
	Password string   `json:"password" binding:"required,min=8,max=72"`
	Phone    string   `json:"phone" binding:"required,e164"`
	Role     UserRole `json:"role" binding:"required,user_role"`
}

type CreatePostRequestDTO struct {
	XMLName xml.Name   `xml:"CreatePostRequestDTO"`
	UserID  uuid.UUID  `xml:"user_id" binding:"required"`
	Title   string     `xml:"title" binding:"required,min=3"`
	Content string     `xml:"content" binding:"required"`
	Status  PostStatus `xml:"status" binding:"required,post_status"`
}

// --- services/user_service.go ---

type IUserService interface {
	CreateUser(ctx context.Context, dto CreateUserRequestDTO) (*User, error)
}

type UserServiceImpl struct {
	// In a real app, a repository/database connection would be here.
}

func NewUserService() IUserService {
	return &UserServiceImpl{}
}

func (s *UserServiceImpl) CreateUser(ctx context.Context, dto CreateUserRequestDTO) (*User, error) {
	// Business logic would go here. For now, just map DTO to domain model.
	user := &User{
		ID:           uuid.New(),
		Email:        dto.Email,
		PasswordHash: fmt.Sprintf("hashed(%s)", dto.Password),
		Role:         dto.Role,
		IsActive:     true,
		CreatedAt:    time.Now().UTC(),
	}
	return user, nil
}

// --- handlers/user_handler.go ---

type UserAPIHandler struct {
	userService IUserService
}

func NewUserAPIHandler(userService IUserService) *UserAPIHandler {
	return &UserAPIHandler{userService: userService}
}

func (h *UserAPIHandler) CreateUser(c *gin.Context) {
	var dto CreateUserRequestDTO
	if err := c.ShouldBindJSON(&dto); err != nil {
		// Error is handled by middleware
		c.Error(err)
		return
	}

	user, err := h.userService.CreateUser(c.Request.Context(), dto)
	if err != nil {
		c.Error(err)
		return
	}

	c.JSON(http.StatusCreated, user)
}

// --- handlers/post_handler.go ---

type PostAPIHandler struct {
	// In a real app, a PostService would be injected here.
}

func NewPostAPIHandler() *PostAPIHandler {
	return &PostAPIHandler{}
}

func (h *PostAPIHandler) CreatePost(c *gin.Context) {
	var dto CreatePostRequestDTO
	if err := c.ShouldBindXML(&dto); err != nil {
		c.Error(err)
		return
	}

	post := &Post{
		ID:      uuid.New(),
		UserID:  dto.UserID,
		Title:   dto.Title,
		Content: dto.Content,
		Status:  dto.Status,
	}

	c.XML(http.StatusCreated, post)
}

func (h *PostAPIHandler) GetUserPosts(c *gin.Context) {
	userID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.Error(errors.New("invalid user ID"))
		return
	}

	mockPosts := []Post{
		{ID: uuid.New(), UserID: userID, Title: "Enterprise Post 1", Content: "Content", Status: StatusPublished},
	}
	c.JSON(http.StatusOK, mockPosts)
}

// --- middleware/error_handler.go ---

func ErrorHandlerMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Next() // Process request

		if len(c.Errors) > 0 {
			err := c.Errors.Last().Err
			var validationErrs validator.ValidationErrors
			if errors.As(err, &validationErrs) {
				errMap := make(map[string]string)
				for _, e := range validationErrs {
					errMap[e.Field()] = fmt.Sprintf("Failed validation on tag '%s'", e.Tag())
				}
				c.JSON(http.StatusBadRequest, gin.H{"error": "Validation failed", "details": errMap})
				return
			}

			// Handle other generic errors
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		}
	}
}

// --- main.go ---

func main() {
	// 1. Setup Validator
	v := validator.New()
	v.RegisterValidation("user_role", func(fl validator.FieldLevel) bool {
		return fl.Field().String() == string(RoleAdmin) || fl.Field().String() == string(RoleUser)
	})
	v.RegisterValidation("post_status", func(fl validator.FieldLevel) bool {
		return fl.Field().String() == string(StatusDraft) || fl.Field().String() == string(StatusPublished)
	})
	binding.Validator = &defaultValidator{validate: v}

	// 2. Dependency Injection
	userService := NewUserService()
	userHandler := NewUserAPIHandler(userService)
	postHandler := NewPostAPIHandler()

	// 3. Setup Gin Router & Middleware
	router := gin.New()
	router.Use(gin.Recovery())
	router.Use(ErrorHandlerMiddleware())

	// 4. Register Routes
	basePath := router.Group("/api/v1")
	{
		users := basePath.Group("/users")
		{
			users.POST("/", userHandler.CreateUser)
			users.GET("/:id/posts", postHandler.GetUserPosts)
		}
		posts := basePath.Group("/posts")
		{
			posts.POST("/", postHandler.CreatePost)
		}
	}

	// Server would be started here in a real application.
	// router.Run(":8080")
}

// Custom validator struct to integrate with Gin
type defaultValidator struct {
	validate *validator.Validate
}

func (v *defaultValidator) ValidateStruct(obj interface{}) error {
	return v.validate.Struct(obj)
}

func (v *defaultValidator) Engine() interface{} {
	return v.validate
}