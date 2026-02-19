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
thiserror = "1.0"
*/

// V1: The "Service-Oriented" Developer
// Organizes code into logical modules (models, services, tasks, web).
// Uses a service layer to abstract job queueing logic from web handlers.
// Employs verbose, descriptive naming for clarity.

// --- 1. Core Domain Models ---
mod models {
    use super::*;
    use chrono::{DateTime, Utc};
    use serde::{Deserialize, Serialize};
    use uuid::Uuid;

    #[derive(Debug, Serialize, Deserialize, Clone)]
    pub enum UserRole {
        ADMIN,
        USER,
    }

    #[derive(Debug, Serialize, Deserialize, Clone)]
    pub struct User {
        pub id: Uuid,
        pub email: String,
        pub password_hash: String,
        pub role: UserRole,
        pub is_active: bool,
        pub created_at: DateTime<Utc>,
    }

    #[derive(Debug, Serialize, Deserialize, Clone)]
    pub enum PostStatus {
        DRAFT,
        PUBLISHED,
    }

    #[derive(Debug, Serialize, Deserialize, Clone)]
    pub struct Post {
        pub id: Uuid,
        pub user_id: Uuid,
        pub title: String,
        pub content: String,
        pub status: PostStatus,
    }
}

// --- 2. Background Task Definitions ---
mod tasks {
    use super::models::{Post, User};
    use async_trait::async_trait;
    use fang::{typetag, AsyncRunnable, FangError};
    use serde::{Deserialize, Serialize};
    use std::time::{Duration, SystemTime};
    use tokio::time::sleep;
    use uuid::Uuid;

    #[derive(Serialize, Deserialize)]
    pub struct SendWelcomeEmailTask {
        user_id: Uuid,
        user_email: String,
    }

    #[typetag::serde]
    #[async_trait]
    impl AsyncRunnable for SendWelcomeEmailTask {
        async fn run(&self, _queue: &mut dyn fang::AsyncQueueable) -> Result<(), FangError> {
            println!(
                "TASK [SendWelcomeEmail]: Sending welcome email to user {} at '{}'.",
                self.user_id, self.user_email
            );
            // Simulate network latency for sending email
            sleep(Duration::from_secs(2)).await;
            println!("TASK [SendWelcomeEmail]: Email sent successfully.");
            Ok(())
        }
    }

    #[derive(Serialize, Deserialize)]
    pub struct ProcessPostImagePipelineTask {
        post_id: Uuid,
        image_url: String,
    }

    #[typetag::serde]
    #[async_trait]
    impl AsyncRunnable for ProcessPostImagePipelineTask {
        async fn run(&self, _queue: &mut dyn fang::AsyncQueueable) -> Result<(), FangError> {
            println!(
                "TASK [ImagePipeline]: Starting processing for post {} image '{}'.",
                self.post_id, self.image_url
            );
            // Step 1: Download
            sleep(Duration::from_millis(500)).await;
            println!("TASK [ImagePipeline]: Image downloaded.");
            // Step 2: Resize
            sleep(Duration::from_millis(1000)).await;
            println!("TASK [ImagePipeline]: Image resized to 1024x768.");
            // Step 3: Apply Watermark
            sleep(Duration::from_millis(300)).await;
            println!("TASK [ImagePipeline]: Watermark applied.");
            // Step 4: Upload to CDN
            sleep(Duration::from_millis(800)).await;
            println!("TASK [ImagePipeline]: Image uploaded to CDN.");
            Ok(())
        }
    }

    #[derive(Serialize, Deserialize)]
    pub struct CleanupInactiveUsersTask;

    #[typetag::serde]
    #[async_trait]
    impl AsyncRunnable for CleanupInactiveUsersTask {
        async fn run(&self, _queue: &mut dyn fang::AsyncQueueable) -> Result<(), FangError> {
            println!("PERIODIC TASK [CleanupUsers]: Starting check for inactive users.");
            // Simulate DB query
            sleep(Duration::from_secs(1)).await;
            println!("PERIODIC TASK [CleanupUsers]: Found 3 inactive users. Deactivating accounts.");
            sleep(Duration::from_secs(2)).await;
            println!("PERIODIC TASK [CleanupUsers]: Cleanup complete.");
            Ok(())
        }
    }

    #[derive(Serialize, Deserialize)]
    pub struct RetryableTask {
        attempt_count: u32,
    }

    impl RetryableTask {
        pub fn new() -> Self {
            Self { attempt_count: 0 }
        }
    }

    #[typetag::serde]
    #[async_trait]
    impl AsyncRunnable for RetryableTask {
        // Fang's default backoff is exponential.
        // We can control retries by returning an error.
        async fn run(&self, _queue: &mut dyn fang::AsyncQueueable) -> Result<(), FangError> {
            let now = SystemTime::now()
                .duration_since(SystemTime::UNIX_EPOCH)
                .unwrap()
                .as_secs();

            println!(
                "TASK [RetryableTask]: Attempting run. Current attempt: {}. Timestamp: {}",
                self.attempt_count, now
            );

            // Succeed only on the 3rd attempt (0, 1, 2)
            if self.attempt_count < 2 {
                let mut new_task = RetryableTask {
                    attempt_count: self.attempt_count + 1,
                };
                // To demonstrate retry, we must return an error with the modified task.
                // Fang will then re-enqueue it according to its backoff strategy.
                return Err(FangError {
                    description: format!("Simulating failure on attempt {}", self.attempt_count),
                });
            }

            println!("TASK [RetryableTask]: Succeeded on attempt {}!", self.attempt_count);
            Ok(())
        }
    }
}

// --- 3. Job Scheduling Service ---
mod services {
    use super::tasks::{
        CleanupInactiveUsersTask, ProcessPostImagePipelineTask, RetryableTask, SendWelcomeEmailTask,
    };
    use fang::{AsyncQueue, FangError};
    use uuid::Uuid;

    // A service to abstract the queueing logic
    pub struct JobService {
        queue: AsyncQueue,
    }

    impl JobService {
        pub fn new(queue: AsyncQueue) -> Self {
            Self { queue }
        }

        pub async fn enqueue_welcome_email(
            &self,
            user_id: Uuid,
            user_email: String,
        ) -> Result<(), FangError> {
            let task = SendWelcomeEmailTask {
                user_id,
                user_email,
            };
            self.queue.insert_task(&task).await
        }

        pub async fn enqueue_image_processing(
            &self,
            post_id: Uuid,
            image_url: String,
        ) -> Result<(), FangError> {
            let task = ProcessPostImagePipelineTask { post_id, image_url };
            self.queue.insert_task(&task).await
        }

        pub async fn enqueue_retryable_task(&self) -> Result<(), FangError> {
            let task = RetryableTask::new();
            self.queue.insert_task(&task).await
        }

        // This would be called by a scheduler, not a web request.
        pub async fn schedule_periodic_cleanup(&self) -> Result<(), FangError> {
            let task = CleanupInactiveUsersTask;
            self.queue.insert_task(&task).await
        }
    }
}

// --- 4. Web Layer (Rocket Handlers) ---
mod web {
    use super::models::{Post, User, UserRole};
    use super::services::JobService;
    use chrono::Utc;
    use rocket::serde::json::{json, Json, Value};
    use rocket::State;
    use uuid::Uuid;

    #[rocket::post("/users", format = "json", data = "<email>")]
    pub async fn create_user(
        email: Json<String>,
        job_service: &State<JobService>,
    ) -> Result<Json<User>, Value> {
        let new_user = User {
            id: Uuid::new_v4(),
            email: email.into_inner(),
            password_hash: "hashed_password".to_string(),
            role: UserRole::USER,
            is_active: true,
            created_at: Utc::now(),
        };

        println!("API: Creating user {}", new_user.id);

        if let Err(e) = job_service
            .enqueue_welcome_email(new_user.id, new_user.email.clone())
            .await
        {
            eprintln!("API Error: Failed to enqueue welcome email: {}", e);
            return Err(json!({"status": "error", "reason": "Failed to schedule job"}));
        }

        println!("API: Enqueued welcome email for user {}", new_user.id);
        Ok(Json(new_user))
    }

    #[rocket::post("/posts", format = "json", data = "<title>")]
    pub async fn create_post(
        title: Json<String>,
        job_service: &State<JobService>,
    ) -> Result<Json<Post>, Value> {
        let new_post = Post {
            id: Uuid::new_v4(),
            user_id: Uuid::new_v4(), // Mock user_id
            title: title.into_inner(),
            content: "This is a sample post content.".to_string(),
            status: super::models::PostStatus::PUBLISHED,
        };

        println!("API: Creating post {}", new_post.id);

        let image_url = format!("https://example.com/images/{}.jpg", new_post.id);
        if let Err(e) = job_service
            .enqueue_image_processing(new_post.id, image_url)
            .await
        {
            eprintln!("API Error: Failed to enqueue image processing: {}", e);
            return Err(json!({"status": "error", "reason": "Failed to schedule job"}));
        }

        println!("API: Enqueued image processing for post {}", new_post.id);
        Ok(Json(new_post))
    }

    #[rocket::post("/test/retry")]
    pub async fn test_retry_logic(job_service: &State<JobService>) -> Value {
        if let Err(e) = job_service.enqueue_retryable_task().await {
            eprintln!("API Error: Failed to enqueue retryable task: {}", e);
            return json!({"status": "error", "reason": "Failed to schedule job"});
        }
        json!({"status": "ok", "message": "Retryable task enqueued. Check worker logs."})
    }

    // In a real app, this would query the fang_tasks table.
    // Here, we just return a mock status.
    #[rocket::get("/jobs/<_job_id>")]
    pub async fn get_job_status(_job_id: Uuid) -> Value {
        println!("API: Checking status for job {}", _job_id);
        json!({
            "job_id": _job_id,
            "status": "completed", // Mocked status
            "updated_at": Utc::now()
        })
    }
}

// --- 5. Main Application Setup ---
use fang::{AsyncQueue, AsyncWorkerPool};
use rocket::{Build, Rocket};
use services::JobService;
use std::time::Duration;
use tokio::time::sleep;

// Mock queue for demonstration without a real DB.
// In production, you'd use `AsyncQueue::builder().uri("postgres://...").build()`
fn setup_mock_queue() -> AsyncQueue {
    AsyncQueue::builder().build()
}

async fn run_mock_worker(queue: AsyncQueue) {
    println!("Starting Mock Worker Pool...");
    let mut worker_pool = AsyncWorkerPool::builder()
        .queue(queue.clone())
        .number_of_workers(2u32)
        .build();
    worker_pool.start().await;
    println!("Mock Worker Pool Stopped.");
}

async fn run_periodic_scheduler(job_service: JobService) {
    println!("Starting Periodic Scheduler...");
    loop {
        println!("SCHEDULER: Enqueuing periodic cleanup task.");
        if let Err(e) = job_service.schedule_periodic_cleanup().await {
            eprintln!("SCHEDULER Error: Failed to enqueue cleanup task: {}", e);
        }
        // Schedule every 30 seconds for demonstration
        sleep(Duration::from_secs(30)).await;
    }
}

#[rocket::launch]
fn rocket() -> Rocket<Build> {
    let queue = setup_mock_queue();
    let job_service = JobService::new(queue.clone());

    // Spawn the worker pool as a background task
    tokio::spawn(run_mock_worker(queue.clone()));

    // Spawn the periodic task scheduler
    let scheduler_service = JobService::new(queue.clone());
    tokio::spawn(run_periodic_scheduler(scheduler_service));

    println!("Starting Rocket Server...");
    rocket::build()
        .manage(job_service)
        .mount(
            "/",
            rocket::routes![
                web::create_user,
                web::create_post,
                web::test_retry_logic,
                web::get_job_status
            ],
        )
}