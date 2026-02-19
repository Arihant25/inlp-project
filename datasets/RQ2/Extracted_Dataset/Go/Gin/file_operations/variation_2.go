package main

import (
	"encoding/csv"
	"fmt"
	"image"
	"image/jpeg"
	_ "image/png"
	"io"
	"log"
	"mime/multipart"
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

// --- Mock Data Store ---

type MockDB struct {
	Posts []Post
}

func NewMockDB() *MockDB {
	return &MockDB{
		Posts: []Post{
			{ID: uuid.New(), UserID: uuid.New(), Title: "Intro to Gin", Content: "Some content here.", Status: PublishedStatus},
			{ID: uuid.New(), UserID: uuid.New(), Title: "Advanced Go", Content: "More content.", Status: PublishedStatus},
		},
	}
}

// --- Service Layer ---

type FileService struct {
	db *MockDB
}

func NewFileService(db *MockDB) *FileService {
	return &FileService{db: db}
}

func (s *FileService) ProcessUserImport(file *multipart.FileHeader) ([]User, error) {
	tempFilePath := filepath.Join(os.TempDir(), file.Filename)
	
	src, err := file.Open()
	if err != nil {
		return nil, fmt.Errorf("failed to open uploaded file: %w", err)
	}
	defer src.Close()

	dst, err := os.Create(tempFilePath)
	if err != nil {
		return nil, fmt.Errorf("failed to create temp file: %w", err)
	}
	defer dst.Close()
	defer os.Remove(tempFilePath)

	if _, err := io.Copy(dst, src); err != nil {
		return nil, fmt.Errorf("failed to save to temp file: %w", err)
	}

	var users []User
	ext := filepath.Ext(file.Filename)
	switch ext {
	case ".csv":
		users, err = s.parseCSV(tempFilePath)
	case ".xlsx":
		users, err = s.parseXLSX(tempFilePath)
	default:
		return nil, fmt.Errorf("unsupported file type: %s", ext)
	}

	if err != nil {
		return nil, fmt.Errorf("parsing error: %w", err)
	}
	// In a real app, you'd save users to s.db here.
	return users, nil
}

func (s *FileService) ResizeAndStoreImage(file multipart.File, postID uuid.UUID) (string, error) {
	img, _, err := image.Decode(file)
	if err != nil {
		return "", fmt.Errorf("could not decode image: %w", err)
	}

	resizedImg := resize.Resize(800, 0, img, resize.Lanczos3)
	
	// In a real app, this path would be in a configurable location or cloud storage
	outputPath := filepath.Join(os.TempDir(), fmt.Sprintf("post_%s_resized.jpg", postID))
	out, err := os.Create(outputPath)
	if err != nil {
		return "", fmt.Errorf("could not create output file: %w", err)
	}
	defer out.Close()

	if err := jpeg.Encode(out, resizedImg, &jpeg.Options{Quality: 85}); err != nil {
		// Attempt to clean up the failed file
		os.Remove(outputPath)
		return "", fmt.Errorf("could not encode resized image: %w", err)
	}

	return outputPath, nil
}

func (s *FileService) WritePostsToCSV(writer io.Writer) error {
	csvWriter := csv.NewWriter(writer)
	defer csvWriter.Flush()

	if err := csvWriter.Write([]string{"id", "user_id", "title", "status"}); err != nil {
		return err
	}

	for _, post := range s.db.Posts {
		record := []string{post.ID.String(), post.UserID.String(), post.Title, string(post.Status)}
		if err := csvWriter.Write(record); err != nil {
			return err
		}
	}
	return csvWriter.Error()
}

// Private helper methods for the service
func (s *FileService) parseCSV(path string) ([]User, error) { /* ... implementation ... */ return []User{}, nil }
func (s *FileService) parseXLSX(path string) ([]User, error) { /* ... implementation ... */ return []User{}, nil }


// --- Handler Layer (Controller) ---

type FileHandler struct {
	fileService *FileService
}

func NewFileHandler(service *FileService) *FileHandler {
	return &FileHandler{fileService: service}
}

func (h *FileHandler) UploadUsers(c *gin.Context) {
	file, err := c.FormFile("users_file")
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Missing 'users_file' in form"})
		return
	}

	users, err := h.fileService.ProcessUserImport(file)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"message": fmt.Sprintf("Processed %d users successfully", len(users)),
	})
}

func (h *FileHandler) UploadPostImage(c *gin.Context) {
	postID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid post ID format"})
		return
	}

	fileHeader, err := c.FormFile("post_image")
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Missing 'post_image' in form"})
		return
	}

	file, err := fileHeader.Open()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Could not open file"})
		return
	}
	defer file.Close()

	storedPath, err := h.fileService.ResizeAndStoreImage(file, postID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"message": "Image processed successfully",
		"path":    storedPath,
	})
}

func (h *FileHandler) ExportPosts(c *gin.Context) {
	c.Header("Content-Type", "text/csv")
	c.Header("Content-Disposition", "attachment; filename=posts.csv")
	
	err := h.fileService.WritePostsToCSV(c.Writer)
	if err != nil {
		// Since headers are already sent, we can't send a JSON error.
		// We log it, and the client will likely see a truncated/failed download.
		log.Printf("Error during CSV streaming: %v", err)
		c.Status(http.StatusInternalServerError)
	}
}

// --- Main Application Setup ---

func main() {
	// Dependencies
	db := NewMockDB()
	fileService := NewFileService(db)
	fileHandler := NewFileHandler(fileService)

	// Router
	router := gin.Default()
	router.POST("/users/import", fileHandler.UploadUsers)
	router.POST("/posts/:id/image", fileHandler.UploadPostImage)
	router.GET("/posts/export", fileHandler.ExportPosts)

	log.Println("Starting server with Service Layer pattern on :8080")
	router.Run(":8080")
}