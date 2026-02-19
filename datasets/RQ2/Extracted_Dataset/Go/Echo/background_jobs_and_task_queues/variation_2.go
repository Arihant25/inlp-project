package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"github.com/google/uuid"
	"github.com/hibiken/asynq"
	"github.com/labstack/echo/v4"
	"github.com/labstack/echo/v4/middleware"
)

// This code requires a running Redis instance.
// Example: docker run -d --name redis -p 6379:6379 redis
const redisConnection = "127.0.0.1:6379"

// --- Domain Models ---

type UserRole string
const (
	RoleAdmin UserRole = "ADMIN"
	RoleUser  UserRole = "USER"
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

// --- Global Variables & Mock Storage ---

var (
	asynqClient    *asynq.Client
	asynqInspector *asynq.Inspector
	mockUsers      = make(map[uuid.UUID]User)
	mockPosts      = make(map[uuid.UUID]Post)
	dbMutex        = &sync.RWMutex{}
)

// --- Task Payloads and Types ---

const (
	TypeEmailWelcome   = "email:send_welcome"
	TypeImageProcess   = "image:process_pipeline"
	TypeImageWatermark = "image:add_watermark"
	TypeReportDaily    = "system:generate_report"
)

type EmailPayload struct {
	UserID uuid.UUID `json:"user_id"`
}

type ImagePayload struct {
	PostID uuid.UUID `json:"post_id"`
}

// --- API Handlers (Procedural Style) ---

func createUserHandler(c echo.Context) error {
	var params struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if err := c.Bind(&params); err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "bad request"})
	}

	newUser := User{
		ID:           uuid.New(),
		Email:        params.Email,
		PasswordHash: "hashed:" + params.Password,
		Role:         RoleUser,
		IsActive:     true,
		CreatedAt:    time.Now(),
	}
	dbMutex.Lock()
	mockUsers[newUser.ID] = newUser
	dbMutex.Unlock()

	payload, err := json.Marshal(EmailPayload{UserID: newUser.ID})
	if err != nil {
		log.Printf("ERROR: could not marshal email payload: %v", err)
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": "internal server error"})
	}

	// Retry 5 times with exponential backoff
	task := asynq.NewTask(TypeEmailWelcome, payload, asynq.MaxRetry(5))
	info, err := asynqClient.Enqueue(task)
	if err != nil {
		log.Printf("ERROR: could not enqueue email task: %v", err)
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": "internal server error"})
	}

	log.Printf("Enqueued welcome email task: id=%s", info.ID)
	return c.JSON(http.StatusCreated, newUser)
}

func publishPostHandler(c echo.Context) error {
	postID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid post ID"})
	}

	payload, err := json.Marshal(ImagePayload{PostID: postID})
	if err != nil {
		log.Printf("ERROR: could not marshal image payload: %v", err)
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": "internal server error"})
	}

	task := asynq.NewTask(TypeImageProcess, payload, asynq.MaxRetry(3), asynq.Timeout(5*time.Minute))
	info, err := asynqClient.Enqueue(task)
	if err != nil {
		log.Printf("ERROR: could not enqueue image task: %v", err)
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": "internal server error"})
	}

	log.Printf("Enqueued image processing task: id=%s", info.ID)
	return c.JSON(http.StatusAccepted, map[string]string{"task_id": info.ID})
}

func getJobStatusHandler(c echo.Context) error {
	taskID := c.Param("id")
	taskInfo, err := asynqInspector.GetTaskInfo("default", taskID)
	if err != nil {
		return c.JSON(http.StatusNotFound, map[string]string{"error": "task not found"})
	}
	return c.JSON(http.StatusOK, taskInfo)
}

// --- Task Handlers (Functional Style) ---

func handleSendWelcomeEmail(ctx context.Context, t *asynq.Task) error {
	var p EmailPayload
	if err := json.Unmarshal(t.Payload(), &p); err != nil {
		return fmt.Errorf("json.Unmarshal failed: %v", err)
	}
	dbMutex.RLock()
	user, ok := mockUsers[p.UserID]
	dbMutex.RUnlock()
	if !ok {
		log.Printf("WARN: user %s not found for welcome email", p.UserID)
		return nil // Don't retry if user is gone
	}

	log.Printf("Sending welcome email to %s", user.Email)
	time.Sleep(2 * time.Second) // Simulate network latency
	log.Printf("-> Welcome email sent to %s", user.Email)
	return nil
}

func handleImagePipeline(ctx context.Context, t *asynq.Task) error {
	var p ImagePayload
	if err := json.Unmarshal(t.Payload(), &p); err != nil {
		return fmt.Errorf("json.Unmarshal failed: %v", err)
	}

	log.Printf("Starting image processing for post %s (resizing...)", p.PostID)
	time.Sleep(5 * time.Second) // Simulate resize
	log.Printf("-> Image resized for post %s", p.PostID)

	// Chain the next task
	watermarkPayload, _ := json.Marshal(p)
	watermarkTask := asynq.NewTask(TypeImageWatermark, watermarkPayload)
	if _, err := asynqClient.Enqueue(watermarkTask); err != nil {
		return fmt.Errorf("failed to enqueue watermark task: %v", err)
	}
	log.Printf("Enqueued watermark task for post %s", p.PostID)
	return nil
}

func handleAddWatermark(ctx context.Context, t *asynq.Task) error {
	var p ImagePayload
	if err := json.Unmarshal(t.Payload(), &p); err != nil {
		return fmt.Errorf("json.Unmarshal failed: %v", err)
	}
	log.Printf("Adding watermark to image for post %s", p.PostID)
	time.Sleep(3 * time.Second) // Simulate watermarking
	log.Printf("-> Watermark added for post %s. Pipeline complete.", p.PostID)
	return nil
}

func handleGenerateReport(ctx context.Context, t *asynq.Task) error {
	log.Printf("Periodic task: Generating daily user report...")
	time.Sleep(10 * time.Second) // Simulate heavy query
	dbMutex.RLock()
	userCount := len(mockUsers)
	dbMutex.RUnlock()
	log.Printf("-> Daily report complete. Total users: %d", userCount)
	return nil
}

// --- Main Function ---

func main() {
	// Initialize Asynq client and inspector
	redisOpt := asynq.RedisClientOpt{Addr: redisConnection}
	asynqClient = asynq.NewClient(redisOpt)
	defer asynqClient.Close()
	asynqInspector = asynq.NewInspector(redisOpt)

	// Setup Asynq worker server
	srv := asynq.NewServer(
		redisOpt,
		asynq.Config{
			Concurrency: 10,
			// Use exponential backoff for retries
			RetryDelayFunc: func(n int, e error, t *asynq.Task) time.Duration {
				return asynq.DefaultRetryDelayFunc(n, e, t)
			},
		},
	)

	mux := asynq.NewServeMux()
	mux.HandleFunc(TypeEmailWelcome, handleSendWelcomeEmail)
	mux.HandleFunc(TypeImageProcess, handleImagePipeline)
	mux.HandleFunc(TypeImageWatermark, handleAddWatermark)
	mux.HandleFunc(TypeReportDaily, handleGenerateReport)

	// Setup Asynq scheduler for periodic tasks
	scheduler := asynq.NewScheduler(redisOpt, nil)
	// Run every 2 minutes for demo purposes
	task := asynq.NewTask(TypeReportDaily, nil)
	_, err := scheduler.Register("*/2 * * * *", task)
	if err != nil {
		log.Fatalf("could not register periodic task: %v", err)
	}

	// Setup Echo web server
	e := echo.New()
	e.Use(middleware.Logger())
	e.POST("/users", createUserHandler)
	e.POST("/posts/:id/publish", publishPostHandler)
	e.GET("/jobs/:id", getJobStatusHandler)

	// Start services
	go func() {
		if err := srv.Run(mux); err != nil {
			log.Fatalf("could not run asynq worker: %v", err)
		}
	}()
	go func() {
		if err := scheduler.Run(); err != nil {
			log.Fatalf("could not run scheduler: %v", err)
		}
	}()
	go func() {
		if err := e.Start(":8080"); err != nil && err != http.ErrServerClosed {
			log.Fatalf("echo server failed: %v", err)
		}
	}()

	// Wait for shutdown signal
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	log.Println("Shutting down servers...")

	// Graceful shutdown
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := e.Shutdown(ctx); err != nil {
		e.Logger.Fatal(err)
	}
	scheduler.Shutdown()
	srv.Shutdown()
	log.Println("All services stopped.")
}