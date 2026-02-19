package main

import (
	"fmt"
	"log"
	"math"
	"math/rand"
	"sync"
	"time"
)

// --- Domain Schema ---

type UserRole string
const (
	AdminRole UserRole = "ADMIN"
	UserRole  UserRole = "USER"
)

type PostStatus string
const (
	DraftStatus     PostStatus = "DRAFT"
	PublishedStatus PostStatus = "PUBLISHED"
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

// --- Job & Queue Implementation (Functional Style) ---

type JobType string
const (
	TypeSendEmail      JobType = "SEND_WELCOME_EMAIL"
	TypeProcessImage   JobType = "PROCESS_POST_IMAGE"
	TypeCleanupDrafts  JobType = "CLEANUP_OLD_DRAFTS"
)

type JobStatus string
const (
	StatusPending   JobStatus = "PENDING"
	StatusRunning   JobStatus = "RUNNING"
	StatusCompleted JobStatus = "COMPLETED"
	StatusFailed    JobStatus = "FAILED"
)

type Job struct {
	ID         string
	Type       JobType
	Payload    interface{}
	Retries    int
	MaxRetries int
	Status     JobStatus
	CreatedAt  time.Time
}

var (
	JobQueue       chan Job
	JobStatusStore sync.Map
	workerWaitGroup sync.WaitGroup
)

const (
	MaxWorkers    = 5
	QueueCapacity = 100
	BaseBackoff   = 1 * time.Second
	MaxBackoff    = 1 * time.Minute
)

func init() {
	rand.Seed(time.Now().UnixNano())
	JobQueue = make(chan Job, QueueCapacity)
}

func newJob(jobType JobType, payload interface{}) Job {
	return Job{
		ID:         fmt.Sprintf("job_%d", time.Now().UnixNano()),
		Type:       jobType,
		Payload:    payload,
		MaxRetries: 5,
		Status:     StatusPending,
		CreatedAt:  time.Now(),
	}
}

func enqueueJob(j Job) {
	JobStatusStore.Store(j.ID, j.Status)
	log.Printf("ENQUEUE: Job %s (%s) added to the queue.", j.ID, j.Type)
	JobQueue <- j
}

func updateJobStatus(jobID string, status JobStatus) {
	JobStatusStore.Store(jobID, status)
	log.Printf("STATUS: Job %s status updated to %s.", jobID, status)
}

func getJobStatus(jobID string) (JobStatus, bool) {
	status, ok := JobStatusStore.Load(jobID)
	if !ok {
		return "", false
	}
	return status.(JobStatus), true
}

func handleJob(job Job) {
	updateJobStatus(job.ID, StatusRunning)
	var err error
	switch job.Type {
	case TypeSendEmail:
		err = sendWelcomeEmail(job.Payload.(User))
	case TypeProcessImage:
		err = processPostImage(job.Payload.(Post))
	case TypeCleanupDrafts:
		err = cleanupOldDrafts()
	default:
		err = fmt.Errorf("unknown job type: %s", job.Type)
	}

	if err != nil {
		log.Printf("ERROR: Job %s (%s) failed: %v", job.ID, job.Type, err)
		job.Retries++
		if job.Retries < job.MaxRetries {
			backoffDuration := time.Duration(math.Pow(2, float64(job.Retries))) * BaseBackoff
			if backoffDuration > MaxBackoff {
				backoffDuration = MaxBackoff
			}
			jitter := time.Duration(rand.Intn(1000)) * time.Millisecond
			totalDelay := backoffDuration + jitter

			log.Printf("RETRY: Job %s will be retried in %v (attempt %d/%d).", job.ID, totalDelay, job.Retries, job.MaxRetries)
			time.Sleep(totalDelay)
			enqueueJob(job)
		} else {
			updateJobStatus(job.ID, StatusFailed)
			log.Printf("FAILED: Job %s has reached max retries.", job.ID)
		}
	} else {
		updateJobStatus(job.ID, StatusCompleted)
		log.Printf("COMPLETED: Job %s (%s) finished successfully.", job.ID, job.Type)
	}
}

func worker(id int) {
	defer workerWaitGroup.Done()
	log.Printf("Worker %d started.", id)
	for job := range JobQueue {
		log.Printf("Worker %d picked up job %s.", id, job.ID)
		handleJob(job)
	}
	log.Printf("Worker %d stopped.", id)
}

func startWorkerPool(numWorkers int) {
	for i := 1; i <= numWorkers; i++ {
		workerWaitGroup.Add(1)
		go worker(i)
	}
}

func schedulePeriodicTasks() {
	log.Println("Scheduler started.")
	ticker := time.NewTicker(30 * time.Second) // Schedule cleanup every 30 seconds
	go func() {
		for range ticker.C {
			log.Println("SCHEDULER: Triggering periodic cleanup task.")
			job := newJob(TypeCleanupDrafts, nil)
			enqueueJob(job)
		}
	}()
}

// --- Mock Task Implementations ---

func sendWelcomeEmail(user User) error {
	log.Printf("--> Sending welcome email to %s...", user.Email)
	time.Sleep(2 * time.Second)
	if rand.Intn(10) < 3 { // 30% chance of failure
		return fmt.Errorf("SMTP server connection failed")
	}
	log.Printf("--> Email sent successfully to %s.", user.Email)
	return nil
}

func processPostImage(post Post) error {
	log.Printf("--> Starting image processing pipeline for post '%s'...", post.Title)
	// Step 1: Resize
	time.Sleep(1 * time.Second)
	log.Printf("    - Step 1/3: Resized image for post %s.", post.ID)
	// Step 2: Apply filter
	time.Sleep(1 * time.Second)
	log.Printf("    - Step 2/3: Applied filter to image for post %s.", post.ID)
	// Step 3: Upload to storage
	time.Sleep(2 * time.Second)
	if rand.Intn(10) < 2 { // 20% chance of failure
		return fmt.Errorf("failed to upload image to storage")
	}
	log.Printf("    - Step 3/3: Uploaded image for post %s.", post.ID)
	log.Printf("--> Image processing pipeline for post '%s' complete.", post.Title)
	return nil
}

func cleanupOldDrafts() error {
	log.Println("--> Running cleanup job for old draft posts...")
	time.Sleep(3 * time.Second)
	log.Println("--> Cleanup job finished. 2 old drafts deleted.")
	return nil
}

func main() {
	log.Println("Starting background job processing system...")
	startWorkerPool(MaxWorkers)
	schedulePeriodicTasks()

	// --- Enqueue Sample Jobs ---
	user1 := User{ID: "user-123", Email: "alice@example.com"}
	enqueueJob(newJob(TypeSendEmail, user1))

	post1 := Post{ID: "post-456", UserID: "user-123", Title: "My First Post"}
	enqueueJob(newJob(TypeProcessImage, post1))

	user2 := User{ID: "user-789", Email: "bob@example.com"}
	enqueueJob(newJob(TypeSendEmail, user2)) // This one is more likely to fail

	// Wait for a while to let jobs process
	time.Sleep(40 * time.Second)

	// Check status of a job
	status, ok := getJobStatus("job_1") // This ID is not real, just for demo
	if ok {
		log.Printf("Final status check for a job: %s", status)
	}

	close(JobQueue)
	workerWaitGroup.Wait()
	log.Println("Job processing system shut down.")
}