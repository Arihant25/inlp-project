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

// --- DOMAIN & MOCKS ---

type UserRole string
const (
	ADMIN UserRole = "ADMIN"
	USER  UserRole = "USER"
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
	DRAFT     PostStatus = "DRAFT"
	PUBLISHED PostStatus = "PUBLISHED"
)

type Post struct {
	ID      uuid.UUID  `json:"id"`
	UserID  uuid.UUID  `json:"user_id"`
	Title   string     `json:"title"`
	Content string     `json:"content"`
	Status  PostStatus `json:"status"`
}

var userStore = make(map[uuid.UUID]User)
var jobStatusStore = make(map[string]string)
var storeMutex = &sync.RWMutex{}

// --- TASK DEFINITIONS ---

const (
	TaskSendWelcomeEmail = "task:send_welcome_email"
	TaskProcessPostImage = "task:process_post_image"
	TaskCleanupOldDrafts = "task:cleanup_old_drafts"
)

// --- FUNCTIONAL HANDLERS ---

func createUserHandler(client *asynq.Client) gin.HandlerFunc {
	return func(c *gin.Context) {
		var input struct {
			Email    string `json:"email" binding:"required"`
			Password string `json:"password" binding:"required"`
		}
		if err := c.ShouldBindJSON(&input); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		newUser := User{
			ID:           uuid.New(),
			Email:        input.Email,
			PasswordHash: fmt.Sprintf("hashed:%s", input.Password),
			Role:         USER,
			IsActive:     true,
			CreatedAt:    time.Now().UTC(),
		}

		storeMutex.Lock()
		userStore[newUser.ID] = newUser
		storeMutex.Unlock()

		payload, _ := json.Marshal(gin.H{"user_id": newUser.ID.String()})
		task := asynq.NewTask(TaskSendWelcomeEmail, payload)
		if _, err := client.Enqueue(task); err != nil {
			log.Printf("ERROR: could not enqueue welcome email task: %v", err)
			// Non-fatal, user is already created
		}

		c.JSON(http.StatusCreated, newUser)
	}
}

func processImageHandler(client *asynq.Client) gin.HandlerFunc {
	return func(c *gin.Context) {
		postID := c.Param("id")
		if _, err := uuid.Parse(postID); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid post ID format"})
			return
		}

		payload, _ := json.Marshal(gin.H{"post_id": postID, "source": "s3://bucket/image.png"})
		task := asynq.NewTask(TaskProcessPostImage, payload)
		
		// Retry up to 3 times with default exponential backoff
		info, err := client.Enqueue(task, asynq.MaxRetry(3), asynq.Timeout(2*time.Minute))
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to schedule image processing"})
			return
		}

		storeMutex.Lock()
		jobStatusStore[info.ID] = "queued"
		storeMutex.Unlock()

		c.JSON(http.StatusAccepted, gin.H{"job_id": info.ID})
	}
}

func getJobStatusHandler(inspector *asynq.Inspector) gin.HandlerFunc {
	return func(c *gin.Context) {
		jobID := c.Param("id")

		storeMutex.RLock()
		status, found := jobStatusStore[jobID]
		storeMutex.RUnlock()

		if found && status != "completed" && status != "failed" {
			c.JSON(http.StatusOK, gin.H{"job_id": jobID, "status": status})
			return
		}

		info, err := inspector.GetTaskInfo("default", jobID)
		if err != nil {
			c.JSON(http.StatusNotFound, gin.H{"error": "job not found or expired"})
			return
		}

		c.JSON(http.StatusOK, gin.H{
			"job_id":     jobID,
			"status":     info.State.String(),
			"retries":    info.Retried,
			"last_error": info.LastErr,
		})
	}
}

// --- TASK WORKER FUNCTIONS ---

func handleSendWelcomeEmail(ctx context.Context, t *asynq.Task) error {
	var payload map[string]string
	if err := json.Unmarshal(t.Payload(), &payload); err != nil {
		return err
	}
	userID, _ := uuid.Parse(payload["user_id"])

	storeMutex.RLock()
	user, ok := userStore[userID]
	storeMutex.RUnlock()

	if !ok {
		return fmt.Errorf("user %s not found for welcome email", userID)
	}

	log.Printf("WORKER: Sending welcome email to %s", user.Email)
	time.Sleep(1 * time.Second) // Simulate network latency
	log.Printf("WORKER: Welcome email sent to %s", user.Email)
	return nil
}

func handleProcessPostImage(ctx context.Context, t *asynq.Task) error {
	info := asynq.GetTaskInfo(ctx)
	
	storeMutex.Lock()
	jobStatusStore[info.ID] = "processing"
	storeMutex.Unlock()

	var payload map[string]string
	if err := json.Unmarshal(t.Payload(), &payload); err != nil {
		storeMutex.Lock()
		jobStatusStore[info.ID] = "failed"
		storeMutex.Unlock()
		return err
	}

	log.Printf("WORKER: Processing image for post %s. Attempt %d.", payload["post_id"], info.Retried+1)
	time.Sleep(3 * time.Second) // Simulate processing time

	// Simulate failure to test retry logic
	if info.Retried < 2 {
		log.Printf("WORKER: Failed to process image for post %s. Retrying...", payload["post_id"])
		storeMutex.Lock()
		jobStatusStore[info.ID] = fmt.Sprintf("failed_attempt_%d", info.Retried+1)
		storeMutex.Unlock()
		return fmt.Errorf("simulated processing error")
	}

	log.Printf("WORKER: Successfully processed image for post %s", payload["post_id"])
	storeMutex.Lock()
	jobStatusStore[info.ID] = "completed"
	storeMutex.Unlock()
	return nil
}

func handleCleanupOldDrafts(ctx context.Context, t *asynq.Task) error {
	log.Println("SCHEDULER: Running periodic task: CleanupOldDrafts")
	// In a real app, you would query the DB for old draft posts and delete them.
	time.Sleep(5 * time.Second)
	log.Println("SCHEDULER: Finished periodic task: CleanupOldDrafts")
	return nil
}

func main() {
	// NOTE: Requires a running Redis server on localhost:6379
	redisConnection := asynq.RedisClientOpt{Addr: "localhost:6379"}

	client := asynq.NewClient(redisConnection)
	defer client.Close()

	inspector := asynq.NewInspector(redisConnection)
	
	// --- WORKER SETUP ---
	go func() {
		srv := asynq.NewServer(redisConnection, asynq.Config{
			Concurrency: 20,
			// Exponential backoff is the default
		})
		mux := asynq.NewServeMux()
		mux.HandleFunc(TaskSendWelcomeEmail, handleSendWelcomeEmail)
		mux.HandleFunc(TaskProcessPostImage, handleProcessPostImage)
		mux.HandleFunc(TaskCleanupOldDrafts, handleCleanupOldDrafts)

		if err := srv.Run(mux); err != nil {
			log.Fatalf("could not start asynq worker server: %v", err)
		}
	}()

	// --- SCHEDULER SETUP ---
	go func() {
		scheduler := asynq.NewScheduler(redisConnection, nil)
		task := asynq.NewTask(TaskCleanupOldDrafts, nil)
		// Run every 2 minutes for demonstration
		if _, err := scheduler.Register("*/2 * * * *", task); err != nil {
			log.Fatalf("could not register scheduled task: %v", err)
		}
		if err := scheduler.Run(); err != nil {
			log.Fatalf("could not start asynq scheduler: %v", err)
		}
	}()

	// --- GIN ROUTER SETUP ---
	r := gin.New()
	r.Use(gin.Logger(), gin.Recovery())

	r.POST("/users", createUserHandler(client))
	r.POST("/posts/:id/image", processImageHandler(client))
	r.GET("/jobs/:id", getJobStatusHandler(inspector))

	log.Println("Starting HTTP server on port 9090")
	if err := r.Run(":9090"); err != nil {
		log.Fatalf("could not start http server: %v", err)
	}
}