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

// --- Enums & Models ---
type Role string
const (
	ADMIN Role = "ADMIN"
	USER  Role = "USER"
)

type Status string
const (
	DRAFT     Status = "DRAFT"
	PUBLISHED Status = "PUBLISHED"
)

type User struct {
	ID        uuid.UUID `json:"id"`
	Email     string    `json:"email"`
	PswdHash  string    `json:"-"`
	Role      Role      `json:"role"`
	IsActive  bool      `json:"is_active"`
	CreatedAt time.Time `json:"created_at"`
}

type Post struct {
	XMLName xml.Name  `json:"-" xml:"Post"`
	ID      uuid.UUID `json:"id" xml:"ID"`
	UserID  uuid.UUID `json:"user_id" xml:"UserID"`
	Title   string    `json:"title" xml:"Title"`
	Content string    `json:"content" xml:"Content"`
	Status  Status    `json:"status" xml:"Status"`
}

// --- DTOs ---
type UserReq struct {
	Email    string `json:"email" validate:"required,email"`
	Password string `json:"password" validate:"required,min=10"`
	Phone    string `json:"phone" validate:"required,e164"`
	Role     Role   `json:"role" validate:"required,role"`
}

type PostReq struct {
	XMLName xml.Name  `xml:"PostReq"`
	UserID  uuid.UUID `xml:"UserID" validate:"required"`
	Title   string    `xml:"Title" validate:"required,min=1"`
	Content string    `xml:"Content" validate:"required"`
	Status  Status    `xml:"Status" validate:"required,status"`
}

// --- Global Validator & Helpers ---
var validate = validator.New()

type ValidationError struct {
	Param string `json:"param"`
	Msg   string `json:"msg"`
}

func formatErrors(err error) []ValidationError {
	var errors []ValidationError
	for _, err := range err.(validator.ValidationErrors) {
		errors = append(errors, ValidationError{
			Param: err.Field(),
			Msg:   fmt.Sprintf("Failed on tag: %s", err.Tag()),
		})
	}
	return errors
}

func main() {
	// --- Validator Setup (Fluent/Closure Style) ---
	_ = validate.RegisterValidation("role", func(fl validator.FieldLevel) bool {
		r := Role(fl.Field().String())
		return r == ADMIN || r == USER
	})
	_ = validate.RegisterValidation("status", func(fl validator.FieldLevel) bool {
		s := Status(fl.Field().String())
		return s == DRAFT || s == PUBLISHED
	})

	app := fiber.New()

	// --- Route Definitions (Minimalist & Inline) ---
	app.Post("/user/json", func(c *fiber.Ctx) error {
		req := new(UserReq)
		if err := c.BodyParser(req); err != nil {
			return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "bad request"})
		}

		if err := validate.Struct(req); err != nil {
			return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"errors": formatErrors(err)})
		}

		// Coercion and creation
		usr := User{
			ID:        uuid.New(),
			Email:     req.Email,
			PswdHash:  "some-hash",
			Role:      req.Role,
			IsActive:  true,
			CreatedAt: time.Now(),
		}
		return c.Status(fiber.StatusCreated).JSON(usr)
	})

	app.Post("/post/xml", func(c *fiber.Ctx) error {
		req := new(PostReq)
		// Fiber's BodyParser handles XML parsing from request body
		if err := c.BodyParser(req); err != nil {
			return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "bad xml request"})
		}

		if err := validate.Struct(req); err != nil {
			return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"errors": formatErrors(err)})
		}

		// Create domain model from DTO
		pst := Post{
			ID:      uuid.New(),
			UserID:  req.UserID,
			Title:   req.Title,
			Content: req.Content,
			Status:  req.Status,
		}
		// Generate XML response
		return c.Status(fiber.StatusCreated).XML(pst)
	})

	app.Get("/post/:id/xml", func(c *fiber.Ctx) error {
		id, err := uuid.Parse(c.Params("id"))
		if err != nil {
			return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "invalid id"})
		}

		// Mock data for XML generation
		mockPost := Post{
			ID:      id,
			UserID:  uuid.New(),
			Title:   "An XML Adventure",
			Content: "This content is serialized to XML.",
			Status:  PUBLISHED,
		}
		return c.XML(mockPost)
	})

	fmt.Println("Minimalist Server is up on port 3000")
	log.Fatal(app.Listen(":3000"))
}
</pre>