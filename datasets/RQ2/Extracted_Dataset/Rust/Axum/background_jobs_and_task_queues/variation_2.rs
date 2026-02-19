/*
// The "Functional & Concise" Developer
// This variation favors a flatter structure, co-locating logic in the main file.
// It uses free functions, closures, and `anyhow::Result` for more direct and
// less ceremonious code. Naming is kept short and to the point.

// Cargo.toml dependencies:
[dependencies]
axum = "0.7"
tokio = { version = "1", features = ["full"] }
serde = { version = "1", features = ["derive"] }
serde_json = "1"
uuid = { version = "1", features = ["v4", "serde"] }
chrono = { version = "0.4", features = ["serde"] }
anyhow = "1"
tracing = "0.1"
tracing-subscriber = { version = "0.3", features = ["env-filter"] }
sqlx = { version = "0.7", features = ["runtime-tokio", "sqlite", "uuid", "chrono", "json"] }
tokio-cron-scheduler = "0.10"
rand = "0.8"
*/

use anyhow::{Context, Result};
use axum::{
    extract::{Path, State},
    http::StatusCode,
    response::{IntoResponse, Json, Response},
    routing::{get, post},
    Router,
};
use chrono::{DateTime, Utc};
use rand::Rng;
use serde::{Deserialize, Serialize};
use sqlx::{sqlite::SqlitePoolOptions, FromRow, SqlitePool};
use std::sync::Arc;
use std::time::Duration;
use tokio::time::sleep;
use tokio_cron_scheduler::{Job, JobScheduler};
use tracing::{error, info};
use uuid::Uuid;

// --- Domain ---
#[derive(Serialize)]
enum UserRole { ADMIN, USER }
#[derive(Serialize)]
struct User { id: Uuid, email: String }

// --- Task Definition ---
#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(tag = "kind")]
enum Task {
    WelcomeEmail { user_id: Uuid, email: String },
    ProcessImage { post_id: Uuid, url: String },
}

// --- App State & Error ---
type AppState = Arc<StateContainer>;
struct StateContainer {
    db: SqlitePool,
}

struct AppError(anyhow::Error);

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        error!("Error processing request: {:#}", self.0);
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({"error": "Something went wrong"})),
        )
            .into_response()
    }
}

impl<E> From<E> for AppError where E: Into<anyhow::Error> {
    fn from(err: E) -> Self {
        Self(err.into())
    }
}

// --- Job Queue Logic ---
#[derive(Debug, Clone, Serialize, FromRow)]
struct JobRecord {
    id: Uuid,
    #[sqlx(json)]
    payload: Task,
    status: String,
    attempts: i32,
}

async fn enqueue_task(db: &SqlitePool, task: Task) -> Result<Uuid> {
    let job_id = Uuid::new_v4();
    sqlx::query("INSERT INTO jobs (id, payload, run_at) VALUES (?, ?, ?)")
        .bind(job_id)
        .bind(serde_json::to_value(&task)?)
        .bind(Utc::now())
        .execute(db)
        .await
        .context("Failed to enqueue task")?;
    Ok(job_id)
}

// --- Task Execution Logic ---
async fn run_task(task: Task) -> Result<(), String> {
    match task {
        Task::WelcomeEmail { user_id, email } => {
            info!(?user_id, "Sending welcome email to {}", email);
            sleep(Duration::from_secs(2)).await;
            if rand::thread_rng().gen_bool(0.2) {
                return Err("SMTP server unavailable".to_string());
            }
            info!("Email sent to {}", email);
        }
        Task::ProcessImage { post_id, url } => {
            info!(?post_id, "Processing image from {}", url);
            sleep(Duration::from_secs(4)).await; // Simulate multi-step process
            info!(?post_id, "Image processing complete");
        }
    }
    Ok(())
}

// --- Worker ---
fn spawn_worker(db: SqlitePool) {
    tokio::spawn(async move {
        info!("Worker process started");
        loop {
            if let Err(e) = process_next_job(&db).await {
                error!("Worker error: {:#}", e);
            }
            sleep(Duration::from_secs(1)).await;
        }
    });
}

async fn process_next_job(db: &SqlitePool) -> Result<()> {
    let mut tx = db.begin().await?;
    let job_row: Option<(Uuid, Task, i32)> = sqlx::query_as(
        "SELECT id, payload, attempts FROM jobs WHERE status = 'pending' AND run_at <= ? ORDER BY created_at LIMIT 1",
    )
    .bind(Utc::now())
    .fetch_optional(&mut *tx)
    .await?;

    let (job_id, task, attempts) = match job_row {
        Some(row) => row,
        None => return Ok(()), // No jobs to process
    };

    sqlx::query("UPDATE jobs SET status = 'running' WHERE id = ?")
        .bind(job_id)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;

    match run_task(task).await {
        Ok(_) => {
            sqlx::query("UPDATE jobs SET status = 'completed' WHERE id = ?")
                .bind(job_id)
                .execute(db)
                .await?;
        }
        Err(err_msg) => {
            let new_attempts = attempts + 1;
            if new_attempts > 5 {
                sqlx::query("UPDATE jobs SET status = 'failed', error_message = ? WHERE id = ?")
                    .bind(err_msg)
                    .bind(job_id)
                    .execute(db)
                    .await?;
            } else {
                let backoff = Duration::from_secs(2u64.pow(new_attempts as u32));
                let next_run = Utc::now() + chrono::Duration::from_std(backoff).unwrap();
                sqlx::query("UPDATE jobs SET status = 'pending', attempts = ?, run_at = ?, error_message = ? WHERE id = ?")
                    .bind(new_attempts)
                    .bind(next_run)
                    .bind(err_msg)
                    .bind(job_id)
                    .execute(db)
                    .await?;
            }
        }
    }
    Ok(())
}

// --- Periodic Scheduler ---
async fn start_scheduler(db: SqlitePool) -> Result<JobScheduler> {
    let sched = JobScheduler::new().await?;
    let job = Job::new_async("0 */5 * * * *", move |_, _| { // Every 5 minutes
        let db_clone = db.clone();
        Box::pin(async move {
            info!("Periodic task: Pruning old jobs");
            let res = sqlx::query("DELETE FROM jobs WHERE status = 'completed' AND created_at < ?")
                .bind(Utc::now() - chrono::Duration::days(7))
                .execute(&db_clone)
                .await;
            if let Err(e) = res {
                error!("Periodic job failed: {}", e);
            }
        })
    })?;
    sched.add(job).await?;
    sched.start().await?;
    info!("Scheduler started");
    Ok(sched)
}

// --- API Handlers ---
#[derive(Deserialize)]
struct SignupReq { email: String }

async fn handle_signup(
    State(state): State<AppState>,
    Json(req): Json<SignupReq>,
) -> Result<impl IntoResponse, AppError> {
    let user = User { id: Uuid::new_v4(), email: req.email };
    info!("New user signup: {}", user.id);

    let email_job_id = enqueue_task(&state.db, Task::WelcomeEmail { user_id: user.id, email: user.email }).await?;
    let image_job_id = enqueue_task(&state.db, Task::ProcessImage { post_id: Uuid::new_v4(), url: "http://images.com/new.png".into() }).await?;

    Ok((
        StatusCode::ACCEPTED,
        Json(serde_json::json!({
            "user_id": user.id,
            "tasks_enqueued": {
                "welcome_email": email_job_id,
                "image_processing": image_job_id
            }
        })),
    ))
}

async fn handle_job_status(
    State(state): State<AppState>,
    Path(job_id): Path<Uuid>,
) -> Result<Json<JobRecord>, AppError> {
    let job = sqlx::query_as("SELECT id, payload, status, attempts FROM jobs WHERE id = ?")
        .bind(job_id)
        .fetch_one(&state.db)
        .await
        .context("Job not found")?;
    Ok(Json(job))
}

// --- Main ---
async fn init_db() -> Result<SqlitePool> {
    let pool = SqlitePoolOptions::new().connect("sqlite::memory:").await?;
    sqlx::query(
        "CREATE TABLE jobs (
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
    .await?;
    Ok(pool)
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt().with_env_filter("info").init();

    let db_pool = init_db().await.context("DB initialization failed")?;
    
    spawn_worker(db_pool.clone());
    let _scheduler = start_scheduler(db_pool.clone()).await?;

    let state = Arc::new(StateContainer { db: db_pool });

    let app = Router::new()
        .route("/signup", post(handle_signup))
        .route("/jobs/:id", get(handle_job_status))
        .with_state(state);

    let listener = tokio::net::TcpListener::bind("0.0.0.0:3000").await?;
    info!("Listening on {}", listener.local_addr()?);
    axum::serve(listener, app).await?;

    Ok(())
}