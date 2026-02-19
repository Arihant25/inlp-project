package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"math/rand"
	"net/http"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
	"github.com/hibiken/asynq"
)

// --- go.mod ---
// module background_jobs_demo
// go 1.21
//
// require (
// 	github.com/gofiber/fiber/v2 v2.52.4
// 	github.com/google/uuid v1.6.0
// 	github.com/hibiken/asynq v0.24.1
// )
// ---

// --- Unified Domain Model ---
type UserRole string
const (
	ADMIN_ROLE UserRole = "ADMIN"
	USER_ROLE  UserRole = "USER"
)

type User struct {
	Id           uuid.UUID `json:"id"`
	Email        string    `json:"email"`
	PasswordHash string    `json:"-"`
	Role         UserRole  `json:"role"`
	IsActive     bool      `json:"is_active"`
	CreatedAt    time.Time `json:"created_at"`
}

type PostStatus string
const (
	DRAFT_STATUS     PostStatus = "DRAFT"
	PUBLISHED_STATUS PostStatus = "PUBLISHED"
)

type Post struct {
	Id      uuid.UUID  `json:"id"`
	UserId  uuid.UUID  `json:"user_id"`
	Title   string     `json:"title"`
	Content string     `json:"content"`
	Status  PostStatus `json:"status"`
}

// --- Global State & Configuration ---
const RedisAddress = "localhost:6379"

var (
	// In-memory data stores for simplicity
	g_UsersData = make(map[uuid.UUID]User)
	g_PostsData = make(map[uuid.UUID]Post)
	g_DataMutex = sync.RWMutex{}

	// Global Asynq client for enqueuing tasks from handlers
	g_AsynqClient *asynq.Client
	// Global Asynq inspector for checking job status
	g_AsynqInspector *asynq.Inspector
)

// --- Task Types and Payloads ---
const (
	TASK_SEND_WELCOME_EMAIL      = "email:welcome"
	TASK_PROCESS_IMAGE_RESIZE    = "image:resize"
	TASK_PROCESS_IMAGE_WATERMARK = "image:watermark"
	TASK_PERIODIC_AUDIT_LOG      = "system:audit"
)

type EmailTaskPayload struct {
	UserId uuid.UUID `json:"user_id"`
}

type ImageProcessingTaskPayload struct {
	PostId uuid.UUID `json:"post_id"`
	Source string    `json:"source"`
}

// --- Task Handler Functions ---
func HandleSendWelcomeEmail(ctx context.Context, task *asynq.Task) error {
	var payload EmailTaskPayload
	if err := json.Unmarshal(task.Payload(), &payload); err != nil {
		return fmt.Errorf("failed to unmarshal payload: %w", asynq.SkipRetry)
	}

	g_DataMutex.RLock()
	user, exists := g_UsersData[payload.UserId]
	g_DataMutex.RUnlock()

	if !exists {
		log.Printf("[TASK-WARN] User %s not found. Skipping welcome email.", payload.UserId)
		return nil // Don't retry if user is gone
	}

	log.Printf("[TASK-EXEC] Sending welcome email to %s", user.Email)
	time.Sleep(1 * time.Second) // Simulate API call to email service
	log.Printf("[TASK-DONE] Welcome email sent to %s", user.Email)
	return nil
}

func HandleImageResize(ctx context.Context, task *asynq.Task) error {
	var payload ImageProcessingTaskPayload
	if err := json.Unmarshal(task.Payload(), &payload); err != nil {
		return fmt.Errorf("failed to unmarshal payload: %w", asynq.SkipRetry)
	}

	log.Printf("[TASK-EXEC] Resizing image '%s' for post %s", payload.Source, payload.PostId)
	// Simulate a flaky service with exponential backoff retry
	if rand.Float32() < 0.4 { // 40% chance of failure
		errMsg := "image processing service unavailable"
		log.Printf("[TASK-FAIL] %s. Will retry.", errMsg)
		return fmt.Errorf(errMsg)
	}
	time.Sleep(3 * time.Second)
	log.Printf("[TASK-DONE] Image resized for post %s", payload.PostId)
	return nil
}

func HandleImageWatermark(ctx context.Context, task *asynq.Task) error {
	var payload ImageProcessingTaskPayload
	if err := json.Unmarshal(task.Payload(), &payload); err != nil {
		return fmt.Errorf("failed to unmarshal payload: %w", asynq.SkipRetry)
	}

	log.Printf("[TASK-EXEC] Applying watermark to image for post %s", payload.PostId)
	time.Sleep(2 * time.Second)
	log.Printf("[TASK-DONE] Watermark applied for post %s", payload.PostId)
	return nil
}

func HandlePeriodicAuditLog(ctx context.Context, task *asynq.Task) error {
	log.Printf("[PERIODIC-TASK] Running audit log...")
	g_DataMutex.RLock()
	userCount := len(g_UsersData)
	g_DataMutex.RUnlock()
	log.Printf("[PERIODIC-TASK] Audit: Found %d total users.", userCount)
	return nil
}

// --- Fiber HTTP Handler Functions ---
func CreateUserEndpoint(c *fiber.Ctx) error {
	type CreateUserRequest struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	req := new(CreateUserRequest)
	if err := c.BodyParser(req); err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"status": "error", "message": "Invalid request body"})
	}

	newUser := User{
		Id:           uuid.New(),
		Email:        req.Email,
		PasswordHash: fmt.Sprintf("hashed(%s)", req.Password),
		Role:         USER_ROLE,
		IsActive:     true,
		CreatedAt:    time.Now().UTC(),
	}

	g_DataMutex.Lock()
	g_UsersData[newUser.Id] = newUser
	g_DataMutex.Unlock()

	// Enqueue background job for sending welcome email
	emailPayload, _ := json.Marshal(EmailTaskPayload{UserId: newUser.Id})
	emailTask := asynq.NewTask(TASK_SEND_WELCOME_EMAIL, emailPayload, asynq.MaxRetry(3))
	info, err := g_AsynqClient.Enqueue(emailTask)
	if err != nil {
		log.Printf("ERROR: Could not enqueue welcome email task: %v", err)
		// Continue, as user creation is the primary goal
	} else {
		log.Printf("Enqueued welcome email job %s for user %s", info.ID, newUser.Id)
	}

	return c.Status(http.StatusCreated).JSON(newUser)
}

func ProcessImageEndpoint(c *fiber.Ctx) error {
	postIdParam := c.Params("id")
	postId, err := uuid.Parse(postIdParam)
	if err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"status": "error", "message": "Invalid post ID format"})
	}

	// Create a mock post if it doesn't exist
	g_DataMutex.Lock()
	if _, ok := g_PostsData[postId]; !ok {
		g_PostsData[postId] = Post{Id: postId, Title: "A Demo Post"}
	}
	g_DataMutex.Unlock()

	// Create a pipeline of tasks
	imgPayload, _ := json.Marshal(ImageProcessingTaskPayload{PostId: postId, Source: "uploads/original.jpg"})
	resizeTask := asynq.NewTask(TASK_PROCESS_IMAGE_RESIZE, imgPayload, asynq.MaxRetry(4), asynq.Timeout(5*time.Minute))
	watermarkTask := asynq.NewTask(TASK_PROCESS_IMAGE_WATERMARK, imgPayload)

	// Enqueue the first task, with the second one chained to execute upon success
	info, err := g_AsynqClient.Enqueue(resizeTask, asynq.ContinueWith(watermarkTask))
	if err != nil {
		log.Printf("ERROR: Could not enqueue image processing pipeline: %v", err)
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"status": "error", "message": "Failed to start processing job"})
	}

	return c.Status(http.StatusAccepted).JSON(fiber.Map{
		"status":  "success",
		"message": "Image processing pipeline initiated",
		"job_id":  info.ID,
		"queue":   info.Queue,
	})
}

func GetJobStatusEndpoint(c *fiber.Ctx) error {
	jobId := c.Params("id")
	queue := c.Query("queue", "default") // Asynq requires queue name for inspection

	taskInfo, err := g_AsynqInspector.GetTaskInfo(queue, jobId)
	if err != nil {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{"status": "error", "message": fmt.Sprintf("Job with ID %s not found in queue %s", jobId, queue)})
	}

	return c.JSON(fiber.Map{
		"job_id":      taskInfo.ID,
		"type":        taskInfo.Type,
		"state":       taskInfo.State.String(),
		"retries":     taskInfo.Retried,
		"max_retries": taskInfo.MaxRetry,
		"last_error":  taskInfo.LastErr,
	})
}

// --- Main Function ---
func main() {
	log.Println("Application starting...")

	// --- Initialize Asynq Client and Inspector ---
	redisConnectionOpt := asynq.RedisClientOpt{Addr: RedisAddress}
	g_AsynqClient = asynq.NewClient(redisConnectionOpt)
	defer g_AsynqClient.Close()
	g_AsynqInspector = asynq.NewInspector(redisConnectionOpt)

	// --- Start Asynq Worker Server in a Goroutine ---
	go func() {
		workerServer := asynq.NewServer(
			redisConnectionOpt,
			asynq.Config{
				Concurrency: 10,
				// Define queues and their priorities
				Queues: map[string]int{
					"critical": 6,
					"default":  3,
					"low":      1,
				},
			},
		)

		taskMux := asynq.NewServeMux()
		taskMux.HandleFunc(TASK_SEND_WELCOME_EMAIL, HandleSendWelcomeEmail)
		taskMux.HandleFunc(TASK_PROCESS_IMAGE_RESIZE, HandleImageResize)
		taskMux.HandleFunc(TASK_PROCESS_IMAGE_WATERMARK, HandleImageWatermark)
		taskMux.HandleFunc(TASK_PERIODIC_AUDIT_LOG, HandlePeriodicAuditLog)

		log.Println("Asynq worker started.")
		if err := workerServer.Run(taskMux); err != nil {
			log.Fatalf("FATAL: Could not start Asynq worker: %v", err)
		}
	}()

	// --- Start Asynq Scheduler in a Goroutine ---
	go func() {
		scheduler := asynq.NewScheduler(redisConnectionOpt, nil)
		// Register a periodic task to run every minute
		_, err := scheduler.Register("@every 1m", asynq.NewTask(TASK_PERIODIC_AUDIT_LOG, nil))
		if err != nil {
			log.Fatalf("FATAL: Could not register periodic task: %v", err)
		}

		log.Println("Asynq scheduler started.")
		if err := scheduler.Run(); err != nil {
			log.Fatalf("FATAL: Could not start Asynq scheduler: %v", err)
		}
	}()

	// --- Setup and Start Fiber Web Server ---
	webApp := fiber.New()
	webApp.Post("/users", CreateUserEndpoint)
	webApp.Post("/posts/:id/process-image", ProcessImageEndpoint)
	webApp.Get("/jobs/:id", GetJobStatusEndpoint)

	// --- Graceful Shutdown Handling ---
	quitChannel := make(chan os.Signal, 1)
	signal.Notify(quitChannel, syscall.SIGINT, syscall.SIGTERM)

	go func() {
		log.Println("Fiber server starting on :3000")
		if err := webApp.Listen(":3000"); err != nil && err != http.ErrServerClosed {
			log.Fatalf("FATAL: Fiber server failed: %v", err)
		}
	}()

	<-quitChannel
	log.Println("Shutdown signal received, initiating graceful shutdown...")

	if err := webApp.Shutdown(); err != nil {
		log.Printf("ERROR: Fiber server shutdown failed: %v", err)
	}

	log.Println("Application shut down successfully.")
}