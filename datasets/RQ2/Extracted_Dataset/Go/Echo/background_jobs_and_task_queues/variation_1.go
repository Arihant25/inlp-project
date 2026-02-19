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
const redisAddr = "127.0.0.1:6379"

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

// --- Mock Database ---

type MockDB struct {
	users map[uuid.UUID]User
	posts map[uuid.UUID]Post
	mu    sync.RWMutex
}

func NewMockDB() *MockDB {
	return &MockDB{
		users: make(map[uuid.UUID]User),
		posts: make(map[uuid.UUID]Post),
	}
}

// --- Task Definitions ---

const (
	TaskTypeWelcomeEmail      = "email:welcome"
	TaskTypeImageResize       = "image:resize"
	TaskTypeImageWatermark    = "image:watermark"
	TaskTypeGenerateDailyReport = "report:daily"
)

type WelcomeEmailPayload struct {
	UserID uuid.UUID `json:"user_id"`
}

type ImageProcessingPayload struct {
	PostID      uuid.UUID `json:"post_id"`
	SourceImage []byte    `json:"source_image"`
}

type DailyReportPayload struct {
	ReportDate string `json:"report_date"`
}

// --- Job Service (Interface-based for DI) ---

type JobService interface {
	EnqueueWelcomeEmail(ctx context.Context, userID uuid.UUID) (*asynq.TaskInfo, error)
	EnqueueImageProcessingPipeline(ctx context.Context, postID uuid.UUID, image []byte) (*asynq.TaskInfo, error)
}

type AsynqJobService struct {
	client *asynq.Client
	db     *MockDB
}

func NewAsynqJobService(client *asynq.Client, db *MockDB) JobService {
	return &AsynqJobService{client: client, db: db}
}

func (s *AsynqJobService) EnqueueWelcomeEmail(ctx context.Context, userID uuid.UUID) (*asynq.TaskInfo, error) {
	payload, err := json.Marshal(WelcomeEmailPayload{UserID: userID})
	if err != nil {
		return nil, fmt.Errorf("failed to marshal welcome email payload: %w", err)
	}
	task := asynq.NewTask(TaskTypeWelcomeEmail, payload, asynq.MaxRetry(5), asynq.Timeout(1*time.Minute))
	return s.client.EnqueueContext(ctx, task)
}

func (s *AsynqJobService) EnqueueImageProcessingPipeline(ctx context.Context, postID uuid.UUID, image []byte) (*asynq.TaskInfo, error) {
	payload, err := json.Marshal(ImageProcessingPayload{PostID: postID, SourceImage: image})
	if err != nil {
		return nil, fmt.Errorf("failed to marshal image processing payload: %w", err)
	}
	task := asynq.NewTask(TaskTypeImageResize, payload, asynq.MaxRetry(3), asynq.Timeout(5*time.Minute))
	return s.client.EnqueueContext(ctx, task)
}

// --- Task Handlers (OOP Style) ---

type TaskProcessor struct {
	db *MockDB
}

func NewTaskProcessor(db *MockDB) *TaskProcessor {
	return &TaskProcessor{db: db}
}

func (p *TaskProcessor) HandleWelcomeEmailTask(ctx context.Context, t *asynq.Task) error {
	var payload WelcomeEmailPayload
	if err := json.Unmarshal(t.Payload(), &payload); err != nil {
		return fmt.Errorf("failed to unmarshal payload: %w", asynq.SkipRetry)
	}
	log.Printf("Sending welcome email to user %s...", payload.UserID)
	time.Sleep(2 * time.Second) // Simulate email sending
	log.Printf("Welcome email sent successfully to user %s", payload.UserID)
	return nil
}

func (p *TaskProcessor) HandleImageResizeTask(ctx context.Context, t *asynq.Task) error {
	var payload ImageProcessingPayload
	if err := json.Unmarshal(t.Payload(), &payload); err != nil {
		return fmt.Errorf("failed to unmarshal payload: %w", asynq.SkipRetry)
	}
	log.Printf("Resizing image for post %s...", payload.PostID)
	time.Sleep(5 * time.Second) // Simulate resizing
	log.Printf("Image resized for post %s. Enqueuing watermark task.", payload.PostID)

	// Chain the next task in the pipeline
	watermarkPayloadBytes, err := json.Marshal(payload)
	if err != nil {
		return fmt.Errorf("failed to marshal watermark payload: %w", err)
	}
	watermarkTask := asynq.NewTask(TaskTypeImageWatermark, watermarkPayloadBytes)
	_, err = asynq.NewClient(asynq.RedisClientOpt{Addr: redisAddr}).Enqueue(watermarkTask)
	return err
}

func (p *TaskProcessor) HandleImageWatermarkTask(ctx context.Context, t *asynq.Task) error {
	var payload ImageProcessingPayload
	if err := json.Unmarshal(t.Payload(), &payload); err != nil {
		return fmt.Errorf("failed to unmarshal payload: %w", asynq.SkipRetry)
	}
	log.Printf("Adding watermark to image for post %s...", payload.PostID)
	time.Sleep(3 * time.Second) // Simulate watermarking
	log.Printf("Image processing pipeline complete for post %s.", payload.PostID)
	return nil
}

func (p *TaskProcessor) HandleDailyReportTask(ctx context.Context, t *asynq.Task) error {
	var payload DailyReportPayload
	if err := json.Unmarshal(t.Payload(), &payload); err != nil {
		return fmt.Errorf("failed to unmarshal payload: %w", asynq.SkipRetry)
	}
	log.Printf("Generating daily report for %s...", payload.ReportDate)
	time.Sleep(10 * time.Second) // Simulate report generation
	log.Printf("Daily report for %s generated successfully.", payload.ReportDate)
	return nil
}

// --- API Handlers ---

type APIHandler struct {
	jobService JobService
	db         *MockDB
	inspector  *asynq.Inspector
}

func NewAPIHandler(js JobService, db *MockDB, inspector *asynq.Inspector) *APIHandler {
	return &APIHandler{jobService: js, db: db, inspector: inspector}
}

func (h *APIHandler) CreateUser(c echo.Context) error {
	var req struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if err := c.Bind(&req); err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid request"})
	}

	newUser := User{
		ID:           uuid.New(),
		Email:        req.Email,
		PasswordHash: "hashed_" + req.Password,
		Role:         RoleUser,
		IsActive:     true,
		CreatedAt:    time.Now(),
	}
	h.db.mu.Lock()
	h.db.users[newUser.ID] = newUser
	h.db.mu.Unlock()

	taskInfo, err := h.jobService.EnqueueWelcomeEmail(c.Request().Context(), newUser.ID)
	if err != nil {
		log.Printf("Error enqueuing welcome email: %v", err)
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": "could not schedule welcome email"})
	}

	return c.JSON(http.StatusCreated, map[string]interface{}{
		"message": "User created, welcome email scheduled.",
		"user":    newUser,
		"task_id": taskInfo.ID,
	})
}

func (h *APIHandler) PublishPost(c echo.Context) error {
	postID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid post ID"})
	}

	// In a real app, you'd get the post from the DB and an image from the request body
	mockImage := []byte("dummy-image-data")
	taskInfo, err := h.jobService.EnqueueImageProcessingPipeline(c.Request().Context(), postID, mockImage)
	if err != nil {
		log.Printf("Error enqueuing image processing: %v", err)
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": "could not start image processing"})
	}

	return c.JSON(http.StatusAccepted, map[string]interface{}{
		"message": "Image processing pipeline started.",
		"task_id": taskInfo.ID,
	})
}

func (h *APIHandler) GetJobStatus(c echo.Context) error {
	taskID := c.Param("id")
	queue := c.QueryParam("queue")
	if queue == "" {
		queue = "default"
	}

	taskInfo, err := h.inspector.GetTaskInfo(queue, taskID)
	if err != nil {
		return c.JSON(http.StatusNotFound, map[string]string{"error": "job not found"})
	}

	return c.JSON(http.StatusOK, taskInfo)
}

// --- Main Application ---

func main() {
	// --- Dependencies ---
	db := NewMockDB()
	redisOpt := asynq.RedisClientOpt{Addr: redisAddr}
	asynqClient := asynq.NewClient(redisOpt)
	defer asynqClient.Close()
	asynqInspector := asynq.NewInspector(redisOpt)

	jobService := NewAsynqJobService(asynqClient, db)
	apiHandler := NewAPIHandler(jobService, db, asynqInspector)

	// --- Echo Server ---
	e := echo.New()
	e.Use(middleware.Logger())
	e.Use(middleware.Recover())

	e.POST("/users", apiHandler.CreateUser)
	e.POST("/posts/:id/publish", apiHandler.PublishPost)
	e.GET("/jobs/:id", apiHandler.GetJobStatus)

	// --- Asynq Worker Server ---
	asynqServer := asynq.NewServer(
		redisOpt,
		asynq.Config{
			Concurrency: 10,
			Queues: map[string]int{
				"critical": 6,
				"default":  3,
				"low":      1,
			},
			// Exponential backoff
			RetryDelayFunc: asynq.DefaultRetryDelayFunc,
		},
	)

	taskProcessor := NewTaskProcessor(db)
	mux := asynq.NewServeMux()
	mux.HandleFunc(TaskTypeWelcomeEmail, taskProcessor.HandleWelcomeEmailTask)
	mux.HandleFunc(TaskTypeImageResize, taskProcessor.HandleImageResizeTask)
	mux.HandleFunc(TaskTypeImageWatermark, taskProcessor.HandleImageWatermarkTask)
	mux.HandleFunc(TaskTypeGenerateDailyReport, taskProcessor.HandleDailyReportTask)

	// --- Asynq Scheduler for Periodic Tasks ---
	scheduler := asynq.NewScheduler(redisOpt, nil)
	reportPayload, _ := json.Marshal(DailyReportPayload{ReportDate: time.Now().Format("2006-01-02")})
	// Schedule to run every minute for demonstration. In production, this would be "0 0 * * *" for daily.
	_, err := scheduler.Register("@every 1m", asynq.NewTask(TaskTypeGenerateDailyReport, reportPayload))
	if err != nil {
		log.Fatalf("could not register scheduler task: %v", err)
	}

	// --- Graceful Shutdown ---
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	go func() {
		if err := scheduler.Run(); err != nil {
			log.Fatalf("could not run scheduler: %v", err)
		}
	}()

	go func() {
		if err := asynqServer.Run(mux); err != nil {
			log.Fatalf("could not run asynq server: %v", err)
		}
	}()

	go func() {
		if err := e.Start(":8080"); err != nil && err != http.ErrServerClosed {
			e.Logger.Fatal("shutting down the server")
		}
	}()

	<-ctx.Done()
	log.Println("Shutting down...")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := e.Shutdown(shutdownCtx); err != nil {
		e.Logger.Fatal(err)
	}
	scheduler.Shutdown()
	asynqServer.Shutdown()
	log.Println("Shutdown complete.")
}