package main

import (
	"encoding/csv"
	"fmt"
	"image/jpeg"
	"io"
	"log"
	"os"
	"path/filepath"
	"strconv"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/gofiber/fiber/v2/middleware/logger"
	"github.com/google/uuid"
	"github.com/nfnt/resize"
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
	ID      uuid.UUID  `json:"id"`
	UserID  uuid.UUID  `json:"user_id"`
	Title   string     `json:"title"`
	Content string     `json:"content"`
	Status  PostStatus `json:"status"`
}

// --- Mock Database ---
var mockUsers = make(map[uuid.UUID]User)
var mockPosts = make(map[uuid.UUID]Post)

func seedData() {
	adminID := uuid.New()
	mockUsers[adminID] = User{
		ID:           adminID,
		Email:        "admin@example.com",
		PasswordHash: "hashed_password",
		Role:         RoleAdmin,
		IsActive:     true,
		CreatedAt:    time.Now(),
	}
	postID := uuid.New()
	mockPosts[postID] = Post{
		ID:      postID,
		UserID:  adminID,
		Title:   "First Post",
		Content: "This is the content of the first post.",
		Status:  StatusPublished,
	}
}

// --- Functional Handlers ---

func handleUserCSVUpload(c *fiber.Ctx) error {
	fileHeader, err := c.FormFile("users_csv")
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "CSV file is required"})
	}

	if filepath.Ext(fileHeader.Filename) != ".csv" {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "Only .csv files are allowed"})
	}

	file, err := fileHeader.Open()
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": "Failed to open file"})
	}
	defer file.Close()

	// Use a temporary file for processing
	tempFile, err := os.CreateTemp("", "upload-*.csv")
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": "Failed to create temp file"})
	}
	defer os.Remove(tempFile.Name()) // Ensure cleanup

	if _, err := io.Copy(tempFile, file); err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": "Failed to save to temp file"})
	}
	tempFile.Seek(0, 0) // Rewind to the beginning of the file

	reader := csv.NewReader(tempFile)
	records, err := reader.ReadAll()
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "Failed to parse CSV"})
	}

	if len(records) < 2 {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "CSV must have a header and at least one data row"})
	}

	var createdUsers []User
	// Skip header row (records[0])
	for _, record := range records[1:] {
		isActive, _ := strconv.ParseBool(record[3])
		newUser := User{
			ID:           uuid.New(),
			Email:        record[0],
			PasswordHash: record[1],
			Role:         UserRole(record[2]),
			IsActive:     isActive,
			CreatedAt:    time.Now(),
		}
		mockUsers[newUser.ID] = newUser
		createdUsers = append(createdUsers, newUser)
	}

	return c.Status(fiber.StatusCreated).JSON(fiber.Map{
		"message":      fmt.Sprintf("Successfully processed %d users", len(createdUsers)),
		"createdUsers": createdUsers,
	})
}

func handlePostImageUpload(c *fiber.Ctx) error {
	postID, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "Invalid post ID"})
	}

	if _, ok := mockPosts[postID]; !ok {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": "Post not found"})
	}

	fileHeader, err := c.FormFile("cover_image")
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "Image file is required"})
	}

	file, err := fileHeader.Open()
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": "Failed to open image file"})
	}
	defer file.Close()

	img, err := jpeg.Decode(file)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "Invalid image format, only JPEG is supported"})
	}

	// Resize image to a thumbnail size (e.g., 300x0, preserving aspect ratio)
	resizedImg := resize.Resize(300, 0, img, resize.Lanczos3)

	// Save to a temporary file
	tempFile, err := os.CreateTemp("", "thumbnail-*.jpg")
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": "Failed to create temp file for image"})
	}
	defer os.Remove(tempFile.Name()) // Important: clean up the temp file

	if err := jpeg.Encode(tempFile, resizedImg, nil); err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": "Failed to encode resized image"})
	}

	// In a real app, you would move this file to permanent storage (e.g., S3)
	// and save the path/URL in the Post model.
	log.Printf("Resized image for post %s saved to temporary file: %s", postID, tempFile.Name())

	return c.JSON(fiber.Map{
		"message":      "Image uploaded and resized successfully",
		"postId":       postID,
		"tempFilePath": tempFile.Name(),
	})
}

func handlePostsReportDownload(c *fiber.Ctx) error {
	c.Set("Content-Type", "text/csv")
	c.Set("Content-Disposition", `attachment; filename="posts_report.csv"`)

	// Use Fiber's streaming capabilities to avoid loading the whole file into memory
	return c.SendStream(func(w io.Writer) error {
		csvWriter := csv.NewWriter(w)
		
		// Write header
		header := []string{"id", "user_id", "title", "status"}
		if err := csvWriter.Write(header); err != nil {
			return err
		}

		// Write data rows
		for _, post := range mockPosts {
			record := []string{
				post.ID.String(),
				post.UserID.String(),
				post.Title,
				string(post.Status),
			}
			if err := csvWriter.Write(record); err != nil {
				return err
			}
		}

		csvWriter.Flush()
		return csvWriter.Error()
	})
}

func main() {
	seedData()

	app := fiber.New()
	app.Use(logger.New())

	api := app.Group("/api")
	
	// File Upload Routes
	api.Post("/users/upload-csv", handleUserCSVUpload)
	api.Post("/posts/:id/upload-image", handlePostImageUpload)

	// File Download Route
	api.Get("/posts/report", handlePostsReportDownload)

	log.Fatal(app.Listen(":3000"))
}