use std::collections::HashMap;
use std::sync::{mpsc, Arc, Mutex, atomic::{AtomicU128, Ordering}};
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

// --- Mock UUID Generator ---
static UUID_COUNTER: AtomicU128 = AtomicU128::new(1);
fn new_uuid() -> u128 {
    UUID_COUNTER.fetch_add(1, Ordering::SeqCst)
}

// --- Domain Schema ---
#[derive(Debug, Clone)]
enum UserRole {
    ADMIN,
    USER,
}

#[derive(Debug, Clone)]
#[allow(dead_code)]
struct User {
    id: u128,
    email: String,
    password_hash: String,
    role: UserRole,
    is_active: bool,
    created_at: u64,
}

#[derive(Debug, Clone)]
enum PostStatus {
    DRAFT,
    PUBLISHED,
}

#[derive(Debug, Clone)]
#[allow(dead_code)]
struct Post {
    id: u128,
    user_id: u128,
    title: String,
    content: String,
    status: PostStatus,
}

// --- Background Job & Task Queue Implementation ---

type JobId = u128;

#[derive(Debug, Clone)]
enum JobStatus {
    Pending,
    InProgress,
    Completed,
    Failed { reason: String, retries_left: u32 },
}

#[derive(Debug, Clone)]
enum Job {
    SendEmail { user_email: String, subject: String, body: String },
    ProcessImage { image_data: Vec<u8> },
    CleanupInactiveUsers { older_than_days: u32 },
}

struct JobQueue {
    sender: mpsc::Sender<(JobId, Job)>,
    statuses: Arc<Mutex<HashMap<JobId, JobStatus>>>,
}

impl JobQueue {
    fn new(sender: mpsc::Sender<(JobId, Job)>, statuses: Arc<Mutex<HashMap<JobId, JobStatus>>>) -> Self {
        JobQueue { sender, statuses }
    }

    fn submit(&self, job: Job) -> Result<JobId, mpsc::SendError<(JobId, Job)>> {
        let job_id = new_uuid();
        self.statuses.lock().unwrap().insert(job_id, JobStatus::Pending);
        self.sender.send((job_id, job))?;
        Ok(job_id)
    }

    fn get_status(&self, job_id: JobId) -> Option<JobStatus> {
        self.statuses.lock().unwrap().get(&job_id).cloned()
    }
}

fn worker_function(
    id: usize,
    receiver: Arc<Mutex<mpsc::Receiver<(JobId, Job)>>>,
    statuses: Arc<Mutex<HashMap<JobId, JobStatus>>>,
) {
    println!("[Worker {}] Started", id);
    loop {
        let message = receiver.lock().unwrap().recv();
        match message {
            Ok((job_id, job)) => {
                println!("[Worker {}] Received job ID: {}", id, job_id);
                statuses.lock().unwrap().insert(job_id, JobStatus::InProgress);

                let max_retries = 3;
                let mut retries_left = max_retries;
                let mut result: Result<(), String> = Err("Job not attempted".to_string());
                
                while retries_left > 0 {
                    result = execute_job(job.clone());
                    if result.is_ok() {
                        break;
                    }
                    let attempts = max_retries - retries_left + 1;
                    let backoff_seconds = 2u64.pow(attempts);
                    println!("[Worker {}] Job ID {} failed. Retrying in {}s... ({} retries left)", id, job_id, backoff_seconds, retries_left - 1);
                    
                    statuses.lock().unwrap().insert(job_id, JobStatus::Failed {
                        reason: result.as_ref().err().unwrap().clone(),
                        retries_left: retries_left - 1,
                    });

                    thread::sleep(Duration::from_secs(backoff_seconds));
                    retries_left -= 1;
                }

                match result {
                    Ok(_) => {
                        statuses.lock().unwrap().insert(job_id, JobStatus::Completed);
                        println!("[Worker {}] Job ID {} completed successfully.", id, job_id);
                    }
                    Err(reason) => {
                        statuses.lock().unwrap().insert(job_id, JobStatus::Failed {
                            reason: format!("Final attempt failed: {}", reason),
                            retries_left: 0,
                        });
                        println!("[Worker {}] Job ID {} failed after all retries.", id, job_id);
                    }
                }
            }
            Err(_) => {
                println!("[Worker {}] Channel closed. Shutting down.", id);
                break;
            }
        }
    }
}

fn execute_job(job: Job) -> Result<(), String> {
    match job {
        Job::SendEmail { user_email, subject, .. } => {
            println!("  -> Sending email to {} with subject '{}'", user_email, subject);
            // Simulate a potentially failing I/O operation
            thread::sleep(Duration::from_millis(500));
            if user_email.contains("fail") {
                return Err("SMTP server connection failed".to_string());
            }
            Ok(())
        }
        Job::ProcessImage { image_data } => {
            println!("  -> Processing image of size {} bytes", image_data.len());
            // Simulate a CPU-intensive task
            thread::sleep(Duration::from_secs(2));
            println!("  -> Image processing pipeline: [Resize -> Apply Filter -> Watermark]");
            Ok(())
        }
        Job::CleanupInactiveUsers { older_than_days } => {
            println!("  -> Running periodic cleanup for users inactive for > {} days", older_than_days);
            thread::sleep(Duration::from_secs(1));
            Ok(())
        }
    }
}

fn scheduler_function(job_queue: Arc<JobQueue>) {
    println!("[Scheduler] Started");
    loop {
        thread::sleep(Duration::from_secs(10));
        println!("[Scheduler] Submitting periodic cleanup job...");
        match job_queue.submit(Job::CleanupInactiveUsers { older_than_days: 90 }) {
            Ok(job_id) => println!("[Scheduler] Submitted job ID {}", job_id),
            Err(e) => eprintln!("[Scheduler] Error submitting job: {}", e),
        }
    }
}

fn main() {
    let (sender, receiver) = mpsc::channel();
    let receiver = Arc::new(Mutex::new(receiver));
    let statuses = Arc::new(Mutex::new(HashMap::new()));

    let job_queue = Arc::new(JobQueue::new(sender, Arc::clone(&statuses)));

    // Start workers
    let mut worker_handles = Vec::new();
    for i in 0..2 {
        let receiver_clone = Arc::clone(&receiver);
        let statuses_clone = Arc::clone(&statuses);
        let handle = thread::spawn(move || {
            worker_function(i + 1, receiver_clone, statuses_clone);
        });
        worker_handles.push(handle);
    }

    // Start scheduler
    let job_queue_clone = Arc::clone(&job_queue);
    thread::spawn(move || {
        scheduler_function(job_queue_clone);
    });

    // --- Submit some initial jobs ---
    println!("[Main] Submitting initial jobs...");
    let email_job_id = job_queue.submit(Job::SendEmail {
        user_email: "test@example.com".to_string(),
        subject: "Welcome!".to_string(),
        body: "Thanks for signing up.".to_string(),
    }).unwrap();

    let failing_email_job_id = job_queue.submit(Job::SendEmail {
        user_email: "fail@example.com".to_string(),
        subject: "This will fail".to_string(),
        body: "This email will demonstrate retry logic.".to_string(),
    }).unwrap();

    let image_job_id = job_queue.submit(Job::ProcessImage {
        image_data: vec![0; 1024 * 1024 * 5], // 5MB image
    }).unwrap();

    println!("[Main] Submitted jobs: Email ({}), Failing Email ({}), Image ({})", email_job_id, failing_email_job_id, image_job_id);

    // Let the system run for a while
    println!("\n[Main] Running for 20 seconds to process jobs...\n");
    for i in 1..=20 {
        thread::sleep(Duration::from_secs(1));
        if i % 5 == 0 {
             println!("\n--- Job Statuses at {}s ---", i);
             let statuses_lock = statuses.lock().unwrap();
             for (id, status) in statuses_lock.iter() {
                 println!("  Job {}: {:?}", id, status);
             }
             println!("--------------------------\n");
        }
    }
    
    println!("[Main] Final job statuses:");
    let statuses_lock = statuses.lock().unwrap();
    for (id, status) in statuses_lock.iter() {
        println!("  Job {}: {:?}", id, status);
    }
}