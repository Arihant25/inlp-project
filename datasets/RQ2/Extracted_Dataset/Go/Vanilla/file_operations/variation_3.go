package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/csv"
	"errors"
	"fmt"
	"image"
	"image/jpeg"
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
	ADMIN Role = "ADMIN"
	USER  Role = "USER"
)
type User struct{ ID, Email, PasswordHash string; Role Role; IsActive bool; CreatedAt time.Time }

type PostStatus string
const (
	DRAFT     PostStatus = "DRAFT"
	PUBLISHED PostStatus = "PUBLISHED"
)
type Post struct{ ID, UserID, Title, Content string; Status PostStatus }

// --- Context Key ---
type contextKey string
const parsedFileKey contextKey = "parsedFile"

// --- Server (Handler-centric) ---
type Server struct {
	db map[string]interface{} // Simple in-memory store
}

func NewServer() *Server {
	// Seed with a downloadable file
	f, err := os.CreateTemp("", "handler-dl-*.txt")
	if err != nil {
		log.Fatal(err)
	}
	f.WriteString("streamed content")
	f.Close()
	return &Server{
		db: map[string]interface{}{
			"post:1:attachmentPath": f.Name(),
		},
	}
}

func (s *Server) cleanup() {
	if path, ok := s.db["post:1:attachmentPath"].(string); ok {
		os.Remove(path)
	}
}

// --- Middleware-like Pattern ---

type ParsedInfo struct {
	Path     string
	Filename string
	MimeType string
}

// parseUpload middleware manually parses a multipart stream and puts info into context.
func (s *Server) parseUpload(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		contentType := r.Header.Get("Content-Type")
		_, params, err := mime.ParseMediaType(contentType)
		if err != nil {
			http.Error(w, "invalid content type", http.StatusBadRequest)
			return
		}
		boundary := params["boundary"]
		if boundary == "" {
			http.Error(w, "missing multipart boundary", http.StatusBadRequest)
			return
		}

		tempFile, filename, err := s.streamPartToTempFile(r.Body, boundary)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		defer tempFile.Close()
		defer os.Remove(tempFile.Name()) // Cleanup after request finishes

		info := ParsedInfo{
			Path:     tempFile.Name(),
			Filename: filename,
			MimeType: mime.TypeByExtension(filepath.Ext(filename)),
		}

		ctx := context.WithValue(r.Context(), parsedFileKey, info)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

// --- Handlers ---

func (s *Server) handleUpload() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		info, ok := r.Context().Value(parsedFileKey).(ParsedInfo)
		if !ok {
			http.Error(w, "file not parsed", http.StatusInternalServerError)
			return
		}

		// Route logic based on file type
		switch {
		case info.MimeType == "text/csv":
			s.processUserImport(w, r, info.Path)
		case strings.HasPrefix(info.MimeType, "image/"):
			s.processImageResize(w, r, info.Path)
		default:
			http.Error(w, "unsupported file type: "+info.MimeType, http.StatusUnsupportedMediaType)
		}
	}
}

func (s *Server) handleDownload() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id := r.URL.Query().Get("id")
		key := "post:" + id + ":attachmentPath"
		path, ok := s.db[key].(string)
		if !ok {
			http.NotFound(w, r)
			return
		}

		f, err := os.Open(path)
		if err != nil {
			http.Error(w, "cannot access file", http.StatusInternalServerError)
			return
		}
		defer f.Close()

		stat, _ := f.Stat()
		w.Header().Set("Content-Length", strconv.FormatInt(stat.Size(), 10))
		w.Header().Set("Content-Disposition", "attachment; filename="+filepath.Base(path))
		w.Header().Set("Content-Type", "application/octet-stream")
		io.Copy(w, f)
	}
}

// --- Business Logic ---

func (s *Server) processUserImport(w http.ResponseWriter, r *http.Request, path string) {
	f, err := os.Open(path)
	if err != nil {
		http.Error(w, "cannot read temp file", http.StatusInternalServerError)
		return
	}
	defer f.Close()

	records, err := csv.NewReader(f).ReadAll()
	if err != nil {
		http.Error(w, "invalid csv format", http.StatusBadRequest)
		return
	}

	count := 0
	for i, rec := range records {
		if i == 0 { continue } // Skip header
		user := User{ID: rec[0], Email: rec[1]}
		s.db["user:"+user.ID] = user
		count++
	}
	log.Printf("Imported %d users", count)
	fmt.Fprintf(w, "Imported %d users", count)
}

func (s *Server) processImageResize(w http.ResponseWriter, r *http.Request, path string) {
	f, err := os.Open(path)
	if err != nil {
		http.Error(w, "cannot read temp image", http.StatusInternalServerError)
		return
	}
	defer f.Close()

	img, _, err := image.Decode(f)
	if err != nil {
		http.Error(w, "cannot decode image", http.StatusBadRequest)
		return
	}

	// Simple resize logic
	resized := image.NewRGBA(image.Rect(0, 0, 100, 100))
	// In a real app, use a proper resizing algorithm. This is a placeholder.
	for y := 0; y < 100; y++ {
		for x := 0; x < 100; x++ {
			resized.Set(x, y, img.At(x, y))
		}
	}

	log.Printf("Resized image from %s", path)
	w.Header().Set("Content-Type", "image/jpeg")
	jpeg.Encode(w, resized, nil)
}

// --- Core Manual Parser ---

func (s *Server) streamPartToTempFile(body io.Reader, boundary string) (*os.File, string, error) {
	r := bufio.NewReader(body)
	boundaryBytes := []byte("--" + boundary)

	// Skip to first part
	for {
		line, _, err := r.ReadLine()
		if err != nil {
			return nil, "", err
		}
		if bytes.Equal(line, boundaryBytes) {
			break
		}
	}

	var filename string
	for { // Read headers
		line, _, err := r.ReadLine()
		if err != nil {
			return nil, "", err
		}
		if len(line) == 0 {
			break
		}
		if bytes.HasPrefix(line, []byte("Content-Disposition")) {
			_, params, _ := mime.ParseMediaType(string(line))
			filename = params["filename"]
		}
	}

	if filename == "" {
		return nil, "", errors.New("no file part found in request")
	}

	tmpFile, err := os.CreateTemp("", "upload-*.bin")
	if err != nil {
		return nil, "", err
	}

	// Stream body to file
	boundaryWithNewline := append([]byte("\r\n"), boundaryBytes...)
	buf := make([]byte, 64*1024)
	var overflow []byte
	for {
		n, err := r.Read(buf)
		if n > 0 {
			data := append(overflow, buf[:n]...)
			if i := bytes.Index(data, boundaryWithNewline); i != -1 {
				tmpFile.Write(data[:i])
				return tmpFile, filename, nil
			}
			writeUpTo := len(data) - len(boundaryWithNewline)
			if writeUpTo < 0 {
				writeUpTo = 0
			}
			tmpFile.Write(data[:writeUpTo])
			overflow = data[writeUpTo:]
		}
		if err == io.EOF {
			tmpFile.Write(overflow) // Write remaining bytes
			return tmpFile, filename, nil
		}
		if err != nil {
			tmpFile.Close()
			os.Remove(tmpFile.Name())
			return nil, "", err
		}
	}
}

// --- Main ---
func main() {
	server := NewServer()
	defer server.cleanup()

	uploadHandler := server.parseUpload(server.handleUpload())

	http.Handle("/upload", uploadHandler)
	http.HandleFunc("/download", server.handleDownload())

	log.Println("Starting handler-centric server on :8080...")
	if err := http.ListenAndServe(":8080", nil); err != nil {
		log.Fatalf("could not start server: %v", err)
	}
}