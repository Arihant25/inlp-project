/*
// The "OOP-Inspired / Trait-Based" Developer
// This variation uses traits to define contracts for tasks, promoting a more
// structured, testable, and extensible design. A generic worker can process
// any type that implements the `Task` trait. The code is organized around
// these abstractions.

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
async-trait = "0.1"
rand = "0.8"
*/

use async_trait::async_trait;
use axum::{
    extract::{Path, State},
    http::StatusCode,
    response::{IntoResponse, Json},
    routing::{get, post},
    Router,
};
use chrono::{DateTime, Utc};
use rand::Rng;
use serde::{de::DeserializeOwned, Deserialize, Serialize};
use sqlx::{sqlite::SqlitePoolOptions, FromRow, SqlitePool};
use std::sync::Arc;
use std::time::Duration;
use tokio::time::sleep;
use tokio_cron_scheduler::{Job, JobScheduler};
use tracing::{error, info};
use uuid::Uuid;

// --- Domain Models ---
#[derive(Debug, Serialize, Clone)]
pub enum UserRole { ADMIN, USER }
#[derive(Debug, Serialize, Clone)]
pub struct User { id: Uuid, email: String }

// --- Task Abstraction (Trait-based) ---
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub enum RetryStrategy {
    None,
    Exponential(u32), // max attempts
}

#[async_trait]
pub trait Task: Serialize + DeserializeOwned + Send + Sync + 'static {
    const NAME: &'static str;
    type Error: std::error::Error + Send + Sync;

    async fn execute(&self, context: Arc<TaskContext>) -> Result<(), Self::Error>;
    fn retry_strategy(&self) -> RetryStrategy {
        RetryStrategy::Exponential(5)
    }
}

// --- Concrete Task Implementations ---
#[derive(Serialize, Deserialize)]
pub struct SendWelcomeEmailTask {
    user_id: Uuid,
    email: String,
}

#[async_trait]
impl Task for SendWelcomeEmailTask {
    const NAME: &'static str = "send_welcome_email";
    type Error = std::io::Error;

    async fn execute(&self, _context: Arc<TaskContext>) -> Result<(), Self::Error> {
        info!("[Task] Sending welcome email to {}", self.email);
        sleep(Duration::from_secs(2)).await;
        if rand::thread_rng().gen_bool(0.25) {
            return Err(std::io::Error::new(
                std::io::ErrorKind::ConnectionAborted,
                "SMTP server connection failed",
            ));
        }
        info!("[Task] Welcome email sent successfully to {}", self.email);
        Ok(())
    }
}

#[derive(Serialize, Deserialize)]
pub struct ImageProcessingTask {
    post_id: Uuid,
    image_url: String,
}

#[async_trait]
impl Task for ImageProcessingTask {
    const NAME: &'static str = "process_image_pipeline";
    type Error = anyhow::Error;

    fn retry_strategy(&self) -> RetryStrategy {
        RetryStrategy::Exponential(3)
    }

    async fn execute(&self, context: Arc<TaskContext>) -> Result<(), Self::Error> {
        info!("[Task] Starting image pipeline for post {}", self.post_id);
        sleep(Duration::from_secs(1)).await;
        info!("[Task] Resizing done.");
        sleep(Duration::from_secs(1)).await;
        info!("[Task] Watermarking done.");
        // Update post status in DB
        sqlx::query("UPDATE posts SET status = 'PUBLISHED' WHERE id = ?")
            .bind(self.post_id)
            .execute(&context.db_pool)
            .await?;
        info!("[Task] Image pipeline complete for post {}", self.post_id);
        Ok(())
    }
}

// --- Task Dispatcher ---
#[derive(Serialize, Deserialize, Debug)]
#[serde(tag = "task_name")]
enum AllTasks {
    #[serde(rename = "send_welcome_email")]
    SendWelcomeEmail(SendWelcomeEmailTask),
    #[serde(rename = "process_image_pipeline")]
    ProcessImage(ImageProcessingTask),
}

impl AllTasks {
    async fn execute_task(self, context: Arc<TaskContext>) -> Result<(), String> {
        match self {
            AllTasks::SendWelcomeEmail(task) => task.execute(context).await.map_err(|e| e.to_string()),
            AllTasks::ProcessImage(task) => task.execute(context).await.map_err(|e| e.to_string()),
        }
    }
    fn get_retry_strategy(&self) -> RetryStrategy {
        match self {
            AllTasks::SendWelcomeEmail(task) => task.retry_strategy(),
            AllTasks::ProcessImage(task) => task.retry_strategy(),
        }
    }
}

// --- Worker & Queue Infrastructure ---
pub struct TaskContext {
    pub db_pool: SqlitePool,
}

#[derive(Debug, FromRow, Serialize)]
pub struct Job {
    id: Uuid,
    task_name: String,
    #[sqlx(json)]
    payload: serde_json::Value,
    status: String,
    attempts: i32,
}

pub struct TaskQueue {
    db_pool: Arc<SqlitePool>,
}

impl TaskQueue {
    pub fn new(db_pool: Arc<SqlitePool>) -> Self {
        Self { db_pool }
    }

    pub async fn submit<T: Task>(&self, task: T) -> Result<Uuid, sqlx::Error> {
        let job_id = Uuid::new_v4();
        let payload = serde_json::to_value(&task).unwrap();
        let all_tasks_payload = serde_json::json!({
            "task_name": T::NAME,
            T::NAME: payload
        });

        sqlx::query(
            "INSERT INTO jobs (id, task_name, payload, run_at) VALUES (?, ?, ?, ?)",
        )
        .bind(job_id)
        .bind(T::NAME)
        .bind(all_tasks_payload)
        .bind(Utc::now())
        .execute(&*self.db_pool)
        .await?;
        Ok(job_id)
    }
}

pub struct Worker {
    id: usize,
    context: Arc<TaskContext>,
}

impl Worker {
    pub fn new(id: usize, context: Arc<TaskContext>) -> Self {
        Self { id, context }
    }

    pub fn start(self) {
        tokio::spawn(async move {
            info!("Worker {} started", self.id);
            loop {
                if let Err(e) = self.process_job().await {
                    error!("Worker {} failed to process job: {}", self.id, e);
                }
                sleep(Duration::from_secs(2)).await;
            }
        });
    }

    async fn process_job(&self) -> Result<(), anyhow::Error> {
        let mut tx = self.context.db_pool.begin().await?;
        let maybe_job: Option<(Uuid, serde_json::Value, i32)> = sqlx::query_as(
            "SELECT id, payload, attempts FROM jobs WHERE status = 'pending' AND run_at <= ? LIMIT 1",
        )
        .bind(Utc::now())
        .fetch_optional(&mut *tx)
        .await?;

        let (job_id, payload, attempts) = match maybe_job {
            Some(j) => j,
            None => return Ok(()),
        };

        sqlx::query("UPDATE jobs SET status = 'running' WHERE id = ?")
            .bind(job_id)
            .execute(&mut *tx)
            .await?;
        tx.commit().await?;

        let task: AllTasks = serde_json::from_value(payload)?;
        let retry_strategy = task.get_retry_strategy();

        match task.execute_task(self.context.clone()).await {
            Ok(_) => {
                sqlx::query("UPDATE jobs SET status = 'completed' WHERE id = ?")
                    .bind(job_id)
                    .execute(&*self.context.db_pool)
                    .await?;
            }
            Err(e) => {
                let new_attempts = attempts + 1;
                let should_retry = match retry_strategy {
                    RetryStrategy::None => false,
                    RetryStrategy::Exponential(max) => new_attempts < max as i32,
                };

                if should_retry {
                    let backoff = 2u64.pow(new_attempts as u32);
                    let next_run = Utc::now() + chrono::Duration::seconds(backoff as i64);
                    sqlx::query("UPDATE jobs SET status = 'pending', attempts = ?, run_at = ?, error_message = ? WHERE id = ?")
                        .bind(new_attempts)
                        .bind(next_run)
                        .bind(e)
                        .bind(job_id)
                        .execute(&*self.context.db_pool).await?;
                } else {
                    sqlx::query("UPDATE jobs SET status = 'failed', error_message = ? WHERE id = ?")
                        .bind(e)
                        .bind(job_id)
                        .execute(&*self.context.db_pool).await?;
                }
            }
        }
        Ok(())
    }
}

// --- App State and Handlers ---
struct AppState {
    task_queue: TaskQueue,
    db_pool: Arc<SqlitePool>,
}

#[derive(Deserialize)]
struct RegistrationPayload { email: String }

async fn register_user_handler(
    State(state): State<Arc<AppState>>,
    Json(payload): Json<RegistrationPayload>,
) -> impl IntoResponse {
    let user = User { id: Uuid::new_v4(), email: payload.email };
    let email_task = SendWelcomeEmailTask { user_id: user.id, email: user.email.clone() };
    let image_task = ImageProcessingTask { post_id: Uuid::new_v4(), image_url: "http://...".to_string() };

    let email_job_id = state.task_queue.submit(email_task).await.unwrap();
    let image_job_id = state.task_queue.submit(image_task).await.unwrap();

    (StatusCode::CREATED, Json(serde_json::json!({
        "message": "User registered, tasks submitted",
        "email_job_id": email_job_id,
        "image_job_id": image_job_id,
    })))
}

async fn get_job_status_handler(
    State(state): State<Arc<AppState>>,
    Path(job_id): Path<Uuid>,
) -> Result<Json<Job>, StatusCode> {
    sqlx::query_as("SELECT id, task_name, payload, status, attempts FROM jobs WHERE id = ?")
        .bind(job_id)
        .fetch_one(&*state.db_pool)
        .await
        .map(Json)
        .map_err(|_| StatusCode::NOT_FOUND)
}

async fn setup_db() -> SqlitePool {
    let pool = SqlitePoolOptions::new().connect("sqlite::memory:").await.unwrap();
    sqlx::query(
        "CREATE TABLE jobs (
            id TEXT PRIMARY KEY,
            task_name TEXT NOT NULL,
            payload TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'pending',
            attempts INTEGER NOT NULL DEFAULT 0,
            run_at DATETIME NOT NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            error_message TEXT
        );",
    ).execute(&pool).await.unwrap();
    sqlx::query(
        "CREATE TABLE posts (id TEXT PRIMARY KEY, status TEXT NOT NULL);"
    ).execute(&pool).await.unwrap();
    pool
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt().with_level(true).init();

    let db_pool = Arc::new(setup_db().await);
    let task_context = Arc::new(TaskContext { db_pool: db_pool.clone() });

    // Start workers
    for i in 0..2 {
        Worker::new(i, task_context.clone()).start();
    }
    
    // Start periodic scheduler
    let scheduler = JobScheduler::new().await.unwrap();
    scheduler.add(Job::new_async("0 0 * * * *", |_,_| {
        Box::pin(async { info!("Periodic task running...") })
    }).unwrap()).await.unwrap();
    scheduler.start().await.unwrap();

    let app_state = Arc::new(AppState {
        task_queue: TaskQueue::new(db_pool.clone()),
        db_pool,
    });

    let app = Router::new()
        .route("/register", post(register_user_handler))
        .route("/jobs/:id", get(get_job_status_handler))
        .with_state(app_state);

    let listener = tokio::net::TcpListener::bind("0.0.0.0:3000").await.unwrap();
    info!("Server running on {}", listener.local_addr().unwrap());
    axum::serve(listener, app).await.unwrap();
}