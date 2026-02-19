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

// --- Models ---
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

// --- Mock DB ---
var (
	userStore = make(map[uuid.UUID]User)
	postStore = make(map[uuid.UUID]Post)
	dbMutex   = &sync.RWMutex{}
)

// --- Task Definitions & Payloads ---
const (
	TaskTypeWelcomeEmail = "email:send_welcome"
	TaskTypeResizeImage  = "image:process_resize"
	TaskTypeWatermarkImage = "image:process_watermark"
	TaskTypePeriodicReport = "report:generate_daily"
)

type WelcomeEmailJobPayload struct {
	Email string    `json:"email"`
	UserID uuid.UUID `json:"user_id"`
}

type ImageJobPayload struct {
	PostID uuid.UUID `json:"post_id"`
}

// --- Task Creation (Functional Style) ---
func newWelcomeEmailTask(userID uuid.UUID, email string) (*asynq.Task, error) {
	payload, err := json.Marshal(WelcomeEmailJobPayload{UserID: userID, Email: email})
	if err != nil {
		return nil, err
	}
	// This task is critical, retry up to 10 times with a 5 minute timeout.
	return asynq.NewTask(TaskTypeWelcomeEmail, payload, asynq.MaxRetry(10), asynq.Timeout(5*time.Minute), asynq.Queue("critical")), nil
}

func newImageProcessingTasks(postID uuid.UUID) (resizeTask *asynq.Task, watermarkTask *asynq.Task, err error) {
	payload, err := json.Marshal(ImageJobPayload{PostID: postID})
	if err != nil {
		return nil, nil, err
	}
	resizeTask = asynq.NewTask(TaskTypeResizeImage, payload, asynq.MaxRetry(3))
	watermarkTask = asynq.NewTask(TaskTypeWatermarkImage, payload, asynq.MaxRetry(3))
	return resizeTask, watermarkTask, nil
}

// --- Task Handlers (Functional Style) ---
func handleWelcomeEmailTask(ctx context.Context, t *asynq.Task) error {
	var p WelcomeEmailJobPayload
	if err := json.Unmarshal(t.Payload(), &p); err != nil {
		return fmt.Errorf("could not unmarshal payload: %v", err)
	}
	log.Printf("[TASK] Sending Welcome Email to %s (User ID: %s)", p.Email, p.UserID)
	time.Sleep(1 * time.Second) // Simulate network latency
	log.Printf("[TASK] Welcome Email successfully sent to %s", p.Email)
	return nil
}

func handleResizeImageTask(ctx context.Context, t *asynq.Task) error {
	var p ImageJobPayload
	if err := json.Unmarshal(t.Payload(), &p); err != nil {
		return fmt.Errorf("could not unmarshal payload: %v", err)
	}
	log.Printf("[TASK] Resizing image for post %s", p.PostID)
	// Simulate a task that might fail and needs retrying with backoff
	if rand.Intn(2) == 0 { // 50% failure rate
		log.Printf("[TASK-FAIL] Failed to resize image for post %s. Will retry.", p.PostID)
		return fmt.Errorf("image service is temporarily unavailable")
	}
	time.Sleep(4 * time.Second)
	log.Printf("[TASK] Image resized for post %s", p.PostID)
	return nil
}

func handleWatermarkImageTask(ctx context.Context, t *asynq.Task) error {
	var p ImageJobPayload
	if err := json.Unmarshal(t.Payload(), &p); err != nil {
		return fmt.Errorf("could not unmarshal payload: %v", err)
	}
	log.Printf("[TASK] Watermarking image for post %s", p.PostID)
	time.Sleep(2 * time.Second)
	log.Printf("[TASK] Image watermarked for post %s", p.PostID)
	return nil
}

func handlePeriodicReportTask(ctx context.Context, t *asynq.Task) error {
	log.Printf("[PERIODIC-TASK] Generating daily report...")
	dbMutex.RLock()
	userCount := len(userStore)
	postCount := len(postStore)
	dbMutex.RUnlock()
	log.Printf("[PERIODIC-TASK] Report: %d users, %d posts", userCount, postCount)
	return nil
}

// --- Global Asynq Client ---
var taskClient *asynq.Client

func main() {
	// Assumes Redis is running on localhost:6379
	redisOpt := asynq.RedisClientOpt{Addr: "localhost:6379"}
	taskClient = asynq.NewClient(redisOpt)
	defer taskClient.Close()

	// --- Worker Goroutine ---
	go func() {
		srv := asynq.NewServer(redisOpt, asynq.Config{
			Concurrency: 20,
			Queues: map[string]int{"critical": 10, "default": 5},
		})
		mux := asynq.NewServeMux()
		mux.HandleFunc(TaskTypeWelcomeEmail, handleWelcomeEmailTask)
		mux.HandleFunc(TaskTypeResizeImage, handleResizeImageTask)
		mux.HandleFunc(TaskTypeWatermarkImage, handleWatermarkImageTask)
		mux.HandleFunc(TaskTypePeriodicReport, handlePeriodicReportTask)

		log.Println("Worker process starting...")
		if err := srv.Run(mux); err != nil {
			log.Fatalf("Could not run worker server: %v", err)
		}
	}()

	// --- Scheduler Goroutine ---
	go func() {
		scheduler := asynq.NewScheduler(redisOpt, nil)
		// Every day at midnight
		_, err := scheduler.Register("0 0 * * *", asynq.NewTask(TaskTypePeriodicReport, nil))
		if err != nil {
			log.Fatalf("Could not register periodic task: %v", err)
		}
		log.Println("Scheduler process starting...")
		if err := scheduler.Run(); err != nil {
			log.Fatalf("Could not run scheduler: %v", err)
		}
	}()

	// --- Fiber App Setup ---
	app := fiber.New()
	inspector := asynq.NewInspector(redisOpt)

	// --- API Handlers (Handler-centric) ---
	app.Post("/users", func(c *fiber.Ctx) error {
		type request struct {
			Email    string `json:"email"`
			Password string `json:"password"`
		}
		var req request
		if err := c.BodyParser(&req); err != nil {
			return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "cannot parse JSON"})
		}

		newUser := User{
			ID:           uuid.New(),
			Email:        req.Email,
			PasswordHash: "...", // Hashing omitted for brevity
			Role:         RoleUser,
			IsActive:     true,
			CreatedAt:    time.Now(),
		}

		dbMutex.Lock()
		userStore[newUser.ID] = newUser
		dbMutex.Unlock()

		task, err := newWelcomeEmailTask(newUser.ID, newUser.Email)
		if err != nil {
			log.Printf("Error creating welcome email task: %v", err)
			return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": "internal server error"})
		}

		info, err := taskClient.Enqueue(task)
		if err != nil {
			log.Printf("Error enqueuing welcome email task: %v", err)
			return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": "internal server error"})
		}
		log.Printf("Enqueued welcome email task %s for user %s", info.ID, newUser.ID)

		return c.Status(fiber.StatusCreated).JSON(newUser)
	})

	app.Post("/posts/:id/process-image", func(c *fiber.Ctx) error {
		postID, err := uuid.Parse(c.Params("id"))
		if err != nil {
			return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "invalid post ID"})
		}

		dbMutex.Lock()
		if _, ok := postStore[postID]; !ok {
			postStore[postID] = Post{ID: postID, Title: "A new post"}
		}
		dbMutex.Unlock()

		resizeTask, watermarkTask, err := newImageProcessingTasks(postID)
		if err != nil {
			log.Printf("Error creating image processing tasks: %v", err)
			return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": "internal server error"})
		}

		// Enqueue the first task, and chain the second to run after it completes.
		info, err := taskClient.Enqueue(resizeTask, asynq.ContinueWith(watermarkTask))
		if err != nil {
			log.Printf("Error enqueuing image processing tasks: %v", err)
			return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": "internal server error"})
		}
		log.Printf("Enqueued image processing pipeline (resize task %s) for post %s", info.ID, postID)

		return c.Status(fiber.StatusAccepted).JSON(fiber.Map{
			"message": "Image processing started",
			"job_id":  info.ID,
			"queue":   info.Queue,
		})
	})

	app.Get("/jobs/:queue/:id", func(c *fiber.Ctx) error {
		id := c.Params("id")
		queue := c.Params("queue")

		info, err := inspector.GetTaskInfo(queue, id)
		if err != nil {
			return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": "job not found"})
		}

		return c.JSON(fiber.Map{
			"id":          info.ID,
			"type":        info.Type,
			"state":       info.State.String(),
			"payload":     string(info.Payload),
			"queue":       info.Queue,
			"retry":       info.Retried,
			"max_retry":   info.MaxRetry,
			"last_error":  info.LastErr,
		})
	})

	// --- Graceful Shutdown ---
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)

	go func() {
		if err := app.Listen(":3000"); err != nil {
			log.Printf("Error starting server: %v", err)
		}
	}()

	<-stop
	log.Println("Shutting down Fiber app...")
	if err := app.Shutdown(); err != nil {
		log.Fatalf("Fiber app shutdown failed: %v", err)
	}
	log.Println("Shutdown complete.")
}