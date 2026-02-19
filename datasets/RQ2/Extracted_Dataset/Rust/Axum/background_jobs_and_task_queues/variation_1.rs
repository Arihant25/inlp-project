/*
// The "Service-Oriented" Developer
// This variation emphasizes clear separation of concerns with distinct modules
// for handlers, services, tasks, and workers. It uses a structured,
// type-safe approach with custom error types and a shared application state.

// Cargo.toml dependencies:
[dependencies]
axum = "0.7"
tokio = { version = "1", features = ["full"] }
serde = { version = "1", features = ["derive"] }
serde_json = "1"
uuid = { version = "1", features = ["v4", "serde"] }
chrono = { version = "0.4", features = ["serde"] }
thiserror = "1"
tracing = "0.1"
tracing-subscriber = { version = "0.3", features = ["env-filter"] }
sqlx = { version = "0.7", features = ["runtime-tokio", "sqlite", "uuid", "chrono", "json"] }
tokio-cron-scheduler = "0.10"
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
use serde::{Deserialize, Serialize};
use sqlx::{sqlite::SqlitePoolOptions, FromRow, SqlitePool};
use std::sync::Arc;
use std::time::Duration;
use tokio::time::sleep;
use tokio_cron_scheduler::{Job, JobScheduler};
use tracing::info;
use uuid::Uuid;

// --- Domain Models ---
#[derive(Debug, Serialize, Clone)]
pub enum UserRole {
    ADMIN,
    USER,
}

#[derive(Debug, Serialize, Clone)]
pub struct User {
    id: Uuid,
    email: String,
    role: UserRole,
    is_active: bool,
    created_at: DateTime<Utc>,
}

// --- Error Handling ---
#[derive(thiserror::Error, Debug)]
pub enum AppError {
    #[error("Database error: {0}")]
    Sqlx(#[from] sqlx::Error),
    #[error("Job not found: {0}")]
    JobNotFound(Uuid),
    #[error("Internal server error")]
    Internal,
}

impl IntoResponse for AppError {
    fn into_response(self) -> axum::response::Response {
        let (status, error_message) = match self {
            AppError::Sqlx(e) => {
                tracing::error!("SQLx error: {:?}", e);
                (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "Database operation failed".to_string(),
                )
            }
            AppError::JobNotFound(id) => (
                StatusCode::NOT_FOUND,
                format!("Job with ID {} not found", id),
            ),
            AppError::Internal => (
                StatusCode::INTERNAL_SERVER_ERROR,
                "An internal error occurred".to_string(),
            ),
        };
        (status, Json(serde_json::json!({ "error": error_message }))).into_response()
    }
}

// --- Task Definitions ---
mod tasks {
    use super::*;
    use rand::Rng;

    #[derive(Serialize, Deserialize, Debug, Clone)]
    #[serde(tag = "type")]
    pub enum TaskPayload {
        SendWelcomeEmail { user_id: Uuid, email: String },
        ProcessImage { post_id: Uuid, image_url: String },
    }

    pub async fn execute_task(payload: TaskPayload, db_pool: SqlitePool) -> Result<(), String> {
        match payload {
            TaskPayload::SendWelcomeEmail { user_id, email } => {
                info!(?user_id, "Starting to send welcome email to {}", email);
                // Simulate a fallible network operation
                sleep(Duration::from_secs(2)).await;
                if rand::thread_rng().gen_bool(0.2) { // 20% chance of failure
                    let err_msg = "Failed to connect to SMTP server".to_string();
                    tracing::error!("{}", err_msg);
                    return Err(err_msg);
                }
                info!("Successfully sent welcome email to {}", email);
                Ok(())
            }
            TaskPayload::ProcessImage { post_id, image_url } => {
                info!(?post_id, "Starting image processing for {}", image_url);
                // Step 1: Download
                sleep(Duration::from_secs(1)).await;
                info!(?post_id, "Downloaded image from {}", image_url);
                // Step 2: Resize
                sleep(Duration::from_secs(2)).await;
                info!(?post_id, "Resized image");
                // Step 3: Watermark
                sleep(Duration::from_secs(1)).await;
                info!(?post_id, "Watermarked image");
                // Step 4: Upload to storage
                sleep(Duration::from_secs(1)).await;
                info!(?post_id, "Uploaded processed image to storage");
                // Here you would update the post status in the DB
                let _ = sqlx::query("UPDATE posts SET status = 'PUBLISHED' WHERE id = ?")
                    .bind(post_id)
                    .execute(&db_pool)
                    .await;
                Ok(())
            }
        }
    }
}

// --- Job Queue Service ---
mod job_queue_service {
    use super::*;

    #[derive(Debug, Clone, Serialize, FromRow)]
    pub struct JobRecord {
        pub id: Uuid,
        #[sqlx(json)]
        pub payload: tasks::TaskPayload,
        pub status: String,
        pub attempts: i32,
        pub run_at: DateTime<Utc>,
        pub created_at: DateTime<Utc>,
        pub error_message: Option<String>,
    }

    #[derive(Clone)]
    pub struct JobQueueService {
        db_pool: SqlitePool,
    }

    impl JobQueueService {
        pub fn new(db_pool: SqlitePool) -> Self {
            Self { db_pool }
        }

        pub async fn schedule_task(&self, payload: tasks::TaskPayload) -> Result<Uuid, AppError> {
            let job_id = Uuid::new_v4();
            sqlx::query(
                "INSERT INTO jobs (id, payload, status, attempts, run_at) VALUES (?, ?, 'pending', 0, ?)",
            )
            .bind(job_id)
            .bind(serde_json::to_value(&payload).unwrap())
            .bind(Utc::now())
            .execute(&self.db_pool)
            .await?;
            Ok(job_id)
        }

        pub async fn get_job_status(&self, job_id: Uuid) -> Result<JobRecord, AppError> {
            sqlx::query_as::<_, JobRecord>("SELECT * FROM jobs WHERE id = ?")
                .bind(job_id)
                .fetch_optional(&self.db_pool)
                .await?
                .ok_or(AppError::JobNotFound(job_id))
        }
    }
}

// --- Background Worker ---
mod worker {
    use super::*;
    use job_queue_service::JobRecord;

    const MAX_RETRIES: i32 = 5;

    pub fn spawn_worker(db_pool: SqlitePool) {
        tokio::spawn(async move {
            info!("Background worker started.");
            loop {
                match fetch_and_process_job(&db_pool).await {
                    Ok(Some(job_id)) => info!("Successfully processed job {}", job_id),
                    Ok(None) => sleep(Duration::from_secs(5)).await, // No jobs, wait a bit
                    Err(e) => tracing::error!("Error in worker loop: {:?}", e),
                }
            }
        });
    }

    async fn fetch_and_process_job(db_pool: &SqlitePool) -> Result<Option<Uuid>, sqlx::Error> {
        let mut tx = db_pool.begin().await?;

        let maybe_job: Option<JobRecord> = sqlx::query_as(
            "SELECT * FROM jobs WHERE status = 'pending' AND run_at <= ? ORDER BY created_at LIMIT 1",
        )
        .bind(Utc::now())
        .fetch_optional(&mut *tx)
        .await?;

        let job = match maybe_job {
            Some(job) => job,
            None => {
                tx.commit().await?;
                return Ok(None);
            }
        };

        sqlx::query("UPDATE jobs SET status = 'running' WHERE id = ?")
            .bind(job.id)
            .execute(&mut *tx)
            .await?;
        
        tx.commit().await?;

        let task_result = tasks::execute_task(job.payload.clone(), db_pool.clone()).await;

        match task_result {
            Ok(_) => {
                sqlx::query("UPDATE jobs SET status = 'completed' WHERE id = ?")
                    .bind(job.id)
                    .execute(db_pool)
                    .await?;
            }
            Err(e) => {
                let new_attempts = job.attempts + 1;
                if new_attempts >= MAX_RETRIES {
                    sqlx::query("UPDATE jobs SET status = 'failed', error_message = ? WHERE id = ?")
                        .bind(e)
                        .bind(job.id)
                        .execute(db_pool)
                        .await?;
                } else {
                    let backoff_seconds = 2i64.pow(new_attempts as u32);
                    let next_run_at = Utc::now() + chrono::Duration::seconds(backoff_seconds);
                    sqlx::query(
                        "UPDATE jobs SET status = 'pending', attempts = ?, run_at = ?, error_message = ? WHERE id = ?",
                    )
                    .bind(new_attempts)
                    .bind(next_run_at)
                    .bind(e)
                    .bind(job.id)
                    .execute(db_pool)
                    .await?;
                }
            }
        }

        Ok(Some(job.id))
    }
}

// --- Periodic Task Scheduler ---
mod scheduler {
    use super::*;
    
    pub async fn setup_scheduler(db_pool: SqlitePool) -> JobScheduler {
        let sched = JobScheduler::new().await.expect("Failed to create scheduler");

        // Example: A periodic task to clean up old, failed jobs every hour
        let cleanup_job = Job::new_async("0 0 * * * *", move |uuid, mut l| {
            let pool = db_pool.clone();
            Box::pin(async move {
                info!("Running periodic job (ID: {}): Cleaning up old failed jobs.", uuid);
                let cutoff_date = Utc::now() - chrono::Duration::days(30);
                match sqlx::query("DELETE FROM jobs WHERE status = 'failed' AND created_at < ?")
                    .bind(cutoff_date)
                    .execute(&pool)
                    .await
                {
                    Ok(result) => info!("Cleaned up {} old failed jobs.", result.rows_affected()),
                    Err(e) => tracing::error!("Periodic cleanup job failed: {}", e),
                }
                let next_tick = l.next_tick_for_job(uuid).await;
                match next_tick {
                    Ok(Some(ts)) => info!("Next cleanup run at: {:?}", ts),
                    _ => info!("Could not get next cleanup run time."),
                }
            })
        }).expect("Failed to create cleanup job");

        sched.add(cleanup_job).await.expect("Failed to add job to scheduler");
        sched.start().await.expect("Failed to start scheduler");
        info!("Periodic job scheduler started.");
        sched
    }
}

// --- API Handlers ---
mod handlers {
    use super::*;
    use job_queue_service::JobQueueService;

    #[derive(Deserialize)]
    pub struct RegisterUserPayload {
        email: String,
        // password etc.
    }

    pub async fn register_user(
        State(app_state): State<Arc<AppState>>,
        Json(payload): Json<RegisterUserPayload>,
    ) -> Result<impl IntoResponse, AppError> {
        // 1. Create user in DB (mocked for simplicity)
        let new_user = User {
            id: Uuid::new_v4(),
            email: payload.email.clone(),
            role: UserRole::USER,
            is_active: true,
            created_at: Utc::now(),
        };
        info!("User created: {}", new_user.id);

        // 2. Schedule a background job to send a welcome email
        let task = tasks::TaskPayload::SendWelcomeEmail {
            user_id: new_user.id,
            email: new_user.email,
        };
        let job_id = app_state.job_queue_service.schedule_task(task).await?;
        info!("Scheduled welcome email job: {}", job_id);

        // 3. Schedule an image processing job (for demonstration)
        let image_task = tasks::TaskPayload::ProcessImage {
            post_id: Uuid::new_v4(), // Assume a new post was created
            image_url: "https://example.com/image.jpg".to_string(),
        };
        let image_job_id = app_state.job_queue_service.schedule_task(image_task).await?;
        info!("Scheduled image processing job: {}", image_job_id);

        Ok((
            StatusCode::CREATED,
            Json(serde_json::json!({
                "message": "User registered successfully. Welcome email and image processing jobs are scheduled.",
                "user_id": new_user.id,
                "email_job_id": job_id,
                "image_job_id": image_job_id,
            })),
        ))
    }

    pub async fn get_job_status(
        State(app_state): State<Arc<AppState>>,
        Path(job_id): Path<Uuid>,
    ) -> Result<impl IntoResponse, AppError> {
        let job = app_state.job_queue_service.get_job_status(job_id).await?;
        Ok(Json(job))
    }
}

// --- Application State and Main ---
pub struct AppState {
    db_pool: SqlitePool,
    job_queue_service: job_queue_service::JobQueueService,
}

async fn setup_database() -> SqlitePool {
    let pool = SqlitePoolOptions::new()
        .connect("sqlite::memory:")
        .await
        .expect("Failed to connect to in-memory SQLite");

    sqlx::query(
        "CREATE TABLE IF NOT EXISTS jobs (
            id TEXT PRIMARY KEY,
            payload TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'pending',
            attempts INTEGER NOT NULL DEFAULT 0,
            run_at DATETIME NOT NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            error_message TEXT
        );",
    )
    .execute(&pool)
    .await
    .expect("Failed to create jobs table");
    
    // Mock posts table for image processing task
    sqlx::query(
        "CREATE TABLE IF NOT EXISTS posts (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL,
            title TEXT NOT NULL,
            content TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'DRAFT'
        );"
    )
    .execute(&pool)
    .await
    .expect("Failed to create posts table");

    pool
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(tracing_subscriber::EnvFilter::from_default_env())
        .init();

    let db_pool = setup_database().await;
    let job_queue_service = job_queue_service::JobQueueService::new(db_pool.clone());

    let app_state = Arc::new(AppState {
        db_pool: db_pool.clone(),
        job_queue_service,
    });

    // Spawn background worker
    worker::spawn_worker(db_pool.clone());
    
    // Setup and start periodic tasks
    let _scheduler = scheduler::setup_scheduler(db_pool.clone()).await;

    let app = Router::new()
        .route("/users/register", post(handlers::register_user))
        .route("/jobs/:id", get(handlers::get_job_status))
        .with_state(app_state);

    let listener = tokio::net::TcpListener::bind("0.0.0.0:3000").await.unwrap();
    info!("Server listening on {}", listener.local_addr().unwrap());
    axum::serve(listener, app).await.unwrap();
}