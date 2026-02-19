package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
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

// --- DOMAIN ---

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

// --- INTERFACES (ABSTRACTIONS) ---

type ITaskDispatcher interface {
	DispatchWelcomeEmail(ctx context.Context, userID uuid.UUID) error
	DispatchImageProcessing(ctx context.Context, postID uuid.UUID) (string, error)
}

type IJobTracker interface {
	GetJobStatus(ctx context.Context, jobID string) (string, error)
}

// --- INFRASTRUCTURE (CONCRETE IMPLEMENTATIONS) ---

// AsynqTaskDispatcher implements ITaskDispatcher
type AsynqTaskDispatcher struct {
	client *asynq.Client
}

func NewAsynqTaskDispatcher(opt asynq.RedisClientOpt) *AsynqTaskDispatcher {
	return &AsynqTaskDispatcher{client: asynq.NewClient(opt)}
}

func (d *AsynqTaskDispatcher) DispatchWelcomeEmail(ctx context.Context, userID uuid.UUID) error {
	payload, _ := json.Marshal(map[string]interface{}{"user_id": userID})
	task := asynq.NewTask("email:welcome", payload)
	_, err := d.client.EnqueueContext(ctx, task, asynq.Queue("notifications"))
	return err
}

func (d *AsynqTaskDispatcher) DispatchImageProcessing(ctx context.Context, postID uuid.UUID) (string, error) {
	payload, _ := json.Marshal(map[string]interface{}{"post_id": postID})
	task := asynq.NewTask("image:process", payload)
	info, err := d.client.EnqueueContext(ctx, task, asynq.MaxRetry(4), asynq.Queue("processing"))
	if err != nil {
		return "", err
	}
	return info.ID, nil
}

// AsynqJobTracker implements IJobTracker
type AsynqJobTracker struct {
	inspector *asynq.Inspector
}

func NewAsynqJobTracker(opt asynq.RedisClientOpt) *AsynqJobTracker {
	return &AsynqJobTracker{inspector: asynq.NewInspector(opt)}
}

func (t *AsynqJobTracker) GetJobStatus(ctx context.Context, jobID string) (string, error) {
	// Check multiple queues if necessary
	queues := []string{"processing", "notifications", "default"}
	for _, q := range queues {
		info, err := t.inspector.GetTaskInfo(q, jobID)
		if err == nil {
			return info.State.String(), nil
		}
	}
	return "", errors.New("job not found")
}

// --- MOCK DATABASE ---
type InMemoryUserStore struct {
	mu    sync.RWMutex
	users map[uuid.UUID]User
}

func NewInMemoryUserStore() *InMemoryUserStore {
	return &InMemoryUserStore{users: make(map[uuid.UUID]User)}
}
func (s *InMemoryUserStore) Save(user User) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.users[user.ID] = user
}
func (s *InMemoryUserStore) Find(id uuid.UUID) (User, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	user, ok := s.users[id]
	return user, ok
}

// --- CONTROLLERS (GIN HANDLERS) ---

type UserController struct {
	db        *InMemoryUserStore
	dispatcher ITaskDispatcher
}

func NewUserController(db *InMemoryUserStore, dispatcher ITaskDispatcher) *UserController {
	return &UserController{db: db, dispatcher: dispatcher}
}

func (ctrl *UserController) Register(c *gin.Context) {
	var req struct {
		Email string `json:"email"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid request"})
		return
	}
	newUser := User{ID: uuid.New(), Email: req.Email, CreatedAt: time.Now()}
	ctrl.db.Save(newUser)

	if err := ctrl.dispatcher.DispatchWelcomeEmail(c.Request.Context(), newUser.ID); err != nil {
		log.Printf("WARN: Failed to dispatch welcome email for user %s: %v", newUser.ID, err)
		// Continue since user creation was successful
	}
	c.JSON(http.StatusCreated, newUser)
}

type PostController struct {
	dispatcher ITaskDispatcher
}

func NewPostController(dispatcher ITaskDispatcher) *PostController {
	return &PostController{dispatcher: dispatcher}
}

func (ctrl *PostController) ProcessImage(c *gin.Context) {
	postID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid post id"})
		return
	}
	jobID, err := ctrl.dispatcher.DispatchImageProcessing(c.Request.Context(), postID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "could not schedule job"})
		return
	}
	c.JSON(http.StatusAccepted, gin.H{"job_id": jobID, "status": "queued"})
}

type JobController struct {
	tracker IJobTracker
}

func NewJobController(tracker IJobTracker) *JobController {
	return &JobController{tracker: tracker}
}

func (ctrl *JobController) GetStatus(c *gin.Context) {
	jobID := c.Param("id")
	status, err := ctrl.tracker.GetJobStatus(c.Request.Context(), jobID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"job_id": jobID, "status": status})
}

// --- WORKER IMPLEMENTATION ---

type TaskProcessor struct {
	userDB *InMemoryUserStore
}

func NewTaskProcessor(userDB *InMemoryUserStore) *TaskProcessor {
	return &TaskProcessor{userDB: userDB}
}

func (p *TaskProcessor) ProcessWelcomeEmail(ctx context.Context, t *asynq.Task) error {
	var payload map[string]uuid.UUID
	json.Unmarshal(t.Payload(), &payload)
	user, ok := p.userDB.Find(payload["user_id"])
	if !ok {
		return fmt.Errorf("user not found: %s", payload["user_id"])
	}
	log.Printf("Processing welcome email for %s", user.Email)
	time.Sleep(1 * time.Second)
	return nil
}

func (p *TaskProcessor) ProcessImage(ctx context.Context, t *asynq.Task) error {
	log.Printf("Processing image task ID %s", asynq.GetTaskInfo(ctx).ID)
	time.Sleep(3 * time.Second)
	// Simulate exponential backoff test
	if asynq.GetTaskInfo(ctx).Retried < 3 {
		return errors.New("simulated network error during image fetch")
	}
	log.Printf("Image task ID %s completed successfully", asynq.GetTaskInfo(ctx).ID)
	return nil
}

func (p *TaskProcessor) ProcessPeriodicCleanup(ctx context.Context, t *asynq.Task) error {
	log.Println("Running periodic cleanup task...")
	time.Sleep(5 * time.Second)
	log.Println("Periodic cleanup finished.")
	return nil
}

// --- APPLICATION SERVER ---

type Application struct {
	Router *gin.Engine
	// other dependencies
}

func NewApplication(userCtrl *UserController, postCtrl *PostController, jobCtrl *JobController) *Application {
	r := gin.Default()
	r.POST("/users", userCtrl.Register)
	r.POST("/posts/:id/image", postCtrl.ProcessImage)
	r.GET("/jobs/:id", jobCtrl.GetStatus)
	return &Application{Router: r}
}

func (app *Application) Start(addr string) error {
	return app.Router.Run(addr)
}

func main() {
	// NOTE: Requires a running Redis server on localhost:6379
	redisOpt := asynq.RedisClientOpt{Addr: "localhost:6379"}

	// --- DEPENDENCY INJECTION ---
	userDB := NewInMemoryUserStore()
	dispatcher := NewAsynqTaskDispatcher(redisOpt)
	tracker := NewAsynqJobTracker(redisOpt)
	
	userController := NewUserController(userDB, dispatcher)
	postController := NewPostController(dispatcher)
	jobController := NewJobController(tracker)

	// --- WORKER AND SCHEDULER ---
	go func() {
		processor := NewTaskProcessor(userDB)
		srv := asynq.NewServer(redisOpt, asynq.Config{
			Queues: map[string]int{"notifications": 2, "processing": 1},
		})
		mux := asynq.NewServeMux()
		mux.HandleFunc("email:welcome", processor.ProcessWelcomeEmail)
		mux.HandleFunc("image:process", processor.ProcessImage)
		mux.HandleFunc("system:cleanup", processor.ProcessPeriodicCleanup)
		if err := srv.Run(mux); err != nil {
			log.Fatalf("Asynq server error: %v", err)
		}
	}()

	go func() {
		scheduler := asynq.NewScheduler(redisOpt, nil)
		task := asynq.NewTask("system:cleanup", nil)
		if _, err := scheduler.Register("@hourly", task); err != nil {
			log.Fatalf("Scheduler registration error: %v", err)
		}
		if err := scheduler.Run(); err != nil {
			log.Fatalf("Scheduler run error: %v", err)
		}
	}()

	// --- HTTP SERVER ---
	app := NewApplication(userController, postController, jobController)
	log.Println("Starting server on http://localhost:8000")
	if err := app.Start(":8000"); err != nil {
		log.Fatalf("Server start error: %v", err)
	}
}