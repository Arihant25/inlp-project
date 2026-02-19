package main

import (
	"encoding/xml"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/gin-gonic/gin/binding"
	"github.com/go-playground/validator/v10"
	"github.com/google/uuid"
)

// --- Type Definitions & Models (Minimalist) ---
type (
	Role   string
	Status string
	User   struct {
		ID        uuid.UUID `json:"id"`
		Email     string    `json:"email"`
		PassHash  string    `json:"-"`
		Role      Role      `json:"role"`
		IsActive  bool      `json:"is_active"`
		CreatedAt time.Time `json:"created_at"`
	}
	Post struct {
		XMLName xml.Name  `xml:"Post"`
		ID      uuid.UUID `xml:"id"`
		UserID  uuid.UUID `xml:"user_id"`
		Title   string    `xml:"title"`
		Content string    `xml:"content"`
		Status  Status    `xml:"status"`
	}
	UserInput struct {
		Email    string `json:"email" form:"email" binding:"required,email"`
		Password string `json:"password" form:"password" binding:"required,min=10"`
		Phone    string `json:"phone" form:"phone" binding:"required,e164"`
		Role     Role   `json:"role" form:"role" binding:"required,valid_role"`
	}
	PostInput struct {
		XMLName xml.Name `xml:"PostInput"`
		UserID  string   `xml:"user_id" binding:"required,uuid"`
		Title   string   `xml:"title" binding:"required,max=120"`
		Content string   `xml:"content" binding:"required"`
		Status  Status   `xml:"status" binding:"required,valid_status"`
	}
)

const (
	ADMIN Role = "ADMIN"
	USER  Role = "USER"
)
const (
	DRAFT     Status = "DRAFT"
	PUBLISHED Status = "PUBLISHED"
)

// --- Main Application ---
func main() {
	r := gin.New()
	r.Use(gin.Logger(), gin.Recovery())

	// Register custom validators fluently
	if v, ok := binding.Validator.Engine().(*validator.Validate); ok {
		v.RegisterValidation("valid_role", func(fl validator.FieldLevel) bool {
			role := Role(fl.Field().String())
			return role == ADMIN || role == USER
		})
		v.RegisterValidation("valid_status", func(fl validator.FieldLevel) bool {
			status := Status(fl.Field().String())
			return status == DRAFT || status == PUBLISHED
		})
	}

	// --- Route Definitions (Fluent & Inline) ---
	api := r.Group("/api")
	{
		// POST /api/users (JSON)
		api.POST("/users", func(c *gin.Context) {
			var input UserInput
			if err := c.ShouldBindJSON(&input); err != nil {
				// Concise error formatting
				var errs []string
				for _, e := range err.(validator.ValidationErrors) {
					errs = append(errs, "field '"+e.Field()+"' failed on '"+e.Tag()+"' validation")
				}
				c.JSON(http.StatusBadRequest, gin.H{"errors": errs})
				return
			}

			user := User{
				ID:        uuid.New(),
				Email:     input.Email,
				PassHash:  "hashed:" + input.Password,
				Role:      input.Role,
				IsActive:  true,
				CreatedAt: time.Now(),
			}
			c.JSON(http.StatusCreated, user)
		})

		// POST /api/posts (XML)
		api.POST("/posts", func(c *gin.Context) {
			var input PostInput
			if err := c.ShouldBindXML(&input); err != nil {
				c.XML(http.StatusBadRequest, gin.H{"error": "Invalid XML format or data", "details": err.Error()})
				return
			}

			userID, _ := uuid.Parse(input.UserID)
			post := Post{
				ID:      uuid.New(),
				UserID:  userID,
				Title:   strings.TrimSpace(input.Title),
				Content: input.Content,
				Status:  input.Status,
			}
			c.XML(http.StatusCreated, post)
		})

		// GET /api/users/:id/posts (JSON)
		api.GET("/users/:id/posts", func(c *gin.Context) {
			uid, err := uuid.Parse(c.Param("id"))
			if err != nil {
				c.JSON(http.StatusBadRequest, gin.H{"error": "bad user id"})
				return
			}
			// Mock data for response serialization
			posts := []Post{
				{ID: uuid.New(), UserID: uid, Title: "Post A", Content: "...", Status: PUBLISHED},
				{ID: uuid.New(), UserID: uid, Title: "Post B", Content: "...", Status: DRAFT},
			}
			c.JSON(http.StatusOK, posts)
		})
	}

	// In a real app, we would run the server.
	// r.Run(":3000")
}