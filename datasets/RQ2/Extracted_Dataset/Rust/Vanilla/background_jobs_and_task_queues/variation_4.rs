use std::collections::{HashMap, VecDeque};
use std::sync::{Arc, Mutex, RwLock, atomic::{AtomicU128, Ordering}};
use std::thread;
use std::time::Duration;

// --- Mock ID Generator ---
static ID_GEN: AtomicU128 = AtomicU128::new(1);
fn gen_id() -> u128 {
    ID_GEN.fetch_add(1, Ordering::Relaxed)
}

// --- Domain Schema ---
#[derive(Debug)]
enum UserRole { ADMIN, USER }
#[allow(dead_code)]
struct User { id: u128, email: String, password_hash: String, role: UserRole, is_active: bool, created_at: u64 }
#[derive(Debug)]
enum PostStatus { DRAFT, PUBLISHED }
#[allow(dead_code)]
struct Post { id: u128, user_id: u128, title: String, content: String, status: PostStatus }

// --- Minimalist Background Job & Task Queue Implementation ---

type JobId = u128;

#[derive(Clone, Debug)]
enum JobType {
    AsyncEmail(String, String), // to, subject
    ImagePipeline(Vec<u8>),
    PeriodicDbScan,
}

#[derive(Clone, Debug)]
struct Job {
    id: JobId,
    job_type: JobType,
}

#[derive(Clone, Debug)]
enum JobStatus {
    Queued,
    Running,
    Succeeded,
    Failed(String),
}

// --- Shared State ---
type JobQueue = Arc<Mutex<VecDeque<Job>>>;
type StatusMap = Arc<RwLock<HashMap<JobId, JobStatus>>>;

// --- Core Logic Functions ---

fn submit_job(q: JobQueue, s: StatusMap, job_type: JobType) -> JobId {
    let job = Job { id: gen_id(), job_type };
    println!("[submit_job] Queuing job {}", job.id);
    s.write().unwrap().insert(job.id, JobStatus::Queued);
    q.lock().unwrap().push_back(job.clone());
    job.id
}

fn worker_loop(worker_id: u32, q: JobQueue, s: StatusMap) {
    println!("[worker-{}] Started.", worker_id);
    loop {
        let job_option = {
            // Lock, pop, and immediately unlock to reduce contention
            q.lock().unwrap().pop_front()
        };

        if let Some(job) = job_option {
            println!("[worker-{}] Picked up job {}", worker_id, job.id);
            s.write().unwrap().insert(job.id, JobStatus::Running);

            let mut retries = 0;
            const MAX_RETRIES: u32 = 3;
            let mut success = false;

            while retries <= MAX_RETRIES {
                let res = process_job_logic(&job.job_type);
                match res {
                    Ok(_) => {
                        s.write().unwrap().insert(job.id, JobStatus::Succeeded);
                        println!("[worker-{}] Job {} succeeded.", worker_id, job.id);
                        success = true;
                        break;
                    }
                    Err(e) => {
                        retries += 1;
                        if retries > MAX_RETRIES {
                            s.write().unwrap().insert(job.id, JobStatus::Failed(e));
                            eprintln!("[worker-{}] Job {} failed permanently.", worker_id, job.id);
                            break;
                        }
                        let backoff_secs = 2u64.pow(retries - 1);
                        eprintln!("[worker-{}] Job {} failed. Retrying in {}s (attempt {}/{})", worker_id, job.id, backoff_secs, retries, MAX_RETRIES);
                        thread::sleep(Duration::from_secs(backoff_secs));
                    }
                }
            }
        } else {
            // No job in queue, sleep to prevent busy-waiting
            thread::sleep(Duration::from_millis(500));
        }
    }
}

fn process_job_logic(job_type: &JobType) -> Result<(), String> {
    match job_type {
        JobType::AsyncEmail(to, subject) => {
            println!("  -> Processing email to '{}' with subject '{}'", to, subject);
            thread::sleep(Duration::from_millis(800)); // Simulate I/O
            if to.contains("error") {
                return Err("SMTP server unavailable".to_string());
            }
            Ok(())
        }
        JobType::ImagePipeline(data) => {
            println!("  -> Processing image pipeline for data of size {}", data.len());
            thread::sleep(Duration::from_secs(3)); // Simulate CPU work
            Ok(())
        }
        JobType::PeriodicDbScan => {
            println!("  -> Processing periodic DB scan");
            thread::sleep(Duration::from_secs(1));
            Ok(())
        }
    }
}

fn scheduler_loop(q: JobQueue, s: StatusMap) {
    println!("[scheduler] Started.");
    loop {
        thread::sleep(Duration::from_secs(15));
        println!("[scheduler] Submitting periodic job.");
        submit_job(q.clone(), s.clone(), JobType::PeriodicDbScan);
    }
}

fn main() {
    let job_q: JobQueue = Arc::new(Mutex::new(VecDeque::new()));
    let statuses: StatusMap = Arc::new(RwLock::new(HashMap::new()));

    // --- Spawn Worker Threads ---
    let mut handles = vec![];
    for i in 0..2 {
        let q_clone = Arc::clone(&job_q);
        let s_clone = Arc::clone(&statuses);
        let handle = thread::spawn(move || {
            worker_loop(i + 1, q_clone, s_clone);
        });
        handles.push(handle);
    }

    // --- Spawn Scheduler Thread ---
    let q_clone_sched = Arc::clone(&job_q);
    let s_clone_sched = Arc::clone(&statuses);
    handles.push(thread::spawn(move || {
        scheduler_loop(q_clone_sched, s_clone_sched);
    }));

    // --- Submit initial jobs from main thread ---
    println!("[main] Submitting initial set of jobs.");
    let id1 = submit_job(job_q.clone(), statuses.clone(), JobType::AsyncEmail("test@example.org".to_string(), "Hello".to_string()));
    let id2 = submit_job(job_q.clone(), statuses.clone(), JobType::ImagePipeline(vec![0; 1024]));
    let id3 = submit_job(job_q.clone(), statuses.clone(), JobType::AsyncEmail("error@example.org".to_string(), "This will fail".to_string()));
    
    println!("[main] Submitted job IDs: {}, {}, {}", id1, id2, id3);

    // --- Monitor system for a while ---
    println!("\n[main] Monitoring for 20 seconds...\n");
    for i in 0..4 {
        thread::sleep(Duration::from_secs(5));
        println!("--- STATUS CHECK at {}s ---", (i + 1) * 5);
        let s_read = statuses.read().unwrap();
        for (id, status) in s_read.iter() {
            println!("  Job {}: {:?}", id, status);
        }
        println!("--------------------------");
    }

    println!("[main] Demo finished.");
    // In a real application, we'd have a shutdown mechanism and join the handles.
}