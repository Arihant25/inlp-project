package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"math/rand"
	"net/http"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/hibiken/asynq"
)

// --- DEPENDENCIES ---
// go get github.com/gin-gonic/gin
// go get github.com/google/uuid
// go get github.com/hibiken/asynq

// --- GLOBAL STATE & CONFIG ---

var (
	// In a real app, this would be configured via env vars or a config file.
	redisAddr = "localhost:6379"
	// Global client for enqueuing tasks from handlers.
	taskClient *asynq.Client
	// Global inspector for checking task status.
	taskInspector *asynq.Inspector
)

// --- DATA MODELS & MOCK DB ---

type User struct {
	ID           uuid.UUID `json:"id"`
	Email        string    `json:"email"`
	PasswordHash string    `json:"-"`
	Role         string    `json:"role"`
	IsActive     bool      `json:"is_active"`
	CreatedAt    time.Time `json:"created_at"`
}

type Post struct {
	ID      uuid.UUID `json:"id"`
	UserID  uuid.UUID `json:"user_id"`
	Title   string    `json:"title"`
	Content string    `json:"content"`
	Status  string    `json:"status"`
}

var (
	// Simple in-memory storage. Use a sync.Map for concurrent access.
	users     = &sync.Map{}
	posts     = &sync.Map{}
	jobStates = &sync.Map{}
)

// --- TASK PAYLOADS & TYPES ---

const (
	TypeWelcomeEmail = "email_welcome"
	TypeProcessImage = "image_process"
	TypeNightlyCleanup = "cleanup_nightly"
)

type WelcomeEmailJob struct {
	Email  string    `json:"email"`
	UserID uuid.UUID `json:"user_id"`
}

type ProcessImageJob struct {
	PostID uuid.UUID `json:"post_id"`
}

// --- API HANDLERS ---

func handleCreateUser(c *gin.Context) {
	var req struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if err := c.BindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"msg": "bad input"})
		return
	}

	newUser := User{
		ID:           uuid.New(),
		Email:        req.Email,
		PasswordHash: "..." , // Hashing logic omitted
		Role:         "USER",
		IsActive:     true,
		CreatedAt:    time.Now(),
	}
	users.Store(newUser.ID, newUser)

	// Dispatch background job
	payload, _ := json.Marshal(WelcomeEmailJob{Email: newUser.Email, UserID: newUser.ID})
	task := asynq.NewTask(TypeWelcomeEmail, payload)
	if _, err := taskClient.Enqueue(task); err != nil {
		log.Printf("ERROR: could not enqueue welcome email: %v", err)
	}

	c.JSON(http.StatusCreated, newUser)
}

func handleProcessImage(c *gin.Context) {
	postID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"msg": "invalid post id"})
		return
	}

	payload, _ := json.Marshal(ProcessImageJob{PostID: postID})
	task := asynq.NewTask(TypeProcessImage, payload)
	
	// Retry with exponential backoff (default), timeout after 5 mins
	info, err := taskClient.Enqueue(task, asynq.MaxRetry(5), asynq.Timeout(5*time.Minute))
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"msg": "could not start job"})
		return
	}

	jobStates.Store(info.ID, "queued")
	c.JSON(http.StatusAccepted, gin.H{"job_id": info.ID})
}

func handleGetJobStatus(c *gin.Context) {
	jobID := c.Param("id")
	
	// Check our simple cache first
	if status, ok := jobStates.Load(jobID); ok {
		c.JSON(http.StatusOK, gin.H{"status": status})
		return
	}

	// Fallback to asking Redis
	info, err := taskInspector.GetTaskInfo("default", jobID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"msg": "job not found"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"status": info.State.String()})
}

// --- TASK HANDLERS (WORKERS) ---

func processWelcomeEmail(ctx context.Context, t *asynq.Task) error {
	var p WelcomeEmailJob
	if err := json.Unmarshal(t.Payload(), &p); err != nil {
		return fmt.Errorf("bad payload: %w", err)
	}
	log.Printf("Sending welcome email to %s (user: %s)", p.Email, p.UserID)
	time.Sleep(2 * time.Second) // Simulate SMTP server latency
	log.Printf("Done sending email to %s", p.Email)
	return nil
}

func processImage(ctx context.Context, t *asynq.Task) error {
	info := asynq.GetTaskInfo(ctx)
	jobStates.Store(info.ID, "processing")

	var p ProcessImageJob
	if err := json.Unmarshal(t.Payload(), &p); err != nil {
		jobStates.Store(info.ID, "failed")
		return fmt.Errorf("bad payload: %w", err)
	}

	log.Printf("Processing image for post %s (attempt %d)", p.PostID, info.Retried)
	time.Sleep(4 * time.Second)

	// Simulate a flaky process
	if rand.Float32() < 0.5 {
		jobStates.Store(info.ID, "retrying")
		return fmt.Errorf("simulated failure: could not connect to image service")
	}

	log.Printf("Image for post %s processed successfully", p.PostID)
	jobStates.Store(info.ID, "completed")
	return nil
}

func runNightlyCleanup(ctx context.Context, t *asynq.Task) error {
	log.Println("--- Running Nightly Cleanup Task ---")
	// Logic to clean up old posts, etc.
	time.Sleep(15 * time.Second)
	log.Println("--- Nightly Cleanup Finished ---")
	return nil
}

// --- MAIN ---

func main() {
	// NOTE: Requires a running Redis server on localhost:6379
	redisOpt := asynq.RedisClientOpt{Addr: redisAddr}

	// Initialize global clients
	taskClient = asynq.NewClient(redisOpt)
	defer taskClient.Close()
	taskInspector = asynq.NewInspector(redisOpt)

	// Start worker server in a goroutine
	go func() {
		srv := asynq.NewServer(redisOpt, asynq.Config{Concurrency: 10})
		mux := asynq.NewServeMux()
		mux.HandleFunc(TypeWelcomeEmail, processWelcomeEmail)
		mux.HandleFunc(TypeProcessImage, processImage)
		mux.HandleFunc(TypeNightlyCleanup, runNightlyCleanup)
		if err := srv.Run(mux); err != nil {
			log.Fatalf("FATAL: asynq server failed: %v", err)
		}
	}()

	// Start scheduler in a goroutine
	go func() {
		scheduler := asynq.NewScheduler(redisOpt, &asynq.SchedulerOpts{})
		// Cron spec for "at 2 AM every day"
		if _, err := scheduler.Register("0 2 * * *", asynq.NewTask(TypeNightlyCleanup, nil)); err != nil {
			log.Fatalf("FATAL: could not register scheduler task: %v", err)
		}
		if err := scheduler.Run(); err != nil {
			log.Fatalf("FATAL: asynq scheduler failed: %v", err)
		}
	}()

	// Setup and run Gin server
	router := gin.Default()
	router.POST("/user", handleCreateUser)
	router.POST("/post/:id/image", handleProcessImage)
	router.GET("/job/:id", handleGetJobStatus)

	log.Println("HTTP server listening on :8080")
	if err := router.Run(":8080"); err != nil {
		log.Fatalf("FATAL: gin server failed: %v", err)
	}
}