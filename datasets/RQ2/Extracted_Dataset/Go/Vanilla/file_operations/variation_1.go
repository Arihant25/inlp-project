package main

import (
	"bufio"
	"bytes"
	"encoding/csv"
	"errors"
	"fmt"
	"image"
	"image/jpeg"
	"image/png"
	"io"
	"log"
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// --- Domain Schema ---

type Role string

const (
	AdminRole Role = "ADMIN"
	UserRole  Role = "USER"
)

type User struct {
	ID           string
	Email        string
	PasswordHash string
	UserRole     Role
	IsActive     bool
	CreatedAt    time.Time
}

type PostStatus string

const (
	DraftStatus     PostStatus = "DRAFT"
	PublishedStatus PostStatus = "PUBLISHED"
)

type Post struct {
	ID      string
	UserID  string
	Title   string
	Content string
	Status  PostStatus
}

// Mock database
var mockUsers = make(map[string]User)
var mockPosts = make(map[string]Post)
var mockPostAttachments = make(map[string]string) // postID -> filePath

// --- Main Application ---

func main() {
	// Setup mock data
	mockPosts["post-123"] = Post{ID: "post-123", UserID: "user-456", Title: "My First Post", Content: "Hello World!", Status: PublishedStatus}
	// Create a dummy file for download
	tempDir := os.TempDir()
	dummyFilePath := filepath.Join(tempDir, "sample-attachment.txt")
	err := os.WriteFile(dummyFilePath, []byte("This is the content of the downloadable file."), 0644)
	if err != nil {
		log.Fatalf("Failed to create dummy file: %v", err)
	}
	mockPostAttachments["post-123"] = dummyFilePath
	defer os.Remove(dummyFilePath)

	http.HandleFunc("/upload-users-csv", handleUserCsvUpload)
	http.HandleFunc("/upload-post-image", handlePostImageUpload)
	http.HandleFunc("/download-post-attachment", handleFileDownload)

	log.Println("Server starting on :8080...")
	if err := http.ListenAndServe(":8080", nil); err != nil {
		log.Fatalf("Server failed to start: %v", err)
	}
}

// --- HTTP Handlers (Procedural Style) ---

func handleUserCsvUpload(responseWriter http.ResponseWriter, request *http.Request) {
	if request.Method != http.MethodPost {
		http.Error(responseWriter, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	parsedFiles, err := parseMultipartRequestManually(request)
	if err != nil {
		http.Error(responseWriter, fmt.Sprintf("Error parsing multipart form: %v", err), http.StatusBadRequest)
		return
	}

	// Clean up all temporary files created during parsing
	for _, tempFile := range parsedFiles {
		defer os.Remove(tempFile.Name())
	}

	var usersFile *os.File
	for _, f := range parsedFiles {
		// In a real app, you'd check the form field name, e.g., from f.FieldName
		if strings.HasSuffix(f.FileName, ".csv") {
			usersFile = f.File
			break
		}
	}

	if usersFile == nil {
		http.Error(responseWriter, "No CSV file found in upload", http.StatusBadRequest)
		return
	}

	// Rewind the file pointer to the beginning before reading
	if _, err := usersFile.Seek(0, 0); err != nil {
		http.Error(responseWriter, "Could not read temporary file", http.StatusInternalServerError)
		return
	}

	users, err := processCsvData(usersFile)
	if err != nil {
		http.Error(responseWriter, fmt.Sprintf("Error processing CSV: %v", err), http.StatusInternalServerError)
		return
	}

	// Store users in mock DB
	for _, u := range users {
		mockUsers[u.ID] = u
		log.Printf("Processed and stored user: %s", u.Email)
	}

	responseWriter.WriteHeader(http.StatusOK)
	fmt.Fprintf(responseWriter, "Successfully processed %d users from CSV.", len(users))
}

func handlePostImageUpload(responseWriter http.ResponseWriter, request *http.Request) {
	if request.Method != http.MethodPost {
		http.Error(responseWriter, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	parsedFiles, err := parseMultipartRequestManually(request)
	if err != nil {
		http.Error(responseWriter, fmt.Sprintf("Error parsing multipart form: %v", err), http.StatusBadRequest)
		return
	}

	// Clean up all temporary files
	for _, tempFile := range parsedFiles {
		defer os.Remove(tempFile.Name())
	}

	var imageFile *os.File
	for _, f := range parsedFiles {
		// A simple check for image content types
		contentType := mime.TypeByExtension(filepath.Ext(f.FileName))
		if strings.HasPrefix(contentType, "image/") {
			imageFile = f.File
			break
		}
	}

	if imageFile == nil {
		http.Error(responseWriter, "No image file found in upload", http.StatusBadRequest)
		return
	}

	if _, err := imageFile.Seek(0, 0); err != nil {
		http.Error(responseWriter, "Could not read temporary image file", http.StatusInternalServerError)
		return
	}

	resizedImage, format, err := resizeImage(imageFile, 150, 150)
	if err != nil {
		http.Error(responseWriter, fmt.Sprintf("Error processing image: %v", err), http.StatusInternalServerError)
		return
	}

	// In a real app, you would save this resized image permanently
	log.Printf("Image successfully resized. Original format: %s", format)

	responseWriter.WriteHeader(http.StatusOK)
	fmt.Fprintf(responseWriter, "Image uploaded and resized successfully.")
}

func handleFileDownload(responseWriter http.ResponseWriter, request *http.Request) {
	postID := request.URL.Query().Get("post_id")
	if postID == "" {
		http.Error(responseWriter, "Missing post_id query parameter", http.StatusBadRequest)
		return
	}

	filePath, ok := mockPostAttachments[postID]
	if !ok {
		http.Error(responseWriter, "Attachment not found for the given post", http.StatusNotFound)
		return
	}

	file, err := os.Open(filePath)
	if err != nil {
		http.Error(responseWriter, "Could not open file", http.StatusInternalServerError)
		return
	}
	defer file.Close()

	fileInfo, err := file.Stat()
	if err != nil {
		http.Error(responseWriter, "Could not get file info", http.StatusInternalServerError)
		return
	}

	responseWriter.Header().Set("Content-Disposition", "attachment; filename="+filepath.Base(filePath))
	responseWriter.Header().Set("Content-Type", "application/octet-stream")
	responseWriter.Header().Set("Content-Length", strconv.FormatInt(fileInfo.Size(), 10))

	// Stream the file
	bytesCopied, err := io.Copy(responseWriter, file)
	if err != nil {
		log.Printf("Error streaming file: %v", err)
	}
	log.Printf("Streamed %d bytes for download.", bytesCopied)
}

// --- Helper Functions ---

type ParsedFile struct {
	FieldName string
	FileName  string
	File      *os.File
}

// parseMultipartRequestManually demonstrates manual stream parsing of a multipart request.
func parseMultipartRequestManually(request *http.Request) ([]ParsedFile, error) {
	contentType := request.Header.Get("Content-Type")
	_, params, err := mime.ParseMediaType(contentType)
	if err != nil {
		return nil, fmt.Errorf("could not parse content type: %w", err)
	}

	boundary, ok := params["boundary"]
	if !ok {
		return nil, errors.New("no boundary found in content type")
	}

	var parsedFiles []ParsedFile
	bodyReader := bufio.NewReader(request.Body)
	boundaryBytes := []byte("--" + boundary)
	finalBoundaryBytes := []byte("--" + boundary + "--")

	// Discard preamble before the first boundary
	for {
		line, err := bodyReader.ReadBytes('\n')
		if err != nil {
			return nil, fmt.Errorf("error finding first boundary: %w", err)
		}
		if bytes.Contains(line, boundaryBytes) {
			break
		}
	}

	for {
		var partHeaders strings.Builder
		for {
			line, err := bodyReader.ReadBytes('\n')
			if err != nil {
				return nil, fmt.Errorf("error reading part headers: %w", err)
			}
			if len(bytes.TrimSpace(line)) == 0 { // End of headers
				break
			}
			partHeaders.Write(line)
		}

		disposition, dispParams, err := mime.ParseMediaType(partHeaders.String())
		if err != nil || !strings.HasPrefix(disposition, "Content-Disposition: form-data") {
			// Not a file part we can handle, skip it
			continue
		}

		fileName, hasFileName := dispParams["filename"]
		if !hasFileName {
			// This is a form field, not a file, skip for this example
			continue
		}
		fieldName := dispParams["name"]

		tempFile, err := os.CreateTemp("", "upload-*-"+filepath.Base(fileName))
		if err != nil {
			return nil, fmt.Errorf("could not create temp file: %w", err)
		}

		// Stream part body to temp file, watching for the next boundary
		var buffer [4096]byte
		var overflow []byte // To handle boundary split across reads
		for {
			n, err := bodyReader.Read(buffer[:])
			if n > 0 {
				searchData := append(overflow, buffer[:n]...)
				if idx := bytes.Index(searchData, boundaryBytes); idx != -1 {
					// Boundary found. Write data before it.
					if _, wErr := tempFile.Write(searchData[:idx]); wErr != nil {
						return nil, wErr
					}
					// Check if it's the final boundary
					if bytes.Contains(searchData[idx:], finalBoundaryBytes) {
						return parsedFiles, nil // End of multipart data
					}
					goto nextPart
				}

				// Boundary not found, write all but the tail that could be a partial boundary
				writeLen := len(searchData) - len(boundaryBytes) - 2
				if writeLen < 0 {
					writeLen = 0
				}
				if _, wErr := tempFile.Write(searchData[:writeLen]); wErr != nil {
					return nil, wErr
				}
				overflow = searchData[writeLen:]
			}
			if err == io.EOF {
				return nil, errors.New("unexpected EOF, missing final boundary")
			}
			if err != nil {
				return nil, err
			}
		}
	nextPart:
		parsedFiles = append(parsedFiles, ParsedFile{FieldName: fieldName, FileName: fileName, File: tempFile})
	}
}

func processCsvData(fileReader io.Reader) ([]User, error) {
	csvReader := csv.NewReader(fileReader)
	// Assuming header: id,email,role
	if _, err := csvReader.Read(); err != nil {
		return nil, fmt.Errorf("failed to read CSV header: %w", err)
	}

	records, err := csvReader.ReadAll()
	if err != nil {
		return nil, fmt.Errorf("failed to read CSV records: %w", err)
	}

	var users []User
	for _, record := range records {
		if len(record) < 3 {
			continue
		}
		user := User{
			ID:        record[0],
			Email:     record[1],
			UserRole:  Role(record[2]),
			IsActive:  true,
			CreatedAt: time.Now(),
		}
		users = append(users, user)
	}
	return users, nil
}

func resizeImage(fileReader io.Reader, targetWidth, targetHeight int) (image.Image, string, error) {
	sourceImage, format, err := image.Decode(fileReader)
	if err != nil {
		return nil, "", fmt.Errorf("could not decode image: %w", err)
	}

	// Simple Nearest-neighbor resizing algorithm
	sourceBounds := sourceImage.Bounds()
	resizedRect := image.Rect(0, 0, targetWidth, targetHeight)
	resizedImage := image.NewRGBA(resizedRect)

	xRatio := float64(sourceBounds.Dx()) / float64(targetWidth)
	yRatio := float64(sourceBounds.Dy()) / float64(targetHeight)

	for y := 0; y < targetHeight; y++ {
		for x := 0; x < targetWidth; x++ {
			sourceX := int(float64(x) * xRatio)
			sourceY := int(float64(y) * yRatio)
			pixel := sourceImage.At(sourceX+sourceBounds.Min.X, sourceY+sourceBounds.Min.Y)
			resizedImage.Set(x, y, pixel)
		}
	}

	// Example of re-encoding (though not used further here)
	var out bytes.Buffer
	switch format {
	case "jpeg":
		jpeg.Encode(&out, resizedImage, nil)
	case "png":
		png.Encode(&out, resizedImage)
	default:
		return nil, "", fmt.Errorf("unsupported image format: %s", format)
	}

	return resizedImage, format, nil
}