package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"math"
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

// --- DOMAIN MODELS ---

type UserRole string
const (
	AdminRole UserRole = "ADMIN"
	UserRoleNormal UserRole = "USER"
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
	DraftStatus     PostStatus = "DRAFT"
	PublishedStatus PostStatus = "PUBLISHED"
)

type Post struct {
	ID      uuid.UUID  `json:"id"`
	UserID  uuid.UUID  `json:"user_id"`
	Title   string     `json:"title"`
	Content string     `json:"content"`
	Status  PostStatus `json:"status"`
}

// --- MOCK DATA STORES ---

var (
	users = make(map[uuid.UUID]User)
	posts = make(map[uuid.UUID]Post)
	jobStatus = make(map[string]string)
	mu    sync.RWMutex
)

// --- TASK DEFINITIONS ---

const (
	TypeEmailWelcome    = "email:welcome"
	TypeImageProcess    = "image:process"
	TypeReportGenerate  = "report:generate"
)

type WelcomeEmailPayload struct {
	UserID uuid.UUID `json:"user_id"`
}

type ImageProcessPayload struct {
	PostID    uuid.UUID `json:"post_id"`
	ImageURL  string    `json:"image_url"`
}

type ReportGenerationPayload struct {
	ReportDate string `json:"report_date"`
}

// --- TASK HANDLERS (WORKERS) ---

func HandleWelcomeEmailTask(ctx context.Context, t *asynq.Task) error {
	var p WelcomeEmailPayload
	if err := json.Unmarshal(t.Payload(), &p); err != nil {
		return fmt.Errorf("failed to unmarshal payload: %w", err)
	}

	mu.RLock()
	user, ok := users[p.UserID]
	mu.RUnlock()
	if !ok {
		return fmt.Errorf("user not found: %s", p.UserID)
	}

	log.Printf("Sending welcome email to %s (User ID: %s)", user.Email, user.ID)
	// Simulate email sending
	time.Sleep(2 * time.Second)
	log.Printf("Successfully sent welcome email to %s", user.Email)
	return nil
}

func HandleImageProcessTask(ctx context.Context, t *asynq.Task) error {
	taskInfo := asynq.GetTaskInfo(ctx)
	jobID := taskInfo.ID
	
	mu.Lock()
	jobStatus[jobID] = "processing"
	mu.Unlock()

	var p ImageProcessPayload
	if err := json.Unmarshal(t.Payload(), &p); err != nil {
		mu.Lock()
		jobStatus[jobID] = "failed"
		mu.Unlock()
		return fmt.Errorf("failed to unmarshal payload: %w", err)
	}

	log.Printf("Processing image for Post ID: %s from URL: %s", p.PostID, p.ImageURL)
	// Simulate a potentially failing image processing task
	time.Sleep(5 * time.Second)
	if rand.Intn(10) < 3 { // 30% chance of failure
		log.Printf("Failed to process image for Post ID: %s. Will retry.", p.PostID)
		mu.Lock()
		jobStatus[jobID] = "retrying"
		mu.Unlock()
		return fmt.Errorf("simulated image processing failure")
	}

	log.Printf("Successfully processed image for Post ID: %s", p.PostID)
	mu.Lock()
	jobStatus[jobID] = "completed"
	mu.Unlock()
	return nil
}

func HandleReportGenerationTask(ctx context.Context, t *asynq.Task) error {
	var p ReportGenerationPayload
	if err := json.Unmarshal(t.Payload(), &p); err != nil {
		return fmt.Errorf("failed to unmarshal payload: %w", err)
	}
	log.Printf("Generating daily report for %s", p.ReportDate)
	time.Sleep(10 * time.Second)
	log.Printf("Daily report for %s generated successfully", p.ReportDate)
	return nil
}

// --- API SERVICE LAYER ---

type APIService struct {
	JobClient *asynq.Client
	JobInspector *asynq.Inspector
}

func NewAPIService(redisOpt asynq.RedisClientOpt) *APIService {
	return &APIService{
		JobClient: asynq.NewClient(redisOpt),
		JobInspector: asynq.NewInspector(redisOpt),
	}
}

func (s *APIService) CreateUserHandler(c *gin.Context) {
	var req struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	newUser := User{
		ID:           uuid.New(),
		Email:        req.Email,
		PasswordHash: "hashed_" + req.Password, // Never store plain text passwords
		Role:         UserRoleNormal,
		IsActive:     true,
		CreatedAt:    time.Now(),
	}

	mu.Lock()
	users[newUser.ID] = newUser
	mu.Unlock()

	payload, err := json.Marshal(WelcomeEmailPayload{UserID: newUser.ID})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to create task payload"})
		return
	}
	task := asynq.NewTask(TypeEmailWelcome, payload)
	_, err = s.JobClient.Enqueue(task, asynq.Queue("emails"))
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to enqueue task"})
		return
	}

	c.JSON(http.StatusCreated, newUser)
}

func (s *APIService) ProcessImageHandler(c *gin.Context) {
	postID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid post ID"})
		return
	}

	payload, err := json.Marshal(ImageProcessPayload{PostID: postID, ImageURL: "https://example.com/image.jpg"})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to create task payload"})
		return
	}

	task := asynq.NewTask(TypeImageProcess, payload)
	// Retry up to 5 times with exponential backoff
	taskInfo, err := s.JobClient.Enqueue(task, asynq.MaxRetry(5), asynq.Queue("images"))
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to enqueue image processing task"})
		return
	}
	
	mu.Lock()
	jobStatus[taskInfo.ID] = "queued"
	mu.Unlock()

	c.JSON(http.StatusAccepted, gin.H{"job_id": taskInfo.ID, "status": "queued"})
}

func (s *APIService) GetJobStatusHandler(c *gin.Context) {
	jobID := c.Param("id")
	
	// First, check our local cache
	mu.RLock()
	status, ok := jobStatus[jobID]
	mu.RUnlock()

	if ok {
		c.JSON(http.StatusOK, gin.H{"job_id": jobID, "status": status})
		return
	}

	// If not in cache, query Asynq directly
	taskInfo, err := s.JobInspector.GetTaskInfo("default", jobID) // Assuming default queue if not specified
	if err != nil {
		taskInfo, err = s.JobInspector.GetTaskInfo("images", jobID)
		if err != nil {
			c.JSON(http.StatusNotFound, gin.H{"error": "job not found"})
			return
		}
	}

	c.JSON(http.StatusOK, gin.H{
		"job_id": jobID, 
		"status": taskInfo.State.String(),
		"last_error": taskInfo.LastErr,
		"retries": taskInfo.Retried,
	})
}

// --- MAIN APPLICATION ---

func main() {
	// NOTE: Requires a running Redis server on localhost:6379
	redisOpt := asynq.RedisClientOpt{Addr: "localhost:6379"}

	// Start the background worker server
	go func() {
		srv := asynq.NewServer(
			redisOpt,
			asynq.Config{
				Concurrency: 10,
				Queues: map[string]int{
					"critical": 6,
					"emails":   3,
					"images":   1,
				},
				// Custom retry delay: 10s, 40s, 90s for 3 retries
				RetryDelayFunc: func(n int, e error, t *asynq.Task) time.Duration {
					return time.Duration(math.Pow(float64(n), 2)+10) * time.Second
				},
			},
		)

		mux := asynq.NewServeMux()
		mux.HandleFunc(TypeEmailWelcome, HandleWelcomeEmailTask)
		mux.HandleFunc(TypeImageProcess, HandleImageProcessTask)
		mux.HandleFunc(TypeReportGenerate, HandleReportGenerationTask)

		if err := srv.Run(mux); err != nil {
			log.Fatalf("could not run asynq server: %v", err)
		}
	}()

	// Start the periodic task scheduler
	go func() {
		scheduler := asynq.NewScheduler(redisOpt, nil)
		payload, _ := json.Marshal(ReportGenerationPayload{ReportDate: time.Now().Format("2006-01-02")})
		// Register a periodic task to run every minute for demonstration
		// In production, this would be a cron spec like "0 0 * * *" for daily
		_, err := scheduler.Register("@every 1m", asynq.NewTask(TypeReportGenerate, payload))
		if err != nil {
			log.Fatalf("could not register scheduler task: %v", err)
		}
		if err := scheduler.Run(); err != nil {
			log.Fatalf("could not run scheduler: %v", err)
		}
	}()

	// Setup Gin HTTP server
	service := NewAPIService(redisOpt)
	router := gin.Default()
	
	api := router.Group("/api")
	{
		api.POST("/users", service.CreateUserHandler)
		api.POST("/posts/:id/image", service.ProcessImageHandler)
		api.GET("/jobs/:id", service.GetJobStatusHandler)
	}

	log.Println("Starting Gin server on :8080")
	if err := router.Run(":8080"); err != nil {
		log.Fatalf("could not run gin server: %v", err)
	}
}