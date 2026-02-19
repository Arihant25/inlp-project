<pre>
package main

import (
	"encoding/xml"
	"net/http"
	"regexp"
	"time"

	"github.com/go-playground/validator/v10"
	"github.comcom/google/uuid"
	"github.com/labstack/echo/v4"
)

// --- Domain Models ---
type UserRole string
const (
	RoleAdmin UserRole = "ADMIN"
	RoleUser  UserRole = "USER"
)
type User struct {
	ID           uuid.UUID `json:"id"`
	Email        string    `json:"email"`
	PasswordHash string    `json:"-"`
	Role         UserRole  `json:"role"`
	IsActive     bool      `json:"is_active"`
	CreatedAt    time.Time `json:"created_at"`
}

type PostStatus string
const (
	StatusDraft     PostStatus = "DRAFT"
	StatusPublished PostStatus = "PUBLISHED"
)
type Post struct {
	XMLName xml.Name   `xml:"post"`
	ID      uuid.UUID  `json:"id" xml:"id"`
	UserID  uuid.UUID  `json:"user_id" xml:"user_id"`
	Title   string     `json:"title" xml:"title"`
	Content string     `json:"content" xml:"content"`
	Status  PostStatus `json:"status" xml:"status"`
}

// --- Payloads (DTOs) ---
type UserPayload struct {
	Email    string `json:"email" validate:"required,email"`
	Password string `json:"password" validate:"required,min=10"`
	Phone    string `json:"phone" validate:"required,e164phone"` // Custom validator
}

type PostPayload struct {
	AuthorID uuid.UUID `json:"author_id" validate:"required"`
	Title    string    `json:"title" validate:"required,max=120"`
	Content  string    `json:"content" validate:"required"`
}

// --- Validator Factory and Custom Rule ---
type Validator struct {
	instance *validator.Validate
}

func (v *Validator) Validate(i interface{}) error {
	return v.instance.Struct(i)
}

// e164phone validates E.164 phone number format
var e164PhoneRegex = regexp.MustCompile(`^\+[1-9]\d{1,14}$`)
func validateE164Phone(fl validator.FieldLevel) bool {
	return e164PhoneRegex.MatchString(fl.Field().String())
}

func NewValidator() *Validator {
	v := validator.New()
	v.RegisterValidation("e164phone", validateE164Phone)
	return &Validator{instance: v}
}

// --- Error Formatting Helper ---
func formatValidationErrors(err error) map[string]string {
	errors := make(map[string]string)
	if ve, ok := err.(validator.ValidationErrors); ok {
		for _, fe := range ve {
			var msg string
			switch fe.Tag() {
			case "required":
				msg = "This field is required"
			case "email":
				msg = "Invalid email format"
			case "e164phone":
				msg = "Phone number must be in E.164 format (e.g., +14155552671)"
			default:
				msg = "Invalid value"
			}
			errors[fe.Field()] = msg
		}
	}
	return errors
}

// --- Main Application with Inline Handlers ---
func main() {
	e := echo.New()
	e.Validator = NewValidator()

	// --- Routes with Anonymous Handlers ---
	e.POST("/v3/users", func(c echo.Context) error {
		payload := new(UserPayload)

		if err := c.Bind(payload); err != nil {
			return c.JSON(http.StatusUnprocessableEntity, map[string]string{"error": "Cannot parse request"})
		}

		if err := c.Validate(payload); err != nil {
			return c.JSON(http.StatusBadRequest, formatValidationErrors(err))
		}

		// Mock user creation
		user := &User{
			ID:        uuid.New(),
			Email:     payload.Email,
			Role:      RoleUser,
			IsActive:  true,
			CreatedAt: time.Now(),
		}
		return c.JSON(http.StatusCreated, user)
	})

	e.POST("/v3/posts", func(c echo.Context) error {
		payload := new(PostPayload)
		if err := c.Bind(payload); err != nil {
			return c.JSON(http.StatusUnprocessableEntity, map[string]string{"error": "Cannot parse request"})
		}
		if err := c.Validate(payload); err != nil {
			return c.JSON(http.StatusBadRequest, formatValidationErrors(err))
		}

		// Mock post creation
		post := &Post{
			ID:      uuid.New(),
			UserID:  payload.AuthorID, // Direct type assignment
			Title:   payload.Title,
			Content: payload.Content,
			Status:  StatusDraft,
		}
		return c.JSON(http.StatusCreated, post)
	})

	e.GET("/v3/posts/:id/xml", func(c echo.Context) error {
		// Mock data for XML serialization
		post := &Post{
			ID:      uuid.New(),
			UserID:  uuid.New(),
			Title:   "Minimalist XML Response",
			Content: "Content served as XML.",
			Status:  StatusPublished,
		}
		return c.XML(http.StatusOK, post)
	})

	e.Logger.Fatal(e.Start(":1325"))
}
</pre>