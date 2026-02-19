/*
// The "Pragmatic Minimalist" Developer
// This variation puts everything in `main.rs` for simplicity and speed of
// development. It uses a simple in-memory MPSC channel for the queue, avoiding
// a database dependency for the queue itself. Error handling is direct, and
// abstractions are kept to a minimum.

// Cargo.toml dependencies:
[dependencies]
axum = "0.7"
tokio = { version = "1", features = ["full"] }
serde = { version = "1", features = ["derive"] }
serde_json = "1"
uuid = { version = "1", features = ["v4", "serde"] }
chrono = { version = "0.4", features = ["serde"] }
tracing = "0.1"
tracing-subscriber = { version = "0.3", features = ["env-filter"] }
tokio-cron-scheduler = "0.10"
dashmap = "5.5"
rand = "0.8"
*/

use axum::{
    extract::{Path, State},
    http::StatusCode,
    response::{IntoResponse, Json},
    routing::{get, post},
    Router,
};
use chrono::{DateTime, Utc};
use dashmap::DashMap;
use rand::Rng;
use serde::{Deserialize, Serialize};
use std::sync::{atomic::{AtomicU32, Ordering}, Arc};
use std::time::Duration;
use tokio::sync::mpsc::{self, Sender};
use tokio::time::sleep;
use tokio_cron_scheduler::{Job, JobScheduler};
use tracing::{error, info};
use uuid::Uuid;

// --- Domain ---
#[derive(Serialize, Clone)]
enum UserRole { ADMIN, USER }
#[derive(Serialize, Clone)]
struct User { id: Uuid, email: String }

// --- Task & Job Definitions ---
#[derive(Debug, Clone)]
enum Task {
    SendEmail(String),
    ProcessImage(Uuid, String),
}

#[derive(Debug, Serialize, Clone)]
enum JobStatus {
    Pending,
    Running,
    Completed,
    Failed(String),
}

#[derive(Debug, Serialize, Clone)]
struct JobRecord {
    id: Uuid,
    status: JobStatus,
    created_at: DateTime<Utc>,
    updated_at: DateTime<Utc>,
}

// --- App State ---
// Using an in-memory MPSC channel for the queue and a DashMap for status tracking.
struct AppState {
    task_tx: Sender<Task>,
    job_statuses: Arc<DashMap<Uuid, JobRecord>>,
}

// --- Worker Logic ---
fn spawn_worker(
    mut task_rx: mpsc::Receiver<Task>,
    job_statuses: Arc<DashMap<Uuid, JobRecord>>,
) {
    tokio::spawn(async move {
        info!("Worker started.");
        while let Some(task) = task_rx.recv().await {
            let job_id = Uuid::new_v4();
            let now = Utc::now();
            let job_record = JobRecord {
                id: job_id,
                status: JobStatus::Running,
                created_at: now,
                updated_at: now,
            };
            job_statuses.insert(job_id, job_record);

            info!("Processing job {}: {:?}", job_id, task);
            let result = execute_task_with_retry(task.clone()).await;

            job_statuses.entry(job_id).and_modify(|rec| {
                rec.status = match result {
                    Ok(_) => JobStatus::Completed,
                    Err(e) => JobStatus::Failed(e),
                };
                rec.updated_at = Utc::now();
            });
            info!("Finished job {}", job_id);
        }
    });
}

async fn execute_task_with_retry(task: Task) -> Result<(), String> {
    let max_retries = 4;
    for attempt in 0..max_retries {
        match &task {
            Task::SendEmail(email) => {
                info!("[Attempt {}] Sending email to {}", attempt + 1, email);
                sleep(Duration::from_secs(1)).await;
                if rand::thread_rng().gen_bool(0.5) { // 50% failure rate
                    if attempt == max_retries - 1 {
                        return Err("SMTP server unreachable after all retries".to_string());
                    }
                    let backoff = Duration::from_secs(2u64.pow(attempt));
                    error!("Email send failed. Retrying in {:?}...", backoff);
                    sleep(backoff).await;
                } else {
                    info!("Email sent successfully.");
                    return Ok(());
                }
            }
            Task::ProcessImage(post_id, url) => {
                info!("[Attempt {}] Processing image {} for post {}", attempt + 1, url, post_id);
                sleep(Duration::from_secs(3)).await; // Simulate long process
                info!("Image processed successfully.");
                return Ok(()); // This task doesn't retry in this simple model
            }
        }
    }
    Err("Task failed after all retries".to_string())
}

// --- Periodic Tasks ---
fn start_periodic_tasks(job_statuses: Arc<DashMap<Uuid, JobRecord>>) {
    tokio::spawn(async move {
        let sched = JobScheduler::new().await.unwrap();
        // Clean up old completed/failed jobs from the in-memory map every minute
        sched.add(Job::new_async("0 * * * * *", move |_, _| {
            let statuses = job_statuses.clone();
            Box::pin(async move {
                info!("Running periodic cleanup task.");
                let cutoff = Utc::now() - chrono::Duration::minutes(5);
                let mut to_remove = Vec::new();
                for entry in statuses.iter() {
                    if entry.value().updated_at < cutoff {
                        to_remove.push(*entry.key());
                    }
                }
                let count = to_remove.len();
                for id in to_remove {
                    statuses.remove(&id);
                }
                info!("Cleaned up {} old job records.", count);
            })
        }).unwrap()).await.unwrap();
        sched.start().await.unwrap();
    });
}

// --- API Handlers ---
#[derive(Deserialize)]
struct CreateUserReq {
    email: String,
}

async fn hndl_create_user(
    State(state): State<Arc<AppState>>,
    Json(payload): Json<CreateUserReq>,
) -> impl IntoResponse {
    let user = User { id: Uuid::new_v4(), email: payload.email };
    info!("Creating user: {}", user.id);

    // Enqueue tasks
    state.task_tx.send(Task::SendEmail(user.email.clone())).await.unwrap();
    state.task_tx.send(Task::ProcessImage(Uuid::new_v4(), "http://.../img.png".to_string())).await.unwrap();

    (StatusCode::ACCEPTED, Json(serde_json::json!({
        "message": "User creation initiated. Background tasks enqueued.",
        "user_id": user.id
    })))
}

async fn hndl_get_job_status(
    State(state): State<Arc<AppState>>,
    Path(job_id): Path<Uuid>,
) -> impl IntoResponse {
    match state.job_statuses.get(&job_id) {
        Some(job) => (StatusCode::OK, Json(job.value().clone())).into_response(),
        None => (StatusCode::NOT_FOUND, "Job not found").into_response(),
    }
}

// --- Main ---
#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter("info")
        .init();

    // Setup in-memory queue and status tracker
    let (task_tx, task_rx) = mpsc::channel::<Task>(100);
    let job_statuses = Arc::new(DashMap::<Uuid, JobRecord>::new());

    // Spawn worker and periodic tasks
    spawn_worker(task_rx, job_statuses.clone());
    start_periodic_tasks(job_statuses.clone());

    let app_state = Arc::new(AppState {
        task_tx,
        job_statuses,
    });

    let app = Router::new()
        .route("/users", post(hndl_create_user))
        .route("/jobs/:id", get(hndl_get_job_status))
        .with_state(app_state);

    let listener = tokio::net::TcpListener::bind("0.0.0.0:3000").await.unwrap();
    info!("Server up on {}", listener.local_addr().unwrap());
    axum::serve(listener, app).await.unwrap();
}