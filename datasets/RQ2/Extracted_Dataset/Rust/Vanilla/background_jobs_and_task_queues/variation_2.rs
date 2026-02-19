use std::collections::HashMap;
use std::sync::{mpsc, Arc, Mutex, atomic::{AtomicU128, Ordering}};
use std::thread::{self, JoinHandle};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

// --- Mock UUID Generator ---
static TASK_ID_COUNTER: AtomicU128 = AtomicU128::new(1);
fn generate_task_id() -> u128 {
    TASK_ID_COUNTER.fetch_add(1, Ordering::SeqCst)
}

// --- Domain Schema ---
#[derive(Debug)]
enum UserRole { ADMIN, USER }
#[allow(dead_code)]
struct User { id: u128, email: String, password_hash: String, role: UserRole, is_active: bool, created_at: u64 }
#[derive(Debug)]
enum PostStatus { DRAFT, PUBLISHED }
#[allow(dead_code)]
struct Post { id: u128, user_id: u128, title: String, content: String, status: PostStatus }

// --- OOP Background Job & Task Queue Implementation ---

type TaskId = u128;

#[derive(Debug, Clone)]
pub enum TaskState {
    Queued,
    Running,
    Success,
    Failed { error_msg: String },
}

// The core trait for any executable task
trait Task: Send + Sync {
    fn get_id(&self) -> TaskId;
    fn execute(&mut self) -> Result<(), String>;
    fn box_clone(&self) -> Box<dyn Task>;
}

impl Clone for Box<dyn Task> {
    fn clone(&self) -> Self {
        self.box_clone()
    }
}

// --- Concrete Task Implementations ---

#[derive(Clone)]
struct AsyncEmailSenderTask {
    task_id: TaskId,
    recipient: String,
    subject: String,
}
impl AsyncEmailSenderTask {
    fn new(recipient: String, subject: String) -> Self {
        Self { task_id: generate_task_id(), recipient, subject }
    }
}
impl Task for AsyncEmailSenderTask {
    fn get_id(&self) -> TaskId { self.task_id }
    fn execute(&mut self) -> Result<(), String> {
        println!("  Executing EmailTask {}: Sending to {}", self.task_id, self.recipient);
        thread::sleep(Duration::from_millis(700)); // Simulate network latency
        if self.recipient.starts_with("invalid") {
            Err("Invalid email address format".to_string())
        } else {
            println!("  EmailTask {} completed.", self.task_id);
            Ok(())
        }
    }
    fn box_clone(&self) -> Box<dyn Task> { Box::new(self.clone()) }
}

#[derive(Clone)]
struct ImageProcessingTask {
    task_id: TaskId,
    image_bytes: usize,
}
impl ImageProcessingTask {
    fn new(image_bytes: usize) -> Self {
        Self { task_id: generate_task_id(), image_bytes }
    }
}
impl Task for ImageProcessingTask {
    fn get_id(&self) -> TaskId { self.task_id }
    fn execute(&mut self) -> Result<(), String> {
        println!("  Executing ImageTask {}: Processing {} bytes", self.task_id, self.image_bytes);
        thread::sleep(Duration::from_secs(2)); // Simulate heavy computation
        println!("  ImageTask {} completed.", self.task_id);
        Ok(())
    }
    fn box_clone(&self) -> Box<dyn Task> { Box::new(self.clone()) }
}

#[derive(Clone)]
struct PeriodicCleanupTask {
    task_id: TaskId,
}
impl PeriodicCleanupTask {
    fn new() -> Self {
        Self { task_id: generate_task_id() }
    }
}
impl Task for PeriodicCleanupTask {
    fn get_id(&self) -> TaskId { self.task_id }
    fn execute(&mut self) -> Result<(), String> {
        println!("  Executing PeriodicCleanupTask {}", self.task_id);
        thread::sleep(Duration::from_secs(1));
        println!("  PeriodicCleanupTask {} completed.", self.task_id);
        Ok(())
    }
    fn box_clone(&self) -> Box<dyn Task> { Box::new(self.clone()) }
}

// --- Task Manager ---

struct TaskManager {
    task_sender: mpsc::Sender<Box<dyn Task>>,
    task_statuses: Arc<Mutex<HashMap<TaskId, TaskState>>>,
    worker_handles: Vec<JoinHandle<()>>,
}

impl TaskManager {
    pub fn new() -> Self {
        let (sender, receiver) = mpsc::channel::<Box<dyn Task>>();
        let shared_receiver = Arc::new(Mutex::new(receiver));
        let shared_statuses = Arc::new(Mutex::new(HashMap::new()));
        
        let mut manager = TaskManager {
            task_sender: sender,
            task_statuses: shared_statuses,
            worker_handles: Vec::new(),
        };
        
        manager.start_workers(2, shared_receiver);
        manager.start_scheduler();
        manager
    }

    fn start_workers(&mut self, count: usize, receiver: Arc<Mutex<mpsc::Receiver<Box<dyn Task>>>>) {
        for i in 0..count {
            let worker_receiver = Arc::clone(&receiver);
            let worker_statuses = Arc::clone(&self.task_statuses);
            let handle = thread::spawn(move || {
                println!("[Worker-{}] Ready for tasks.", i);
                while let Ok(mut task) = worker_receiver.lock().unwrap().recv() {
                    let task_id = task.get_id();
                    println!("[Worker-{}] Processing task {}", i, task_id);
                    worker_statuses.lock().unwrap().insert(task_id, TaskState::Running);

                    let mut attempts = 0;
                    const MAX_ATTEMPTS: u32 = 3;
                    loop {
                        attempts += 1;
                        match task.execute() {
                            Ok(_) => {
                                worker_statuses.lock().unwrap().insert(task_id, TaskState::Success);
                                break;
                            }
                            Err(e) => {
                                if attempts >= MAX_ATTEMPTS {
                                    worker_statuses.lock().unwrap().insert(task_id, TaskState::Failed { error_msg: e });
                                    eprintln!("[Worker-{}] Task {} failed permanently.", i, task_id);
                                    break;
                                } else {
                                    let backoff = Duration::from_secs(2u64.pow(attempts));
                                    eprintln!("[Worker-{}] Task {} failed. Retrying in {:?}...", i, task_id, backoff);
                                    thread::sleep(backoff);
                                }
                            }
                        }
                    }
                }
            });
            self.worker_handles.push(handle);
        }
    }
    
    fn start_scheduler(&self) {
        let sender_clone = self.task_sender.clone();
        let statuses_clone = self.task_statuses.clone();
        thread::spawn(move || {
            println!("[Scheduler] Started.");
            loop {
                thread::sleep(Duration::from_secs(12));
                let task = PeriodicCleanupTask::new();
                let task_id = task.get_id();
                println!("[Scheduler] Enqueuing periodic task {}", task_id);
                statuses_clone.lock().unwrap().insert(task_id, TaskState::Queued);
                sender_clone.send(Box::new(task)).unwrap();
            }
        });
    }

    pub fn submit_task(&self, task: Box<dyn Task>) -> TaskId {
        let task_id = task.get_id();
        self.task_statuses.lock().unwrap().insert(task_id, TaskState::Queued);
        self.task_sender.send(task).expect("Failed to send task to worker");
        task_id
    }

    pub fn get_task_status(&self, task_id: TaskId) -> Option<TaskState> {
        self.task_statuses.lock().unwrap().get(&task_id).cloned()
    }
}

fn main() {
    let manager = TaskManager::new();

    println!("[Main] Submitting tasks to the TaskManager.");
    
    let task1 = Box::new(AsyncEmailSenderTask::new("admin@example.com".to_string(), "System Report".to_string()));
    let id1 = manager.submit_task(task1);
    
    let task2 = Box::new(ImageProcessingTask::new(1024 * 1024 * 3)); // 3MB
    let id2 = manager.submit_task(task2);

    let task3 = Box::new(AsyncEmailSenderTask::new("invalid-user@example.com".to_string(), "Will Fail".to_string()));
    let id3 = manager.submit_task(task3);

    println!("[Main] Submitted tasks: {}, {}, {}", id1, id2, id3);

    println!("\n[Main] Monitoring task progress for 15 seconds...\n");
    for _ in 0..5 {
        thread::sleep(Duration::from_secs(3));
        println!("--- Current Statuses ---");
        println!("Task {}: {:?}", id1, manager.get_task_status(id1).unwrap());
        println!("Task {}: {:?}", id2, manager.get_task_status(id2).unwrap());
        println!("Task {}: {:?}", id3, manager.get_task_status(id3).unwrap());
        println!("----------------------");
    }
    
    println!("\n[Main] System shutdown initiated (demo ends).");
}