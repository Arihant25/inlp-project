package main

import (
	"context"
	"encoding/json"
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
)

// --- Globals & Config ---
var (
	redisAddr = "127.0.0.1:6379"
	ac        *asynq.Client
	ai        *asynq.Inspector
	db        = struct {
		sync.RWMutex
		users map[uuid.UUID]User
		posts map[uuid.UUID]Post
	}{
		users: make(map[uuid.UUID]User),
		posts: make(map[uuid.UUID]Post),
	}
)

// --- Domain ---
type User struct {
	ID    uuid.UUID `json:"id"`
	Email string    `json:"email"`
}
type Post struct {
	ID     uuid.UUID `json:"id"`
	UserID uuid.UUID `json:"user_id"`
	Title  string    `json:"title"`
}

// --- Task Definitions ---
const (
	TASK_EMAIL_WELCOME = "email_welcome"
	TASK_IMG_RESIZE    = "img_resize"
	TASK_IMG_WATERMARK = "img_watermark"
	TASK_SYS_CLEANUP   = "sys_cleanup"
)

type EmailPayload struct{ UserID uuid.UUID }
type ImgPayload struct{ PostID uuid.UUID }

func main() {
	// --- Init ---
	redisOpt := asynq.RedisClientOpt{Addr: redisAddr}
	ac = asynq.NewClient(redisOpt)
	defer ac.Close()
	ai = asynq.NewInspector(redisOpt)

	// --- Worker ---
	worker := asynq.NewServer(redisOpt, asynq.Config{
		Concurrency: 5,
		// Simple exponential backoff: 2s, 4s, 8s, ...
		RetryDelayFunc: func(n int, e error, t *asynq.Task) time.Duration {
			return time.Duration(1<<n) * time.Second
		},
	})
	mux := asynq.NewServeMux()
	mux.HandleFunc(TASK_EMAIL_WELCOME, func(ctx context.Context, t *asynq.Task) error {
		var p EmailPayload
		json.Unmarshal(t.Payload(), &p)
		log.Printf("TASK: Sending welcome email to user %v", p.UserID)
		time.Sleep(1 * time.Second)
		return nil
	})
	mux.HandleFunc(TASK_IMG_RESIZE, func(ctx context.Context, t *asynq.Task) error {
		var p ImgPayload
		json.Unmarshal(t.Payload(), &p)
		log.Printf("TASK: Resizing image for post %v", p.PostID)
		time.Sleep(4 * time.Second)
		// Chain next task
		payload, _ := json.Marshal(ImgPayload{PostID: p.PostID})
		ac.Enqueue(asynq.NewTask(TASK_IMG_WATERMARK, payload))
		return nil
	})
	mux.HandleFunc(TASK_IMG_WATERMARK, func(ctx context.Context, t *asynq.Task) error {
		var p ImgPayload
		json.Unmarshal(t.Payload(), &p)
		log.Printf("TASK: Watermarking image for post %v", p.PostID)
		time.Sleep(2 * time.Second)
		return nil
	})
	mux.HandleFunc(TASK_SYS_CLEANUP, func(ctx context.Context, t *asynq.Task) error {
		log.Println("TASK: Running periodic cleanup job.")
		time.Sleep(5 * time.Second)
		return nil
	})

	// --- Scheduler ---
	scheduler := asynq.NewScheduler(redisOpt, nil)
	// Run every 5 minutes for demo
	scheduler.Register("*/5 * * * *", asynq.NewTask(TASK_SYS_CLEANUP, nil))

	// --- Web Server ---
	e := echo.New()
	e.POST("/users", func(c echo.Context) error {
		var req struct{ Email string }
		c.Bind(&req)
		u := User{ID: uuid.New(), Email: req.Email}
		db.Lock()
		db.users[u.ID] = u
		db.Unlock()

		payload, _ := json.Marshal(EmailPayload{UserID: u.ID})
		task := asynq.NewTask(TASK_EMAIL_WELCOME, payload, asynq.MaxRetry(10))
		info, err := ac.Enqueue(task)
		if err != nil {
			return c.JSON(500, map[string]string{"err": err.Error()})
		}
		return c.JSON(201, map[string]interface{}{"user": u, "taskId": info.ID})
	})
	e.POST("/posts/:id/process", func(c echo.Context) error {
		id, _ := uuid.Parse(c.Param("id"))
		payload, _ := json.Marshal(ImgPayload{PostID: id})
		task := asynq.NewTask(TASK_IMG_RESIZE, payload, asynq.MaxRetry(3), asynq.Timeout(3*time.Minute))
		info, err := ac.Enqueue(task)
		if err != nil {
			return c.JSON(500, map[string]string{"err": err.Error()})
		}
		return c.JSON(202, map[string]string{"taskId": info.ID})
	})
	e.GET("/jobs/:id", func(c echo.Context) error {
		info, err := ai.GetTaskInfo("default", c.Param("id"))
		if err != nil {
			return c.JSON(404, map[string]string{"err": "not found"})
		}
		return c.JSON(200, info)
	})

	// --- Start & Shutdown ---
	go func() {
		if err := worker.Run(mux); err != nil {
			log.Fatalf("worker failed: %v", err)
		}
	}()
	go func() {
		if err := scheduler.Run(); err != nil {
			log.Fatalf("scheduler failed: %v", err)
		}
	}()
	go func() {
		if err := e.Start(":8080"); err != nil && err != http.ErrServerClosed {
			log.Fatalf("web server failed: %v", err)
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	log.Println("Shutting down...")

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	e.Shutdown(ctx)
	scheduler.Shutdown()
	worker.Shutdown()
	log.Println("Done.")
}