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
	RoleAdmin UserRole = "ADMIN"
	RoleUser  UserRole = "USER"
)

type PostStatus string
const (
	StatusDraft     PostStatus = "DRAFT"
	StatusPublished PostStatus = "PUBLISHED"
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

// --- Job & Queue Implementation (OOP Style) ---

type TaskType string
const (
	EmailTask      TaskType = "EMAIL_SEND"
	ImageProcTask  TaskType = "IMAGE_PROCESS"
	PeriodicCleanupTask TaskType = "PERIODIC_CLEANUP"
)

type TaskStatus string
const (
	StatusTaskPending   TaskStatus = "PENDING"
	StatusTaskRunning   TaskStatus = "RUNNING"
	StatusTaskCompleted TaskStatus = "COMPLETED"
	StatusTaskFailed    TaskStatus = "FAILED"
)

type Task struct {
	ID         string
	Type       TaskType
	Payload    interface{}
	Attempt    int
	MaxRetries int
	Status     TaskStatus
}

type Worker struct {
	id          int
	dispatcher  *Dispatcher
	taskChannel chan Task
	quit        chan bool
}

func NewWorker(id int, dispatcher *Dispatcher) *Worker {
	return &Worker{
		id:          id,
		dispatcher:  dispatcher,
		taskChannel: make(chan Task),
		quit:        make(chan bool),
	}
}

func (w *Worker) Start() {
	go func() {
		log.Printf("Worker %d starting", w.id)
		for {
			w.dispatcher.workerPool <- w.taskChannel
			select {
			case task := <-w.taskChannel:
				w.processTask(task)
			case <-w.quit:
				log.Printf("Worker %d stopping", w.id)
				return
			}
		}
	}()
}

func (w *Worker) Stop() {
	go func() {
		w.quit <- true
	}()
}

func (w *Worker) processTask(task Task) {
	log.Printf("Worker %d is processing task %s", w.id, task.ID)
	w.dispatcher.UpdateTaskStatus(task.ID, StatusTaskRunning)

	var err error
	switch task.Type {
	case EmailTask:
		err = w.handleSendEmail(task.Payload.(User))
	case ImageProcTask:
		err = w.handleImageProcessing(task.Payload.(Post))
	case PeriodicCleanupTask:
		err = w.handleCleanup()
	default:
		err = fmt.Errorf("unrecognized task type: %s", task.Type)
	}

	if err != nil {
		log.Printf("Task %s failed on attempt %d: %v", task.ID, task.Attempt, err)
		task.Attempt++
		if task.Attempt <= task.MaxRetries {
			backoff := time.Duration(math.Pow(2, float64(task.Attempt))) * time.Second
			jitter := time.Duration(rand.Intn(500)) * time.Millisecond
			delay := backoff + jitter
			log.Printf("Retrying task %s in %v", task.ID, delay)
			time.AfterFunc(delay, func() {
				w.dispatcher.Submit(task)
			})
		} else {
			log.Printf("Task %s failed after %d retries.", task.ID, task.MaxRetries)
			w.dispatcher.UpdateTaskStatus(task.ID, StatusTaskFailed)
		}
	} else {
		log.Printf("Task %s completed successfully", task.ID)
		w.dispatcher.UpdateTaskStatus(task.ID, StatusTaskCompleted)
	}
}

type Dispatcher struct {
	maxWorkers   int
	taskQueue    chan Task
	workerPool   chan chan Task
	workers      []*Worker
	statusStore  *sync.Map
	wg           sync.WaitGroup
	quit         chan bool
}

func NewDispatcher(maxWorkers int, queueSize int) *Dispatcher {
	return &Dispatcher{
		maxWorkers:   maxWorkers,
		taskQueue:    make(chan Task, queueSize),
		workerPool:   make(chan chan Task, maxWorkers),
		statusStore:  &sync.Map{},
		quit:         make(chan bool),
	}
}

func (d *Dispatcher) Run() {
	d.workers = make([]*Worker, d.maxWorkers)
	for i := 0; i < d.maxWorkers; i++ {
		worker := NewWorker(i+1, d)
		d.workers[i] = worker
		worker.Start()
	}
	d.wg.Add(1)
	go d.dispatch()
}

func (d *Dispatcher) dispatch() {
	defer d.wg.Done()
	log.Println("Dispatcher starting")
	for {
		select {
		case task := <-d.taskQueue:
			go func(t Task) {
				taskChannel := <-d.workerPool
				taskChannel <- t
			}(task)
		case <-d.quit:
			log.Println("Dispatcher stopping")
			for _, w := range d.workers {
				w.Stop()
			}
			return
		}
	}
}

func (d *Dispatcher) Stop() {
	d.quit <- true
	d.wg.Wait()
	log.Println("Dispatcher stopped gracefully.")
}

func (d *Dispatcher) Submit(task Task) {
	if task.ID == "" {
		task.ID = fmt.Sprintf("task_%d", time.Now().UnixNano())
		task.MaxRetries = 5
		task.Attempt = 1
	}
	d.UpdateTaskStatus(task.ID, StatusTaskPending)
	log.Printf("Submitting task %s (%s)", task.ID, task.Type)
	d.taskQueue <- task
}

func (d *Dispatcher) UpdateTaskStatus(taskID string, status TaskStatus) {
	d.statusStore.Store(taskID, status)
}

func (d *Dispatcher) GetTaskStatus(taskID string) (TaskStatus, bool) {
	val, ok := d.statusStore.Load(taskID)
	if !ok {
		return "", false
	}
	return val.(TaskStatus), true
}

func (d *Dispatcher) StartScheduler(interval time.Duration, taskType TaskType, payload interface{}) {
	ticker := time.NewTicker(interval)
	go func() {
		for {
			select {
			case <-ticker.C:
				log.Printf("Scheduler triggering task of type %s", taskType)
				d.Submit(Task{Type: taskType, Payload: payload})
			case <-d.quit:
				ticker.Stop()
				return
			}
		}
	}()
}

// --- Mock Task Handlers (methods on Worker for context) ---
func (w *Worker) handleSendEmail(user User) error {
	log.Printf("Worker %d: Sending email to %s", w.id, user.Email)
	time.Sleep(1 * time.Second)
	if rand.Intn(10) > 5 {
		return fmt.Errorf("SMTP error")
	}
	return nil
}

func (w *Worker) handleImageProcessing(post Post) error {
	log.Printf("Worker %d: Processing image for post '%s'", w.id, post.Title)
	time.Sleep(3 * time.Second)
	return nil
}

func (w *Worker) handleCleanup() error {
	log.Printf("Worker %d: Running periodic cleanup", w.id)
	time.Sleep(2 * time.Second)
	return nil
}

func main() {
	rand.Seed(time.Now().UnixNano())
	dispatcher := NewDispatcher(4, 100)
	dispatcher.Run()
	dispatcher.StartScheduler(20*time.Second, PeriodicCleanupTask, nil)

	user1 := User{ID: "u-1", Email: "test1@example.com"}
	dispatcher.Submit(Task{Type: EmailTask, Payload: user1})

	post1 := Post{ID: "p-1", Title: "A Great Adventure"}
	dispatcher.Submit(Task{Type: ImageProcTask, Payload: post1})

	user2 := User{ID: "u-2", Email: "test2@example.com"}
	dispatcher.Submit(Task{Type: EmailTask, Payload: user2})

	log.Println("Tasks submitted. System running...")
	time.Sleep(30 * time.Second)

	dispatcher.Stop()
	log.Println("Application finished.")
}