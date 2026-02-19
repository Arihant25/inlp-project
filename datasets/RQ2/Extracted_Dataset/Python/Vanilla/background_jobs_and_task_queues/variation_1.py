import uuid
import time
import threading
import queue
import random
import math
from datetime import datetime, timezone
from enum import Enum
from dataclasses import dataclass, field

# --- Domain Schema ---

class UserRole(Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

@dataclass
class User:
    id: uuid.UUID = field(default_factory=uuid.uuid4)
    email: str = ""
    password_hash: str = ""
    role: UserRole = UserRole.USER
    is_active: bool = True
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))

@dataclass
class Post:
    id: uuid.UUID = field(default_factory=uuid.uuid4)
    user_id: uuid.UUID = field(default_factory=uuid.uuid4)
    title: str = ""
    content: str = ""
    status: PostStatus = PostStatus.DRAFT

# --- Mock Data Store ---
# In a real app, this would be a database.
MOCK_DB = {
    "users": {},
    "posts": {},
}

# --- Mock Task Functions ---

def send_welcome_email(user_id: uuid.UUID):
    """Simulates sending a welcome email asynchronously."""
    user_email = MOCK_DB["users"].get(user_id, {}).get("email", "not_found@example.com")
    print(f"[{datetime.now(timezone.utc)}] Sending welcome email to {user_email}...")
    time.sleep(random.uniform(1, 3))  # Simulate network latency
    if random.random() < 0.2:  # 20% chance of failure
        raise ConnectionError("Failed to connect to SMTP server")
    print(f"[{datetime.now(timezone.utc)}] Successfully sent welcome email to {user_email}")

def process_post_image(post_id: uuid.UUID, image_data: bytes):
    """Simulates a multi-step image processing pipeline."""
    print(f"[{datetime.now(timezone.utc)}] Starting image processing for post {post_id}...")
    # Step 1: Resize
    time.sleep(random.uniform(0.5, 1))
    print(f"[{datetime.now(timezone.utc)}] -> Resized image for post {post_id}")
    # Step 2: Apply filter
    time.sleep(random.uniform(0.5, 1))
    print(f"[{datetime.now(timezone.utc)}] -> Applied filter for post {post_id}")
    # Step 3: Upload to storage
    time.sleep(random.uniform(1, 2))
    if random.random() < 0.3: # 30% chance of failure
        raise IOError("Failed to upload image to storage")
    print(f"[{datetime.now(timezone.utc)}] Finished image processing for post {post_id}")

def cleanup_inactive_users():
    """Simulates a periodic task to clean up old, inactive users."""
    print(f"[{datetime.now(timezone.utc)}] PERIODIC TASK: Running inactive user cleanup...")
    # In a real implementation, this would query the DB and deactivate users.
    time.sleep(2)
    print(f"[{datetime.now(timezone.utc)}] PERIODIC TASK: Cleanup complete.")

# --- Background Job System (OOP with Threading) ---

class JobStatus(Enum):
    PENDING = "PENDING"
    RUNNING = "RUNNING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
    RETRYING = "RETRYING"

@dataclass
class Job:
    id: uuid.UUID = field(default_factory=uuid.uuid4)
    task_func: callable = None
    args: tuple = field(default_factory=tuple)
    kwargs: dict = field(default_factory=dict)
    retries: int = 0
    max_retries: int = 3
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))

class TaskQueueManager:
    def __init__(self, num_workers=4):
        self.task_queue = queue.Queue()
        self.job_statuses = {}
        self.status_lock = threading.Lock()
        self.num_workers = num_workers
        self.workers = []
        self.scheduler_thread = None
        self.shutdown_event = threading.Event()

    def _update_status(self, job_id, status, result=None, error=None):
        with self.status_lock:
            if job_id in self.job_statuses:
                self.job_statuses[job_id]['status'] = status
                self.job_statuses[job_id]['updated_at'] = datetime.now(timezone.utc)
                if result is not None:
                    self.job_statuses[job_id]['result'] = result
                if error is not None:
                    self.job_statuses[job_id]['error'] = str(error)

    def _worker_loop(self):
        while not self.shutdown_event.is_set():
            try:
                job = self.task_queue.get(timeout=1)
                if job is None:  # Sentinel value for shutdown
                    break
                
                self._update_status(job.id, JobStatus.RUNNING)
                print(f"Worker {threading.get_ident()} picked up job {job.id} ({job.task_func.__name__})")
                
                try:
                    result = job.task_func(*job.args, **job.kwargs)
                    self._update_status(job.id, JobStatus.COMPLETED, result=result)
                    print(f"Job {job.id} completed successfully.")
                except Exception as e:
                    print(f"Job {job.id} failed: {e}")
                    if job.retries < job.max_retries:
                        job.retries += 1
                        backoff_time = (2 ** job.retries) + random.uniform(0, 1)
                        self._update_status(job.id, JobStatus.RETRYING, error=e)
                        print(f"Retrying job {job.id} in {backoff_time:.2f} seconds... (Attempt {job.retries}/{job.max_retries})")
                        time.sleep(backoff_time)
                        self.task_queue.put(job)
                    else:
                        self._update_status(job.id, JobStatus.FAILED, error=e)
                        print(f"Job {job.id} failed after {job.max_retries} retries.")
                finally:
                    self.task_queue.task_done()
            except queue.Empty:
                continue

    def _scheduler_loop(self):
        # Schedule cleanup every 15 seconds
        while not self.shutdown_event.wait(15):
            print("Scheduler: Enqueuing periodic cleanup task.")
            self.submit_job(cleanup_inactive_users)

    def start(self):
        print(f"Starting {self.num_workers} workers...")
        for _ in range(self.num_workers):
            worker = threading.Thread(target=self._worker_loop)
            worker.start()
            self.workers.append(worker)
        
        print("Starting scheduler...")
        self.scheduler_thread = threading.Thread(target=self._scheduler_loop)
        self.scheduler_thread.start()

    def shutdown(self):
        print("Shutting down task queue system...")
        self.shutdown_event.set()
        for _ in self.workers:
            self.task_queue.put(None) # Unblock workers
        
        for worker in self.workers:
            worker.join()
        
        if self.scheduler_thread:
            self.scheduler_thread.join()
        print("Shutdown complete.")

    def submit_job(self, func, *args, **kwargs):
        job = Job(task_func=func, args=args, kwargs=kwargs)
        with self.status_lock:
            self.job_statuses[job.id] = {
                'status': JobStatus.PENDING,
                'created_at': job.created_at,
                'updated_at': job.created_at,
                'result': None,
                'error': None
            }
        self.task_queue.put(job)
        print(f"Submitted job {job.id} for {func.__name__}")
        return job.id

    def get_job_status(self, job_id):
        with self.status_lock:
            return self.job_statuses.get(job_id, {}).copy()

if __name__ == "__main__":
    # --- Setup ---
    task_manager = TaskQueueManager(num_workers=3)
    task_manager.start()

    # --- Create Mock Data ---
    new_user = User(email="test.user@example.com")
    MOCK_DB["users"][new_user.id] = {"email": new_user.email}
    new_post = Post(user_id=new_user.id, title="My First Post")
    MOCK_DB["posts"][new_post.id] = {"title": new_post.title}

    # --- Submit Jobs ---
    print("\n--- Submitting initial jobs ---")
    email_job_id = task_manager.submit_job(send_welcome_email, user_id=new_user.id)
    image_job_id = task_manager.submit_job(process_post_image, post_id=new_post.id, image_data=b"fake_image_bytes")
    time.sleep(2)
    another_email_job_id = task_manager.submit_job(send_welcome_email, user_id=uuid.uuid4()) # Will fail sometimes

    # --- Monitor Status ---
    print("\n--- Monitoring job statuses ---")
    try:
        for i in range(20):
            email_status = task_manager.get_job_status(email_job_id).get('status', 'UNKNOWN')
            image_status = task_manager.get_job_status(image_job_id).get('status', 'UNKNOWN')
            print(f"Time {i*2}s: Email Job: {email_status.value}, Image Job: {image_status.value}")
            if email_status in (JobStatus.COMPLETED, JobStatus.FAILED) and \
               image_status in (JobStatus.COMPLETED, JobStatus.FAILED):
                break
            time.sleep(2)
    finally:
        # --- Shutdown ---
        task_manager.shutdown()