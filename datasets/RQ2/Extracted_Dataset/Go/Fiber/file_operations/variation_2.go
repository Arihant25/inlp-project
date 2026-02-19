package main

import (
	"encoding/csv"
	"fmt"
	"image"
	"image/jpeg"
	"io"
	"log"
	"mime/multipart"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
	"github.com/nfnt/resize"
)

// --- Domain Models ---

type UserRole string
const (
	ADMIN UserRole = "ADMIN"
	USER  UserRole = "USER"
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
	DRAFT     PostStatus = "DRAFT"
	PUBLISHED PostStatus = "PUBLISHED"
)

type Post struct {
	ID      uuid.UUID  `json:"id"`
	UserID  uuid.UUID  `json:"user_id"`
	Title   string     `json:"title"`
	Content string     `json:"content"`
	Status  PostStatus `json:"status"`
}

// --- Mock Datastore ---
type Datastore struct {
	mu    sync.RWMutex
	Users map[uuid.UUID]User
	Posts map[uuid.UUID]Post
}

func NewDatastore() *Datastore {
	ds := &Datastore{
		Users: make(map[uuid.UUID]User),
		Posts: make(map[uuid.UUID]Post),
	}
	// Seed data
	adminID := uuid.New()
	ds.Users[adminID] = User{ID: adminID, Email: "admin@example.com", Role: ADMIN, IsActive: true, CreatedAt: time.Now()}
	postID := uuid.New()
	ds.Posts[postID] = Post{ID: postID, UserID: adminID, Title: "Seed Post", Status: PUBLISHED}
	return ds
}

// --- Service Layer ---

type FileService struct {
	db *Datastore
}

func NewFileService(db *Datastore) *FileService {
	return &FileService{db: db}
}

func (s *FileService) ProcessUserCSV(file io.Reader) ([]User, error) {
	reader := csv.NewReader(file)
	records, err := reader.ReadAll()
	if err != nil {
		return nil, fmt.Errorf("failed to parse CSV: %w", err)
	}
	if len(records) < 2 {
		return nil, fmt.Errorf("CSV must contain a header and at least one record")
	}

	var createdUsers []User
	s.db.mu.Lock()
	defer s.db.mu.Unlock()

	for i, record := range records {
		if i == 0 { continue } // Skip header
		isActive, _ := strconv.ParseBool(record[3])
		newUser := User{
			ID:           uuid.New(),
			Email:        record[0],
			PasswordHash: record[1],
			Role:         UserRole(record[2]),
			IsActive:     isActive,
			CreatedAt:    time.Now(),
		}
		s.db.Users[newUser.ID] = newUser
		createdUsers = append(createdUsers, newUser)
	}
	return createdUsers, nil
}

func (s *FileService) ResizePostImage(postID uuid.UUID, file io.Reader) (string, error) {
	s.db.mu.RLock()
	_, exists := s.db.Posts[postID]
	s.db.mu.RUnlock()
	if !exists {
		return "", fmt.Errorf("post with ID %s not found", postID)
	}

	img, _, err := image.Decode(file)
	if err != nil {
		return "", fmt.Errorf("failed to decode image: %w", err)
	}

	resizedImg := resize.Resize(800, 0, img, resize.Lanczos3)

	tempFile, err := os.CreateTemp("", fmt.Sprintf("post-%s-*.jpg", postID))
	if err != nil {
		return "", fmt.Errorf("could not create temp file: %w", err)
	}
	// Note: The caller (handler) is responsible for cleaning up the temp file.
	
	if err := jpeg.Encode(tempFile, resizedImg, &jpeg.Options{Quality: 85}); err != nil {
		os.Remove(tempFile.Name()) // Clean up on encoding failure
		return "", fmt.Errorf("failed to encode resized image: %w", err)
	}
	
	log.Printf("Image for post %s processed and stored at %s", postID, tempFile.Name())
	return tempFile.Name(), nil
}

func (s *FileService) GeneratePostsCSV(writer io.Writer) error {
	csvWriter := csv.NewWriter(writer)
	defer csvWriter.Flush()

	if err := csvWriter.Write([]string{"id", "user_id", "title", "status"}); err != nil {
		return err
	}

	s.db.mu.RLock()
	defer s.db.mu.RUnlock()
	for _, post := range s.db.Posts {
		record := []string{post.ID.String(), post.UserID.String(), post.Title, string(post.Status)}
		if err := csvWriter.Write(record); err != nil {
			return err
		}
	}
	return csvWriter.Error()
}

// --- Handler/Controller Layer ---

type FileHandler struct {
	fileSvc *FileService
}

func NewFileHandler(fs *FileService) *FileHandler {
	return &FileHandler{fileSvc: fs}
}

func (h *FileHandler) UploadUsers(c *fiber.Ctx) error {
	fileHeader, err := c.FormFile("user_data")
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "form file 'user_data' is required"})
	}

	if filepath.Ext(fileHeader.Filename) != ".csv" {
		return c.Status(fiber.StatusUnsupportedMediaType).JSON(fiber.Map{"error": "file must be a .csv"})
	}

	file, err := fileHeader.Open()
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": "could not open uploaded file"})
	}
	defer file.Close()

	users, err := h.fileSvc.ProcessUserCSV(file)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": err.Error()})
	}

	return c.Status(fiber.StatusCreated).JSON(fiber.Map{
		"message": fmt.Sprintf("Successfully imported %d users", len(users)),
	})
}

func (h *FileHandler) UploadPostImage(c *fiber.Ctx) error {
	postID, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "invalid post ID format"})
	}

	fileHeader, err := c.FormFile("image")
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "form file 'image' is required"})
	}

	file, err := fileHeader.Open()
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": "could not open uploaded image"})
	}
	defer file.Close()

	tempFilePath, err := h.fileSvc.ResizePostImage(postID, file)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": err.Error()})
	}
	defer os.Remove(tempFilePath) // Clean up the temporary file after the request is done

	return c.JSON(fiber.Map{
		"message": "image processed successfully",
		"postId":  postID,
	})
}

func (h *FileHandler) DownloadPostsReport(c *fiber.Ctx) error {
	c.Set(fiber.HeaderContentType, "text/csv")
	c.Set(fiber.HeaderContentDisposition, `attachment; filename="posts_report.csv"`)

	return c.SendStream(func(w io.Writer) error {
		return h.fileSvc.GeneratePostsCSV(w)
	})
}

func main() {
	// Dependency Injection
	datastore := NewDatastore()
	fileService := NewFileService(datastore)
	fileHandler := NewFileHandler(fileService)

	// Fiber App Setup
	app := fiber.New()

	app.Post("/users/import", fileHandler.UploadUsers)
	app.Post("/posts/:id/image", fileHandler.UploadPostImage)
	app.Get("/posts/export", fileHandler.DownloadPostsReport)

	log.Fatal(app.Listen(":3000"))
}