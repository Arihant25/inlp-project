package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"math"
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

// --- Domain Models (models/user.go, models/post.go) ---

type UserRole string
const (
	AdminRole UserRole = "ADMIN"
	UserRoleDefault  UserRole = "USER"
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
	DraftPost PostStatus = "DRAFT"
	PublishedPost PostStatus = "PUBLISHED"
)

type Post struct {
	ID      uuid.UUID  `json:"id"`
	UserID  uuid.UUID  `json:"user_id"`
	Title   string     `json:"title"`
	Content string     `json:"content"`
	Status  PostStatus `json:"status"`
}

// --- In-Memory Storage (for demonstration) ---
var (
	users = make(map[uuid.UUID]User)
	posts = make(map[uuid.UUID]Post)
	mu    sync.RWMutex
)

// --- Task Definitions (tasks/tasks.go) ---

const (
	TypeEmailWelcome      = "email:welcome"
	TypeImageResize       = "image:resize"
	TypeImageWatermark    = "image:watermark"
	TypePeriodicCleanup   = "task:cleanup:inactive_users"
)

type WelcomeEmailPayload struct {
	UserID uuid.UUID `json:"user_id"`
}

type ImageProcessingPayload struct {
	PostID uuid.UUID `json:"post_id"`
	ImageURL string `json:"image_url"`
}

type CleanupPayload struct {
	CutoffDate time.Time `json:"cutoff_date"`
}

// --- Task Dispatcher (tasks/dispatcher.go) ---

type TaskDispatcher struct {
	client *asynq.Client
}

func NewTaskDispatcher(redisOpt asynq.RedisClientOpt) *TaskDispatcher {
	return &TaskDispatcher{client: asynq.NewClient(redisOpt)}
}

func (d *TaskDispatcher) DispatchWelcomeEmail(ctx context.Context, userID uuid.UUID) (*asynq.TaskInfo, error) {
	payload, err := json.Marshal(WelcomeEmailPayload{UserID: userID})
	if err != nil {
		return nil, err
	}
	task := asynq.NewTask(TypeEmailWelcome, payload, asynq.MaxRetry(5), asynq.Timeout(2*time.Minute))
	return d.client.EnqueueContext(ctx, task)
}

func (d *TaskDispatcher) DispatchImageProcessingPipeline(ctx context.Context, postID uuid.UUID, imageURL string) (*asynq.TaskInfo, error) {
	resizePayload, err := json.Marshal(ImageProcessingPayload{PostID: postID, ImageURL: imageURL})
	if err != nil {
		return nil, err
	}
	resizeTask := asynq.NewTask(TypeImageResize, resizePayload, asynq.MaxRetry(3))
	watermarkTask := asynq.NewTask(TypeImageWatermark, resizePayload, asynq.MaxRetry(3))

	// Chain tasks: watermark runs after resize is successful
	return d.client.EnqueueContext(ctx, resizeTask, asynq.ContinueWith(watermarkTask))
}

func (d *TaskDispatcher) Close() error {
	return d.client.Close()
}

// --- Task Processor (tasks/processor.go) ---

type TaskProcessor struct {
	server *asynq.Server
}

func NewTaskProcessor(redisOpt asynq.RedisClientOpt) *TaskProcessor {
	server := asynq.NewServer(
		redisOpt,
		asynq.Config{
			Concurrency: 10,
			Queues: map[string]int{
				"critical": 6,
				"default":  3,
				"low":      1,
			},
			ErrorHandler: asynq.ErrorHandlerFunc(func(ctx context.Context, task *asynq.Task, err error) {
				retried, _ := asynq.GetRetryCount(ctx)
				maxRetry, _ := asynq.GetMaxRetry(ctx)
				if retried >= maxRetry {
					log.Printf("task %s failed after %d retries: %v", task.Type(), maxRetry, err)
				} else {
					log.Printf("task %s failed, will be retried (attempt %d/%d): %v", task.Type(), retried+1, maxRetry, err)
				}
			}),
		},
	)
	return &TaskProcessor{server: server}
}

func (p *TaskProcessor) Start() error {
	mux := asynq.NewServeMux()
	mux.HandleFunc(TypeEmailWelcome, HandleWelcomeEmailTask)
	mux.HandleFunc(TypeImageResize, HandleImageResizeTask)
	mux.HandleFunc(TypeImageWatermark, HandleImageWatermarkTask)
	mux.HandleFunc(TypePeriodicCleanup, HandleCleanupTask)

	return p.server.Run(mux)
}

func (p *TaskProcessor) Stop() {
	p.server.Shutdown()
}

// Task Handlers
func HandleWelcomeEmailTask(ctx context.Context, t *asynq.Task) error {
	var p WelcomeEmailPayload
	if err := json.Unmarshal(t.Payload(), &p); err != nil {
		return fmt.Errorf("json.Unmarshal failed: %v: %w", err, asynq.SkipRetry)
	}

	mu.RLock()
	user, ok := users[p.UserID]
	mu.RUnlock()

	if !ok {
		log.Printf("User with ID %s not found for welcome email.", p.UserID)
		return nil // Don't retry if user doesn't exist
	}

	log.Printf("Sending welcome email to user: %s (%s)", user.Email, p.UserID)
	time.Sleep(1 * time.Second) // Simulate email sending
	log.Printf("Welcome email sent successfully to %s", user.Email)
	return nil
}

func HandleImageResizeTask(ctx context.Context, t *asynq.Task) error {
	var p ImageProcessingPayload
	if err := json.Unmarshal(t.Payload(), &p); err != nil {
		return fmt.Errorf("json.Unmarshal failed: %v: %w", err, asynq.SkipRetry)
	}
	log.Printf("Processing image for PostID: %s. Step 1: Resizing image %s", p.PostID, p.ImageURL)
	// Simulate a potentially failing operation
	if rand.Intn(10) < 3 { // 30% chance of failure
		log.Printf("Error resizing image for PostID: %s. Retrying...", p.PostID)
		return fmt.Errorf("failed to connect to image processing service")
	}
	time.Sleep(3 * time.Second)
	log.Printf("Image resized for PostID: %s", p.PostID)
	return nil
}

func HandleImageWatermarkTask(ctx context.Context, t *asynq.Task) error {
	var p ImageProcessingPayload
	if err := json.Unmarshal(t.Payload(), &p); err != nil {
		return fmt.Errorf("json.Unmarshal failed: %v: %w", err, asynq.SkipRetry)
	}
	log.Printf("Processing image for PostID: %s. Step 2: Adding watermark to %s", p.PostID, p.ImageURL)
	time.Sleep(2 * time.Second)
	log.Printf("Watermark added for PostID: %s", p.PostID)
	return nil
}

func HandleCleanupTask(ctx context.Context, t *asynq.Task) error {
	var p CleanupPayload
	if err := json.Unmarshal(t.Payload(), &p); err != nil {
		return fmt.Errorf("json.Unmarshal failed: %v: %w", err, asynq.SkipRetry)
	}
	log.Printf("Running periodic cleanup task for users created before %s", p.CutoffDate.Format(time.RFC3339))
	// In a real app, you would query the DB and delete/deactivate users.
	time.Sleep(500 * time.Millisecond)
	log.Println("Periodic cleanup task finished.")
	return nil
}

// --- Services (services/user_service.go) ---

type UserService struct {
	taskDispatcher *TaskDispatcher
}

func NewUserService(td *TaskDispatcher) *UserService {
	return &UserService{taskDispatcher: td}
}

func (s *UserService) RegisterUser(ctx context.Context, email, password string) (*User, error) {
	mu.Lock()
	defer mu.Unlock()

	newUser := User{
		ID:           uuid.New(),
		Email:        email,
		PasswordHash: "hashed_" + password, // In real app, use bcrypt
		Role:         UserRoleDefault,
		IsActive:     true,
		CreatedAt:    time.Now(),
	}
	users[newUser.ID] = newUser

	log.Printf("User %s registered successfully.", email)

	// Dispatch background job
	_, err := s.taskDispatcher.DispatchWelcomeEmail(ctx, newUser.ID)
	if err != nil {
		log.Printf("Failed to enqueue welcome email for user %s: %v", newUser.ID, err)
		// This might warrant a compensating transaction in a real system, but for now we just log.
	}

	return &newUser, nil
}

// --- API Handlers (handlers/user_handler.go, handlers/job_handler.go) ---

type UserHandler struct {
	userService *UserService
}

func NewUserHandler(us *UserService) *UserHandler {
	return &UserHandler{userService: us}
}

func (h *UserHandler) CreateUser(c *fiber.Ctx) error {
	var req struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if err := c.BodyParser(&req); err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "invalid request"})
	}

	user, err := h.userService.RegisterUser(c.Context(), req.Email, req.Password)
	if err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "could not create user"})
	}

	return c.Status(http.StatusCreated).JSON(user)
}

type PostHandler struct {
	taskDispatcher *TaskDispatcher
}

func NewPostHandler(td *TaskDispatcher) *PostHandler {
	return &PostHandler{taskDispatcher: td}
}

func (h *PostHandler) ProcessImage(c *fiber.Ctx) error {
	postID, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "invalid post ID"})
	}

	mu.Lock()
	if _, ok := posts[postID]; !ok {
		// Create a mock post for the demo
		posts[postID] = Post{ID: postID, UserID: uuid.New(), Title: "Demo Post", Status: DraftPost}
	}
	mu.Unlock()

	taskInfo, err := h.taskDispatcher.DispatchImageProcessingPipeline(c.Context(), postID, "https://example.com/image.jpg")
	if err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "could not enqueue image processing task"})
	}

	return c.Status(http.StatusAccepted).JSON(fiber.Map{
		"message": "Image processing pipeline started",
		"job_id":  taskInfo.ID,
		"queue":   taskInfo.Queue,
	})
}

type JobHandler struct {
	inspector *asynq.Inspector
}

func NewJobHandler(redisOpt asynq.RedisClientOpt) *JobHandler {
	return &JobHandler{inspector: asynq.NewInspector(redisOpt)}
}

func (h *JobHandler) GetJobStatus(c *fiber.Ctx) error {
	jobID := c.Params("id")
	queue := c.Query("queue", "default")

	taskInfo, err := h.inspector.GetTaskInfo(queue, jobID)
	if err != nil {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{"error": fmt.Sprintf("job not found: %v", err)})
	}

	return c.JSON(fiber.Map{
		"job_id":      taskInfo.ID,
		"type":        taskInfo.Type,
		"state":       taskInfo.State.String(),
		"retries":     taskInfo.Retried,
		"max_retries": taskInfo.MaxRetry,
		"last_error":  taskInfo.LastErr,
		"next_try_at": taskInfo.NextProcessAt,
	})
}

// --- Main Application (main.go) ---

func main() {
	// Assumes Redis is running on localhost:6379
	redisConnection := asynq.RedisClientOpt{Addr: "localhost:6379"}

	// Setup Task Processor (Worker)
	processor := NewTaskProcessor(redisConnection)
	go func() {
		log.Println("Starting Task Processor...")
		if err := processor.Start(); err != nil {
			log.Fatalf("Could not start task processor: %v", err)
		}
	}()
	defer processor.Stop()

	// Setup Periodic Task Scheduler
	scheduler := asynq.NewScheduler(redisConnection, &asynq.SchedulerOpts{})
	payload, _ := json.Marshal(CleanupPayload{CutoffDate: time.Now().Add(-30 * 24 * time.Hour)})
	// Every 5 minutes, enqueue a cleanup task.
	entryID, err := scheduler.Register("@every 5m", asynq.NewTask(TypePeriodicCleanup, payload))
	if err != nil {
		log.Fatalf("could not register scheduler entry: %v", err)
	}
	log.Printf("registered periodic task with entry ID: %s", entryID)

	go func() {
		log.Println("Starting Periodic Task Scheduler...")
		if err := scheduler.Run(); err != nil {
			log.Fatalf("Could not start scheduler: %v", err)
		}
	}()
	defer scheduler.Shutdown()

	// Setup Fiber App
	app := fiber.New()
	taskDispatcher := NewTaskDispatcher(redisConnection)
	defer taskDispatcher.Close()

	// Dependency Injection
	userService := NewUserService(taskDispatcher)
	userHandler := NewUserHandler(userService)
	postHandler := NewPostHandler(taskDispatcher)
	jobHandler := NewJobHandler(redisConnection)

	// Routes
	api := app.Group("/api")
	api.Post("/users", userHandler.CreateUser)
	api.Post("/posts/:id/process-image", postHandler.ProcessImage)
	api.Get("/jobs/:id", jobHandler.GetJobStatus)

	// Graceful Shutdown
	go func() {
		if err := app.Listen(":3000"); err != nil && err != http.ErrServerClosed {
			log.Fatalf("listen: %s\n", err)
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	log.Println("Shutting down server...")

	if err := app.Shutdown(); err != nil {
		log.Fatal("Server forced to shutdown:", err)
	}

	log.Println("Server exiting")
}