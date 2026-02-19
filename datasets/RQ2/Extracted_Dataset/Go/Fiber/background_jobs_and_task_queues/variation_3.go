package main

import (
	"context"
	"encoding/json"
	"errors"
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

// --- Domain Models ---
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

// --- In-memory DB for demo ---
var (
	users = sync.Map{} // Use sync.Map for concurrent access
	posts = sync.Map{}
)

// --- Interfaces for Dependency Injection ---

type IJobDispatcher interface {
	DispatchWelcomeEmail(ctx context.Context, userID uuid.UUID, email string) (*asynq.TaskInfo, error)
	DispatchImagePipeline(ctx context.Context, postID uuid.UUID) (*asynq.TaskInfo, error)
}

type IJobStatusChecker interface {
	CheckStatus(queue, id string) (*asynq.TaskInfo, error)
}

type ITaskProcessor interface {
	Register() http.Handler
	Start()
	Stop()
}

type IUserService interface {
	Create(ctx context.Context, email string) (*User, error)
}

// --- Asynq Implementation of Interfaces ---

const (
	TaskWelcomeEmail = "task:welcome_email"
	TaskImageResize  = "task:image_resize"
	TaskImageWatermark = "task:image_watermark"
	TaskPeriodicCleanup = "task:periodic_cleanup"
)

type AsynqJobDispatcher struct {
	client *asynq.Client
}

func NewAsynqJobDispatcher(opt asynq.RedisClientOpt) *AsynqJobDispatcher {
	return &AsynqJobDispatcher{client: asynq.NewClient(opt)}
}

func (d *AsynqJobDispatcher) DispatchWelcomeEmail(ctx context.Context, userID uuid.UUID, email string) (*asynq.TaskInfo, error) {
	payload, _ := json.Marshal(map[string]interface{}{"user_id": userID, "email": email})
	task := asynq.NewTask(TaskWelcomeEmail, payload, asynq.MaxRetry(5))
	return d.client.EnqueueContext(ctx, task, asynq.Queue("default"))
}

func (d *AsynqJobDispatcher) DispatchImagePipeline(ctx context.Context, postID uuid.UUID) (*asynq.TaskInfo, error) {
	payload, _ := json.Marshal(map[string]interface{}{"post_id": postID})
	resize := asynq.NewTask(TaskImageResize, payload, asynq.MaxRetry(3))
	watermark := asynq.NewTask(TaskImageWatermark, payload, asynq.MaxRetry(3))
	return d.client.EnqueueContext(ctx, resize, asynq.ContinueWith(watermark))
}

type AsynqJobStatusChecker struct {
	inspector *asynq.Inspector
}

func NewAsynqJobStatusChecker(opt asynq.RedisClientOpt) *AsynqJobStatusChecker {
	return &AsynqJobStatusChecker{inspector: asynq.NewInspector(opt)}
}

func (c *AsynqJobStatusChecker) CheckStatus(queue, id string) (*asynq.TaskInfo, error) {
	return c.inspector.GetTaskInfo(queue, id)
}

type AsynqTaskProcessor struct {
	server *asynq.Server
}

func NewAsynqTaskProcessor(opt asynq.RedisClientOpt) *AsynqTaskProcessor {
	srv := asynq.NewServer(opt, asynq.Config{Concurrency: 10})
	return &AsynqTaskProcessor{server: srv}
}

func (p *AsynqTaskProcessor) Register() *asynq.ServeMux {
	mux := asynq.NewServeMux()
	mux.HandleFunc(TaskWelcomeEmail, p.handleWelcomeEmail)
	mux.HandleFunc(TaskImageResize, p.handleImageResize)
	mux.HandleFunc(TaskImageWatermark, p.handleImageWatermark)
	mux.HandleFunc(TaskPeriodicCleanup, p.handlePeriodicCleanup)
	return mux
}

func (p *AsynqTaskProcessor) Start() {
	mux := p.Register()
	if err := p.server.Run(mux); err != nil {
		log.Fatalf("could not run asynq server: %v", err)
	}
}

func (p *AsynqTaskProcessor) Stop() {
	p.server.Shutdown()
}

// Task Handlers as methods
func (p *AsynqTaskProcessor) handleWelcomeEmail(ctx context.Context, t *asynq.Task) error {
	var payload map[string]string
	json.Unmarshal(t.Payload(), &payload)
	log.Printf("HANDLER: Sending welcome email to %s", payload["email"])
	time.Sleep(500 * time.Millisecond)
	return nil
}

func (p *AsynqTaskProcessor) handleImageResize(ctx context.Context, t *asynq.Task) error {
	var payload map[string]string
	json.Unmarshal(t.Payload(), &payload)
	log.Printf("HANDLER: Resizing image for post %s", payload["post_id"])
	if rand.Intn(10) > 6 { // 30% chance of failure
		return errors.New("transient error in image resizing service")
	}
	time.Sleep(2 * time.Second)
	return nil
}

func (p *AsynqTaskProcessor) handleImageWatermark(ctx context.Context, t *asynq.Task) error {
	var payload map[string]string
	json.Unmarshal(t.Payload(), &payload)
	log.Printf("HANDLER: Watermarking image for post %s", payload["post_id"])
	time.Sleep(1 * time.Second)
	return nil
}

func (p *AsynqTaskProcessor) handlePeriodicCleanup(ctx context.Context, t *asynq.Task) error {
	log.Printf("HANDLER: Running periodic cleanup job")
	return nil
}

// --- Service Implementation ---

type UserService struct {
	dispatcher IJobDispatcher
}

func NewUserService(d IJobDispatcher) *UserService {
	return &UserService{dispatcher: d}
}

func (s *UserService) Create(ctx context.Context, email string) (*User, error) {
	newUser := &User{
		ID: uuid.New(), Email: email, Role: USER, IsActive: true, CreatedAt: time.Now(),
	}
	users.Store(newUser.ID, *newUser)
	
	_, err := s.dispatcher.DispatchWelcomeEmail(ctx, newUser.ID, newUser.Email)
	if err != nil {
		// In a real app, you might want to handle this failure more gracefully
		log.Printf("WARN: User %s created, but failed to dispatch welcome email: %v", newUser.ID, err)
	}
	return newUser, nil
}

// --- API Layer ---

type API struct {
	userService IUserService
	dispatcher  IJobDispatcher
	statusChecker IJobStatusChecker
}

func NewAPI(u IUserService, d IJobDispatcher, s IJobStatusChecker) *API {
	return &API{userService: u, dispatcher: d, statusChecker: s}
}

func (a *API) RegisterUser(c *fiber.Ctx) error {
	var body struct{ Email string `json:"email"` }
	if err := c.BodyParser(&body); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "invalid body"})
	}
	user, err := a.userService.Create(c.Context(), body.Email)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": err.Error()})
	}
	return c.Status(fiber.StatusCreated).JSON(user)
}

func (a *API) ProcessPostImage(c *fiber.Ctx) error {
	id, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "invalid post id"})
	}
	posts.Store(id, Post{ID: id, Title: "Sample Post"}) // mock post
	
	info, err := a.dispatcher.DispatchImagePipeline(c.Context(), id)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": "could not start job"})
	}
	return c.Status(fiber.StatusAccepted).JSON(fiber.Map{"job_id": info.ID, "queue": info.Queue})
}

func (a *API) GetJobStatus(c *fiber.Ctx) error {
	jobID := c.Params("id")
	queue := c.Query("queue", "default")
	info, err := a.statusChecker.CheckStatus(queue, jobID)
	if err != nil {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": "job not found"})
	}
	return c.JSON(info)
}

// --- Main ---
func main() {
	// Assumes Redis is running on localhost:6379
	redisOpt := asynq.RedisClientOpt{Addr: "localhost:6379"}

	// --- Dependency Injection ---
	dispatcher := NewAsynqJobDispatcher(redisOpt)
	statusChecker := NewAsynqJobStatusChecker(redisOpt)
	userService := NewUserService(dispatcher)
	api := NewAPI(userService, dispatcher, statusChecker)
	processor := NewAsynqTaskProcessor(redisOpt)

	// --- Start Worker ---
	go func() {
		log.Println("Starting worker...")
		processor.Start()
	}()
	defer processor.Stop()

	// --- Start Scheduler ---
	scheduler := asynq.NewScheduler(redisOpt, nil)
	go func() {
		log.Println("Starting scheduler...")
		// Every 2 minutes
		scheduler.Register("*/2 * * * *", asynq.NewTask(TaskPeriodicCleanup, nil))
		if err := scheduler.Run(); err != nil {
			log.Fatalf("could not run scheduler: %v", err)
		}
	}()
	defer scheduler.Shutdown()

	// --- Start Fiber App ---
	app := fiber.New()
	app.Post("/users", api.RegisterUser)
	app.Post("/posts/:id/process-image", api.ProcessPostImage)
	app.Get("/jobs/:id", api.GetJobStatus)

	// --- Graceful Shutdown ---
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