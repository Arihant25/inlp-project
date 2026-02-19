/*
Variation 1: Functional & Service-Oriented Approach

This implementation uses a functional style with services. A central `JobProcessor`
is simulated by a long-running task that pulls jobs from a `tokio::mpsc` channel.
State, such as job statuses, is managed in a shared, thread-safe `HashMap`
and accessed via `web::Data`. Retry logic is implemented manually within the task handlers.

Dependencies (add to Cargo.toml):
actix-web = "4"
tokio = { version = "1", features = ["full"] }
serde = { version = "1.0", features = ["derive"] }
uuid = { version = "1", features = ["v4", "serde"] }
chrono = { version = "0.4", features = ["serde"] }
futures = "0.3"
*/

use actix_web::{web, App, HttpServer, Responder, HttpResponse};
use serde::{Serialize, Deserialize};
use uuid::Uuid;
use chrono::{DateTime, Utc};
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use tokio::sync::mpsc;
use tokio::time::{sleep, Duration};
use std::time::Instant;

// --- Domain Models ---

#[derive(Serialize, Clone, Debug)]
enum UserRole {
    ADMIN,
    USER,
}

#[derive(Serialize, Clone, Debug)]
struct User {
    id: Uuid,
    email: String,
    // password_hash would not be serialized in a real app
    role: UserRole,
    is_active: bool,
    created_at: DateTime<Utc>,
}

#[derive(Serialize, Clone, Debug)]
enum PostStatus {
    DRAFT,
    PUBLISHED,
}

#[derive(Serialize, Clone, Debug)]
struct Post {
    id: Uuid,
    user_id: Uuid,
    title: String,
    content: String,
    status: PostStatus,
}

// --- Job & Task Definitions ---

#[derive(Serialize, Clone, Debug, PartialEq, Eq, Hash)]
pub enum JobStatus {
    Pending,
    InProgress,
    Completed,
    Failed(String),
    Retrying { attempt: u32, next_run: Instant },
}

#[derive(Clone, Debug)]
pub enum Job {
    SendWelcomeEmail(User),
    ProcessPostImage(Post),
}

type JobId = Uuid;
type JobStore = Arc<Mutex<HashMap<JobId, JobStatus>>>;
type JobSender = mpsc::Sender<(JobId, Job)>;

// --- Task Execution Logic (Services) ---

async fn send_email_task(user: User) -> Result<(), String> {
    println!("TASK [SendWelcomeEmail]: Starting for user {}", user.email);
    // Simulate a fallible I/O operation
    sleep(Duration::from_secs(2)).await;
    if user.email.contains("fail") {
        println!("TASK [SendWelcomeEmail]: Failed for user {}", user.email);
        Err("SMTP server connection timed out".to_string())
    } else {
        println!("TASK [SendWelcomeEmail]: Successfully sent to {}", user.email);
        Ok(())
    }
}

async fn process_image_task(post: Post) -> Result<(), String> {
    println!("TASK [ProcessPostImage]: Starting for post '{}'", post.title);
    // Simulate a multi-step, fallible CPU-bound operation
    sleep(Duration::from_secs(1)).await;
    println!(" > Step 1/3: Resizing image...");
    sleep(Duration::from_secs(1)).await;
    println!(" > Step 2/3: Applying watermark...");
    sleep(Duration::from_secs(1)).await;
    println!(" > Step 3/3: Uploading to CDN...");
    sleep(Duration::from_secs(1)).await;
    println!("TASK [ProcessPostImage]: Successfully processed for post '{}'", post.title);
    Ok(())
}

// --- Background Worker ---

async fn job_processor(mut receiver: mpsc::Receiver<(JobId, Job)>, job_store: JobStore) {
    while let Some((job_id, job)) = receiver.recv().await {
        println!("WORKER: Picked up job {}", job_id);
        let store_clone = job_store.clone();
        
        tokio::spawn(async move {
            let max_retries = 3;
            for attempt in 1..=max_retries {
                {
                    let mut store = store_clone.lock().unwrap();
                    if attempt > 1 {
                        let backoff_secs = 2u64.pow(attempt - 1);
                        let next_run = Instant::now() + Duration::from_secs(backoff_secs);
                        store.insert(job_id, JobStatus::Retrying { attempt, next_run });
                        println!("WORKER: Job {} failed. Retrying in {}s (attempt {}/{})", job_id, backoff_secs, attempt, max_retries);
                        sleep(Duration::from_secs(backoff_secs)).await;
                    }
                    store.insert(job_id, JobStatus::InProgress);
                }

                let result = match &job {
                    Job::SendWelcomeEmail(user) => send_email_task(user.clone()).await,
                    Job::ProcessPostImage(post) => process_image_task(post.clone()).await,
                };

                if result.is_ok() {
                    let mut store = store_clone.lock().unwrap();
                    store.insert(job_id, JobStatus::Completed);
                    println!("WORKER: Job {} completed successfully.", job_id);
                    return; // Exit retry loop on success
                } else if attempt == max_retries {
                    let mut store = store_clone.lock().unwrap();
                    let error_msg = result.err().unwrap_or_else(|| "Unknown error".to_string());
                    store.insert(job_id, JobStatus::Failed(error_msg));
                    println!("WORKER: Job {} failed after all retries.", job_id);
                }
            }
        });
    }
}

async fn periodic_task_scheduler(job_store: JobStore) {
    loop {
        sleep(Duration::from_secs(60)).await;
        println!("PERIODIC TASK: Running cleanup...");
        let mut store = job_store.lock().unwrap();
        let initial_count = store.len();
        store.retain(|_id, status| !matches!(status, JobStatus::Completed | JobStatus::Failed(_)));
        let final_count = store.len();
        println!("PERIODIC TASK: Cleaned up {} completed/failed jobs. {} jobs remain.", initial_count - final_count, final_count);
    }
}

// --- API Handlers ---

async fn schedule_email(
    user_data: web::Json<User>,
    job_sender: web::Data<JobSender>,
    job_store: web::Data<JobStore>,
) -> impl Responder {
    let user = user_data.into_inner();
    let job_id = Uuid::new_v4();
    let job = Job::SendWelcomeEmail(user);

    {
        let mut store = job_store.lock().unwrap();
        store.insert(job_id, JobStatus::Pending);
    }

    if job_sender.send((job_id, job)).await.is_err() {
        return HttpResponse::InternalServerError().body("Failed to schedule job");
    }

    HttpResponse::Accepted().json(serde_json::json!({ "job_id": job_id }))
}

async fn schedule_image_processing(
    post_data: web::Json<Post>,
    job_sender: web::Data<JobSender>,
    job_store: web::Data<JobStore>,
) -> impl Responder {
    let post = post_data.into_inner();
    let job_id = Uuid::new_v4();
    let job = Job::ProcessPostImage(post);

    {
        let mut store = job_store.lock().unwrap();
        store.insert(job_id, JobStatus::Pending);
    }

    if job_sender.send((job_id, job)).await.is_err() {
        return HttpResponse::InternalServerError().body("Failed to schedule job");
    }

    HttpResponse::Accepted().json(serde_json::json!({ "job_id": job_id }))
}

async fn get_job_status(
    job_id: web::Path<Uuid>,
    job_store: web::Data<JobStore>,
) -> impl Responder {
    let store = job_store.lock().unwrap();
    match store.get(&job_id) {
        Some(status) => HttpResponse::Ok().json(status),
        None => HttpResponse::NotFound().body("Job not found"),
    }
}

#[actix_web::main]
async fn main() -> std::io::Result<()> {
    // State initialization
    let job_store: JobStore = Arc::new(Mutex::new(HashMap::new()));
    let (tx, rx) = mpsc::channel(100);

    // Start background workers
    let store_clone_proc = job_store.clone();
    tokio::spawn(job_processor(rx, store_clone_proc));
    
    let store_clone_periodic = job_store.clone();
    tokio::spawn(periodic_task_scheduler(store_clone_periodic));

    println!("Starting server at http://127.0.0.1:8080");

    HttpServer::new(move || {
        App::new()
            .app_data(web::Data::new(tx.clone()))
            .app_data(web::Data::new(job_store.clone()))
            .route("/users", web::post().to(schedule_email))
            .route("/posts", web::post().to(schedule_image_processing))
            .route("/jobs/{job_id}", web::get().to(get_job_status))
    })
    .bind("127.0.0.1:8080")?
    .run()
    .await
}