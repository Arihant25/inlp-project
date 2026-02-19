use std::collections::HashMap;
use std::sync::{mpsc, Arc, atomic::{AtomicU128, Ordering}};
use std::thread;
use std::time::Duration;

// --- Mock UUID Generator ---
static GLOBAL_ID_COUNTER: AtomicU128 = AtomicU128::new(1);
fn generate_id() -> u128 {
    GLOBAL_ID_COUNTER.fetch_add(1, Ordering::SeqCst)
}

// --- Domain Schema ---
#[derive(Debug, Clone)]
enum UserRole { ADMIN, USER }
#[allow(dead_code)]
struct User { id: u128, email: String, password_hash: String, role: UserRole, is_active: bool, created_at: u128 }
#[derive(Debug, Clone)]
enum PostStatus { DRAFT, PUBLISHED }
#[allow(dead_code)]
struct Post { id: u128, user_id: u128, title: String, content: String, status: PostStatus }

// --- Actor-like Background Job & Task Queue Implementation ---

type JobId = u128;

#[derive(Debug, Clone)]
enum JobPayload {
    Email { to: String, body: String },
    ImageResize { path: String },
    DataCleanup,
}

#[derive(Debug, Clone)]
enum JobStatus {
    Submitted,
    Running,
    Done,
    Error { info: String, attempt: u32 },
}

// --- Messages for Actors ---

// Message to the central dispatcher
enum DispatcherMsg {
    SubmitJob(JobPayload),
    Shutdown,
}

// Message to a worker actor
#[derive(Clone)]
struct WorkOrder {
    id: JobId,
    payload: JobPayload,
}

// Message to the status tracking actor
enum StatusTrackerMsg {
    Update(JobId, JobStatus),
    Query(JobId, mpsc::Sender<Option<JobStatus>>),
}

// --- Actor Implementations ---

// StatusTracker: Owns the state of all jobs
fn status_tracker_actor(mailbox: mpsc::Receiver<StatusTrackerMsg>) {
    let mut statuses = HashMap::<JobId, JobStatus>::new();
    println!("[StatusTracker] Online.");
    for msg in mailbox {
        match msg {
            StatusTrackerMsg::Update(id, status) => {
                println!("[StatusTracker] Updating job {} to {:?}", id, status);
                statuses.insert(id, status);
            }
            StatusTrackerMsg::Query(id, reply_channel) => {
                let status = statuses.get(&id).cloned();
                reply_channel.send(status).unwrap();
            }
        }
    }
    println!("[StatusTracker] Offline.");
}

// Worker: Executes a single job and communicates status changes
fn worker_actor(id: usize, mailbox: mpsc::Receiver<WorkOrder>, status_tracker: mpsc::Sender<StatusTrackerMsg>) {
    println!("[Worker-{}] Online.", id);
    for order in mailbox {
        let job_id = order.id;
        status_tracker.send(StatusTrackerMsg::Update(job_id, JobStatus::Running)).unwrap();
        
        let mut attempt = 1;
        const MAX_ATTEMPTS: u32 = 3;

        loop {
            let result = match &order.payload {
                JobPayload::Email { to, body } => {
                    println!("[Worker-{}] Sending email '{}' to {}", id, body, to);
                    thread::sleep(Duration::from_millis(600));
                    if to.contains("fail") { Err("Simulated SMTP Error".to_string()) } else { Ok(()) }
                }
                JobPayload::ImageResize { path } => {
                    println!("[Worker-{}] Resizing image at {}", id, path);
                    thread::sleep(Duration::from_secs(2));
                    Ok(())
                }
                JobPayload::DataCleanup => {
                    println!("[Worker-{}] Performing data cleanup", id);
                    thread::sleep(Duration::from_secs(1));
                    Ok(())
                }
            };

            match result {
                Ok(_) => {
                    status_tracker.send(StatusTrackerMsg::Update(job_id, JobStatus::Done)).unwrap();
                    break;
                }
                Err(info) => {
                    status_tracker.send(StatusTrackerMsg::Update(job_id, JobStatus::Error { info: info.clone(), attempt })).unwrap();
                    if attempt >= MAX_ATTEMPTS {
                        println!("[Worker-{}] Job {} failed permanently.", id, job_id);
                        break;
                    }
                    let backoff = Duration::from_secs(2u64.pow(attempt as u32));
                    println!("[Worker-{}] Job {} failed. Retrying in {:?}...", id, job_id, backoff);
                    thread::sleep(backoff);
                    attempt += 1;
                }
            }
        }
    }
    println!("[Worker-{}] Offline.", id);
}

// Dispatcher: Receives jobs and distributes them to workers
fn dispatcher_actor(mailbox: mpsc::Receiver<DispatcherMsg>, workers: Vec<mpsc::Sender<WorkOrder>>, status_tracker: mpsc::Sender<StatusTrackerMsg>) {
    let mut next_worker = 0;
    println!("[Dispatcher] Online.");
    for msg in mailbox {
        match msg {
            DispatcherMsg::SubmitJob(payload) => {
                let job_id = generate_id();
                println!("[Dispatcher] Received job {}, assigning to Worker-{}", job_id, next_worker);
                status_tracker.send(StatusTrackerMsg::Update(job_id, JobStatus::Submitted)).unwrap();
                let order = WorkOrder { id: job_id, payload };
                workers[next_worker].send(order).unwrap();
                next_worker = (next_worker + 1) % workers.len();
            }
            DispatcherMsg::Shutdown => {
                println!("[Dispatcher] Shutdown command received.");
                break;
            }
        }
    }
    println!("[Dispatcher] Offline.");
}

// Scheduler: A simple actor that sends periodic jobs
fn scheduler_actor(dispatcher: mpsc::Sender<DispatcherMsg>) {
    println!("[Scheduler] Online.");
    loop {
        thread::sleep(Duration::from_secs(10));
        println!("[Scheduler] Triggering periodic data cleanup job.");
        dispatcher.send(DispatcherMsg::SubmitJob(JobPayload::DataCleanup)).unwrap();
    }
}

fn main() {
    // Create mailboxes (channels) for all actors
    let (dispatcher_tx, dispatcher_rx) = mpsc::channel();
    let (status_tracker_tx, status_tracker_rx) = mpsc::channel();

    // Spawn Status Tracker Actor
    let status_thread = thread::spawn(move || status_tracker_actor(status_tracker_rx));

    // Spawn Worker Actors
    let num_workers = 2;
    let mut worker_senders = Vec::new();
    let mut worker_threads = Vec::new();
    for i in 0..num_workers {
        let (worker_tx, worker_rx) = mpsc::channel();
        worker_senders.push(worker_tx);
        let status_tx_clone = status_tracker_tx.clone();
        let worker_thread = thread::spawn(move || worker_actor(i, worker_rx, status_tx_clone));
        worker_threads.push(worker_thread);
    }

    // Spawn Dispatcher Actor
    let dispatcher_thread = thread::spawn(move || dispatcher_actor(dispatcher_rx, worker_senders, status_tracker_tx.clone()));

    // Spawn Scheduler Actor
    let dispatcher_tx_clone = dispatcher_tx.clone();
    thread::spawn(move || scheduler_actor(dispatcher_tx_clone));

    // --- Main thread acts as the client, submitting jobs ---
    println!("[Main] Submitting initial jobs via Dispatcher.");
    dispatcher_tx.send(DispatcherMsg::SubmitJob(JobPayload::Email {
        to: "user@system.com".to_string(),
        body: "Welcome to the system!".to_string(),
    })).unwrap();
    
    dispatcher_tx.send(DispatcherMsg::SubmitJob(JobPayload::ImageResize {
        path: "/images/new_post.jpg".to_string(),
    })).unwrap();

    dispatcher_tx.send(DispatcherMsg::SubmitJob(JobPayload::Email {
        to: "fail@system.com".to_string(),
        body: "This message will fail and retry.".to_string(),
    })).unwrap();

    // Let the system run
    println!("[Main] System is running. Waiting for 20 seconds.");
    thread::sleep(Duration::from_secs(20));

    // To gracefully shutdown, we would send Shutdown messages and join threads.
    // For this example, we just exit.
    println!("[Main] Demo finished.");
    // In a real app:
    // dispatcher_tx.send(DispatcherMsg::Shutdown).unwrap();
    // dispatcher_thread.join().unwrap();
    // for handle in worker_threads { handle.join().unwrap(); }
    // drop(status_tracker_tx); // Close channel to let status tracker exit
    // status_thread.join().unwrap();
}