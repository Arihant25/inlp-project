package main

import (
	"fmt"
	"log"
	"reflect"
	"strings"
	"sync"
	"time"

	"github.com/go-playground/validator/v10"
	"github.com/gofiber/fiber/v2"
	"github.com/gofiber/fiber/v2/middleware/logger"
	"github.com/google/uuid"
)

// --- Domain Models ---

type Role string

const (
	ADMIN Role = "ADMIN"
	USER  Role = "USER"
)

type User struct {
	ID           uuid.UUID `json:"id"`
	Email        string    `json:"email"`
	PasswordHash string    `json:"-"`
	Role         Role      `json:"role"`
	IsActive     bool      `json:"is_active"`
	CreatedAt    time.Time `json:"created_at"`
}

type Post struct {
	ID      uuid.UUID `json:"id"`
	UserID  uuid.UUID `json:"user_id"`
	Title   string    `json:"title"`
	Content string    `json:"content"`
	Status  string    `json:"status"` // DRAFT, PUBLISHED
}

// --- DTOs (Data Transfer Objects) with Validation ---

type CreateUserDTO struct {
	Email    string `json:"email" validate:"required,email"`
	Password string `json:"password" validate:"required,min=8"`
	Role     Role   `json:"role" validate:"required,oneof=ADMIN USER"`
}

type UpdateUserDTO struct {
	Email    *string `json:"email,omitempty" validate:"omitempty,email"`
	Role     *Role   `json:"role,omitempty" validate:"omitempty,oneof=ADMIN USER"`
	IsActive *bool   `json:"is_active,omitempty" validate:"omitempty"`
}

type UserQueryDTO struct {
	Limit    int    `query:"limit" validate:"omitempty,min=1,max=100"`
	Offset   int    `query:"offset" validate:"omitempty,min=0"`
	Role     Role   `query:"role" validate:"omitempty,oneof=ADMIN USER"`
	IsActive *bool  `query:"is_active" validate:"omitempty"`
}

// --- Custom Validator ---

type XValidator struct {
	validator *validator.Validate
}

func (v *XValidator) Validate(data interface{}) []string {
	var validationErrors []string
	errs := v.validator.Struct(data)
	if errs != nil {
		for _, err := range errs.(validator.ValidationErrors) {
			validationErrors = append(validationErrors, fmt.Sprintf(
				"Field '%s' failed on the '%s' tag",
				err.Field(),
				err.Tag(),
			))
		}
	}
	return validationErrors
}

func NewValidator() *XValidator {
	validate := validator.New()
	// Use JSON tag name for error messages
	validate.RegisterTagNameFunc(func(fld reflect.StructField) string {
		name := strings.SplitN(fld.Tag.Get("json"), ",", 2)[0]
		if name == "-" {
			return ""
		}
		return name
	})
	return &XValidator{validator: validate}
}

// --- Mock Data Store ---

var userDB = struct {
	sync.RWMutex
	data map[uuid.UUID]User
}{data: make(map[uuid.UUID]User)}

func init() {
	// Seed data
	id1, _ := uuid.NewRandom()
	id2, _ := uuid.NewRandom()
	userDB.data[id1] = User{ID: id1, Email: "admin@example.com", PasswordHash: "hash", Role: ADMIN, IsActive: true, CreatedAt: time.Now()}
	userDB.data[id2] = User{ID: id2, Email: "user@example.com", PasswordHash: "hash", Role: USER, IsActive: true, CreatedAt: time.Now()}
}

// --- Controller Layer ---

type UserController struct {
	Validator *XValidator
}

func NewUserController(v *XValidator) *UserController {
	return &UserController{Validator: v}
}

func (ctrl *UserController) Create(c *fiber.Ctx) error {
	var dto CreateUserDTO
	if err := c.BodyParser(&dto); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"status": "fail", "message": "Invalid JSON"})
	}

	if errs := ctrl.Validator.Validate(dto); len(errs) > 0 {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"status": "fail", "errors": errs})
	}

	user := User{
		ID:           uuid.New(),
		Email:        dto.Email,
		PasswordHash: fmt.Sprintf("hashed-%s", dto.Password),
		Role:         dto.Role,
		IsActive:     true,
		CreatedAt:    time.Now().UTC(),
	}

	userDB.Lock()
	defer userDB.Unlock()
	for _, u := range userDB.data {
		if u.Email == user.Email {
			return c.Status(fiber.StatusConflict).JSON(fiber.Map{"status": "fail", "message": "Email already exists"})
		}
	}
	userDB.data[user.ID] = user

	return c.Status(fiber.StatusCreated).JSON(fiber.Map{"status": "success", "data": user})
}

func (ctrl *UserController) GetAll(c *fiber.Ctx) error {
	query := new(UserQueryDTO)
	if err := c.QueryParser(query); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"status": "fail", "message": "Invalid query parameters"})
	}
	
	// Set defaults
	if query.Limit == 0 { query.Limit = 10 }

	if errs := ctrl.Validator.Validate(query); len(errs) > 0 {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"status": "fail", "errors": errs})
	}

	userDB.RLock()
	defer userDB.RUnlock()

	var results []User
	for _, user := range userDB.data {
		if (query.Role == "" || user.Role == query.Role) && (query.IsActive == nil || user.IsActive == *query.IsActive) {
			results = append(results, user)
		}
	}

	total := len(results)
	start := query.Offset
	end := query.Offset + query.Limit
	if start > total { start = total }
	if end > total { end = total }

	return c.JSON(fiber.Map{"status": "success", "total": total, "data": results[start:end]})
}

func (ctrl *UserController) GetByID(c *fiber.Ctx) error {
	id, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"status": "fail", "message": "Invalid UUID"})
	}

	userDB.RLock()
	defer userDB.RUnlock()
	user, ok := userDB.data[id]
	if !ok {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"status": "fail", "message": "User not found"})
	}

	return c.JSON(fiber.Map{"status": "success", "data": user})
}

func (ctrl *UserController) Update(c *fiber.Ctx) error { // Handles both PUT and PATCH
	id, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"status": "fail", "message": "Invalid UUID"})
	}

	var dto UpdateUserDTO
	if err := c.BodyParser(&dto); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"status": "fail", "message": "Invalid JSON"})
	}

	if errs := ctrl.Validator.Validate(dto); len(errs) > 0 {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"status": "fail", "errors": errs})
	}

	userDB.Lock()
	defer userDB.Unlock()
	user, ok := userDB.data[id]
	if !ok {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"status": "fail", "message": "User not found"})
	}

	if dto.Email != nil {
		user.Email = *dto.Email
	}
	if dto.Role != nil {
		user.Role = *dto.Role
	}
	if dto.IsActive != nil {
		user.IsActive = *dto.IsActive
	}
	userDB.data[id] = user

	return c.JSON(fiber.Map{"status": "success", "data": user})
}

func (ctrl *UserController) Delete(c *fiber.Ctx) error {
	id, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"status": "fail", "message": "Invalid UUID"})
	}

	userDB.Lock()
	defer userDB.Unlock()
	if _, ok := userDB.data[id]; !ok {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"status": "fail", "message": "User not found"})
	}
	delete(userDB.data, id)

	return c.SendStatus(fiber.StatusNoContent)
}

// --- Router Setup ---

func SetupUserRoutes(router fiber.Router, ctrl *UserController) {
	userGroup := router.Group("/users")
	userGroup.Post("/", ctrl.Create)
	userGroup.Get("/", ctrl.GetAll)
	userGroup.Get("/:id", ctrl.GetByID)
	userGroup.Put("/:id", ctrl.Update)
	userGroup.Patch("/:id", ctrl.Update)
	userGroup.Delete("/:id", ctrl.Delete)
}

// --- Main Application ---

func main() {
	app := fiber.New()
	app.Use(logger.New())

	// Initialize dependencies
	customValidator := NewValidator()
	userController := NewUserController(customValidator)

	// Setup routes
	apiV1 := app.Group("/api/v1")
	SetupUserRoutes(apiV1, userController)

	log.Println("Server starting on port 3000...")
	log.Fatal(app.Listen(":3000"))
}