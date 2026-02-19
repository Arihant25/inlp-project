package main

import (
	"encoding/csv"
	"fmt"
	"image"
	"image/jpeg"
	_ "image/png" // register PNG decoder
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/nfnt/resize"
	"github.com/xuri/excelize/v2"
)

// --- Domain Schema ---

type UserRole string

const (
	AdminRole UserRole = "ADMIN"
	UserRole  UserRole = "USER"
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
	DraftStatus     PostStatus = "DRAFT"
	PublishedStatus PostStatus = "PUBLISHED"
)

type Post struct {
	ID      uuid.UUID  `json:"id"`
	UserID  uuid.UUID  `json:"user_id"`
	Title   string     `json:"title"`
	Content string     `json:"content"`
	Status  PostStatus `json:"status"`
}

// --- Mock Data ---

var mockPosts = []Post{
	{ID: uuid.New(), UserID: uuid.New(), Title: "Gin is Great", Content: "Content of the first post.", Status: PublishedStatus},
	{ID: uuid.New(), UserID: uuid.New(), Title: "File Operations in Go", Content: "Streaming downloads are efficient.", Status: PublishedStatus},
	{ID: uuid.New(), UserID: uuid.New(), Title: "A Draft Post", Content: "This is not yet published.", Status: DraftStatus},
}

// --- Main Application ---

func main() {
	// In a real app, you'd use gin.ReleaseMode
	gin.SetMode(gin.DebugMode)

	router := gin.Default()
	// Set a lower memory limit for multipart forms (default is 32 MiB)
	router.MaxMultipartMemory = 8 << 20 // 8 MiB

	// --- Routes ---
	api := router.Group("/api/v1")
	{
		// POST /api/v1/users/import - Upload a CSV or XLSX file to bulk-create users
		api.POST("/users/import", handleUserImport)

		// POST /api/v1/posts/:id/image - Upload and resize a cover image for a post
		api.POST("/posts/:id/image", handlePostImageUpload)

		// GET /api/v1/posts/export - Download a CSV report of all posts
		api.GET("/posts/export", handlePostsExport)
	}

	log.Println("Server starting on port 8080...")
	if err := router.Run(":8080"); err != nil {
		log.Fatalf("Failed to run server: %v", err)
	}
}

// --- Handlers (Functional/Procedural Style) ---

// handleUserImport processes a CSV or Excel file to import users.
func handleUserImport(c *gin.Context) {
	file, err := c.FormFile("user_data")
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "File not provided"})
		return
	}

	// Create a temporary file to avoid holding the entire file in memory
	tempDir := os.TempDir()
	tempFilePath := filepath.Join(tempDir, uuid.New().String()+filepath.Ext(file.Filename))
	if err := c.SaveUploadedFile(file, tempFilePath); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to save temporary file"})
		return
	}
	// Ensure the temporary file is cleaned up
	defer os.Remove(tempFilePath)

	var users []User
	ext := filepath.Ext(file.Filename)

	switch ext {
	case ".csv":
		users, err = parseUsersFromCSV(tempFilePath)
	case ".xlsx":
		users, err = parseUsersFromXLSX(tempFilePath)
	default:
		c.JSON(http.StatusBadRequest, gin.H{"error": "Unsupported file type. Please use .csv or .xlsx"})
		return
	}

	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": fmt.Sprintf("Failed to parse file: %v", err)})
		return
	}

	log.Printf("Successfully parsed %d users from %s", len(users), file.Filename)
	// In a real app, you would now save these users to the database.
	c.JSON(http.StatusOK, gin.H{
		"message":      fmt.Sprintf("Successfully imported %d users.", len(users)),
		"imported_ids": len(users),
	})
}

// handlePostImageUpload resizes an uploaded image for a post.
func handlePostImageUpload(c *gin.Context) {
	postID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid post ID"})
		return
	}

	file, err := c.FormFile("image")
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Image file not provided"})
		return
	}

	// Open the uploaded file
	src, err := file.Open()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to open uploaded image"})
		return
	}
	defer src.Close()

	// Decode the image
	img, _, err := image.Decode(src)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid image format"})
		return
	}

	// Resize the image to a max width of 1024, maintaining aspect ratio
	resizedImg := resize.Resize(1024, 0, img, resize.Lanczos3)

	// Create a temporary file for the resized image
	tempFile, err := os.CreateTemp("", "resized-*.jpg")
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to create temp file for resized image"})
		return
	}
	defer os.Remove(tempFile.Name())
	defer tempFile.Close()

	// Encode the resized image as JPEG
	if err := jpeg.Encode(tempFile, resizedImg, nil); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to encode resized image"})
		return
	}

	log.Printf("Image for post %s resized and saved to %s", postID, tempFile.Name())
	// In a real app, you would now move this file to permanent storage (e.g., S3)
	// and update the post record with the image URL.

	c.JSON(http.StatusOK, gin.H{
		"message":     "Image uploaded and resized successfully",
		"post_id":     postID,
		"temp_path":   tempFile.Name(),
		"new_width":   resizedImg.Bounds().Dx(),
		"new_height":  resizedImg.Bounds().Dy(),
	})
}

// handlePostsExport streams a CSV file of all posts.
func handlePostsExport(c *gin.Context) {
	fileName := fmt.Sprintf("posts_export_%s.csv", time.Now().Format("20060102150405"))
	c.Header("Content-Disposition", "attachment; filename="+fileName)
	c.Header("Content-Type", "text/csv")

	// Use c.Stream to write the response body chunk by chunk
	c.Stream(func(w io.Writer) bool {
		csvWriter := csv.NewWriter(w)

		// Write header
		headers := []string{"id", "user_id", "title", "status"}
		if err := csvWriter.Write(headers); err != nil {
			log.Printf("Error writing CSV header: %v", err)
			return false // Stop streaming
		}

		// Write data rows
		for _, post := range mockPosts {
			row := []string{
				post.ID.String(),
				post.UserID.String(),
				post.Title,
				string(post.Status),
			}
			if err := csvWriter.Write(row); err != nil {
				log.Printf("Error writing CSV row: %v", err)
				return false // Stop streaming
			}
		}

		csvWriter.Flush()
		if err := csvWriter.Error(); err != nil {
			log.Printf("Error flushing CSV writer: %v", err)
			return false
		}

		return false // End of stream
	})
}

// --- Helper Functions ---

func parseUsersFromCSV(filePath string) ([]User, error) {
	file, err := os.Open(filePath)
	if err != nil {
		return nil, err
	}
	defer file.Close()

	reader := csv.NewReader(file)
	records, err := reader.ReadAll()
	if err != nil {
		return nil, err
	}

	if len(records) < 2 {
		return nil, fmt.Errorf("CSV must have a header and at least one data row")
	}

	var users []User
	// Skip header row (records[0])
	for _, record := range records[1:] {
		isActive, _ := strconv.ParseBool(record[2])
		user := User{
			ID:        uuid.New(),
			Email:     record[0],
			Role:      UserRole(record[1]),
			IsActive:  isActive,
			CreatedAt: time.Now().UTC(),
		}
		users = append(users, user)
	}
	return users, nil
}

func parseUsersFromXLSX(filePath string) ([]User, error) {
	f, err := excelize.OpenFile(filePath)
	if err != nil {
		return nil, err
	}
	defer f.Close()

	// Get all the rows in the first sheet.
	rows, err := f.GetRows(f.GetSheetName(0))
	if err != nil {
		return nil, err
	}

	if len(rows) < 2 {
		return nil, fmt.Errorf("XLSX must have a header and at least one data row")
	}

	var users []User
	// Skip header row (rows[0])
	for _, row := range rows[1:] {
		isActive, _ := strconv.ParseBool(row[2])
		user := User{
			ID:        uuid.New(),
			Email:     row[0],
			Role:      UserRole(row[1]),
			IsActive:  isActive,
			CreatedAt: time.Now().UTC(),
		}
		users = append(users, user)
	}
	return users, nil
}