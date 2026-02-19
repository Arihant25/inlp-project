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

// V2: The "Functional & Concise" Developer
// Prefers a flatter structure, keeping related logic in handler modules.
// Avoids extra layers like a dedicated service struct, passing the queue directly.
// Uses concise naming and leverages `anyhow` for flexible error handling.

use async_trait::async_trait;
use chrono::{DateTime, Utc};
use fang::{typetag, AsyncQueue, AsyncRunnable, AsyncWorkerPool, FangError};
use rocket::serde::json::{json, Json, Value};
use rocket::{Build, Rocket, State};
use serde::{Deserialize, Serialize};
use std::time::Duration;
use tokio::time::sleep;
use uuid::Uuid;

// --- Domain Models ---
#[derive(Debug, Serialize, Deserialize, Clone)]
enum UserRole { ADMIN, USER }
#[derive(Debug, Serialize, Deserialize, Clone)]
struct User { id: Uuid, email: String, role: UserRole, created_at: DateTime<Utc> }

#[derive(Debug, Serialize, Deserialize, Clone)]
enum PostStatus { DRAFT, PUBLISHED }
#[derive(Debug, Serialize, Deserialize, Clone)]
struct Post { id: Uuid, user_id: Uuid, title: String, status: PostStatus }

// --- Task Definitions ---
#[derive(Serialize, Deserialize)]
struct EmailTask(Uuid, String); // (user_id, email)

#[typetag::serde]
#[async_trait]
impl AsyncRunnable for EmailTask {
    async fn run(&self, _q: &mut dyn fang::AsyncQueueable) -> Result<(), FangError> {
        println!("JOB: Sending welcome email to user {} ({})", self.0, self.1);
        sleep(Duration::from_secs(2)).await;
        println!("JOB: Email sent to {}", self.1);
        Ok(())
    }
}

#[derive(Serialize, Deserialize)]
struct ImageProcTask(Uuid); // (post_id)

#[typetag::serde]
#[async_trait]
impl AsyncRunnable for ImageProcTask {
    async fn run(&self, _q: &mut dyn fang::AsyncQueueable) -> Result<(), FangError> {
        println!("JOB: Processing image for post {}", self.0);
        sleep(Duration::from_millis(1500)).await;
        println!("JOB: Image processing complete for post {}", self.0);
        Ok(())
    }
}

#[derive(Serialize, Deserialize)]
struct PeriodicCleanup;

#[typetag::serde]
#[async_trait]
impl AsyncRunnable for PeriodicCleanup {
    async fn run(&self, _q: &mut dyn fang::AsyncQueueable) -> Result<(), FangError> {
        println!("CRON: Running periodic cleanup job.");
        sleep(Duration::from_secs(3)).await;
        println!("CRON: Cleanup finished.");
        Ok(())
    }
}

#[derive(Serialize, Deserialize)]
struct FlakyTask { fails_left: u8 }

#[typetag::serde]
#[async_trait]
impl AsyncRunnable for FlakyTask {
    async fn run(&self, _q: &mut dyn fang::AsyncQueueable) -> Result<(), FangError> {
        println!("JOB: Attempting flaky task. Fails left: {}", self.fails_left);
        if self.fails_left > 0 {
            // Fang automatically retries on Err.
            // We don't need to manage the state ourselves like in V1.
            // Fang will re-insert the *original* task.
            // For stateful retries, the V1 pattern is better.
            // This demonstrates simple failure/retry.
            return Err(FangError {
                description: "Service temporarily unavailable".to_string(),
            });
        }
        println!("JOB: Flaky task succeeded!");
        Ok(())
    }
}

// --- API Handlers ---
#[rocket::post("/users", format = "json", data = "<email>")]
async fn new_user(email: Json<String>, queue: &State<AsyncQueue>) -> Result<Json<User>, Value> {
    let user = User {
        id: Uuid::new_v4(),
        email: email.into_inner(),
        role: UserRole::USER,
        created_at: Utc::now(),
    };
    println!("API: User created: {}", user.id);

    let task = EmailTask(user.id, user.email.clone());
    queue.insert_task(&task).await.map_err(|e| {
        eprintln!("Failed to queue email task: {}", e);
        json!({"error": "could not queue job"})
    })?;

    Ok(Json(user))
}

#[rocket::post("/posts", format = "json", data = "<title>")]
async fn new_post(title: Json<String>, queue: &State<AsyncQueue>) -> Result<Json<Post>, Value> {
    let post = Post {
        id: Uuid::new_v4(),
        user_id: Uuid::new_v4(),
        title: title.into_inner(),
        status: PostStatus::PUBLISHED,
    };
    println!("API: Post created: {}", post.id);

    let task = ImageProcTask(post.id);
    queue.insert_task(&task).await.map_err(|e| {
        eprintln!("Failed to queue image task: {}", e);
        json!({"error": "could not queue job"})
    })?;

    Ok(Json(post))
}

#[rocket::post("/test/flaky")]
async fn test_flaky(queue: &State<AsyncQueue>) -> Value {
    let task = FlakyTask { fails_left: 3 };
    if let Err(e) = queue.insert_task(&task).await {
        return json!({"status": "error", "reason": e.to_string()});
    }
    json!({"status": "ok", "message": "Flaky task with 3 retries enqueued."})
}

#[rocket::get("/jobs/<_id>")]
async fn job_status(_id: Uuid) -> Value {
    // Mocked response for job status tracking
    json!({ "id": _id, "status": "pending" })
}

// --- Main Application ---
#[rocket::launch]
fn rocket() -> Rocket<Build> {
    // In a real app, this would connect to Postgres.
    // The in-memory queue is not persistent.
    let queue = AsyncQueue::builder().build();

    // Start worker pool in the background
    let worker_queue = queue.clone();
    tokio::spawn(async move {
        let mut worker_pool = AsyncWorkerPool::builder()
            .queue(worker_queue)
            .number_of_workers(4u32)
            .build();
        worker_pool.start().await;
    });

    // Start periodic task scheduler in the background
    let scheduler_queue = queue.clone();
    tokio::spawn(async move {
        loop {
            sleep(Duration::from_secs(45)).await;
            println!("SCHEDULER: Queuing periodic cleanup.");
            let task = PeriodicCleanup;
            if let Err(e) = scheduler_queue.insert_task(&task).await {
                eprintln!("Scheduler failed to queue task: {}", e);
            }
        }
    });

    rocket::build()
        .manage(queue)
        .mount("/", rocket::routes![new_user, new_post, test_flaky, job_status])
}