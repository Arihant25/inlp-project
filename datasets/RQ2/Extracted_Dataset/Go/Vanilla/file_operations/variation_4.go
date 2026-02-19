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
	RoleAdmin Role = "ADMIN"
	RoleUser  Role = "USER"
)
type User struct{ ID, Email, PasswordHash string; Role Role; IsActive bool; CreatedAt time.Time }

type PostStatus string
const (
	StatusDraft     PostStatus = "DRAFT"
	StatusPublished PostStatus = "PUBLISHED"
)
type Post struct{ ID, UserID, Title, Content string; Status PostStatus }

// --- Interfaces for Dependency Injection ---

type Uploader interface {
	Upload(r *http.Request) (filePath, originalFilename string, err error)
}

type CSVParser interface {
	ParseUsers(reader io.Reader) ([]User, error)
}

type ImageProcessor interface {
	Resize(reader io.Reader, width, height int) (image.Image, error)
}

type Downloader interface {
	Stream(w http.ResponseWriter, filePath string) error
}

// --- Concrete Implementations ---

// ManualStreamUploader implements Uploader with manual multipart parsing.
type ManualStreamUploader struct {
	TempDir string
}

func NewManualStreamUploader() *ManualStreamUploader {
	return &ManualStreamUploader{TempDir: os.TempDir()}
}

func (u *ManualStreamUploader) Upload(r *http.Request) (string, string, error) {
	_, params, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if err != nil {
		return "", "", fmt.Errorf("invalid content-type: %w", err)
	}
	boundary := params["boundary"]
	if boundary == "" {
		return "", "", errors.New("multipart boundary not found")
	}

	reader := bufio.NewReader(r.Body)
	boundaryBytes := []byte("--" + boundary)

	// Advance to the first part's headers
	for {
		line, isPrefix, err := reader.ReadLine()
		if err != nil || isPrefix {
			return "", "", fmt.Errorf("error finding first boundary: %w", err)
		}
		if bytes.Contains(line, boundaryBytes) {
			break
		}
	}

	var filename string
	for {
		line, _, err := reader.ReadLine()
		if err != nil || len(line) == 0 { // Empty line signifies end of headers
			break
		}
		if bytes.Contains(bytes.ToLower(line), []byte("content-disposition")) {
			_, dparams, _ := mime.ParseMediaType(string(line))
			filename = dparams["filename"]
		}
	}
	if filename == "" {
		return "", "", errors.New("no file part detected")
	}

	tmpFile, err := os.CreateTemp(u.TempDir, "upload-*-"+filepath.Base(filename))
	if err != nil {
		return "", "", err
	}

	// Stream body to file, watching for boundary
	boundaryWithNewline := append([]byte("\r\n"), boundaryBytes...)
	buf := make([]byte, 4096)
	var trailingBytes []byte
	for {
		n, err := reader.Read(buf)
		if n > 0 {
			data := append(trailingBytes, buf[:n]...)
			if i := bytes.Index(data, boundaryWithNewline); i != -1 {
				if _, wErr := tmpFile.Write(data[:i]); wErr != nil {
					return "", "", wErr
				}
				return tmpFile.Name(), filename, nil
			}
			
			writeUpto := len(data) - len(boundaryWithNewline)
			if writeUpto < 0 { writeUpto = 0 }
			if _, wErr := tmpFile.Write(data[:writeUpto]); wErr != nil {
				return "", "", wErr
			}
			trailingBytes = data[writeUpto:]
		}
		if err == io.EOF {
			if _, wErr := tmpFile.Write(trailingBytes); wErr != nil {
				return "", "", wErr
			}
			break
		}
		if err != nil {
			return "", "", err
		}
	}
	return tmpFile.Name(), filename, nil
}

// StandardCSVParser implements CSVParser.
type StandardCSVParser struct{}

func (p *StandardCSVParser) ParseUsers(reader io.Reader) ([]User, error) {
	r := csv.NewReader(reader)
	r.Read() // Skip header
	records, err := r.ReadAll()
	if err != nil {
		return nil, err
	}
	var users []User
	for _, rec := range records {
		if len(rec) < 2 { continue }
		users = append(users, User{ID: rec[0], Email: rec[1], Role: RoleUser, IsActive: true})
	}
	return users, nil
}

// StandardImageProcessor implements ImageProcessor.
type StandardImageProcessor struct{}

func (p *StandardImageProcessor) Resize(reader io.Reader, width, height int) (image.Image, error) {
	img, _, err := image.Decode(reader)
	if err != nil {
		return nil, err
	}
	// Basic nearest-neighbor scaling
	dst := image.NewRGBA(image.Rect(0, 0, width, height))
	x_ratio := float64(img.Bounds().Dx()) / float64(width)
	y_ratio := float64(img.Bounds().Dy()) / float64(height)
	for y := 0; y < height; y++ {
		for x := 0; x < width; x++ {
			px := int(float64(x) * x_ratio)
			py := int(float64(y) * y_ratio)
			dst.Set(x, y, img.At(px, py))
		}
	}
	return dst, nil
}

// FileStreamDownloader implements Downloader.
type FileStreamDownloader struct{}

func (d *FileStreamDownloader) Stream(w http.ResponseWriter, filePath string) error {
	f, err := os.Open(filePath)
	if err != nil {
		return err
	}
	defer f.Close()
	stat, err := f.Stat()
	if err != nil {
		return err
	}
	w.Header().Set("Content-Disposition", "attachment; filename="+filepath.Base(filePath))
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", strconv.FormatInt(stat.Size(), 10))
	_, err = io.Copy(w, f)
	return err
}

// --- Web Service using Dependency Injection ---

type WebService struct {
	uploader       Uploader
	csvParser      CSVParser
	imageProcessor ImageProcessor
	downloader     Downloader
	// Mock DB
	userStore map[string]User
	fileStore map[string]string
}

func NewWebService(u Uploader, p CSVParser, i ImageProcessor, d Downloader) *WebService {
	return &WebService{
		uploader:       u,
		csvParser:      p,
		imageProcessor: i,
		downloader:     d,
		userStore:      make(map[string]User),
		fileStore:      make(map[string]string),
	}
}

func (ws *WebService) HandleUserImport(w http.ResponseWriter, r *http.Request) {
	tmpPath, _, err := ws.uploader.Upload(r)
	if err != nil {
		http.Error(w, "Upload failed: "+err.Error(), http.StatusBadRequest)
		return
	}
	defer os.Remove(tmpPath)

	f, err := os.Open(tmpPath)
	if err != nil {
		http.Error(w, "Could not open temp file", http.StatusInternalServerError)
		return
	}
	defer f.Close()

	users, err := ws.csvParser.ParseUsers(f)
	if err != nil {
		http.Error(w, "CSV parsing failed: "+err.Error(), http.StatusBadRequest)
		return
	}

	for _, user := range users {
		ws.userStore[user.ID] = user
	}
	log.Printf("Imported %d users", len(users))
	fmt.Fprintf(w, "Successfully imported %d users.", len(users))
}

func (ws *WebService) HandleImageUpload(w http.ResponseWriter, r *http.Request) {
	tmpPath, _, err := ws.uploader.Upload(r)
	if err != nil {
		http.Error(w, "Upload failed: "+err.Error(), http.StatusBadRequest)
		return
	}
	defer os.Remove(tmpPath)

	f, err := os.Open(tmpPath)
	if err != nil {
		http.Error(w, "Could not open temp file", http.StatusInternalServerError)
		return
	}
	defer f.Close()

	resizedImg, err := ws.imageProcessor.Resize(f, 80, 80)
	if err != nil {
		http.Error(w, "Image processing failed: "+err.Error(), http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "image/png")
	png.Encode(w, resizedImg)
	log.Println("Resized and served image.")
}

func (ws *WebService) HandleDownload(w http.ResponseWriter, r *http.Request) {
	fileID := r.URL.Query().Get("file_id")
	filePath, ok := ws.fileStore[fileID]
	if !ok {
		http.NotFound(w, r)
		return
	}
	if err := ws.downloader.Stream(w, filePath); err != nil {
		log.Printf("Download stream error: %v", err)
		http.Error(w, "Failed to stream file", http.StatusInternalServerError)
	}
}

// --- Main Application ---
func main() {
	// Dependency Injection: Create concrete instances
	uploader := NewManualStreamUploader()
	csvParser := &StandardCSVParser{}
	imageProcessor := &StandardImageProcessor{}
	downloader := &FileStreamDownloader{}

	// Inject them into the web service
	service := NewWebService(uploader, csvParser, imageProcessor, downloader)

	// Create a dummy file for the downloader to find
	f, _ := os.CreateTemp("", "downloadable.txt")
	f.WriteString("Hello from a downloadable file!")
	f.Close()
	service.fileStore["file123"] = f.Name()
	defer os.Remove(f.Name())

	// Register handlers
	http.HandleFunc("/import/users", service.HandleUserImport)
	http.HandleFunc("/upload/image", service.HandleImageUpload)
	http.HandleFunc("/download", service.HandleDownload)

	log.Println("Starting DI-based server on :8080...")
	http.ListenAndServe(":8080", nil)
}