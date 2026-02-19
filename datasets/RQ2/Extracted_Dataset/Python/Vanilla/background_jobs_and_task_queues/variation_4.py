import uuid
import time
import threading
import queue
import random
import math
from datetime import datetime, timezone
from enum import Enum
from dataclasses import dataclass, field
from concurrent.futures import ThreadPoolExecutor

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
MOCK_DATABASE = {
    "users": {},
    "posts": {},
}

# --- Mock Task Functions ---

def send_password_reset_email(user_email: str):
    """Simulates sending a password reset email."""
    print(f"EMAIL_SERVICE: Sending password reset to {user_email}.")
    time.sleep(random.uniform(1, 2.5))
    if random.random() < 0.2:
        raise RuntimeError("Mail server is temporarily unavailable")
    print(f"EMAIL_SERVICE: Email successfully sent to {user_email}.")

def process_video_for_post(post_id: uuid.UUID):
    """Simulates a long-running video processing task."""
    print(f"VIDEO_PIPELINE: Starting transcoding for post {post_id}.")
    time.sleep(random.uniform(3, 5))
    print(f"VIDEO_PIPELINE: -> Step 1/3: Re-encoding complete.")
    time.sleep(random.uniform(1, 2))
    print(f"VIDEO_PIPELINE: -> Step 2/3: Watermark applied.")
    if random.random() < 0.3:
        raise BufferError("Processing buffer corrupted")
    time.sleep(random.uniform(1, 2))
    print(f"VIDEO_PIPELINE: -> Step 3/3: Uploaded to CDN.")
    print(f"VIDEO_PIPELINE: Transcoding finished for post {post_id}.")

def generate_monthly_report():
    """Simulates a periodic report generation task."""
    print(f"REPORTS: Generating monthly activity report...")
    time.sleep(4)
    print(f"REPORTS: Monthly report generated and saved.")

# --- Background Job System (concurrent.futures.ThreadPoolExecutor) ---

class JobStatusEnum(Enum):
    PENDING = "PENDING"
    DISPATCHED = "DISPATCHED"
    SUCCESS = "SUCCESS"
    FAILED = "FAILED"
    RETRY_SCHEDULED = "RETRY_SCHEDULED"

class JobManager:
    def __init__(self, max_workers=5):
        self.executor = ThreadPoolExecutor(max_workers=max_workers, thread_name_prefix="JobWorker")
        self.pending_jobs = queue.Queue()
        self.job_registry = {}
        self.registry_lock = threading.Lock()
        self.shutdown_event = threading.Event()
        self.dispatcher_thread = threading.Thread(target=self._dispatcher_loop)
        self.scheduler_thread = threading.Thread(target=self._scheduler_loop)

    def _update_status(self, job_id, status, error=None):
        with self.registry_lock:
            if job_id in self.job_registry:
                self.job_registry[job_id]['status'] = status
                if error:
                    self.job_registry[job_id]['last_error'] = str(error)

    def _task_wrapper(self, job_id, func, args, kwargs):
        """Wraps the actual task to handle execution, retries, and status updates."""
        try:
            func(*args, **kwargs)
            self._update_status(job_id, JobStatusEnum.SUCCESS)
            print(f"Job {job_id} ({func.__name__}) completed successfully.")
        except Exception as e:
            print(f"Job {job_id} ({func.__name__}) failed with error: {e}")
            with self.registry_lock:
                job_info = self.job_registry.get(job_id)
                if not job_info: return
                
                current_attempt = job_info['attempts']
                max_attempts = job_info['max_attempts']

                if current_attempt < max_attempts:
                    job_info['attempts'] += 1
                    backoff = (2 ** current_attempt) + random.uniform(0, 1)
                    self._update_status(job_id, JobStatusEnum.RETRY_SCHEDULED, error=e)
                    print(f"Scheduling retry {current_attempt + 1}/{max_attempts} for job {job_id} in {backoff:.2f}s.")
                    # Re-queue the job after a delay
                    time.sleep(backoff)
                    self.pending_jobs.put(job_info['original_task'])
                else:
                    self._update_status(job_id, JobStatusEnum.FAILED, error=e)
                    print(f"Job {job_id} has reached max retries and failed permanently.")

    def _dispatcher_loop(self):
        """Pulls from the internal queue and submits to the ThreadPoolExecutor."""
        print("Dispatcher started.")
        while not self.shutdown_event.is_set():
            try:
                task_info = self.pending_jobs.get(timeout=1)
                job_id = task_info['id']
                self._update_status(job_id, JobStatusEnum.DISPATCHED)
                self.executor.submit(self._task_wrapper, job_id, *task_info['call'])
            except queue.Empty:
                continue
        print("Dispatcher shutting down.")

    def _scheduler_loop(self):
        """Periodically submits recurring jobs."""
        print("Scheduler started.")
        while not self.shutdown_event.wait(30): # Every 30 seconds
            print("Scheduler: Submitting monthly report job.")
            self.submit(generate_monthly_report)
        print("Scheduler shutting down.")

    def start(self):
        self.dispatcher_thread.start()
        self.scheduler_thread.start()

    def shutdown(self):
        print("Shutting down Job Manager...")
        self.shutdown_event.set()
        self.executor.shutdown(wait=True)
        self.dispatcher_thread.join()
        self.scheduler_thread.join()
        print("Job Manager shut down.")

    def submit(self, func, *args, **kwargs):
        job_id = uuid.uuid4()
        task_info = {
            'id': job_id,
            'call': (func, args, kwargs)
        }
        with self.registry_lock:
            self.job_registry[job_id] = {
                'status': JobStatusEnum.PENDING,
                'attempts': 1,
                'max_attempts': 3,
                'last_error': None,
                'original_task': task_info
            }
        self.pending_jobs.put(task_info)
        print(f"Enqueued job {job_id} for {func.__name__}")
        return job_id

    def get_status(self, job_id):
        with self.registry_lock:
            status_info = self.job_registry.get(job_id)
            return {k: v for k, v in status_info.items() if k != 'original_task'} if status_info else {}

if __name__ == "__main__":
    # --- Setup ---
    job_manager = JobManager(max_workers=3)
    job_manager.start()

    # --- Create Mock Data ---
    user = User(email="video.user@example.com")
    MOCK_DATABASE["users"][user.id] = {"email": user.email}
    post = Post(user_id=user.id, title="My Awesome Video")
    MOCK_DATABASE["posts"][post.id] = {"title": post.title}

    # --- Submit Jobs ---
    print("\n--- Submitting initial jobs ---")
    email_id = job_manager.submit(send_password_reset_email, user_email=user.email)
    video_id = job_manager.submit(process_video_for_post, post_id=post.id)

    # --- Monitor Status ---
    print("\n--- Monitoring job statuses ---")
    try:
        for i in range(25):
            email_status = job_manager.get_status(email_id).get('status', 'UNKNOWN')
            video_status = job_manager.get_status(video_id).get('status', 'UNKNOWN')
            print(f"Time {i*2}s: Email Job: {email_status.value}, Video Job: {video_status.value}")
            if email_status in (JobStatusEnum.SUCCESS, JobStatusEnum.FAILED) and \
               video_status in (JobStatusEnum.SUCCESS, JobStatusEnum.FAILED):
                print("All initial jobs have completed or failed.")
                break
            time.sleep(2)
    finally:
        # --- Shutdown ---
        job_manager.shutdown()