package main

import (
	"errors"
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
	ADMIN UserRole = "ADMIN"
	USER  UserRole = "USER"
)

type PostStatus string
const (
	DRAFT     PostStatus = "DRAFT"
	PUBLISHED PostStatus = "PUBLISHED"
)

type User struct {
	Id           string
	Email        string
	PasswordHash string
	Role         UserRole
	IsActive     bool
	CreatedAt    time.Time
}

type Post struct {
	Id      string
	UserId  string
	Title   string
	Content string
	Status  PostStatus
}

// --- Job & Queue Implementation (Interface-based/Decoupled) ---

type JobStatus string
const (
	JobStatusPending   JobStatus = "PENDING"
	JobStatusRunning   JobStatus = "RUNNING"
	JobStatusCompleted JobStatus = "COMPLETED"
	JobStatusFailed    JobStatus = "FAILED"
)

// Executable defines the contract for any job.
type Executable interface {
	Execute() error
	GetID() string
	GetType() string
	HandleFailure(e error) (shouldRetry bool, delay time.Duration)
	SetStatus(s JobStatus)
	GetStatus() JobStatus
}

// BaseJob provides common fields and default retry logic.
type BaseJob struct {
	ID         string
	Type       string
	Retries    int
	MaxRetries int
	Status     JobStatus
	sync.Mutex
}

func (b *BaseJob) GetID() string {
	return b.ID
}

func (b *BaseJob) GetType() string {
	return b.Type
}

func (b *BaseJob) SetStatus(s JobStatus) {
	b.Lock()
	defer b.Unlock()
	b.Status = s
}

func (b *BaseJob) GetStatus() JobStatus {
	b.Lock()
	defer b.Unlock()
	return b.Status
}

func (b *BaseJob) HandleFailure(e error) (bool, time.Duration) {
	b.Lock()
	defer b.Unlock()
	b.Retries++
	if b.Retries >= b.MaxRetries {
		return false, 0
	}
	backoff := time.Duration(math.Pow(2, float64(b.Retries))) * time.Second
	jitter := time.Duration(rand.Intn(1000)) * time.Millisecond
	return true, backoff + jitter
}

// --- Concrete Job Implementations ---

type EmailJob struct {
	BaseJob
	Recipient User
}

func (j *EmailJob) Execute() error {
	log.Printf("Executing EmailJob for %s", j.Recipient.Email)
	time.Sleep(1 * time.Second)
	if rand.Intn(10) < 4 { // 40% failure rate
		return errors.New("failed to connect to mail server")
	}
	log.Printf("Email sent to %s", j.Recipient.Email)
	return nil
}

type ImageProcessingJob struct {
	BaseJob
	SourcePost Post
}

func (j *ImageProcessingJob) Execute() error {
	log.Printf("Executing ImageProcessingJob for post '%s'", j.SourcePost.Title)
	log.Println(" -> Resizing image...")
	time.Sleep(1500 * time.Millisecond)
	log.Println(" -> Applying watermark...")
	time.Sleep(1500 * time.Millisecond)
	log.Println(" -> Uploading to cloud storage...")
	time.Sleep(1 * time.Second)
	log.Printf("Image processing complete for post '%s'", j.SourcePost.Title)
	return nil
}

type CleanupJob struct {
	BaseJob
}

func (j *CleanupJob) Execute() error {
	log.Println("Executing periodic CleanupJob")
	time.Sleep(2 * time.Second)
	log.Println("Cleanup complete. Inactive users pruned.")
	return nil
}

// --- Worker Pool ---

type JobProcessor struct {
	jobQueue    chan Executable
	jobStore    *sync.Map
	wg          sync.WaitGroup
}

func NewJobProcessor(queueSize int) *JobProcessor {
	return &JobProcessor{
		jobQueue: make(chan Executable, queueSize),
		jobStore: &sync.Map{},
	}
}

func (p *JobProcessor) Start(numWorkers int) {
	for i := 0; i < numWorkers; i++ {
		p.wg.Add(1)
		go p.worker(i + 1)
	}
}

func (p *JobProcessor) Stop() {
	close(p.jobQueue)
	p.wg.Wait()
}

func (p *JobProcessor) worker(id int) {
	defer p.wg.Done()
	log.Printf("Worker %d is active", id)
	for job := range p.jobQueue {
		log.Printf("Worker %d picked up job %s (%s)", id, job.GetID(), job.GetType())
		job.SetStatus(JobStatusRunning)
		p.jobStore.Store(job.GetID(), job)

		err := job.Execute()
		if err != nil {
			log.Printf("Job %s failed: %v", job.GetID(), err)
			shouldRetry, delay := job.HandleFailure(err)
			if shouldRetry {
				log.Printf("Job %s will be retried after %v", job.GetID(), delay)
				job.SetStatus(JobStatusPending)
				p.jobStore.Store(job.GetID(), job)
				time.AfterFunc(delay, func() {
					p.Submit(job)
				})
			} else {
				log.Printf("Job %s has reached max retries and will not be retried.", job.GetID())
				job.SetStatus(JobStatusFailed)
				p.jobStore.Store(job.GetID(), job)
			}
		} else {
			job.SetStatus(JobStatusCompleted)
			p.jobStore.Store(job.GetID(), job)
			log.Printf("Job %s completed successfully.", job.GetID())
		}
	}
	log.Printf("Worker %d is shutting down", id)
}

func (p *JobProcessor) Submit(job Executable) {
	job.SetStatus(JobStatusPending)
	p.jobStore.Store(job.GetID(), job)
	log.Printf("Submitting job %s (%s)", job.GetID(), job.GetType())
	p.jobQueue <- job
}

func (p *JobProcessor) GetJobStatus(id string) (JobStatus, bool) {
	val, ok := p.jobStore.Load(id)
	if !ok {
		return "", false
	}
	return val.(Executable).GetStatus(), true
}

// --- Scheduler ---
type Scheduler struct {
	processor *JobProcessor
	stopChan  chan struct{}
}

func NewScheduler(p *JobProcessor) *Scheduler {
	return &Scheduler{processor: p, stopChan: make(chan struct{})}
}

func (s *Scheduler) AddPeriodic(interval time.Duration, jobFactory func() Executable) {
	ticker := time.NewTicker(interval)
	go func() {
		for {
			select {
			case <-ticker.C:
				job := jobFactory()
				s.processor.Submit(job)
			case <-s.stopChan:
				ticker.Stop()
				return
			}
		}
	}()
}

func (s *Scheduler) Stop() {
	close(s.stopChan)
}

func main() {
	rand.Seed(time.Now().UnixNano())
	log.Println("Initializing Job Processor...")
	processor := NewJobProcessor(100)
	processor.Start(3)

	scheduler := NewScheduler(processor)
	scheduler.AddPeriodic(25*time.Second, func() Executable {
		return &CleanupJob{
			BaseJob: BaseJob{
				ID: fmt.Sprintf("cleanup_%d", time.Now().UnixNano()),
				Type: "Cleanup",
				MaxRetries: 2,
			},
		}
	})

	user1 := User{Id: "user-001", Email: "dev1@corp.com"}
	emailJob1 := &EmailJob{
		BaseJob: BaseJob{ID: "email-001", Type: "Email", MaxRetries: 3},
		Recipient: user1,
	}
	processor.Submit(emailJob1)

	post1 := Post{Id: "post-abc", Title: "Interface-based design"}
	imgJob1 := &ImageProcessingJob{
		BaseJob: BaseJob{ID: "img-001", Type: "ImageProcessing", MaxRetries: 2},
		SourcePost: post1,
	}
	processor.Submit(imgJob1)

	time.Sleep(30 * time.Second)

	status, ok := processor.GetJobStatus("email-001")
	if ok {
		log.Printf("Final status of job 'email-001': %s", status)
	}

	scheduler.Stop()
	processor.Stop()
	log.Println("System shutdown complete.")
}