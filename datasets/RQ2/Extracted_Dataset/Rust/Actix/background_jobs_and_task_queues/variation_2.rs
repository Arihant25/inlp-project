/*
Variation 2: Actor-Based & Message-Passing Approach

This version heavily utilizes the Actix actor model. Each job type has a dedicated
worker actor (`EmailWorker`, `ImageProcessingWorker`). A central `JobStatusTracker`
actor manages the state of all jobs. HTTP handlers send messages to these actors
to schedule jobs. Periodic tasks are handled using `ctx.run_interval` within an actor.
This pattern encapsulates state and logic within actors, promoting isolation.

Dependencies (add to Cargo.toml):
actix = "0.13"
actix-web = "4"
tokio = { version = "1", features = ["full"] }
serde = { version = "1.0", features = ["derive"] }
uuid = { version = "1", features = ["v4", "serde"] }
chrono = { version = "0.4", features = ["serde"] }
*/

use actix::prelude::*;
use actix_web::{web, App, HttpServer, Responder, HttpResponse};
use serde::{Serialize, Deserialize};
use uuid::Uuid;
use chrono::{DateTime, Utc};
use std::collections::HashMap;
use std::time::Duration;

// --- Domain Models ---

#[derive(Serialize, Clone, Debug)]
enum UserRole { ADMIN, USER }

#[derive(Serialize, Clone, Debug, Message)]
#[rtype(result = "()")]
struct User {
    id: Uuid,
    email: String,
    role: UserRole,
    is_active: bool,
    created_at: DateTime<Utc>,
}

#[derive(Serialize, Clone, Debug)]
enum PostStatus { DRAFT, PUBLISHED }

#[derive(Serialize, Clone, Debug, Message)]
#[rtype(result = "()")]
struct Post {
    id: Uuid,
    user_id: Uuid,
    title: String,
    content: String,
    status: PostStatus,
}

// --- Job Status & Tracking Actor ---

#[derive(Serialize, Clone, Debug)]
pub enum JobState {
    Pending,
    InProgress,
    Completed,
    Failed(String),
}

type JobId = Uuid;

#[derive(Message)]
#[rtype(result = "Option<JobState>")]
struct GetJobStatus(JobId);

#[derive(Message)]
#[rtype(result = "()")]
struct UpdateJobStatus(JobId, JobState);

#[derive(Message)]
#[rtype(result = "JobId")]
struct CreateJob;

#[derive(Message)]
#[rtype(result = "()")]
struct CleanupJobs;

struct JobStatusTracker {
    statuses: HashMap<JobId, JobState>,
}

impl Actor for JobStatusTracker {
    type Context = Context<Self>;

    fn started(&mut self, ctx: &mut Self::Context) {
        println!("JobStatusTracker actor started.");
        // Schedule periodic cleanup task
        ctx.run_interval(Duration::from_secs(60), |act, _| {
            act.statuses.retain(|_, state| !matches!(state, JobState::Completed | JobState::Failed(_)));
            println!("PERIODIC TASK: Cleaned up completed/failed jobs.");
        });
    }
}

impl Handler<GetJobStatus> for JobStatusTracker {
    type Result = Option<JobState>;
    fn handle(&mut self, msg: GetJobStatus, _ctx: &mut Context<Self>) -> Self::Result {
        self.statuses.get(&msg.0).cloned()
    }
}

impl Handler<UpdateJobStatus> for JobStatusTracker {
    type Result = ();
    fn handle(&mut self, msg: UpdateJobStatus, _ctx: &mut Context<Self>) -> Self::Result {
        println!("TRACKER: Updating status for job {} to {:?}", msg.0, msg.1);
        self.statuses.insert(msg.0, msg.1);
    }
}

impl Handler<CreateJob> for JobStatusTracker {
    type Result = JobId;
    fn handle(&mut self, _msg: CreateJob, _ctx: &mut Context<Self>) -> Self::Result {
        let job_id = Uuid::new_v4();
        self.statuses.insert(job_id, JobState::Pending);
        println!("TRACKER: Created new job with ID {}", job_id);
        job_id
    }
}

// --- Worker Actors ---

#[derive(Message)]
#[rtype(result = "()")]
struct ScheduleEmailJob {
    job_id: JobId,
    user: User,
}

struct EmailWorker {
    status_tracker: Addr<JobStatusTracker>,
}

impl Actor for EmailWorker {
    type Context = Context<Self>;
}

impl Handler<ScheduleEmailJob> for EmailWorker {
    type Result = ResponseFuture<()>;

    fn handle(&mut self, msg: ScheduleEmailJob, _ctx: &mut Context<Self>) -> Self::Result {
        let tracker = self.status_tracker.clone();
        Box::pin(async move {
            let max_retries = 3;
            for attempt in 1..=max_retries {
                tracker.do_send(UpdateJobStatus(msg.job_id, JobState::InProgress));
                println!("EMAIL WORKER: Processing job {} for {}", msg.job_id, msg.user.email);
                
                // Simulate fallible I/O
                tokio::time::sleep(Duration::from_secs(2)).await;
                let success = !msg.user.email.contains("fail");

                if success {
                    println!("EMAIL WORKER: Job {} succeeded.", msg.job_id);
                    tracker.do_send(UpdateJobStatus(msg.job_id, JobState::Completed));
                    return;
                } else {
                    println!("EMAIL WORKER: Job {} failed on attempt {}.", msg.job_id, attempt);
                    if attempt < max_retries {
                        let backoff = Duration::from_secs(2u64.pow(attempt));
                        tokio::time::sleep(backoff).await;
                    } else {
                        let error_msg = "SMTP server failed after all retries".to_string();
                        tracker.do_send(UpdateJobStatus(msg.job_id, JobState::Failed(error_msg)));
                    }
                }
            }
        })
    }
}

#[derive(Message)]
#[rtype(result = "()")]
struct ScheduleImageJob {
    job_id: JobId,
    post: Post,
}

struct ImageProcessingWorker {
    status_tracker: Addr<JobStatusTracker>,
}

impl Actor for ImageProcessingWorker {
    type Context = Context<Self>;
}

impl Handler<ScheduleImageJob> for ImageProcessingWorker {
    type Result = ResponseFuture<()>;

    fn handle(&mut self, msg: ScheduleImageJob, _ctx: &mut Context<Self>) -> Self::Result {
        let tracker = self.status_tracker.clone();
        Box::pin(async move {
            tracker.do_send(UpdateJobStatus(msg.job_id, JobState::InProgress));
            println!("IMAGE WORKER: Processing job {} for post '{}'", msg.job_id, msg.post.title);
            tokio::time::sleep(Duration::from_secs(4)).await; // Simulate work
            println!("IMAGE WORKER: Job {} succeeded.", msg.job_id);
            tracker.do_send(UpdateJobStatus(msg.job_id, JobState::Completed));
        })
    }
}

// --- API Handlers ---

async fn schedule_email(
    user_data: web::Json<User>,
    tracker: web::Data<Addr<JobStatusTracker>>,
    email_worker: web::Data<Addr<EmailWorker>>,
) -> impl Responder {
    let job_id = tracker.send(CreateJob).await.expect("Failed to create job");
    email_worker.do_send(ScheduleEmailJob { job_id, user: user_data.into_inner() });
    HttpResponse::Accepted().json(serde_json::json!({ "job_id": job_id }))
}

async fn schedule_image_processing(
    post_data: web::Json<Post>,
    tracker: web::Data<Addr<JobStatusTracker>>,
    image_worker: web::Data<Addr<ImageProcessingWorker>>,
) -> impl Responder {
    let job_id = tracker.send(CreateJob).await.expect("Failed to create job");
    image_worker.do_send(ScheduleImageJob { job_id, post: post_data.into_inner() });
    HttpResponse::Accepted().json(serde_json::json!({ "job_id": job_id }))
}

async fn get_job_status(
    job_id: web::Path<Uuid>,
    tracker: web::Data<Addr<JobStatusTracker>>,
) -> impl Responder {
    match tracker.send(GetJobStatus(*job_id)).await {
        Ok(Some(status)) => HttpResponse::Ok().json(status),
        Ok(None) => HttpResponse::NotFound().body("Job not found"),
        Err(_) => HttpResponse::InternalServerError().finish(),
    }
}

#[actix_web::main]
async fn main() -> std::io::Result<()> {
    // Start actors
    let status_tracker = JobStatusTracker { statuses: HashMap::new() }.start();
    let email_worker = EmailWorker { status_tracker: status_tracker.clone() }.start();
    let image_worker = ImageProcessingWorker { status_tracker: status_tracker.clone() }.start();

    println!("Starting server at http://127.0.0.1:8080");

    HttpServer::new(move || {
        App::new()
            .app_data(web::Data::new(status_tracker.clone()))
            .app_data(web::Data::new(email_worker.clone()))
            .app_data(web::Data::new(image_worker.clone()))
            .route("/users", web::post().to(schedule_email))
            .route("/posts", web::post().to(schedule_image_processing))
            .route("/jobs/{job_id}", web::get().to(get_job_status))
    })
    .bind("127.0.0.1:8080")?
    .run()
    .await
}