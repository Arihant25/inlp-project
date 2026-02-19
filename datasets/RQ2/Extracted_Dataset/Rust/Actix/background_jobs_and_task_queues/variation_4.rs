/*
Variation 4: Command/Handler Pattern with a Persistent Queue (sqlx)

This is the most robust and production-ready pattern. It uses a PostgreSQL database
(via sqlx) as a persistent job queue. HTTP handlers create job records in a `jobs`
table. A separate, long-running worker process polls this table for pending jobs,
locks them, and executes them. Job status, retries, and scheduling are all managed
via database fields. This ensures job durability even if the application restarts.

Dependencies (add to Cargo.toml):
actix-web = "4"
tokio = { version = "1", features = ["full"] }
serde = { version = "1.0", features = ["derive"] }
serde_json = "1.0"
uuid = { version = "1", features = ["v4", "serde"] }
chrono = { version = "0.4", features = ["serde"] }
sqlx = { version = "0.7", features = ["runtime-tokio-rustls", "postgres", "uuid", "chrono", "json"] }
anyhow = "1.0"
*/

use actix_web::{web, App, HttpServer, Responder, HttpResponse, error};
use serde::{Serialize, Deserialize};
use uuid::Uuid;
use chrono::{DateTime, Utc, Duration as ChronoDuration};
use sqlx::postgres::{PgPool, PgPoolOptions};
use std::sync::Arc;
use tokio::time::{sleep, Duration};
use anyhow::Result;

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

// --- Job Queue Infrastructure (SQLx) ---

#[derive(sqlx::Type, Serialize, Deserialize, Debug, Clone, PartialEq)]
#[sqlx(type_name = "job_status", rename_all = "SCREAMING_SNAKE_CASE")]
pub enum JobStatus {
    Pending,
    Running,
    Completed,
    Failed,
}

#[derive(sqlx::Type, Serialize, Deserialize, Debug, Clone)]
#[sqlx(type_name = "job_type", rename_all = "SCREAMING_SNAKE_CASE")]
pub enum JobType {
    SendWelcomeEmail,
    ProcessPostImage,
}

#[derive(sqlx::FromRow, Serialize, Debug, Clone)]
pub struct Job {
    pub id: Uuid,
    pub job_type: JobType,
    pub payload: serde_json::Value,
    pub status: JobStatus,
    pub created_at: DateTime<Utc>,
    pub run_at: DateTime<Utc>,
    pub attempt: i32,
    pub max_attempts: i32,
    pub last_error: Option<String>,
}

// --- Job Commands & Handlers ---

// Command to create a new job
async fn enqueue_job(
    pool: &PgPool,
    job_type: JobType,
    payload: serde_json::Value,
    max_attempts: i32,
) -> Result<Uuid> {
    let job_id = Uuid::new_v4();
    sqlx::query!(
        r#"
        INSERT INTO jobs (id, job_type, payload, max_attempts, run_at)
        VALUES ($1, $2, $3, $4, NOW())
        "#,
        job_id,
        job_type as JobType,
        payload,
        max_attempts
    )
    .execute(pool)
    .await?;
    Ok(job_id)
}

// --- Task Execution Logic ---

async fn handle_send_welcome_email(payload: serde_json::Value) -> Result<(), String> {
    let user: User = serde_json::from_value(payload).map_err(|e| e.to_string())?;
    println!("EXECUTING [SendWelcomeEmail] for {}", user.email);
    sleep(Duration::from_secs(2)).await;
    if user.email.contains("fail") {
        Err("Simulated SMTP failure".to_string())
    } else {
        println!("COMPLETED [SendWelcomeEmail] for {}", user.email);
        Ok(())
    }
}

async fn handle_process_post_image(payload: serde_json::Value) -> Result<(), String> {
    let post: Post = serde_json::from_value(payload).map_err(|e| e.to_string())?;
    println!("EXECUTING [ProcessPostImage] for post '{}'", post.title);
    sleep(Duration::from_secs(4)).await;
    println!("COMPLETED [ProcessPostImage] for post '{}'", post.title);
    Ok(())
}

// --- Background Worker ---

async fn worker_loop(pool: Arc<PgPool>) {
    println!("Worker started.");
    loop {
        match fetch_and_process_job(&pool).await {
            Ok(Some(_)) => { /* Processed a job, look for another immediately */ }
            Ok(None) => {
                // No jobs available, wait before polling again
                sleep(Duration::from_secs(5)).await;
            }
            Err(e) => {
                eprintln!("WORKER ERROR: Failed to process job: {}", e);
                sleep(Duration::from_secs(10)).await;
            }
        }
    }
}

async fn fetch_and_process_job(pool: &PgPool) -> Result<Option<Uuid>> {
    // Atomically fetch and lock a pending job
    let maybe_job: Option<Job> = sqlx::query_as!(
        Job,
        r#"
        UPDATE jobs
        SET status = 'RUNNING'
        WHERE id = (
            SELECT id
            FROM jobs
            WHERE status = 'PENDING' AND run_at <= NOW()
            ORDER BY created_at
            FOR UPDATE SKIP LOCKED
            LIMIT 1
        )
        RETURNING id, job_type as "job_type: _", payload, status as "status: _", created_at, run_at, attempt, max_attempts, last_error
        "#
    )
    .fetch_optional(pool)
    .await?;

    let job = match maybe_job {
        Some(j) => j,
        None => return Ok(None),
    };

    println!("WORKER: Picked up job {}", job.id);

    let result = match job.job_type {
        JobType::SendWelcomeEmail => handle_send_welcome_email(job.payload.clone()).await,
        JobType::ProcessPostImage => handle_process_post_image(job.payload.clone()).await,
    };

    match result {
        Ok(_) => {
            sqlx::query!(
                "UPDATE jobs SET status = 'COMPLETED' WHERE id = $1",
                job.id
            )
            .execute(pool)
            .await?;
            println!("WORKER: Job {} completed successfully.", job.id);
        }
        Err(e) => {
            let new_attempt = job.attempt + 1;
            if new_attempt >= job.max_attempts {
                sqlx::query!(
                    "UPDATE jobs SET status = 'FAILED', last_error = $1 WHERE id = $2",
                    e,
                    job.id
                )
                .execute(pool)
                .await?;
                println!("WORKER: Job {} failed permanently.", job.id);
            } else {
                let backoff_seconds = 5 * 2i64.pow(new_attempt as u32);
                let next_run_at = Utc::now() + ChronoDuration::seconds(backoff_seconds);
                sqlx::query!(
                    r#"
                    UPDATE jobs
                    SET status = 'PENDING', attempt = $1, last_error = $2, run_at = $3
                    WHERE id = $4
                    "#,
                    new_attempt,
                    e,
                    next_run_at,
                    job.id
                )
                .execute(pool)
                .await?;
                println!("WORKER: Job {} failed. Retrying at {}", job.id, next_run_at);
            }
        }
    }

    Ok(Some(job.id))
}

// --- API Handlers ---

async fn schedule_email(
    user_data: web::Json<User>,
    pool: web::Data<PgPool>,
) -> Result<HttpResponse, error::Error> {
    let job_id = enqueue_job(
        &pool,
        JobType::SendWelcomeEmail,
        serde_json::to_value(user_data.into_inner()).unwrap(),
        3,
    )
    .await
    .map_err(|_| error::ErrorInternalServerError("Failed to enqueue job"))?;

    Ok(HttpResponse::Accepted().json(serde_json::json!({ "job_id": job_id })))
}

async fn schedule_image_processing(
    post_data: web::Json<Post>,
    pool: web::Data<PgPool>,
) -> Result<HttpResponse, error::Error> {
    let job_id = enqueue_job(
        &pool,
        JobType::ProcessPostImage,
        serde_json::to_value(post_data.into_inner()).unwrap(),
        1,
    )
    .await
    .map_err(|_| error::ErrorInternalServerError("Failed to enqueue job"))?;

    Ok(HttpResponse::Accepted().json(serde_json::json!({ "job_id": job_id })))
}

async fn get_job_status(
    job_id: web::Path<Uuid>,
    pool: web::Data<PgPool>,
) -> Result<HttpResponse, error::Error> {
    let job: Option<Job> = sqlx::query_as!(
        Job,
        r#"SELECT id, job_type as "job_type: _", payload, status as "status: _", created_at, run_at, attempt, max_attempts, last_error FROM jobs WHERE id = $1"#,
        *job_id
    )
    .fetch_optional(pool.get_ref())
    .await
    .map_err(|_| error::ErrorInternalServerError("DB query failed"))?;

    match job {
        Some(j) => Ok(HttpResponse::Ok().json(j)),
        None => Ok(HttpResponse::NotFound().body("Job not found")),
    }
}

// --- Main Function & Setup ---
// NOTE: This requires a running PostgreSQL database with the specified schema.
//
// SQL Schema:
// CREATE TYPE job_status AS ENUM ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED');
// CREATE TYPE job_type AS ENUM ('SEND_WELCOME_EMAIL', 'PROCESS_POST_IMAGE');
//
// CREATE TABLE jobs (
//     id UUID PRIMARY KEY,
//     job_type job_type NOT NULL,
//     payload JSONB NOT NULL,
//     status job_status NOT NULL DEFAULT 'PENDING',
//     created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
//     run_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
//     attempt INT NOT NULL DEFAULT 0,
//     max_attempts INT NOT NULL DEFAULT 1,
//     last_error TEXT
// );

#[actix_web::main]
async fn main() -> std::io::Result<()> {
    // In a real app, this would come from config
    let database_url = std::env::var("DATABASE_URL")
        .unwrap_or_else(|_| "postgres://user:password@localhost:5432/jobs_db".to_string());

    let pool = PgPoolOptions::new()
        .max_connections(5)
        .connect(&database_url)
        .await
        .expect("Failed to create DB pool. Make sure PostgreSQL is running and the schema is created.");

    let pool = Arc::new(pool);

    // Start background worker
    let worker_pool = pool.clone();
    tokio::spawn(async move {
        worker_loop(worker_pool).await;
    });

    println!("Starting server at http://127.0.0.1:8080");

    HttpServer::new(move || {
        App::new()
            .app_data(web::Data::from(pool.clone()))
            .route("/users", web::post().to(schedule_email))
            .route("/posts", web::post().to(schedule_image_processing))
            .route("/jobs/{job_id}", web::get().to(get_job_status))
    })
    .bind("127.0.0.1:8080")?
    .run()
    .await
}