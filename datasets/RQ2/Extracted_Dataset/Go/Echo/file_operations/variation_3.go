package main

import (
	"encoding/csv"
	"fmt"
	"image"
	"image/jpeg"
	"io"
	"log"
	"mime/multipart"
	"net/http"
	"os"
	"strconv"
	"sync"
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

// --- Repository Layer (Mock) ---

type UserRepository interface {
	SaveBatch([]User) (int, error)
	FindAll() ([]User, error)
}

type MockUserRepository struct {
	mu    sync.RWMutex
	users map[uuid.UUID]User
}

func NewMockUserRepository() *MockUserRepository {
	return &MockUserRepository{users: make(map[uuid.UUID]User)}
}

func (r *MockUserRepository) SaveBatch(users []User) (int, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	for _, u := range users {
		r.users[u.ID] = u
	}
	return len(users), nil
}

func (r *MockUserRepository) FindAll() ([]User, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	allUsers := make([]User, 0, len(r.users))
	for _, u := range r.users {
		allUsers = append(allUsers, u)
	}
	return allUsers, nil
}

// --- Service Layer ---

type FileService interface {
	BulkCreateUsersFromCSV(file io.Reader) ([]User, error)
	ResizeImage(file io.Reader, width, height uint) (image.Image, error)
	GenerateUserReport(writer io.Writer) error
}

type fileServiceImpl struct {
	userRepo UserRepository
}

func NewFileService(userRepo UserRepository) FileService {
	return &fileServiceImpl{userRepo: userRepo}
}

func (s *fileServiceImpl) BulkCreateUsersFromCSV(file io.Reader) ([]User, error) {
	r := csv.NewReader(file)
	records, err := r.ReadAll()
	if err != nil || len(records) < 2 {
		return nil, fmt.Errorf("invalid or empty CSV file")
	}

	var usersToCreate []User
	for _, row := range records[1:] { // Skip header
		isActive, _ := strconv.ParseBool(row[2])
		user := User{
			ID:           uuid.New(),
			Email:        row[0],
			PasswordHash: "placeholder_hash",
			Role:         Role(row[3]),
			IsActive:     isActive,
			CreatedAt:    time.Now(),
		}
		usersToCreate = append(usersToCreate, user)
	}

	_, err = s.userRepo.SaveBatch(usersToCreate)
	if err != nil {
		return nil, err
	}
	return usersToCreate, nil
}

func (s *fileServiceImpl) ResizeImage(file io.Reader, width, height uint) (image.Image, error) {
	img, _, err := image.Decode(file)
	if err != nil {
		return nil, fmt.Errorf("failed to decode image: %w", err)
	}
	return resize.Resize(width, height, img, resize.Lanczos3), nil
}

func (s *fileServiceImpl) GenerateUserReport(writer io.Writer) error {
	users, err := s.userRepo.FindAll()
	if err != nil {
		return err
	}

	csvWriter := csv.NewWriter(writer)
	defer csvWriter.Flush()

	headers := []string{"id", "email", "role", "is_active", "created_at"}
	if err := csvWriter.Write(headers); err != nil {
		return err
	}

	for _, user := range users {
		record := []string{
			user.ID.String(), user.Email, string(user.Role),
			strconv.FormatBool(user.IsActive), user.CreatedAt.Format(time.RFC3339),
		}
		if err := csvWriter.Write(record); err != nil {
			return err
		}
	}
	return nil
}

// --- Handler/Controller Layer ---

type FileHandler struct {
	service FileService
}

func NewFileHandler(service FileService) *FileHandler {
	return &FileHandler{service: service}
}

func (h *FileHandler) UploadUsers(c echo.Context) error {
	file, err := c.FormFile("file")
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "file form field is required"})
	}
	src, err := file.Open()
	if err != nil {
		return echo.NewHTTPError(http.StatusInternalServerError, "cannot open file")
	}
	defer src.Close()

	users, err := h.service.BulkCreateUsersFromCSV(src)
	if err != nil {
		return c.JSON(http.StatusUnprocessableEntity, map[string]string{"error": err.Error()})
	}

	return c.JSON(http.StatusCreated, map[string]interface{}{
		"message": fmt.Sprintf("Processed %d users", len(users)),
		"users":   users,
	})
}

func (h *FileHandler) UploadPostImage(c echo.Context) error {
	file, err := c.FormFile("image")
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "image form field is required"})
	}
	src, err := file.Open()
	if err != nil {
		return echo.NewHTTPError(http.StatusInternalServerError, "cannot open image")
	}
	defer src.Close()

	resizedImg, err := h.service.ResizeImage(src, 1024, 0) // 1024px width, auto height
	if err != nil {
		return c.JSON(http.StatusUnprocessableEntity, map[string]string{"error": err.Error()})
	}

	// Create a temporary file to hold the processed image
	tmpFile, err := os.CreateTemp("", "processed-*.jpg")
	if err != nil {
		return echo.NewHTTPError(http.StatusInternalServerError, "cannot create temp file")
	}
	defer os.Remove(tmpFile.Name())

	if err := jpeg.Encode(tmpFile, resizedImg, &jpeg.Options{Quality: 85}); err != nil {
		return echo.NewHTTPError(http.StatusInternalServerError, "cannot encode processed image")
	}

	log.Printf("Image processed and stored temporarily at: %s", tmpFile.Name())
	return c.JSON(http.StatusOK, map[string]string{
		"message": "Image processed successfully",
		"path":    tmpFile.Name(),
	})
}

func (h *FileHandler) DownloadUserReport(c echo.Context) error {
	c.Response().Header().Set(echo.HeaderContentType, "text/csv")
	c.Response().Header().Set(echo.HeaderContentDisposition, "attachment; filename=user_report.csv")
	c.Response().WriteHeader(http.StatusOK)

	// The service writes directly to the response writer, streaming the output.
	return h.service.GenerateUserReport(c.Response().Writer)
}

// --- Main Application Setup ---

func main() {
	e := echo.New()
	e.Use(middleware.Logger())
	e.Use(middleware.Recover())

	// Dependency Injection
	userRepo := NewMockUserRepository()
	fileService := NewFileService(userRepo)
	fileHandler := NewFileHandler(fileService)

	// Routes
	e.POST("/users/upload", fileHandler.UploadUsers)
	e.POST("/posts/image/upload", fileHandler.UploadPostImage)
	e.GET("/users/report/download", fileHandler.DownloadUserReport)

	log.Println("Server with Service Layer architecture starting on :8080")
	e.Logger.Fatal(e.Start(":8080"))
}