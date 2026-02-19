package main

import (
	"fmt"
	"log"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

// --- Domain Models ---

type Role struct {
	ID   uuid.UUID `gorm:"type:uuid;primary_key;"`
	Name string    `gorm:"uniqueIndex;not null"`
}

type User struct {
	ID           uuid.UUID `gorm:"type:uuid;primary_key;"`
	Email        string    `gorm:"uniqueIndex;not null"`
	PasswordHash string    `gorm:"not null"`
	IsActive     bool      `gorm:"default:true"`
	CreatedAt    time.Time `gorm:"autoCreateTime"`
	Posts        []Post    `gorm:"foreignKey:UserID"`
	Roles        []*Role   `gorm:"many2many:user_roles;"`
}

type PostStatus string

const (
	Draft     PostStatus = "DRAFT"
	Published PostStatus = "PUBLISHED"
)

type Post struct {
	ID      uuid.UUID  `gorm:"type:uuid;primary_key;"`
	UserID  uuid.UUID  `gorm:"type:uuid;not null"`
	Title   string     `gorm:"not null"`
	Content string     `gorm:"type:text"`
	Status  PostStatus `gorm:"type:varchar(20);default:'DRAFT'"`
}

// --- GORM Hooks to generate UUIDs ---

func (u *User) BeforeCreate(tx *gorm.DB) (err error) {
	u.ID = uuid.New()
	return
}

func (p *Post) BeforeCreate(tx *gorm.DB) (err error) {
	p.ID = uuid.New()
	return
}

func (r *Role) BeforeCreate(tx *gorm.DB) (err error) {
	r.ID = uuid.New()
	return
}

// --- Database Setup ---

func setupDatabase() *gorm.DB {
	db, err := gorm.Open(sqlite.Open("file::memory:?cache=shared"), &gorm.Config{
		Logger: logger.Default.LogMode(logger.Silent),
	})
	if err != nil {
		log.Fatalf("Failed to connect to database: %v", err)
	}

	// Migrations
	err = db.AutoMigrate(&User{}, &Post{}, &Role{})
	if err != nil {
		log.Fatalf("Failed to migrate database: %v", err)
	}

	// Seed Roles
	roles := []Role{{Name: "ADMIN"}, {Name: "USER"}}
	if db.First(&Role{}).RowsAffected == 0 {
		db.Create(&roles)
	}

	return db
}

// --- Main Application ---

func main() {
	db := setupDatabase()
	app := fiber.New()

	// --- Routes ---
	app.Post("/users", createUser(db))
	app.Get("/users", findUsers(db))
	app.Get("/users/:id", findUserByID(db))
	app.Put("/users/:id", updateUser(db))
	app.Delete("/users/:id", deleteUser(db))

	// Transactional Route
	app.Post("/users/onboarding", onboardUserWithPost(db))

	// In a real app, you would run:
	// log.Fatal(app.Listen(":3000"))
	fmt.Println("Server routes are configured. This is a runnable example.")
}

// --- Handlers (Functional Style) ---

// createUser handles creating a new user.
func createUser(db *gorm.DB) fiber.Handler {
	return func(c *fiber.Ctx) error {
		req := new(struct {
			Email    string   `json:"email"`
			Password string   `json:"password"`
			RoleIDs  []string `json:"role_ids"`
		})

		if err := c.BodyParser(req); err != nil {
			return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "cannot parse JSON"})
		}

		var roles []*Role
		if len(req.RoleIDs) > 0 {
			if err := db.Where("id IN ?", req.RoleIDs).Find(&roles).Error; err != nil {
				return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": "failed to find roles"})
			}
		}

		user := User{
			Email:        req.Email,
			PasswordHash: "hashed_" + req.Password, // In production, use bcrypt
			Roles:        roles,
		}

		if err := db.Create(&user).Error; err != nil {
			return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": "could not create user"})
		}

		return c.Status(fiber.StatusCreated).JSON(user)
	}
}

// findUsers handles querying users with filters.
func findUsers(db *gorm.DB) fiber.Handler {
	return func(c *fiber.Ctx) error {
		var users []User
		query := db.Model(&User{})

		// Filtering
		if isActive := c.Query("is_active"); isActive != "" {
			query = query.Where("is_active = ?", isActive == "true")
		}

		if err := query.Preload("Roles").Preload("Posts").Find(&users).Error; err != nil {
			return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": "could not fetch users"})
		}

		return c.JSON(users)
	}
}

// findUserByID retrieves a single user with their relations.
func findUserByID(db *gorm.DB) fiber.Handler {
	return func(c *fiber.Ctx) error {
		id := c.Params("id")
		var user User

		if err := db.Preload("Posts").Preload("Roles").First(&user, "id = ?", id).Error; err != nil {
			if err == gorm.ErrRecordNotFound {
				return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": "user not found"})
			}
			return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": "database error"})
		}

		return c.JSON(user)
	}
}

// updateUser handles updating a user's details.
func updateUser(db *gorm.DB) fiber.Handler {
	return func(c *fiber.Ctx) error {
		id := c.Params("id")
		var user User
		if err := db.First(&user, "id = ?", id).Error; err != nil {
			return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": "user not found"})
		}

		req := new(struct {
			Email    string `json:"email"`
			IsActive *bool  `json:"is_active"`
		})
		if err := c.BodyParser(req); err != nil {
			return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "cannot parse JSON"})
		}

		if req.Email != "" {
			user.Email = req.Email
		}
		if req.IsActive != nil {
			user.IsActive = *req.IsActive
		}

		db.Save(&user)
		return c.JSON(user)
	}
}

// deleteUser handles deleting a user.
func deleteUser(db *gorm.DB) fiber.Handler {
	return func(c *fiber.Ctx) error {
		id := c.Params("id")
		result := db.Delete(&User{}, "id = ?", id)

		if result.Error != nil {
			return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": result.Error.Error()})
		}
		if result.RowsAffected == 0 {
			return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": "user not found"})
		}

		return c.SendStatus(fiber.StatusNoContent)
	}
}

// onboardUserWithPost demonstrates a transaction with rollback.
func onboardUserWithPost(db *gorm.DB) fiber.Handler {
	return func(c *fiber.Ctx) error {
		req := new(struct {
			Email    string `json:"email"`
			Password string `json:"password"`
		})
		if err := c.BodyParser(req); err != nil {
			return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "cannot parse JSON"})
		}

		var user User
		err := db.Transaction(func(tx *gorm.DB) error {
			// 1. Create the user
			newUser := User{
				Email:        req.Email,
				PasswordHash: "hashed_" + req.Password,
			}
			if err := tx.Create(&newUser).Error; err != nil {
				return err // Rollback
			}

			// 2. Assign a default "USER" role
			var userRole Role
			if err := tx.Where("name = ?", "USER").First(&userRole).Error; err != nil {
				return err // Rollback
			}
			if err := tx.Model(&newUser).Association("Roles").Append(&userRole); err != nil {
				return err // Rollback
			}

			// 3. Create a welcome post
			welcomePost := Post{
				UserID:  newUser.ID,
				Title:   "Welcome!",
				Content: "This is your first post.",
				Status:  Published,
			}
			if err := tx.Create(&welcomePost).Error; err != nil {
				return err // Rollback
			}

			user = newUser // Assign to outer scope for response
			return nil      // Commit
		})

		if err != nil {
			return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": "transaction failed: " + err.Error()})
		}

		// Reload to get associations
		db.Preload("Posts").Preload("Roles").First(&user, "id = ?", user.ID)
		return c.Status(fiber.StatusCreated).JSON(user)
	}
}