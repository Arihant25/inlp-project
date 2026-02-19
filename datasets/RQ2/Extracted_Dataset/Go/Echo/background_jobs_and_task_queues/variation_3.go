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
const redisDSN = "127.0.0.1:6379"

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

// --- Mock Datastore ---

type Datastore struct {
	users map[uuid.UUID]*User
	posts map[uuid.UUID]*Post
	mu    sync.RWMutex
}

func NewDatastore() *Datastore {
	return &Datastore{
		users: make(map[uuid.UUID]*User),
		posts: make(map[uuid.UUID]*Post),
	}
}

// --- Application Context ---

type AppContext struct {
	echo.Context
	app *Application
}

func (c *AppContext) DB() *Datastore {
	return c.app.db
}

func (c *AppContext) JobQueue() *asynq.Client {
	return c.app.asynqClient
}

func (c *AppContext) JobInspector() *asynq.Inspector {
	return c.app.asynqInspector
}

// --- Application Container ---

type Application struct {
	echo           *echo.Echo
	db             *Datastore
	asynqClient    *asynq.Client
	asynqServer    *asynq.Server
	asynqScheduler *asynq.Scheduler
	asynqInspector *asynq.Inspector
}

func NewApplication() *Application {
	redisOpt := asynq.RedisClientOpt{Addr: redisDSN}
	
	app := &Application{
		echo:           echo.New(),
		db:             NewDatastore(),
		asynqClient:    asynq.NewClient(redisOpt),
		asynqInspector: asynq.NewInspector(redisOpt),
		asynqScheduler: asynq.NewScheduler(redisOpt, &asynq.SchedulerOpts{}),
		asynqServer: asynq.NewServer(redisOpt, asynq.Config{
			Concurrency:    20,
			RetryDelayFunc: asynq.DefaultRetryDelayFunc, // Exponential backoff
		}),
	}

	app.echo.Use(middleware.Logger())
	app.echo.Use(func(h echo.HandlerFunc) echo.HandlerFunc {
		return func(c echo.Context) error {
			cc := &AppContext{c, app}
			return h(cc)
		}
	})

	return app
}

func (app *Application) Start() {
	// Register API routes
	apiHandlers := &APIHandler{app: app}
	app.echo.POST("/users", apiHandlers.HandleCreateUser)
	app.echo.POST("/posts/:id/publish", apiHandlers.HandlePublishPost)
	app.echo.GET("/jobs/:id", apiHandlers.HandleGetJobStatus)

	// Register Task handlers
	taskHandlers := &TaskHandler{app: app}
	mux := asynq.NewServeMux()
	mux.Handle(TaskSendWelcomeEmail, taskHandlers)
	mux.Handle(TaskProcessImage, taskHandlers)
	mux.Handle(TaskWatermarkImage, taskHandlers)
	mux.Handle(TaskGenerateReport, taskHandlers)

	// Register Periodic tasks
	_, err := app.asynqScheduler.Register("@hourly", NewGenerateReportTask())
	if err != nil {
		log.Fatalf("FATAL: could not register periodic task: %v", err)
	}

	// Start services in goroutines
	go func() {
		if err := app.asynqServer.Run(mux); err != nil {
			log.Fatalf("FATAL: asynq server error: %v", err)
		}
	}()
	go func() {
		if err := app.asynqScheduler.Run(); err != nil {
			log.Fatalf("FATAL: asynq scheduler error: %v", err)
		}
	}()
	go func() {
		if err := app.echo.Start(":8080"); err != nil && err != http.ErrServerClosed {
			log.Fatalf("FATAL: echo server error: %v", err)
		}
	}()
}

func (app *Application) Shutdown(ctx context.Context) {
	log.Println("Shutting down application...")
	app.asynqScheduler.Shutdown()
	app.asynqServer.Shutdown()
	if err := app.echo.Shutdown(ctx); err != nil {
		log.Printf("WARN: echo shutdown error: %v", err)
	}
	app.asynqClient.Close()
	log.Println("Shutdown complete.")
}

// --- API Handlers ---

type APIHandler struct {
	app *Application
}

func (h *APIHandler) HandleCreateUser(c echo.Context) error {
	cc := c.(*AppContext)
	var body struct{ Email, Password string }
	if err := cc.Bind(&body); err != nil {
		return cc.JSON(http.StatusBadRequest, echo.Map{"error": "invalid payload"})
	}

	user := &User{ID: uuid.New(), Email: body.Email, CreatedAt: time.Now()}
	cc.DB().mu.Lock()
	cc.DB().users[user.ID] = user
	cc.DB().mu.Unlock()

	task := NewWelcomeEmailTask(user.ID)
	info, err := cc.JobQueue().Enqueue(task, asynq.MaxRetry(5), asynq.Timeout(time.Minute))
	if err != nil {
		return cc.JSON(http.StatusInternalServerError, echo.Map{"error": "failed to enqueue job"})
	}

	return cc.JSON(http.StatusCreated, echo.Map{"user": user, "task_id": info.ID})
}

func (h *APIHandler) HandlePublishPost(c echo.Context) error {
	cc := c.(*AppContext)
	postID, err := uuid.Parse(cc.Param("id"))
	if err != nil {
		return cc.JSON(http.StatusBadRequest, echo.Map{"error": "invalid post id"})
	}

	task := NewImageProcessingTask(postID)
	info, err := cc.JobQueue().Enqueue(task, asynq.MaxRetry(3), asynq.Timeout(5*time.Minute))
	if err != nil {
		return cc.JSON(http.StatusInternalServerError, echo.Map{"error": "failed to enqueue job"})
	}

	return cc.JSON(http.StatusAccepted, echo.Map{"message": "image processing started", "task_id": info.ID})
}

func (h *APIHandler) HandleGetJobStatus(c echo.Context) error {
	cc := c.(*AppContext)
	taskID := cc.Param("id")
	info, err := cc.JobInspector().GetTaskInfo("default", taskID)
	if err != nil {
		return cc.JSON(http.StatusNotFound, echo.Map{"error": "job not found"})
	}
	return cc.JSON(http.StatusOK, info)
}

// --- Task Definitions & Handlers ---

const (
	TaskSendWelcomeEmail = "task:email:welcome"
	TaskProcessImage     = "task:image:resize"
	TaskWatermarkImage   = "task:image:watermark"
	TaskGenerateReport   = "task:report:generate"
)

func NewWelcomeEmailTask(userID uuid.UUID) *asynq.Task {
	payload, _ := json.Marshal(echo.Map{"user_id": userID})
	return asynq.NewTask(TaskSendWelcomeEmail, payload)
}

func NewImageProcessingTask(postID uuid.UUID) *asynq.Task {
	payload, _ := json.Marshal(echo.Map{"post_id": postID})
	return asynq.NewTask(TaskProcessImage, payload)
}

func NewWatermarkTask(postID uuid.UUID) *asynq.Task {
	payload, _ := json.Marshal(echo.Map{"post_id": postID})
	return asynq.NewTask(TaskWatermarkImage, payload)
}

func NewGenerateReportTask() *asynq.Task {
	return asynq.NewTask(TaskGenerateReport, nil)
}

type TaskHandler struct {
	app *Application
}

func (h *TaskHandler) ProcessTask(ctx context.Context, t *asynq.Task) error {
	switch t.Type() {
	case TaskSendWelcomeEmail:
		var p struct{ UserID uuid.UUID `json:"user_id"` }
		json.Unmarshal(t.Payload(), &p)
		log.Printf("Processing welcome email for user %s", p.UserID)
		time.Sleep(2 * time.Second)
		return nil
	case TaskProcessImage:
		var p struct{ PostID uuid.UUID `json:"post_id"` }
		json.Unmarshal(t.Payload(), &p)
		log.Printf("Resizing image for post %s", p.PostID)
		time.Sleep(5 * time.Second)
		// Chain next task
		_, err := h.app.asynqClient.Enqueue(NewWatermarkTask(p.PostID))
		return err
	case TaskWatermarkImage:
		var p struct{ PostID uuid.UUID `json:"post_id"` }
		json.Unmarshal(t.Payload(), &p)
		log.Printf("Watermarking image for post %s", p.PostID)
		time.Sleep(3 * time.Second)
		return nil
	case TaskGenerateReport:
		log.Println("Generating hourly report...")
		time.Sleep(10 * time.Second)
		log.Println("Hourly report generated.")
		return nil
	default:
		return fmt.Errorf("unhandled task type: %s", t.Type())
	}
}

// --- Main Execution ---

func main() {
	app := NewApplication()
	app.Start()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, os.Interrupt, syscall.SIGTERM)
	<-quit

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	app.Shutdown(ctx)
}