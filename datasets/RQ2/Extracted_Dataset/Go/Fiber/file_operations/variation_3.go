package main

import (
	"encoding/csv"
	"fmt"
	"image"
	"image/png"
	"io"
	"log"
	"mime/multipart"
	"os"
	"strconv"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
	"github.com/nfnt/resize"
)

// --- package domain ---

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

// --- package services ---

// FileService defines the interface for file operations.
type FileService interface {
	BulkCreateUsersFromCSV(file io.Reader) (int, error)
	ProcessAndStorePostImage(postID uuid.UUID, file io.Reader) (string, error)
	StreamPostsAsCSV(writer io.Writer) error
}

// fileServiceImpl is the concrete implementation of FileService.
type fileServiceImpl struct {
	// In a real app, this would be a database connection pool.
	mockUsers map[uuid.UUID]User
	mockPosts map[uuid.UUID]Post
}

// NewFileService creates a new instance of the file service.
func NewFileService(users map[uuid.UUID]User, posts map[uuid.UUID]Post) FileService {
	return &fileServiceImpl{
		mockUsers: users,
		mockPosts: posts,
	}
}

func (s *fileServiceImpl) BulkCreateUsersFromCSV(file io.Reader) (int, error) {
	r := csv.NewReader(file)
	r.Comment = '#'
	records, err := r.ReadAll()
	if err != nil {
		return 0, err
	}
	
	count := 0
	for _, rec := range records[1:] { // Skip header
		isActive, _ := strconv.ParseBool(rec[3])
		user := User{
			ID:           uuid.New(),
			Email:        rec[0],
			PasswordHash: rec[1],
			Role:         UserRole(rec[2]),
			IsActive:     isActive,
			CreatedAt:    time.Now().UTC(),
		}
		s.mockUsers[user.ID] = user
		count++
	}
	return count, nil
}

func (s *fileServiceImpl) ProcessAndStorePostImage(postID uuid.UUID, file io.Reader) (string, error) {
	if _, ok := s.mockPosts[postID]; !ok {
		return "", fmt.Errorf("post not found")
	}

	img, format, err := image.Decode(file)
	if err != nil {
		return "", fmt.Errorf("unsupported image format: %w", err)
	}
	log.Printf("Decoded image format: %s", format)

	// Resize to a fixed width, maintaining aspect ratio
	thumbnail := resize.Resize(400, 0, img, resize.MitchellNetravali)

	// Create a temporary file for the processed image
	tmpFile, err := os.CreateTemp(os.TempDir(), "processed-*.png")
	if err != nil {
		return "", fmt.Errorf("failed to create temp file: %w", err)
	}
	// The handler is responsible for cleaning this up.

	// Encode as PNG
	if err := png.Encode(tmpFile, thumbnail); err != nil {
		os.Remove(tmpFile.Name()) // Clean up on failure
		return "", fmt.Errorf("failed to encode image to png: %w", err)
	}
	
	return tmpFile.Name(), nil
}

func (s *fileServiceImpl) StreamPostsAsCSV(writer io.Writer) error {
	w := csv.NewWriter(writer)
	if err := w.Write([]string{"id", "user_id", "title", "status"}); err != nil {
		return err
	}
	for _, post := range s.mockPosts {
		row := []string{post.ID.String(), post.UserID.String(), post.Title, string(post.Status)}
		if err := w.Write(row); err != nil {
			return err
		}
	}
	w.Flush()
	return w.Error()
}

// --- package handlers ---

// FileRoutesHandler encapsulates handlers for file-related routes.
type FileRoutesHandler struct {
	service FileService
}

// NewFileRoutesHandler creates a new handler with its dependencies.
func NewFileRoutesHandler(service FileService) *FileRoutesHandler {
	return &FileRoutesHandler{service: service}
}

func (h *FileRoutesHandler) RegisterRoutes(router fiber.Router) {
	router.Post("/users/batch-create", h.handleUserBatchCreate)
	router.Post("/posts/:id/cover-image", h.handlePostCoverImage)
	router.Get("/posts/csv-report", h.handlePostCSVReport)
}

func (h *FileRoutesHandler) handleUserBatchCreate(c *fiber.Ctx) error {
	file, err := c.FormFile("csv_file")
	if err != nil {
		return fiber.NewError(fiber.StatusBadRequest, "missing 'csv_file' in form data")
	}

	src, err := file.Open()
	if err != nil {
		return fiber.NewError(fiber.StatusInternalServerError, "cannot open file")
	}
	defer src.Close()

	count, err := h.service.BulkCreateUsersFromCSV(src)
	if err != nil {
		return fiber.NewError(fiber.StatusUnprocessableEntity, fmt.Sprintf("csv processing failed: %v", err))
	}

	return c.JSON(fiber.Map{"status": "success", "users_created": count})
}

func (h *FileRoutesHandler) handlePostCoverImage(c *fiber.Ctx) error {
	id := c.Params("id")
	postID, err := uuid.Parse(id)
	if err != nil {
		return fiber.NewError(fiber.StatusBadRequest, "invalid post ID")
	}

	file, err := c.FormFile("image_file")
	if err != nil {
		return fiber.NewError(fiber.StatusBadRequest, "missing 'image_file' in form data")
	}

	src, err := file.Open()
	if err != nil {
		return fiber.NewError(fiber.StatusInternalServerError, "cannot open file")
	}
	defer src.Close()

	tempPath, err := h.service.ProcessAndStorePostImage(postID, src)
	if err != nil {
		return fiber.NewError(fiber.StatusInternalServerError, err.Error())
	}
	defer os.Remove(tempPath) // Clean up temp file after request

	return c.JSON(fiber.Map{"status": "success", "message": "image processed", "temp_path": tempPath})
}

func (h *FileRoutesHandler) handlePostCSVReport(c *fiber.Ctx) error {
	c.Type("csv")
	c.Set(fiber.HeaderContentDisposition, `attachment; filename="posts.csv"`)
	
	return c.SendStream(func(w io.Writer) error {
		return h.service.StreamPostsAsCSV(w)
	})
}

// --- package main ---

func main() {
	// Mock Data Initialization
	mockUsers := make(map[uuid.UUID]User)
	mockPosts := make(map[uuid.UUID]Post)
	adminID := uuid.New()
	mockUsers[adminID] = User{ID: adminID, Email: "admin@example.com", Role: RoleAdmin, IsActive: true}
	mockPosts[uuid.New()] = Post{ID: uuid.New(), UserID: adminID, Title: "Getting Started with Fiber", Status: StatusPublished}

	// Dependency Injection
	fileService := NewFileService(mockUsers, mockPosts)
	fileHandler := NewFileRoutesHandler(fileService)

	// Fiber App Configuration
	app := fiber.New(fiber.Config{
		ErrorHandler: func(c *fiber.Ctx, err error) error {
			code := fiber.StatusInternalServerError
			if e, ok := err.(*fiber.Error); ok {
				code = e.Code
			}
			return c.Status(code).JSON(fiber.Map{"error": err.Error()})
		},
	})

	apiGroup := app.Group("/v1")
	fileHandler.RegisterRoutes(apiGroup)

	log.Fatal(app.Listen(":3000"))
}