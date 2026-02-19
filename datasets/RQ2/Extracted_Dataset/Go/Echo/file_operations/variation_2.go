package main

import (
	"fmt"
	"image/png"
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
	"github.com/xuri/excelize/v2"
)

// To run this code, you need the following dependencies:
// go get github.com/labstack/echo/v4
// go get github.com/google/uuid
// go get github.com/nfnt/resize
// go get github.com/xuri/excelize/v2

// --- Domain Models ---

type UserRole string

const (
	RoleAdmin UserRole = "ADMIN"
	RoleUser  UserRole = "USER"
)

type User struct {
	ID          uuid.UUID `json:"id"`
	Email       string    `json:"email"`
	Password    string    `json:"-"`
	Role        UserRole  `json:"role"`
	IsActive    bool      `json:"is_active"`
	DateCreated time.Time `json:"created_at"`
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

// --- Mock Data Store ---

type MockDB struct {
	Users map[uuid.UUID]User
	Posts map[uuid.UUID]Post
}

func NewMockDB() *MockDB {
	postID := uuid.New()
	return &MockDB{
		Users: make(map[uuid.UUID]User),
		Posts: map[uuid.UUID]Post{
			postID: {
				ID:     postID,
				UserID: uuid.New(),
				Title:  "Test Post",
				Status: StatusPublished,
			},
		},
	}
}

// --- OOP/Struct-based Handler ---

type FileOperationHandler struct {
	DB     *MockDB
	Logger echo.Logger
}

func NewFileOperationHandler(db *MockDB, logger echo.Logger) *FileOperationHandler {
	return &FileOperationHandler{DB: db, Logger: logger}
}

// UploadUsersFromXLSX handles bulk user creation from an Excel file.
func (h *FileOperationHandler) UploadUsersFromXLSX(c echo.Context) error {
	formFile, err := c.FormFile("user_data")
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"message": "user_data file is required"})
	}

	src, err := formFile.Open()
	if err != nil {
		return echo.NewHTTPError(http.StatusInternalServerError, "Could not open file")
	}
	defer src.Close()

	xlsxFile, err := excelize.OpenReader(src)
	if err != nil {
		return echo.NewHTTPError(http.StatusBadRequest, "Could not read Excel file")
	}

	sheetName := xlsxFile.GetSheetName(0)
	rows, err := xlsxFile.GetRows(sheetName)
	if err != nil {
		return echo.NewHTTPError(http.StatusInternalServerError, "Could not get rows from sheet")
	}

	if len(rows) < 2 {
		return c.JSON(http.StatusBadRequest, map[string]string{"message": "Excel file is empty or has no data rows"})
	}

	var createdCount int
	for _, row := range rows[1:] { // Skip header
		isActive, _ := strconv.ParseBool(row[2])
		newUser := User{
			ID:          uuid.New(),
			Email:       row[0],
			Password:    "hashed_" + row[1],
			IsActive:    isActive,
			Role:        UserRole(row[3]),
			DateCreated: time.Now().UTC(),
		}
		h.DB.Users[newUser.ID] = newUser
		createdCount++
	}

	h.Logger.Infof("Processed %d users from XLSX file.", createdCount)
	return c.JSON(http.StatusCreated, map[string]interface{}{
		"status":  "success",
		"created": createdCount,
	})
}

// ProcessAndStoreAvatar resizes a user's avatar (PNG) and stores it.
func (h *FileOperationHandler) ProcessAndStoreAvatar(c echo.Context) error {
	userID, err := uuid.Parse(c.Param("user_id"))
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"message": "Invalid user ID"})
	}

	fileHeader, err := c.FormFile("avatar")
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"message": "avatar file is required"})
	}

	src, err := fileHeader.Open()
	if err != nil {
		return echo.NewHTTPError(http.StatusInternalServerError, "Could not open avatar file")
	}
	defer src.Close()

	img, err := png.Decode(src)
	if err != nil {
		return echo.NewHTTPError(http.StatusBadRequest, "Invalid image format; only PNG is supported")
	}

	// Resize to a 128x128 thumbnail
	thumb := resize.Thumbnail(128, 128, img, resize.Lanczos3)

	// Use a temporary file for processing
	tmpFile, err := os.CreateTemp(os.TempDir(), "avatar-*.png")
	if err != nil {
		return echo.NewHTTPError(http.StatusInternalServerError, "Could not create temp file")
	}
	defer os.Remove(tmpFile.Name())

	if err = png.Encode(tmpFile, thumb); err != nil {
		return echo.NewHTTPError(http.StatusInternalServerError, "Could not encode resized avatar")
	}
	tmpFile.Close()

	h.Logger.Infof("Avatar for user %s processed and saved to %s", userID, tmpFile.Name())
	return c.JSON(http.StatusOK, map[string]string{
		"status":   "success",
		"user_id":  userID.String(),
		"tempFile": tmpFile.Name(),
	})
}

// StreamDataExport provides a file download via streaming.
func (h *FileOperationHandler) StreamDataExport(c echo.Context) error {
	// Create a temporary file with some data to stream
	tmpFile, err := os.CreateTemp("", "export-*.txt")
	if err != nil {
		return echo.NewHTTPError(http.StatusInternalServerError, "Failed to create export file")
	}
	defer os.Remove(tmpFile.Name())

	content := "This is a sample data export.\n"
	content += fmt.Sprintf("Report generated at: %s\n", time.Now().UTC().Format(time.RFC1123))
	content += fmt.Sprintf("Total users in DB: %d\n", len(h.DB.Users))

	if _, err := tmpFile.WriteString(content); err != nil {
		return echo.NewHTTPError(http.StatusInternalServerError, "Failed to write to export file")
	}
	tmpFile.Close()

	// Stream the file
	c.Response().Header().Set(echo.HeaderContentDisposition, "attachment; filename=\"data_export.txt\"")
	return c.File(tmpFile.Name())
}

func main() {
	e := echo.New()
	e.Use(middleware.Logger())
	e.Use(middleware.Recover())

	db := NewMockDB()
	handler := NewFileOperationHandler(db, e.Logger)

	// --- Routes ---
	g := e.Group("/files")
	g.POST("/users/import", handler.UploadUsersFromXLSX)
	g.POST("/users/:user_id/avatar", handler.ProcessAndStoreAvatar)
	g.GET("/export", handler.StreamDataExport)

	e.GET("/", func(c echo.Context) error {
		return c.String(http.StatusOK, "File Operations Server (OOP Style) is running.")
	})

	log.Println("Starting server on http://localhost:8080")
	e.Logger.Fatal(e.Start(":8080"))
}