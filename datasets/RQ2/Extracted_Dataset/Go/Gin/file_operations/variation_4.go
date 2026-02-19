package main

import (
	"context"
	"encoding/csv"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"time"

	"github.com/disintegration/imaging"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

// --- Domain Schema ---

type UserRole string
const (
	ADMIN UserRole = "ADMIN"
	USER  UserRole = "USER"
)

type User struct {
	ID           uuid.UUID
	Email        string
	PasswordHash string
	Role         UserRole
	IsActive     bool
	CreatedAt    time.Time
}

type PostStatus string
const (
	DRAFT     PostStatus = "DRAFT"
	PUBLISHED PostStatus = "PUBLISHED"
)

type Post struct {
	ID      uuid.UUID
	UserID  uuid.UUID
	Title   string
	Content string
	Status  PostStatus
}

// --- Application Server ---

// Server holds dependencies for the application handlers.
type Server struct {
	logger    *log.Logger
	mockPosts []Post
}

// NewServer creates a new server instance.
func NewServer() *Server {
	return &Server{
		logger: log.New(os.Stdout, "PRAGMATIC_API | ", log.LstdFlags),
		mockPosts: []Post{
			{ID: uuid.New(), UserID: uuid.New(), Title: "Minimalist Approach", Content: "Less boilerplate.", Status: PUBLISHED},
			{ID: uuid.New(), UserID: uuid.New(), Title: "Using io.Pipe", Content: "For efficient streaming.", Status: PUBLISHED},
			{ID: uuid.New(), UserID: uuid.New(), Title: "Error Handling in Gin", Content: "Using c.Error.", Status: DRAFT},
		},
	}
}

// --- Handlers (Methods on Server) ---

// handleUserImport processes a file upload, focusing on CSV.
func (s *Server) handleUserImport(c *gin.Context) {
	file, header, err := c.Request.FormFile("user_batch")
	if err != nil {
		_ = c.Error(fmt.Errorf("form file 'user_batch' not found: %w", err))
		c.AbortWithStatusJSON(http.StatusBadRequest, gin.H{"error": "Missing user_batch file"})
		return
	}
	defer file.Close()

	if filepath.Ext(header.Filename) != ".csv" {
		c.AbortWithStatusJSON(http.StatusUnsupportedMediaType, gin.H{"error": "Only .csv files are supported"})
		return
	}

	// Create a temporary file to work with
	tempFile, err := os.CreateTemp("", "user-import-*.csv")
	if err != nil {
		_ = c.Error(fmt.Errorf("failed to create temp file: %w", err))
		c.AbortWithStatusJSON(http.StatusInternalServerError, gin.H{"error": "Internal server error"})
		return
	}
	defer os.Remove(tempFile.Name()) // Schedule cleanup

	_, err = io.Copy(tempFile, file)
	if err != nil {
		_ = c.Error(fmt.Errorf("failed to copy to temp file: %w", err))
		c.AbortWithStatusJSON(http.StatusInternalServerError, gin.H{"error": "Internal server error"})
		return
	}
	tempFile.Close() // Close it so it can be read by the parser

	// In a real app, you'd parse the tempFile and create users.
	s.logger.Printf("User batch file '%s' saved to '%s' for processing.", header.Filename, tempFile.Name())

	c.JSON(http.StatusAccepted, gin.H{"message": "File uploaded and is being processed."})
}

// handleImageProcess resizes an image using the 'imaging' library.
func (s *Server) handleImageProcess(c *gin.Context) {
	postID := c.Param("id")
	if _, err := uuid.Parse(postID); err != nil {
		c.AbortWithStatusJSON(http.StatusBadRequest, gin.H{"error": "Invalid post ID"})
		return
	}

	file, err := c.FormFile("photo")
	if err != nil {
		c.AbortWithStatusJSON(http.StatusBadRequest, gin.H{"error": "Missing 'photo' file"})
		return
	}

	src, err := file.Open()
	if err != nil {
		_ = c.Error(err)
		c.AbortWithStatusJSON(http.StatusInternalServerError, gin.H{"error": "Could not open image file"})
		return
	}
	defer src.Close()

	// Use a robust library for image processing
	img, err := imaging.Decode(src)
	if err != nil {
		c.AbortWithStatusJSON(http.StatusBadRequest, gin.H{"error": "Unsupported image format"})
		return
	}

	// Create a 128x128 centered thumbnail
	thumb := imaging.Thumbnail(img, 128, 128, imaging.Lanczos)

	// In a real app, save to a persistent store (e.g., S3)
	// For this example, we save it to a temporary location
	destPath := filepath.Join(os.TempDir(), fmt.Sprintf("thumb_%s.jpg", postID))
	err = imaging.Save(thumb, destPath)
	if err != nil {
		_ = c.Error(err)
		c.AbortWithStatusJSON(http.StatusInternalServerError, gin.H{"error": "Could not save thumbnail"})
		return
	}
	s.logger.Printf("Thumbnail for post %s saved to %s", postID, destPath)
	// Schedule cleanup for the created thumbnail
	go func() {
		time.Sleep(1 * time.Minute)
		os.Remove(destPath)
		s.logger.Printf("Cleaned up temporary thumbnail: %s", destPath)
	}()

	c.JSON(http.StatusOK, gin.H{"message": "Thumbnail created", "path": destPath})
}

// handleDownloadReport streams a CSV report using io.Pipe for memory efficiency.
func (s *Server) handleDownloadReport(c *gin.Context) {
	reader, writer := io.Pipe()

	// Start a goroutine to write data to the pipe
	go func() {
		defer writer.Close() // Ensure the writer is closed to signal EOF to the reader
		csvWriter := csv.NewWriter(writer)
		
		// Write header
		_ = csvWriter.Write([]string{"id", "title", "status"})

		// Write data
		for _, post := range s.mockPosts {
			if err := csvWriter.Write([]string{post.ID.String(), post.Title, string(post.Status)}); err != nil {
				s.logger.Printf("Error writing to pipe: %v", err)
				// Close the writer with an error to propagate it to the reader
				writer.CloseWithError(err)
				return
			}
		}
		csvWriter.Flush()
	}(
	)

	c.Header("Content-Type", "text/csv")
	c.Header("Content-Disposition", `attachment; filename="posts_report.csv"`)
	
	// Stream the data from the pipe's reader to the HTTP response
	_, err := io.Copy(c.Writer, reader)
	if err != nil {
		s.logger.Printf("Error streaming response: %v", err)
	}
}

// --- Main Function ---

func main() {
	server := NewServer()
	
	router := gin.New()
	// Custom logger middleware
	router.Use(gin.LoggerWithFormatter(func(param gin.LogFormatterParams) string {
		return fmt.Sprintf("%s - \"%s %s\" %d %s\n",
			param.ClientIP,
			param.Method,
			param.Path,
			param.StatusCode,
			param.Latency,
		)
	}))
	router.Use(gin.Recovery())

	// Setup routes
	router.POST("/upload/users", server.handleUserImport)
	router.PUT("/process/posts/:id/image", server.handleImageProcess)
	router.GET("/download/posts", server.handleDownloadReport)

	server.logger.Println("Starting pragmatic server on http://localhost:8080")
	if err := router.Run(":8080"); err != nil {
		log.Fatalf("Failed to start server: %v", err)
	}
}