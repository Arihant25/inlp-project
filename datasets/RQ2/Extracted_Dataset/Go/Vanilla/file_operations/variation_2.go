package main

import (
	"bufio"
	"bytes"
	"encoding/csv"
	"errors"
	"fmt"
	"image"
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

// --- Mock Data Store ---

type DataStore struct {
	users           map[string]User
	posts           map[string]Post
	postAttachments map[string]string // postID -> filePath
}

func NewDataStore() *DataStore {
	ds := &DataStore{
		users:           make(map[string]User),
		posts:           make(map[string]Post),
		postAttachments: make(map[string]string),
	}
	// Seed data
	ds.posts["post-001"] = Post{ID: "post-001", UserID: "user-007", Title: "A Downloadable Post", Content: "Content here."}
	return ds
}

// --- File Operation Service (OOP Style) ---

type FileOperationService struct {
	store       *DataStore
	tempFileDir string
}

func NewFileOperationService(store *DataStore) *FileOperationService {
	return &FileOperationService{
		store:       store,
		tempFileDir: os.TempDir(),
	}
}

// UploadUsersFromCSVHandler is an HTTP handler for uploading and processing a user CSV.
func (s *FileOperationService) UploadUsersFromCSVHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	parsedParts, err := s.parseMultipartStream(r)
	if err != nil {
		http.Error(w, fmt.Sprintf("Parsing error: %v", err), http.StatusBadRequest)
		return
	}
	defer s.cleanupTempFiles(parsedParts)

	csvPart := s.findPartByExtension(parsedParts, ".csv")
	if csvPart == nil {
		http.Error(w, "No CSV file part found", http.StatusBadRequest)
		return
	}

	users, err := s.parseUserCSV(csvPart.File)
	if err != nil {
		http.Error(w, fmt.Sprintf("CSV processing error: %v", err), http.StatusInternalServerError)
		return
	}

	for _, user := range users {
		s.store.users[user.ID] = user
		log.Printf("Service stored user: %s", user.Email)
	}

	w.WriteHeader(http.StatusOK)
	fmt.Fprintf(w, "Successfully processed %d users.", len(users))
}

// ProcessPostImageHandler is an HTTP handler for uploading and resizing an image.
func (s *FileOperationService) ProcessPostImageHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	parsedParts, err := s.parseMultipartStream(r)
	if err != nil {
		http.Error(w, fmt.Sprintf("Parsing error: %v", err), http.StatusBadRequest)
		return
	}
	defer s.cleanupTempFiles(parsedParts)

	imagePart := s.findPartByMimePrefix(parsedParts, "image/")
	if imagePart == nil {
		http.Error(w, "No image file part found", http.StatusBadRequest)
		return
	}

	resizedImg, err := s.resizeImage(imagePart.File, 200, 200)
	if err != nil {
		http.Error(w, fmt.Sprintf("Image processing error: %v", err), http.StatusInternalServerError)
		return
	}

	// In a real app, save the resized image. Here we just log.
	log.Printf("Image %s resized successfully. New dimensions: %v", imagePart.FileName, resizedImg.Bounds())
	w.WriteHeader(http.StatusOK)
	fmt.Fprint(w, "Image processed successfully.")
}

// StreamFileHandler provides a file for download.
func (s *FileOperationService) StreamFileHandler(w http.ResponseWriter, r *http.Request) {
	postID := r.URL.Query().Get("post_id")
	filePath, ok := s.store.postAttachments[postID]
	if !ok {
		http.NotFound(w, r)
		return
	}

	file, err := os.Open(filePath)
	if err != nil {
		http.Error(w, "File not accessible", http.StatusInternalServerError)
		return
	}
	defer file.Close()

	stat, _ := file.Stat()
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Disposition", "attachment; filename="+filepath.Base(filePath))
	w.Header().Set("Content-Length", strconv.FormatInt(stat.Size(), 10))

	if _, err := io.Copy(w, file); err != nil {
		log.Printf("Error during file streaming: %v", err)
	}
}

// --- Private Service Methods ---

type multipartPart struct {
	FieldName string
	FileName  string
	File      *os.File
}

func (s *FileOperationService) parseMultipartStream(r *http.Request) ([]*multipartPart, error) {
	contentType := r.Header.Get("Content-Type")
	_, params, err := mime.ParseMediaType(contentType)
	if err != nil {
		return nil, err
	}
	boundary := "--" + params["boundary"]
	finalBoundary := boundary + "--"

	var parts []*multipartPart
	reader := bufio.NewReader(r.Body)

	// Find first boundary
	line, err := reader.ReadString('\n')
	if err != nil || !strings.Contains(line, boundary) {
		return nil, errors.New("multipart boundary not found at start")
	}

	for {
		var headers strings.Builder
		for {
			line, err := reader.ReadString('\n')
			if err != nil {
				return nil, err
			}
			if line == "\r\n" { // End of headers
				break
			}
			headers.WriteString(line)
		}

		_, dispParams, err := mime.ParseMediaType("Content-Disposition: " + headers.String())
		fileName, isFile := dispParams["filename"]
		if !isFile {
			// Skip non-file parts
			continue
		}

		tempF, err := os.CreateTemp(s.tempFileDir, "upload-")
		if err != nil {
			return nil, err
		}

		part := &multipartPart{
			FieldName: dispParams["name"],
			FileName:  fileName,
			File:      tempF,
		}
		parts = append(parts, part)

		// Read body until next boundary
		buf := make([]byte, 1024*4)
		var overflow []byte
		for {
			n, err := reader.Read(buf)
			if n > 0 {
				data := append(overflow, buf[:n]...)
				if i := bytes.Index(data, []byte(boundary)); i != -1 {
					tempF.Write(data[:i-2]) // -2 to remove \r\n
					if bytes.Contains(data[i:], []byte(finalBoundary)) {
						return parts, nil
					}
					goto nextPart
				} else {
					// To avoid splitting boundary, don't write the last few bytes
					writeUpTo := len(data) - len(boundary)
					if writeUpTo < 0 {
						writeUpTo = 0
					}
					tempF.Write(data[:writeUpTo])
					overflow = data[writeUpTo:]
				}
			}
			if err == io.EOF {
				return nil, errors.New("unexpected EOF in multipart stream")
			}
			if err != nil {
				return nil, err
			}
		}
	nextPart:
	}
}

func (s *FileOperationService) cleanupTempFiles(parts []*multipartPart) {
	for _, p := range parts {
		p.File.Close()
		os.Remove(p.File.Name())
	}
}

func (s *FileOperationService) findPartByExtension(parts []*multipartPart, ext string) *multipartPart {
	for _, p := range parts {
		if strings.HasSuffix(strings.ToLower(p.FileName), ext) {
			return p
		}
	}
	return nil
}

func (s *FileOperationService) findPartByMimePrefix(parts []*multipartPart, prefix string) *multipartPart {
	for _, p := range parts {
		mimeType := mime.TypeByExtension(filepath.Ext(p.FileName))
		if strings.HasPrefix(mimeType, prefix) {
			return p
		}
	}
	return nil
}

func (s *FileOperationService) parseUserCSV(f *os.File) ([]User, error) {
	if _, err := f.Seek(0, 0); err != nil {
		return nil, err
	}
	r := csv.NewReader(f)
	r.Read() // Skip header
	records, err := r.ReadAll()
	if err != nil {
		return nil, err
	}
	var users []User
	for _, rec := range records {
		users = append(users, User{ID: rec[0], Email: rec[1], UserRole: Role(rec[2]), CreatedAt: time.Now()})
	}
	return users, nil
}

func (s *FileOperationService) resizeImage(f *os.File, w, h int) (image.Image, error) {
	if _, err := f.Seek(0, 0); err != nil {
		return nil, err
	}
	img, _, err := image.Decode(f)
	if err != nil {
		return nil, err
	}

	dst := image.NewRGBA(image.Rect(0, 0, w, h))
	srcBounds := img.Bounds()
	xRatio := float64(srcBounds.Dx()) / float64(w)
	yRatio := float64(srcBounds.Dy()) / float64(h)

	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			srcX := int(float64(x) * xRatio)
			srcY := int(float64(y) * yRatio)
			dst.Set(x, y, img.At(srcX+srcBounds.Min.X, srcY+srcBounds.Min.Y))
		}
	}
	return dst, nil
}

// --- Main Application Setup ---

func main() {
	dataStore := NewDataStore()
	// Create a dummy file for download
	dummyFile, err := os.CreateTemp("", "download-*.txt")
	if err != nil {
		log.Fatal(err)
	}
	dummyFile.WriteString("This is a sample file for streaming download.")
	dummyFile.Close()
	dataStore.postAttachments["post-001"] = dummyFile.Name()
	defer os.Remove(dummyFile.Name())

	fileService := NewFileOperationService(dataStore)

	mux := http.NewServeMux()
	mux.HandleFunc("/users/import", fileService.UploadUsersFromCSVHandler)
	mux.HandleFunc("/posts/image", fileService.ProcessPostImageHandler)
	mux.HandleFunc("/posts/attachment", fileService.StreamFileHandler)

	log.Println("Starting OOP-style server on :8080...")
	if err := http.ListenAndServe(":8080", mux); err != nil {
		log.Fatalf("Server failed: %v", err)
	}
}