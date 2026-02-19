import uuid
import time
import asyncio
import random
import math
import threading
from datetime import datetime, timezone
from enum import Enum
from dataclasses import dataclass, field
from typing import Coroutine, Any, Dict

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
MOCK_DB = {
    "users": {},
    "posts": {},
}

# --- Mock Async Task Functions ---

async def send_registration_email(user_id: uuid.UUID):
    """Simulates an I/O-bound email sending task."""
    user_email = MOCK_DB["users"].get(user_id, {}).get("email", "unknown@example.com")
    print(f"EMAIL_SENDER: Preparing to send email to {user_email}")
    await asyncio.sleep(random.uniform(1, 3))  # Simulate network I/O
    if random.random() < 0.2:
        raise ConnectionRefusedError("SMTP connection refused")
    print(f"EMAIL_SENDER: Email sent to {user_email}")

def cpu_bound_image_processing(post_id: uuid.UUID):
    """A blocking, CPU-intensive function."""
    print(f"IMAGE_PROCESSOR: Starting CPU-bound processing for post {post_id}")
    # Simulate heavy computation with time.sleep, as it's blocking
    time.sleep(random.uniform(2, 4))
    if random.random() < 0.3:
        raise ValueError("Invalid image format")
    print(f"IMAGE_PROCESSOR: Finished CPU-bound processing for post {post_id}")
    return "processed_image_url"

async def process_image_pipeline_async(post_id: uuid.UUID):
    """Runs the CPU-bound task in a separate thread to avoid blocking the event loop."""
    loop = asyncio.get_running_loop()
    # Use to_thread to run blocking code in a separate thread
    result = await loop.run_in_executor(
        None, cpu_bound_image_processing, post_id
    )
    return result

async def perform_daily_backup():
    """Simulates a periodic async task."""
    print(f"SCHEDULER: Starting daily backup...")
    await asyncio.sleep(5) # Simulate backup process
    print(f"SCHEDULER: Daily backup completed.")

# --- Background Job System (Asyncio) ---

class JobState(Enum):
    QUEUED = "QUEUED"
    ACTIVE = "ACTIVE"
    SUCCESS = "SUCCESS"
    ERROR = "ERROR"
    RETRY = "RETRY"

# Global state (thread-safe due to asyncio's single-threaded nature, but lock for executor access)
JOB_STATUSES: Dict[uuid.UUID, Dict[str, Any]] = {}
STATUS_LOCK = threading.Lock() # Needed because executor runs in other threads

async def worker(name: str, job_queue: asyncio.Queue):
    print(f"Worker `{name}` started.")
    while True:
        job = await job_queue.get()
        job_id, coro_func, args, kwargs, attempt = job

        with STATUS_LOCK:
            JOB_STATUSES[job_id]['status'] = JobState.ACTIVE
        print(f"Worker `{name}`: Starting job {job_id} ({coro_func.__name__}), attempt {attempt}")

        try:
            result = await coro_func(*args, **kwargs)
            with STATUS_LOCK:
                JOB_STATUSES[job_id]['status'] = JobState.SUCCESS
                JOB_STATUSES[job_id]['result'] = result
            print(f"Worker `{name}`: Job {job_id} succeeded.")
        except Exception as e:
            max_retries = 3
            if attempt < max_retries:
                backoff_duration = (2 ** attempt) * 0.5 + random.uniform(0, 0.5)
                with STATUS_LOCK:
                    JOB_STATUSES[job_id]['status'] = JobState.RETRY
                    JOB_STATUSES[job_id]['error'] = str(e)
                print(f"Worker `{name}`: Job {job_id} failed. Retrying in {backoff_duration:.2f}s.")
                await asyncio.sleep(backoff_duration)
                await job_queue.put((job_id, coro_func, args, kwargs, attempt + 1))
            else:
                with STATUS_LOCK:
                    JOB_STATUSES[job_id]['status'] = JobState.ERROR
                    JOB_STATUSES[job_id]['error'] = str(e)
                print(f"Worker `{name}`: Job {job_id} failed permanently after {max_retries} retries.")
        finally:
            job_queue.task_done()

async def scheduler(job_queue: asyncio.Queue, interval_seconds: int):
    print(f"Scheduler started, will run tasks every {interval_seconds}s.")
    while True:
        await asyncio.sleep(interval_seconds)
        print("Scheduler: Enqueuing periodic backup task.")
        await submit_job(job_queue, perform_daily_backup)

async def submit_job(job_queue: asyncio.Queue, coro_func: Coroutine, *args, **kwargs) -> uuid.UUID:
    job_id = uuid.uuid4()
    with STATUS_LOCK:
        JOB_STATUSES[job_id] = {
            'status': JobState.QUEUED,
            'created_at': datetime.now(timezone.utc),
            'result': None,
            'error': None
        }
    await job_queue.put((job_id, coro_func, args, kwargs, 1))
    print(f"Submitted job {job_id} for {coro_func.__name__}")
    return job_id

def get_job_status(job_id: uuid.UUID) -> Dict[str, Any]:
    with STATUS_LOCK:
        return JOB_STATUSES.get(job_id, {}).copy()

async def main():
    job_queue = asyncio.Queue()

    # Create worker tasks
    worker_tasks = [
        asyncio.create_task(worker(f"worker-{i}", job_queue)) for i in range(3)
    ]
    # Create scheduler task
    scheduler_task = asyncio.create_task(scheduler(job_queue, 25))

    # --- Create Mock Data ---
    test_user = User(email="async.user@example.com")
    MOCK_DB["users"][test_user.id] = {"email": test_user.email}
    test_post = Post(user_id=test_user.id, title="Async Adventures")
    MOCK_DB["posts"][test_post.id] = {"title": test_post.title}

    # --- Submit initial jobs ---
    print("\n--- Submitting initial jobs ---")
    email_job = await submit_job(job_queue, send_registration_email, user_id=test_user.id)
    image_job = await submit_job(job_queue, process_image_pipeline_async, post_id=test_post.id)

    # --- Monitor for a while ---
    print("\n--- Monitoring job statuses ---")
    for _ in range(20):
        email_stat = get_job_status(email_job).get('status', JobState.QUEUED)
        image_stat = get_job_status(image_job).get('status', JobState.QUEUED)
        print(f"STATUS -> Email: {email_stat.value}, Image: {image_stat.value}")
        if email_stat in (JobState.SUCCESS, JobState.ERROR) and image_stat in (JobState.SUCCESS, JobState.ERROR):
            break
        await asyncio.sleep(2)

    # --- Shutdown ---
    print("\n--- Shutting down ---")
    for task in worker_tasks:
        task.cancel()
    scheduler_task.cancel()
    await asyncio.gather(*worker_tasks, scheduler_task, return_exceptions=True)
    print("All tasks cancelled.")

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("Shutdown requested by user.")