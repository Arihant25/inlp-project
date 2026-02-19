package main

// This single file simulates a modular, package-based structure.
// Comments indicate the intended file separation.

// --- file: models/domain.go ---

import (
	"time"
	"github.com/google/uuid"
)

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

// --- file: services/file_service.go ---

import (
	"encoding/csv"
	"fmt"
	"image"
	"image/jpeg"
	_ "image/png"
	"io"
	"log"
	"mime/multipart"
	"os"
	"path/filepath"

	"github.com/nfnt/resize"
)

// FileService defines the interface for file-related operations.
type FileService interface {
	ProcessUserImport(file *multipart.FileHeader) (int, error)
	ProcessPostImage(file multipart.File, postID uuid.UUID) (string, error)
	StreamPostExport(writer io.Writer) error
}

// fileServiceImpl is the concrete implementation of FileService.
type fileServiceImpl struct {
	// In a real app, this would hold dependencies like a database connection pool.
	mockPosts []Post
}

// NewFileService creates a new instance of the file service.
func NewFileService() FileService {
	return &fileServiceImpl{
		mockPosts: []Post{
			{ID: uuid.New(), UserID: uuid.New(), Title: "Modular Design", Content: "It's clean.", Status: StatusPublished},
			{ID: uuid.New(), UserID: uuid.New(), Title: "Interfaces in Go", Content: "Promote loose coupling.", Status: StatusPublished},
		},
	}
}

func (s *fileServiceImpl) ProcessUserImport(fileHeader *multipart.FileHeader) (int, error) {
	// This method would contain the full CSV/XLSX parsing logic.
	// For brevity, we'll just log it.
	log.Printf("Processing user import from file: %s", fileHeader.Filename)
	// Simulate parsing 10 users
	return 10, nil
}

func (s *fileServiceImpl) ProcessPostImage(file multipart.File, postID uuid.UUID) (string, error) {
	img, _, err := image.Decode(file)
	if err != nil {
		return "", fmt.Errorf("image.Decode failed: %w", err)
	}

	// Resize to a thumbnail size
	thumb := resize.Thumbnail(250, 250, img, resize.Lanczos3)

	// Use a temporary file for this example
	tempFile, err := os.CreateTemp("", fmt.Sprintf("thumb-%s-*.jpg", postID))
	if err != nil {
		return "", fmt.Errorf("failed to create temp file: %w", err)
	}
	defer tempFile.Close()

	if err = jpeg.Encode(tempFile, thumb, nil); err != nil {
		os.Remove(tempFile.Name()) // Clean up on failure
		return "", fmt.Errorf("jpeg.Encode failed: %w", err)
	}

	log.Printf("Thumbnail for post %s created at %s", postID, tempFile.Name())
	return tempFile.Name(), nil
}

func (s *fileServiceImpl) StreamPostExport(writer io.Writer) error {
	csvWriter := csv.NewWriter(writer)
	
	// Write header
	if err := csvWriter.Write([]string{"id", "title", "status"}); err != nil {
		return fmt.Errorf("csv write header failed: %w", err)
	}

	// Write rows
	for _, p := range s.mockPosts {
		row := []string{p.ID.String(), p.Title, string(p.Status)}
		if err := csvWriter.Write(row); err != nil {
			// The stream is likely broken, so we return the error to stop.
			return fmt.Errorf("csv write row failed for post %s: %w", p.ID, err)
		}
	}

	csvWriter.Flush()
	return csvWriter.Error()
}

// --- file: handlers/file_handler.go ---

import (
	"net/http"
	"github.com/gin-gonic/gin"
)

// FileHandler encapsulates request handling logic for file operations.
type FileHandler struct {
	service FileService
}

// NewFileHandler creates a new handler with its dependencies.
func NewFileHandler(service FileService) *FileHandler {
	return &FileHandler{service: service}
}

// RegisterRoutes registers the file-related routes with the Gin engine.
func (h *FileHandler) RegisterRoutes(router *gin.RouterGroup) {
	router.POST("/users/import", h.handleUserImport)
	router.POST("/posts/:id/image", h.handlePostImage)
	router.GET("/posts/export", h.handlePostExport)
}

func (h *FileHandler) handleUserImport(c *gin.Context) {
	file, err := c.FormFile("import_file")
	if err != nil {
		c.AbortWithStatusJSON(http.StatusBadRequest, gin.H{"error": "form file 'import_file' is required"})
		return
	}

	count, err := h.service.ProcessUserImport(file)
	if err != nil {
		c.AbortWithStatusJSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"message": "Import successful", "users_created": count})
}

func (h *FileHandler) handlePostImage(c *gin.Context) {
	postID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.AbortWithStatusJSON(http.StatusBadRequest, gin.H{"error": "invalid post ID"})
		return
	}

	fileHeader, err := c.FormFile("post_image")
	if err != nil {
		c.AbortWithStatusJSON(http.StatusBadRequest, gin.H{"error": "form file 'post_image' is required"})
		return
	}

	file, err := fileHeader.Open()
	if err != nil {
		c.AbortWithStatusJSON(http.StatusInternalServerError, gin.H{"error": "could not open file"})
		return
	}
	defer file.Close()

	path, err := h.service.ProcessPostImage(file, postID)
	if err != nil {
		c.AbortWithStatusJSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"message": "Image processed", "thumbnail_path": path})
}

func (h *FileHandler) handlePostExport(c *gin.Context) {
	c.Header("Content-Type", "application/octet-stream")
	c.Header("Content-Disposition", `attachment; filename="posts_report.csv"`)
	
	// The service streams directly to the response writer.
	err := h.service.StreamPostExport(c.Writer)
	if err != nil {
		// Can't send a JSON error as headers are already written.
		// Log the error and let the connection terminate.
		log.Printf("Error during post export stream: %v", err)
		c.Status(http.StatusInternalServerError)
	}
}

// --- file: main.go ---

func main() {
	// --- Dependency Injection ---
	fileSvc := NewFileService()
	fileHndlr := NewFileHandler(fileSvc)

	// --- Router Setup ---
	router := gin.New()
	router.Use(gin.Logger(), gin.Recovery())

	apiGroup := router.Group("/api")
	fileHndlr.RegisterRoutes(apiGroup)

	log.Println("Starting server with Modular pattern on :8080")
	if err := router.Run(":8080"); err != nil {
		log.Fatalf("Server failed to start: %v", err)
	}
}