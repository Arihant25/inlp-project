package main

import (
	"encoding/csv"
	"fmt"
	"image"
	_ "image/gif"
	"image/jpeg"
	_ "image/png"
	"io"
	"log"
	"os"
	"strconv"
	"sync"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/gofiber/fiber/v2/middleware/recover"
	"github.com/google/uuid"
	"github.com/nfnt/resize"
)

// --- Domain & Mock DB ---

type UserRole string
const (
	ADMIN UserRole = "ADMIN"
	USER  UserRole = "USER"
)
type PostStatus string
const (
	DRAFT     PostStatus = "DRAFT"
	PUBLISHED PostStatus = "PUBLISHED"
)

type User struct {
	ID uuid.UUID; Email string; PasswordHash string; Role UserRole; IsActive bool; CreatedAt time.Time
}
type Post struct {
	ID uuid.UUID; UserID uuid.UUID; Title string; Content string; Status PostStatus
}

var (
	dbUsers = make(map[uuid.UUID]User)
	dbPosts = make(map[uuid.UUID]Post)
	dbLock  = &sync.RWMutex{}
)

// --- Helper Functions ---

func checkErr(c *fiber.Ctx, err error, status int, msg string) bool {
	if err != nil {
		log.Printf("Error: %v", err)
		c.Status(status).JSON(fiber.Map{"error": msg, "details": err.Error()})
		return true
	}
	return false
}

func parseUsersFromCSV(f io.Reader) (int, error) {
	records, err := csv.NewReader(f).ReadAll()
	if err != nil {
		return 0, err
	}
	if len(records) <= 1 {
		return 0, fmt.Errorf("csv is empty or has no data rows")
	}

	dbLock.Lock()
	defer dbLock.Unlock()
	
	createdCount := 0
	for _, row := range records[1:] { // Skip header
		isActive, _ := strconv.ParseBool(row[3])
		u := User{
			ID: uuid.New(), Email: row[0], PasswordHash: row[1],
			Role: UserRole(row[2]), IsActive: isActive, CreatedAt: time.Now(),
		}
		dbUsers[u.ID] = u
		createdCount++
	}
	return createdCount, nil
}

func processImage(f io.Reader, width uint) (*os.File, error) {
	img, _, err := image.Decode(f)
	if err != nil {
		return nil, err
	}

	resized := resize.Resize(width, 0, img, resize.Lanczos2)
	
	tmpFile, err := os.CreateTemp("", "thumb_*.jpg")
	if err != nil {
		return nil, err
	}

	err = jpeg.Encode(tmpFile, resized, &jpeg.Options{Quality: 75})
	if err != nil {
		tmpFile.Close()
		os.Remove(tmpFile.Name())
		return nil, err
	}
	
	// Rewind the file pointer for potential future reads
	tmpFile.Seek(0, 0)
	return tmpFile, nil
}

// --- Main Application ---

func main() {
	// Seed
	adminId := uuid.New()
	dbUsers[adminId] = User{ID: adminId, Email: "admin@example.com", Role: ADMIN, IsActive: true}
	postId := uuid.New()
	dbPosts[postId] = Post{ID: postId, UserID: adminId, Title: "My First Post", Status: PUBLISHED}

	app := fiber.New()
	app.Use(recover.New())

	// Route: Bulk user import via CSV
	app.Post("/upload/users", func(c *fiber.Ctx) error {
		hdr, err := c.FormFile("csv")
		if checkErr(c, err, fiber.StatusBadRequest, "missing 'csv' file") {
			return nil
		}

		f, err := hdr.Open()
		if checkErr(c, err, fiber.StatusInternalServerError, "failed to open file") {
			return nil
		}
		defer f.Close()

		count, err := parseUsersFromCSV(f)
		if checkErr(c, err, fiber.StatusUnprocessableEntity, "failed to parse CSV") {
			return nil
		}

		return c.JSON(fiber.Map{"message": fmt.Sprintf("processed %d users", count)})
	})

	// Route: Upload and resize a post's image
	app.Post("/upload/post/:id/image", func(c *fiber.Ctx) error {
		id, err := uuid.Parse(c.Params("id"))
		if checkErr(c, err, fiber.StatusBadRequest, "invalid post id") {
			return nil
		}

		dbLock.RLock()
		_, ok := dbPosts[id]
		dbLock.RUnlock()
		if !ok {
			return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": "post not found"})
		}

		hdr, err := c.FormFile("image")
		if checkErr(c, err, fiber.StatusBadRequest, "missing 'image' file") {
			return nil
		}

		f, err := hdr.Open()
		if checkErr(c, err, fiber.StatusInternalServerError, "failed to open image") {
			return nil
		}
		defer f.Close()

		tmpFile, err := processImage(f, 500)
		if checkErr(c, err, fiber.StatusInternalServerError, "failed to process image") {
			return nil
		}
		defer os.Remove(tmpFile.Name()) // Cleanup temp file
		defer tmpFile.Close()

		log.Printf("Image for post %s processed to %s", id, tmpFile.Name())
		// In a real app, this file would be moved to permanent storage.

		return c.JSON(fiber.Map{
			"message": "image resized successfully",
			"postId":  id,
			"tempFile": tmpFile.Name(),
		})
	})

	// Route: Download a streaming CSV report of posts
	app.Get("/download/posts.csv", func(c *fiber.Ctx) error {
		c.Set("Content-Type", "text/csv")
		c.Set("Content-Disposition", "attachment; filename=posts.csv")

		return c.SendStream(func(w io.Writer) error {
			csvW := csv.NewWriter(w)
			
			if err := csvW.Write([]string{"id", "user_id", "title", "status"}); err != nil {
				return err
			}

			dbLock.RLock()
			defer dbLock.RUnlock()
			for _, p := range dbPosts {
				row := []string{p.ID.String(), p.UserID.String(), p.Title, string(p.Status)}
				if err := csvW.Write(row); err != nil {
					return err
				}
			}
			
			csvW.Flush()
			return csvW.Error()
		})
	})

	log.Fatal(app.Listen(":3000"))
}