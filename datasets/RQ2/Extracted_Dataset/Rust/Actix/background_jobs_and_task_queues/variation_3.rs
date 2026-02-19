/*
Variation 3: Trait-Based & Generic Worker Pool

This approach defines a generic `BackgroundTask` trait that all jobs must implement.
A pool of generic `Worker`s pulls jobs from a shared, in-memory queue (`deadqueue`)
and executes them. This is highly extensible, as adding new job types only requires
implementing the trait. Job status is managed through a `JobStore` trait, allowing for
pluggable storage backends. `tokio-cron-scheduler` is used for robust periodic task scheduling.

Dependencies (add to Cargo.toml):
actix-web = "4"
tokio = { version = "1", features = ["full"] }
serde = { version = "1.0", features = ["derive"] }
uuid = { version = "1", features = ["v4", "serde"] }
chrono = { version = "0.4", features = ["serde"] }
async-trait = "0.1"
deadqueue = "0.2"
tokio-cron-scheduler = "0.9"
dashmap = "5.4"
*/

use actix_web::{web, App, HttpServer, Responder, HttpResponse};
use serde::{Serialize, Deserialize};
use uuid::Uuid;
use chrono::{DateTime, Utc};
use std::sync::Arc;
use tokio::time::{sleep, Duration};
use async_trait::async_trait;
use deadqueue::Queue;
use tokio_cron_scheduler::{Job, JobScheduler};
use dashmap::DashMap;

// --- Domain Models ---

#[derive(Serialize, Deserialize, Clone, Debug)]
enum UserRole { ADMIN, USER }

#[derive(Serialize, Deserialize, Clone, Debug)]
struct User {
    id: Uuid,
    email: String,
    role: UserRole,
    is_active: bool,
    created_at: DateTime<Utc>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
enum PostStatus { DRAFT, PUBLISHED }

#[derive(Serialize, Deserialize, Clone, Debug)]
struct Post {
    id: Uuid,
    user_id: Uuid,
    title: String,
    content: String,
    status: PostStatus,
}

// --- Generic Job & Worker Infrastructure ---

#[derive(Serialize, Clone, Debug)]
pub enum JobStatus {
    Pending,
    Running,
    Completed,
    Failed { error: String },
}

#[derive(Clone, Copy, Debug)]
pub enum RetryPolicy {
    None,
    Exponential { max_retries: u32, base_delay_ms: u64 },
}

#[async_trait]
pub trait BackgroundTask: Send + Sync + 'static {
    async fn execute(&self) -> Result<(), String>;
    fn task_name(&self) -> String;
    fn retry_policy(&self) -> RetryPolicy {
        RetryPolicy::None
    }
}

pub struct JobEnvelope {
    id: Uuid,
    task: Box<dyn BackgroundTask>,
}

// --- Job Store Abstraction ---

// Using DashMap for a thread-safe in-memory store
type InMemoryJobStore = Arc<DashMap<Uuid, JobStatus>>;

// --- Specific Job Implementations ---

pub struct WelcomeEmailJob { user: User }
#[async_trait]
impl BackgroundTask for WelcomeEmailJob {
    fn task_name(&self) -> String { format!("WelcomeEmail for {}", self.user.email) }
    fn retry_policy(&self) -> RetryPolicy {
        RetryPolicy::Exponential { max_retries: 3, base_delay_ms: 1000 }
    }
    async fn execute(&self) -> Result<(), String> {
        println!("EXECUTING: {}", self.task_name());
        sleep(Duration::from_secs(2)).await;
        if self.user.email.contains("fail") {
            Err("Simulated SMTP failure".to_string())
        } else {
            println!("COMPLETED: {}", self.task_name());
            Ok(())
        }
    }
}

pub struct ImageResizeJob { post: Post }
#[async_trait]
impl BackgroundTask for ImageResizeJob {
    fn task_name(&self) -> String { format!("ImageResize for post '{}'", self.post.title) }
    async fn execute(&self) -> Result<(), String> {
        println!("EXECUTING: {}", self.task_name());
        sleep(Duration::from_secs(4)).await;
        println!("COMPLETED: {}", self.task_name());
        Ok(())
    }
}

pub struct CleanupJob { store: InMemoryJobStore }
#[async_trait]
impl BackgroundTask for CleanupJob {
    fn task_name(&self) -> String { "CleanupJob".to_string() }
    async fn execute(&self) -> Result<(), String> {
        println!("PERIODIC TASK: Running cleanup...");
        let initial_count = self.store.len();
        self.store.retain(|_, status| !matches!(status, JobStatus::Completed | JobStatus::Failed {..}));
        let final_count = self.store.len();
        println!("PERIODIC TASK: Cleaned up {} jobs. {} remain.", initial_count - final_count, final_count);
        Ok(())
    }
}

// --- Worker Implementation ---

struct Worker {
    id: usize,
    queue: Arc<Queue<JobEnvelope>>,
    store: InMemoryJobStore,
}

impl Worker {
    async fn run(self) {
        println!("Worker {} started.", self.id);
        loop {
            let job = self.queue.pop().await;
            self.store.insert(job.id, JobStatus::Running);
            println!("Worker {} picked up job {}: {}", self.id, job.id, job.task.task_name());

            let mut attempts = 0;
            let retry_policy = job.task.retry_policy();
            
            loop {
                attempts += 1;
                match job.task.execute().await {
                    Ok(_) => {
                        self.store.insert(job.id, JobStatus::Completed);
                        break;
                    }
                    Err(e) => {
                        if let RetryPolicy::Exponential { max_retries, base_delay_ms } = retry_policy {
                            if attempts < max_retries {
                                let delay = Duration::from_millis(base_delay_ms * 2u64.pow(attempts - 1));
                                println!("Worker {} failed job {}. Retrying in {:?}...", self.id, job.id, delay);
                                sleep(delay).await;
                            } else {
                                self.store.insert(job.id, JobStatus::Failed { error: e });
                                break;
                            }
                        } else {
                            self.store.insert(job.id, JobStatus::Failed { error: e });
                            break;
                        }
                    }
                }
            }
        }
    }
}

// --- API Handlers ---

type JobQueue = Arc<Queue<JobEnvelope>>;

async fn schedule_task(
    task: Box<dyn BackgroundTask>,
    queue: web::Data<JobQueue>,
    store: web::Data<InMemoryJobStore>,
) -> impl Responder {
    let job_id = Uuid::new_v4();
    let envelope = JobEnvelope { id: job_id, task };
    
    store.insert(job_id, JobStatus::Pending);
    queue.push(envelope);

    HttpResponse::Accepted().json(serde_json::json!({ "job_id": job_id }))
}

async fn schedule_email_handler(
    user: web::Json<User>,
    queue: web::Data<JobQueue>,
    store: web::Data<InMemoryJobStore>,
) -> impl Responder {
    let job = Box::new(WelcomeEmailJob { user: user.into_inner() });
    schedule_task(job, queue, store).await
}

async fn schedule_image_handler(
    post: web::Json<Post>,
    queue: web::Data<JobQueue>,
    store: web::Data<InMemoryJobStore>,
) -> impl Responder {
    let job = Box::new(ImageResizeJob { post: post.into_inner() });
    schedule_task(job, queue, store).await
}

async fn get_job_status_handler(
    job_id: web::Path<Uuid>,
    store: web::Data<InMemoryJobStore>,
) -> impl Responder {
    match store.get(&job_id) {
        Some(status) => HttpResponse::Ok().json(status.value()),
        None => HttpResponse::NotFound().body("Job not found"),
    }
}

#[actix_web::main]
async fn main() -> std::io::Result<()> {
    let job_queue: JobQueue = Arc::new(Queue::new(100));
    let job_store: InMemoryJobStore = Arc::new(DashMap::new());

    // Start worker pool
    let num_workers = 4;
    for i in 0..num_workers {
        let worker = Worker {
            id: i + 1,
            queue: job_queue.clone(),
            store: job_store.clone(),
        };
        tokio::spawn(worker.run());
    }

    // Start periodic task scheduler
    let sched = JobScheduler::new().await.unwrap();
    let queue_clone = job_queue.clone();
    let store_clone = job_store.clone();
    let cron_job = Job::new_async("0 * * * * *", move |_uuid, _l| {
        let q = queue_clone.clone();
        let s = store_clone.clone();
        Box::pin(async move {
            let job_id = Uuid::new_v4();
            let task = Box::new(CleanupJob { store: s.clone() });
            s.insert(job_id, JobStatus::Pending);
            q.push(JobEnvelope { id: job_id, task });
        })
    }).unwrap();
    sched.add(cron_job).await.unwrap();
    sched.start().await.unwrap();

    println!("Starting server at http://127.0.0.1:8080");

    HttpServer::new(move || {
        App::new()
            .app_data(web::Data::from(job_queue.clone()))
            .app_data(web::Data::from(job_store.clone()))
            .route("/users", web::post().to(schedule_email_handler))
            .route("/posts", web::post().to(schedule_image_handler))
            .route("/jobs/{job_id}", web::get().to(get_job_status_handler))
    })
    .bind("127.0.0.1:8080")?
    .run()
    .await
}