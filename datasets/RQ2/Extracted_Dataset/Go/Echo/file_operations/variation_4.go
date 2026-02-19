package main

import (
	"encoding/csv"
	"fmt"
	"image/png"
	"io"
	"log"
	"net/http"
	"os"
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

// --- Mock Data Store (Global for simplicity in this style) ---
var usersDB = make(map[uuid.UUID]User)

func main() {
	e := echo.New()
	e.Use(middleware.Logger())
	e.Use(middleware.Recover())

	// --- Routes with Inline Handlers (Closures) ---

	// Endpoint to upload and parse an Excel (XLSX) file of users
	e.POST("/import/users", func(c echo.Context) error {
		fh, err := c.FormFile("file")
		if err != nil {
			return c.JSON(http.StatusBadRequest, echo.Map{"error": "file is required"})
		}

		f, err := fh.Open()
		if err != nil {
			return echo.NewHTTPError(http.StatusInternalServerError, "failed to open file")
		}
		defer f.Close()

		// Use a temporary file to read from, as some libraries work better with files
		tmp, err := os.CreateTemp("", "upload-*.xlsx")
		if err != nil {
			return echo.NewHTTPError(http.StatusInternalServerError, "failed to create temp file")
		}
		defer os.Remove(tmp.Name())

		if _, err := io.Copy(tmp, f); err != nil {
			return echo.NewHTTPError(http.StatusInternalServerError, "failed to copy to temp file")
		}
		tmp.Close() // Close before excelize opens it

		xlsx, err := excelize.OpenFile(tmp.Name())
		if err != nil {
			return c.JSON(http.StatusUnprocessableEntity, echo.Map{"error": "failed to parse xlsx"})
		}

		rows, err := xlsx.GetRows(xlsx.GetSheetName(0))
		if err != nil || len(rows) < 2 {
			return c.JSON(http.StatusBadRequest, echo.Map{"error": "invalid or empty sheet"})
		}

		var count int
		for _, row := range rows[1:] {
			isActive, _ := strconv.ParseBool(row[1])
			user := User{
				ID:        uuid.New(),
				Email:     row[0],
				IsActive:  isActive,
				Role:      USER,
				CreatedAt: time.Now(),
			}
			usersDB[user.ID] = user
			count++
		}

		return c.JSON(http.StatusCreated, echo.Map{
			"message": fmt.Sprintf("Successfully imported %d users.", count),
		})
	})

	// Endpoint to upload a PNG, resize it, and return info
	e.POST("/process/image", func(c echo.Context) error {
		fh, err := c.FormFile("image")
		if err != nil {
			return c.JSON(http.StatusBadRequest, echo.Map{"error": "image is required"})
		}

		f, err := fh.Open()
		if err != nil {
			return echo.NewHTTPError(http.StatusInternalServerError, "failed to open image")
		}
		defer f.Close()

		img, err := png.Decode(f)
		if err != nil {
			return c.JSON(http.StatusUnprocessableEntity, echo.Map{"error": "invalid png format"})
		}

		// Resize to a fixed 200x200 thumbnail
		thumb := resize.Thumbnail(200, 200, img, resize.Lanczos3)

		// Save to a temporary file
		tmp, err := os.CreateTemp("", "thumb-*.png")
		if err != nil {
			return echo.NewHTTPError(http.StatusInternalServerError, "failed to create temp thumb")
		}
		defer os.Remove(tmp.Name())

		if err = png.Encode(tmp, thumb); err != nil {
			return echo.NewHTTPError(http.StatusInternalServerError, "failed to save thumb")
		}

		log.Printf("Thumbnail created at %s", tmp.Name())
		return c.JSON(http.StatusOK, echo.Map{
			"message":  "Image processed",
			"tempPath": tmp.Name(),
			"width":    thumb.Bounds().Dx(),
			"height":   thumb.Bounds().Dy(),
		})
	})

	// Endpoint to download a CSV report by streaming it
	e.GET("/export/users", func(c echo.Context) error {
		c.Response().Header().Set(echo.HeaderContentType, "text/csv")
		c.Response().Header().Set(echo.HeaderContentDisposition, "attachment; filename=\"users.csv\"")

		// Stream directly to the response writer
		w := csv.NewWriter(c.Response())
		if err := w.Write([]string{"id", "email", "is_active", "created_at"}); err != nil {
			return err
		}

		for _, u := range usersDB {
			record := []string{
				u.ID.String(),
				u.Email,
				strconv.FormatBool(u.IsActive),
				u.CreatedAt.Format(time.RFC3339),
			}
			if err := w.Write(record); err != nil {
				return err
			}
		}
		w.Flush()
		return nil
	})

	log.Println("Minimalist server starting on :8080")
	e.Logger.Fatal(e.Start(":8080"))
}