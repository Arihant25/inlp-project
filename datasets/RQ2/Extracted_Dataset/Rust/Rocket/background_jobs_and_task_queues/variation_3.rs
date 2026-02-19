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

// V3: The "Domain-Driven Design (DDD)" Developer
// Organizes code by domain (`user`, `post`, `shared`).
// Tasks are modeled as handlers for domain events (e.g., `UserRegisteredEvent`).
// Uses `thiserror` for structured, domain-specific errors in tasks.

use async_trait::async_trait;
use fang::{AsyncQueue, AsyncWorkerPool, FangError};
use rocket::{Build, Rocket, State};
use std::time::Duration;
use tokio::time::sleep;

// --- Shared Kernel ---
mod shared {
    pub mod domain {
        use chrono::{DateTime, Utc};
        use serde::{Deserialize, Serialize};
        use uuid::Uuid;

        #[derive(Debug, Serialize, Deserialize, Clone)]
        pub enum UserRole { ADMIN, USER }
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
        pub enum PostStatus { DRAFT, PUBLISHED }
        #[derive(Debug, Serialize, Deserialize, Clone)]
        pub struct Post {
            pub id: Uuid,
            pub user_id: Uuid,
            pub title: String,
            pub content: String,
            pub status: PostStatus,
        }
    }

    pub mod application {
        use fang::{AsyncQueue, FangError};
        use serde::Serialize;

        // A simple event dispatcher that enqueues events as tasks
        pub struct EventDispatcher {
            queue: AsyncQueue,
        }

        impl EventDispatcher {
            pub fn new(queue: AsyncQueue) -> Self {
                Self { queue }
            }

            pub async fn dispatch<T: Serialize + Send + Sync + 'static>(
                &self,
                event: &T,
            ) -> Result<(), FangError> {
                self.queue.insert_task(event).await
            }
        }
    }
}

// --- User Domain ---
mod user {
    use super::shared::domain::{User, UserRole};
    use async_trait::async_trait;
    use chrono::Utc;
    use fang::{typetag, AsyncRunnable, FangError};
    use rocket::serde::json::{Json, Value};
    use rocket::State;
    use serde::{Deserialize, Serialize};
    use std::time::Duration;
    use thiserror::Error;
    use tokio::time::sleep;
    use uuid::Uuid;

    // --- Domain Events as Tasks ---
    #[derive(Serialize, Deserialize)]
    pub struct UserRegisteredEvent {
        user_id: Uuid,
        email: String,
    }

    #[derive(Error, Debug)]
    pub enum NotificationError {
        #[error("SMTP service connection failed")]
        SmtpConnection,
        #[error("Invalid email format for user {0}")]
        InvalidFormat(Uuid),
    }

    #[typetag::serde]
    #[async_trait]
    impl AsyncRunnable for UserRegisteredEvent {
        async fn run(&self, _queue: &mut dyn fang::AsyncQueueable) -> Result<(), FangError> {
            println!("[Event Handler] UserRegistered: Sending welcome notification to {}.", self.email);
            sleep(Duration::from_secs(2)).await;
            // Simulate a possible failure
            if self.email.contains("fail") {
                return Err(NotificationError::SmtpConnection.into());
            }
            println!("[Event Handler] UserRegistered: Notification sent for user {}.", self.user_id);
            Ok(())
        }
    }

    // --- Application Service ---
    pub struct UserService;

    impl UserService {
        pub fn register_user(email: String) -> User {
            User {
                id: Uuid::new_v4(),
                email,
                password_hash: "secret".to_string(),
                role: UserRole::USER,
                is_active: false,
                created_at: Utc::now(),
            }
        }
    }

    // --- API Endpoint ---
    #[rocket::post("/users", format = "json", data = "<email>")]
    pub async fn register_user_endpoint(
        email: Json<String>,
        dispatcher: &State<super::shared::application::EventDispatcher>,
    ) -> Result<Json<User>, Value> {
        let user = UserService::register_user(email.into_inner());
        println!("[API] User registered: {}", user.id);

        let event = UserRegisteredEvent {
            user_id: user.id,
            email: user.email.clone(),
        };

        dispatcher.dispatch(&event).await.unwrap(); // Handle error in prod
        println!("[API] Dispatched UserRegisteredEvent for {}", user.id);

        Ok(Json(user))
    }
}

// --- Post Domain ---
mod post {
    use super::shared::domain::{Post, PostStatus};
    use async_trait::async_trait;
    use chrono::Utc;
    use fang::{typetag, AsyncRunnable, FangError};
    use rocket::serde::json::{Json, Value};
    use rocket::State;
    use serde::{Deserialize, Serialize};
    use std::time::Duration;
    use thiserror::Error;
    use tokio::time::sleep;
    use uuid::Uuid;

    // --- Domain Events as Tasks ---
    #[derive(Serialize, Deserialize)]
    pub struct PostPublishedEvent {
        post_id: Uuid,
        user_id: Uuid,
    }

    #[derive(Error, Debug)]
    pub enum ProcessingError {
        #[error("Failed to download image for post {0}")]
        DownloadFailed(Uuid),
        #[error("CDN upload failed for post {0}")]
        UploadFailed(Uuid),
    }

    impl From<ProcessingError> for FangError {
        fn from(error: ProcessingError) -> Self {
            FangError {
                description: error.to_string(),
            }
        }
    }

    #[typetag::serde]
    #[async_trait]
    impl AsyncRunnable for PostPublishedEvent {
        // This task demonstrates retry logic. Fang retries on Err.
        fn max_retries(&self) -> i32 { 5 }

        async fn run(&self, _queue: &mut dyn fang::AsyncQueueable) -> Result<(), FangError> {
            println!("[Event Handler] PostPublished: Processing media for post {}.", self.post_id);
            sleep(Duration::from_secs(3)).await;
            // Simulate a transient failure
            if self.post_id.as_u128() % 2 == 0 {
                 println!("[Event Handler] PostPublished: Simulating CDN failure for post {}.", self.post_id);
                 return Err(ProcessingError::UploadFailed(self.post_id).into());
            }
            println!("[Event Handler] PostPublished: Media processing complete for post {}.", self.post_id);
            Ok(())
        }
    }

    // --- API Endpoint ---
    #[rocket::post("/posts", format = "json", data = "<title>")]
    pub async fn publish_post_endpoint(
        title: Json<String>,
        dispatcher: &State<super::shared::application::EventDispatcher>,
    ) -> Json<Post> {
        let post = Post {
            id: Uuid::new_v4(),
            user_id: Uuid::new_v4(),
            title: title.into_inner(),
            content: "...".to_string(),
            status: PostStatus::PUBLISHED,
        };
        println!("[API] Post published: {}", post.id);

        let event = PostPublishedEvent {
            post_id: post.id,
            user_id: post.user_id,
        };
        dispatcher.dispatch(&event).await.unwrap();
        println!("[API] Dispatched PostPublishedEvent for {}", post.id);

        Json(post)
    }
}

// --- System Operations (Periodic Tasks) ---
mod system_ops {
    use async_trait::async_trait;
    use fang::{typetag, AsyncRunnable, FangError};
    use serde::{Deserialize, Serialize};
    use std::time::Duration;
    use tokio::time::sleep;

    #[derive(Serialize, Deserialize)]
    pub struct NightlyAudit;

    #[typetag::serde]
    #[async_trait]
    impl AsyncRunnable for NightlyAudit {
        async fn run(&self, _q: &mut dyn fang::AsyncQueueable) -> Result<(), FangError> {
            println!("[CRON] Starting nightly audit task.");
            sleep(Duration::from_secs(5)).await;
            println!("[CRON] Nightly audit complete.");
            Ok(())
        }
    }
}

// --- Main Application ---
#[rocket::launch]
fn rocket() -> Rocket<Build> {
    let queue = AsyncQueue::builder().build();
    let event_dispatcher = shared::application::EventDispatcher::new(queue.clone());

    // Start worker pool
    let worker_queue = queue.clone();
    tokio::spawn(async move {
        AsyncWorkerPool::builder()
            .queue(worker_queue)
            .number_of_workers(2u32)
            .start()
            .await;
    });

    // Start cron scheduler
    let cron_queue = queue.clone();
    tokio::spawn(async move {
        loop {
            // Run every 60 seconds for demo
            sleep(Duration::from_secs(60)).await;
            println!("[Scheduler] Enqueuing nightly audit.");
            let _ = cron_queue.insert_task(&system_ops::NightlyAudit).await;
        }
    });

    rocket::build()
        .manage(event_dispatcher)
        .mount("/", rocket::routes![
            user::register_user_endpoint,
            post::publish_post_endpoint
        ])
}