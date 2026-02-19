/*
--- Cargo.toml ---
[dependencies]
rocket = { version = "0.5.0", features = ["json"] }
serde = { version = "1.0", features = ["derive"] }
uuid = { version = "1.8", features = ["v4", "serde"] }
chrono = { version = "0.4", features = ["serde"] }
fang = { version = "0.10", features = ["asynk-postgres-queue"], default-features = false }
async-trait = "0.1"
tokio = { version = "1", features = ["full"] }
anyhow = "1.0"
*/

// V4: The "Minimalist & Pragmatic" Developer
// Puts everything in a single file for simplicity.
// Avoids complex abstractions, directly creating and queueing tasks in handlers.
// Uses `anyhow` for straightforward error handling.

use async_trait::async_trait;
use chrono::{DateTime, Utc};
use fang::{typetag, AsyncQueue, AsyncRunnable, AsyncWorkerPool, FangError};
use rocket::serde::json::{json, Json, Value};
use rocket::{Build, Rocket, State};
use serde::{Deserialize, Serialize};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;
use tokio::time::sleep;
use uuid::Uuid;

// --- 1. Schema ---
#[derive(Serialize)]
enum UserRole { ADMIN, USER }
#[derive(Serialize)]
struct User { id: Uuid, email: String }

#[derive(Serialize)]
enum PostStatus { DRAFT, PUBLISHED }
#[derive(Serialize)]
struct Post { id: Uuid, user_id: Uuid, title: String }

// --- 2. Task Definitions ---

// A simple task to send an email.
#[derive(Serialize, Deserialize)]
struct SendEmailJob { user_id: Uuid, email: String }

#[typetag::serde]
#[async_trait]
impl AsyncRunnable for SendEmailJob {
    async fn run(&self, _queue: &mut dyn fang::AsyncQueueable) -> Result<(), FangError> {
        println!("-> Starting SendEmailJob for {}", self.email);
        sleep(Duration::from_secs(2)).await;
        println!("<- Finished SendEmailJob for {}", self.email);
        Ok(())
    }
}

// A task to process an image.
#[derive(Serialize, Deserialize)]
struct ProcessImageJob { post_id: Uuid }

#[typetag::serde]
#[async_trait]
impl AsyncRunnable for ProcessImageJob {
    async fn run(&self, _queue: &mut dyn fang::AsyncQueueable) -> Result<(), FangError> {
        println!("-> Starting ProcessImageJob for post {}", self.post_id);
        sleep(Duration::from_secs(3)).await;
        println!("<- Finished ProcessImageJob for post {}", self.post_id);
        Ok(())
    }
}

// A periodic task for cleanup.
#[derive(Serialize, Deserialize)]
struct CleanupJob;

#[typetag::serde]
#[async_trait]
impl AsyncRunnable for CleanupJob {
    async fn run(&self, _queue: &mut dyn fang::AsyncQueueable) -> Result<(), FangError> {
        println!("-> Running periodic CleanupJob");
        sleep(Duration::from_secs(4)).await;
        println!("<- Finished periodic CleanupJob");
        Ok(())
    }
}

// A task that fails and retries.
#[derive(Serialize, Deserialize)]
struct UnreliableJob { id: Uuid }

// Use a global atomic counter to track attempts for this specific job type.
// This is a simple, non-persistent way to manage state for a demo.
static RETRY_ATTEMPTS: AtomicUsize = AtomicUsize::new(0);

#[typetag::serde]
#[async_trait]
impl AsyncRunnable for UnreliableJob {
    async fn run(&self, _queue: &mut dyn fang::AsyncQueueable) -> Result<(), FangError> {
        let attempt = RETRY_ATTEMPTS.fetch_add(1, Ordering::SeqCst);
        println!("-> Attempting UnreliableJob (id: {}), attempt #{}", self.id, attempt);

        // Fail the first 2 times
        if attempt < 2 {
            println!("-- UnreliableJob failed. It will be retried.");
            return Err(FangError {
                description: "External service is down".to_string(),
            });
        }

        println!("<- UnreliableJob (id: {}) succeeded!", self.id);
        RETRY_ATTEMPTS.store(0, Ordering::SeqCst); // Reset for next test
        Ok(())
    }
}

// --- 3. API Endpoints ---
#[rocket::post("/users", format = "json", data = "<email>")]
async fn create_user(email: Json<String>, queue: &State<AsyncQueue>) -> Result<Json<User>, Value> {
    let user = User { id: Uuid::new_v4(), email: email.into_inner() };
    println!("API: Created user {}", user.id);

    let job = SendEmailJob { id: user.id, email: user.email.clone() };
    if let Err(e) = queue.insert_task(&job).await {
        eprintln!("API ERROR: Failed to queue job: {}", e);
        return Err(json!({"error": "server_error"}));
    }
    
    Ok(Json(user))
}

#[rocket::post("/posts", format = "json", data = "<title>")]
async fn create_post(title: Json<String>, queue: &State<AsyncQueue>) -> Result<Json<Post>, Value> {
    let post = Post { id: Uuid::new_v4(), user_id: Uuid::new_v4(), title: title.into_inner() };
    println!("API: Created post {}", post.id);

    let job = ProcessImageJob { post_id: post.id };
    if let Err(e) = queue.insert_task(&job).await {
        eprintln!("API ERROR: Failed to queue job: {}", e);
        return Err(json!({"error": "server_error"}));
    }

    Ok(Json(post))
}

#[rocket::post("/test/unreliable")]
async fn test_unreliable_job(queue: &State<AsyncQueue>) -> Value {
    let job = UnreliableJob { id: Uuid::new_v4() };
    RETRY_ATTEMPTS.store(0, Ordering::SeqCst); // Reset counter for the test
    
    if queue.insert_task(&job).await.is_ok() {
        json!({"status": "Job enqueued. Check logs for retries."})
    } else {
        json!({"status": "Failed to enqueue job."})
    }
}

#[rocket::get("/jobs/<job_id>")]
async fn get_job_status(job_id: Uuid) -> Value {
    // In a real app, you'd query the `fang_tasks` table in your DB.
    println!("API: Checking status for job {}", job_id);
    json!({
        "job_id": job_id,
        "status": "unknown",
        "note": "This is a mock response. Status tracking requires a DB."
    })
}

// --- 4. Main App Setup ---
#[rocket::launch]
fn rocket() -> Rocket<Build> {
    // Use an in-memory queue for this simple example.
    // For persistence, use `AsyncQueue::builder().uri("postgres://...").build()`
    let queue = AsyncQueue::builder().build();

    // Start a worker pool in a separate tokio task.
    let worker_queue = queue.clone();
    tokio::spawn(async move {
        println!("Worker pool starting.");
        let mut worker_pool = AsyncWorkerPool::builder()
            .queue(worker_queue)
            .number_of_workers(3u32)
            .build();
        worker_pool.start().await;
    });

    // Start a simple scheduler for periodic jobs.
    let scheduler_queue = queue.clone();
    tokio::spawn(async move {
        println!("Scheduler starting.");
        loop {
            // Run every 20 seconds for demo purposes.
            sleep(Duration::from_secs(20)).await;
            println!("Scheduler: Queuing CleanupJob.");
            let _ = scheduler_queue.insert_task(&CleanupJob).await;
        }
    });

    println!("Rocket server starting.");
    rocket::build()
        .manage(queue)
        .mount("/", rocket::routes![
            create_user,
            create_post,
            test_unreliable_job,
            get_job_status
        ])
}