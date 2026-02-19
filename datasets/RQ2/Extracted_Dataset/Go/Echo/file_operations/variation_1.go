package main

import (
	"encoding/csv"
	"fmt"
	"image/jpeg"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"time"

	"github.com/google/uuid"
	"github.com/labstack/echo/v4"
	"github.com/labstack/echo/v4/middleware"
	"github.com/nfnt/resize"
)

// To run this code, you need the following dependencies:
// go get github.com/labstack/echo/v4
// go get github.com/google/uuid
// go get github.com/nfnt/resize

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

type Status string

const (
	DRAFT     Status = "DRAFT"
	PUBLISHED Status = "PUBLISHED"
)

type Post struct {
	ID      uuid.UUID `json:"id"`
	UserID  uuid.UUID `json:"user_id"`
	Title   string    `json:"title"`
	Content string    `json:"content"`
	Status  Status    `json:"status"`
}

// Mock database
var mockUsers = make(map[uuid.UUID]User)
var mockPosts = make(map[uuid.UUID]Post)

func init() {
	// Pre-seed a post for image upload testing
	postID := uuid.New()
	mockPosts[postID] = Post{
		ID:      postID,
		UserID:  uuid.New(),
		Title:   "Sample Post",
		Content: "Content for image upload.",
		Status:  PUBLISHED,
	}
}

// --- Functional Handlers ---

// handleUploadUsersCSV handles bulk user creation from a CSV file.
func handleUploadUsersCSV(c echo.Context) error {
	file, err := c.FormFile("users_csv")
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "Missing 'users_csv' file in form"})
	}

	src, err := file.Open()
	if err != nil {
		return echo.NewHTTPError(http.StatusInternalServerError, "Failed to open uploaded file")
	}
	defer src.Close()

	reader := csv.NewReader(src)
	records, err := reader.ReadAll()
	if err != nil {
		return echo.NewHTTPError(http.StatusBadRequest, "Failed to parse CSV file")
	}

	// Skip header row
	if len(records) < 2 {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "CSV file must have a header and at least one data row"})
	}

	var createdUsers []User
	for _, record := range records[1:] {
		isActive, _ := strconv.ParseBool(record[2])
		user := User{
			ID:           uuid.New(),
			Email:        record[0],
			PasswordHash: "mock_hash_for_" + record[1],
			Role:         Role(record[3]),
			IsActive:     isActive,
			CreatedAt:    time.Now(),
		}
		mockUsers[user.ID] = user
		createdUsers = append(createdUsers, user)
	}

	return c.JSON(http.StatusCreated, map[string]interface{}{
		"message":      fmt.Sprintf("Successfully processed %d users.", len(createdUsers)),
		"createdCount": len(createdUsers),
	})
}

// handleUploadPostImage handles resizing and saving a post's banner image.
func handleUploadPostImage(c echo.Context) error {
	postID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "Invalid post ID format"})
	}

	if _, ok := mockPosts[postID]; !ok {
		return c.JSON(http.StatusNotFound, map[string]string{"error": "Post not found"})
	}

	file, err := c.FormFile("image")
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "Missing 'image' file in form"})
	}

	src, err := file.Open()
	if err != nil {
		return echo.NewHTTPError(http.StatusInternalServerError, "Failed to open image file")
	}
	defer src.Close()

	img, err := jpeg.Decode(src)
	if err != nil {
		return echo.NewHTTPError(http.StatusBadRequest, "Invalid image format; only JPEG is supported")
	}

	// Resize image to a width of 800px, maintaining aspect ratio
	resizedImg := resize.Resize(800, 0, img, resize.Lanczos3)

	// Use a temporary file for processing
	tempFile, err := os.CreateTemp("", "resized-*.jpg")
	if err != nil {
		return echo.NewHTTPError(http.StatusInternalServerError, "Failed to create temporary file")
	}
	defer os.Remove(tempFile.Name()) // Ensure cleanup

	if err := jpeg.Encode(tempFile, resizedImg, nil); err != nil {
		return echo.NewHTTPError(http.StatusInternalServerError, "Failed to save resized image")
	}

	// In a real app, you'd move this file to permanent storage (e.g., S3)
	log.Printf("Resized image for post %s saved to temporary file: %s", postID, tempFile.Name())

	return c.JSON(http.StatusOK, map[string]string{
		"message":  "Image uploaded and resized successfully.",
		"post_id":  postID.String(),
		"tempPath": tempFile.Name(),
	})
}

// handleDownloadUserReport streams a CSV report of all users.
func handleDownloadUserReport(c echo.Context) error {
	c.Response().Header().Set(echo.HeaderContentType, "text/csv")
	c.Response().Header().Set(echo.HeaderContentDisposition, "attachment; filename=\"user_report.csv\"")

	// Use a temporary file to build the CSV
	tempFile, err := os.CreateTemp("", "report-*.csv")
	if err != nil {
		return echo.NewHTTPError(http.StatusInternalServerError, "Failed to create temp report file")
	}
	defer os.Remove(tempFile.Name())

	writer := csv.NewWriter(tempFile)
	headers := []string{"id", "email", "role", "is_active", "created_at"}
	if err := writer.Write(headers); err != nil {
		return err
	}

	for _, user := range mockUsers {
		record := []string{
			user.ID.String(),
			user.Email,
			string(user.Role),
			strconv.FormatBool(user.IsActive),
			user.CreatedAt.Format(time.RFC3339),
		}
		if err := writer.Write(record); err != nil {
			return err
		}
	}
	writer.Flush()
	tempFile.Close()

	// Stream the temporary file's content
	return c.File(tempFile.Name())
}

func main() {
	e := echo.New()
	e.Use(middleware.Logger())
	e.Use(middleware.Recover())

	// --- Routes ---
	api := e.Group("/api/v1")

	// File Uploads
	api.POST("/users/import/csv", handleUploadUsersCSV)
	api.POST("/posts/:id/image", handleUploadPostImage)

	// File Downloads
	api.GET("/reports/users", handleDownloadUserReport)

	// A simple health check
	e.GET("/health", func(c echo.Context) error {
		return c.String(http.StatusOK, "OK")
	})

	log.Println("Server starting on :8080...")
	log.Printf("Sample post ID for image upload: %s", getFirstPostID())
	e.Logger.Fatal(e.Start(":8080"))
}

func getFirstPostID() string {
	for id := range mockPosts {
		return id.String()
	}
	return "none"
}