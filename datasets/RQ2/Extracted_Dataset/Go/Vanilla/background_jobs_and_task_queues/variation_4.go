package main

import (
	"context"
	"fmt"
	"log"
	"math"
	"math/rand"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"
)

// --- Domain Schema ---
type UserRole string
const (
	ROLE_ADMIN UserRole = "ADMIN"
	ROLE_USER  UserRole = "USER"
)

type PostStatus string
const (
	STATUS_DRAFT     PostStatus = "DRAFT"
	STATUS_PUBLISHED PostStatus = "PUBLISHED"
)

type User struct {
	ID           string
	Email        string
	PasswordHash string
	Role         UserRole
	IsActive     bool
	CreatedAt    time.Time
}

type Post struct {
	ID      string
	UserID  string
	Title   string
	Content string
	Status  PostStatus
}

// --- Job & Queue Implementation (Context-aware & Graceful Shutdown) ---

type JobType string
type JobStatus string

const (
	TypeEmailJob      JobType = "email"
	TypeImageProcJob  JobType = "image_processing"
	TypeCleanupJob    JobType = "cleanup"
)

const (
	StatusJobPending   JobStatus = "pending"
	StatusJobRunning   JobStatus = "running"
	StatusJobCompleted JobStatus = "completed"
	StatusJobFailed    JobStatus = "failed"
)

type Job struct {
	ID         string
	Type       JobType
	Payload    interface{}
	Status     JobStatus
	Retries    int
	MaxRetries int
}

type TaskEngine struct {
	jobChan    chan Job
	statusMap  *sync.Map
	wg         *sync.WaitGroup
	maxWorkers int
}

func NewTaskEngine(maxWorkers, queueCapacity int) *TaskEngine {
	return &TaskEngine{
		jobChan:    make(chan Job, queueCapacity),
		statusMap:  &sync.Map{},
		wg:         &sync.WaitGroup{},
		maxWorkers: maxWorkers,
	}
}

func (e *TaskEngine) Start(ctx context.Context) {
	log.Printf("TaskEngine starting with %d workers...", e.maxWorkers)
	for i := 0; i < e.maxWorkers; i++ {
		e.wg.Add(1)
		go e.worker(ctx, i+1)
	}

	e.wg.Add(1)
	go e.runPeriodicScheduler(ctx)
}

func (e *TaskEngine) Stop() {
	log.Println("TaskEngine stopping... waiting for workers to finish.")
	close(e.jobChan)
	e.wg.Wait()
	log.Println("TaskEngine stopped.")
}

func (e *TaskEngine) Dispatch(job Job) error {
	select {
	case e.jobChan <- job:
		e.updateStatus(job.ID, StatusJobPending)
		log.Printf("Dispatched job %s", job.ID)
		return nil
	default:
		return fmt.Errorf("job queue is full, cannot dispatch job %s", job.ID)
	}
}

func (e *TaskEngine) updateStatus(jobID string, status JobStatus) {
	e.statusMap.Store(jobID, status)
}

func (e *TaskEngine) GetStatus(jobID string) (JobStatus, bool) {
	status, ok := e.statusMap.Load(jobID)
	if !ok {
		return "", false
	}
	return status.(JobStatus), true
}

func (e *TaskEngine) worker(ctx context.Context, id int) {
	defer e.wg.Done()
	log.Printf("Worker %d started", id)
	for {
		select {
		case job, ok := <-e.jobChan:
			if !ok {
				log.Printf("Worker %d shutting down (channel closed)", id)
				return
			}
			e.processJob(ctx, job)
		case <-ctx.Done():
			log.Printf("Worker %d shutting down (context cancelled)", id)
			return
		}
	}
}

func (e *TaskEngine) processJob(ctx context.Context, job Job) {
	e.updateStatus(job.ID, StatusJobRunning)
	log.Printf("Processing job %s (%s)", job.ID, job.Type)

	var err error
	switch job.Type {
	case TypeEmailJob:
		err = handleSendEmail(ctx, job.Payload.(User))
	case TypeImageProcJob:
		err = handleImageProcessing(ctx, job.Payload.(Post))
	case TypeCleanupJob:
		err = handleCleanup(ctx)
	default:
		err = fmt.Errorf("unknown job type: %s", job.Type)
	}

	if err != nil {
		log.Printf("Job %s failed: %v", job.ID, err)
		if job.Retries < job.MaxRetries {
			job.Retries++
			backoff := time.Duration(math.Pow(2, float64(job.Retries)))*time.Second + time.Duration(rand.Intn(1000))*time.Millisecond
			log.Printf("Re-queueing job %s for retry in %v", job.ID, backoff)
			
			select {
			case <-time.After(backoff):
				e.Dispatch(job)
			case <-ctx.Done():
				log.Printf("Context cancelled, not re-queueing job %s", job.ID)
				e.updateStatus(job.ID, StatusJobFailed)
			}
		} else {
			log.Printf("Job %s reached max retries.", job.ID)
			e.updateStatus(job.ID, StatusJobFailed)
		}
	} else {
		log.Printf("Job %s completed.", job.ID)
		e.updateStatus(job.ID, StatusJobCompleted)
	}
}

func (e *TaskEngine) runPeriodicScheduler(ctx context.Context) {
	defer e.wg.Done()
	log.Println("Periodic scheduler started.")
	ticker := time.NewTicker(20 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			job := Job{
				ID:         fmt.Sprintf("cleanup-%d", time.Now().Unix()),
				Type:       TypeCleanupJob,
				MaxRetries: 2,
			}
			if err := e.Dispatch(job); err != nil {
				log.Printf("Scheduler error: %v", err)
			}
		case <-ctx.Done():
			log.Println("Periodic scheduler shutting down.")
			return
		}
	}
}

// --- Mock Task Handlers ---
func handleSendEmail(ctx context.Context, user User) error {
	log.Printf("-> Sending email to %s", user.Email)
	select {
	case <-time.After(2 * time.Second):
		if rand.Intn(10) < 3 {
			return fmt.Errorf("SMTP timeout")
		}
		log.Printf("-> Email sent to %s", user.Email)
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func handleImageProcessing(ctx context.Context, post Post) error {
	log.Printf("-> Processing image for post: %s", post.Title)
	steps := []string{"resize", "filter", "upload"}
	for i, step := range steps {
		log.Printf("   - Step %d: %s", i+1, step)
		select {
		case <-time.After(1500 * time.Millisecond):
			// continue
		case <-ctx.Done():
			return fmt.Errorf("image processing cancelled at step '%s': %w", step, ctx.Err())
		}
	}
	log.Printf("-> Image processing complete for post: %s", post.Title)
	return nil
}

func handleCleanup(ctx context.Context) error {
	log.Println("-> Running cleanup task")
	select {
	case <-time.After(3 * time.Second):
		log.Println("-> Cleanup task finished")
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func main() {
	rand.Seed(time.Now().UnixNano())
	log.Println("Application starting...")

	ctx, cancel := context.WithCancel(context.Background())
	
	// Set up signal handling for graceful shutdown
	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)

	engine := NewTaskEngine(5, 100)
	engine.Start(ctx)

	// Dispatch initial jobs
	user1 := User{ID: "u-1", Email: "grace@example.com"}
	engine.Dispatch(Job{ID: "email-1", Type: TypeEmailJob, Payload: user1, MaxRetries: 3})

	post1 := Post{ID: "p-1", Title: "Context is Key"}
	engine.Dispatch(Job{ID: "img-1", Type: TypeImageProcJob, Payload: post1, MaxRetries: 2})

	// Wait for shutdown signal
	go func() {
		sig := <-sigChan
		log.Printf("Received signal: %s. Initiating graceful shutdown...", sig)
		cancel()
	}()

	// Block main until context is cancelled
	<-ctx.Done()

	engine.Stop()
	log.Println("Application shut down gracefully.")
}