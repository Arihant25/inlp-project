import uuid
import time
import threading
import queue
import random
import math
from datetime import datetime, timezone
from enum import Enum
from dataclasses import dataclass, field
from typing import Dict, Any, Callable, Tuple

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
MOCK_DATA_STORE = {
    "users": {},
    "posts": {},
}

# --- Mock Task Functions ---

def send_email_notification(user_id: uuid.UUID, message: str):
    user_email = MOCK_DATA_STORE["users"].get(user_id, {}).get("email", "not_found@example.com")
    print(f"TASK [send_email]: Attempting to send '{message}' to {user_email}")
    time.sleep(random.uniform(1, 2))
    if random.random() < 0.25:
        raise TimeoutError("SMTP server timed out")
    print(f"TASK [send_email]: Successfully sent email to {user_email}")

def generate_post_thumbnail(post_id: uuid.UUID):
    post_title = MOCK_DATA_STORE["posts"].get(post_id, {}).get("title", "Untitled")
    print(f"TASK [thumbnail]: Generating thumbnail for post '{post_title}' ({post_id})")
    time.sleep(random.uniform(2, 4))
    if random.random() < 0.3:
        raise MemoryError("Out of memory while processing image")
    print(f"TASK [thumbnail]: Thumbnail generated for post {post_id}")

def audit_log_cleanup():
    print(f"PERIODIC [audit_cleanup]: Starting audit log cleanup...")
    time.sleep(3)
    print(f"PERIODIC [audit_cleanup]: Finished audit log cleanup.")

# --- Background Job System (Functional/Procedural with globals) ---

# Shared state
TASK_QUEUE = queue.Queue()
JOB_STATUS_REGISTRY: Dict[uuid.UUID, Dict[str, Any]] = {}
REGISTRY_LOCK = threading.Lock()
SHUTDOWN_FLAG = threading.Event()

# Constants
MAX_RETRIES = 3
BASE_BACKOFF_SECONDS = 2.0

def update_job_status(job_id: uuid.UUID, status: str, error_msg: str = None):
    with REGISTRY_LOCK:
        if job_id in JOB_STATUS_REGISTRY:
            JOB_STATUS_REGISTRY[job_id]['status'] = status
            JOB_STATUS_REGISTRY[job_id]['updated_at'] = datetime.now(timezone.utc).isoformat()
            if error_msg:
                JOB_STATUS_REGISTRY[job_id]['error'] = error_msg

def worker_thread_main():
    """Main function for each worker thread."""
    ident = threading.get_ident()
    print(f"Worker {ident} started.")
    while not SHUTDOWN_FLAG.is_set():
        try:
            job_payload = TASK_QUEUE.get(timeout=1)
            if job_payload is None:
                break

            job_id = job_payload['id']
            target_func = job_payload['target']
            args = job_payload['args']
            kwargs = job_payload['kwargs']
            current_attempt = job_payload['attempt']

            update_job_status(job_id, 'RUNNING')
            print(f"Worker {ident} processing job {job_id} ({target_func.__name__})")

            try:
                target_func(*args, **kwargs)
                update_job_status(job_id, 'COMPLETED')
                print(f"Job {job_id} finished successfully.")
            except Exception as e:
                print(f"Job {job_id} failed on attempt {current_attempt}: {e}")
                if current_attempt < MAX_RETRIES:
                    backoff = BASE_BACKOFF_SECONDS * (2 ** current_attempt) + random.uniform(0, 1)
                    update_job_status(job_id, 'RETRYING', str(e))
                    print(f"Job {job_id} will be retried in {backoff:.2f}s.")
                    time.sleep(backoff)
                    job_payload['attempt'] += 1
                    TASK_QUEUE.put(job_payload)
                else:
                    update_job_status(job_id, 'FAILED', str(e))
                    print(f"Job {job_id} has failed permanently.")
            finally:
                TASK_QUEUE.task_done()

        except queue.Empty:
            continue
    print(f"Worker {ident} shutting down.")

def scheduler_thread_main():
    """Schedules periodic tasks."""
    print("Scheduler started.")
    while not SHUTDOWN_FLAG.wait(20): # Run every 20 seconds
        print("Scheduler is queueing periodic tasks.")
        submit_task(audit_log_cleanup)
    print("Scheduler shutting down.")

def submit_task(target_func: Callable, *args: Any, **kwargs: Any) -> uuid.UUID:
    job_id = uuid.uuid4()
    job_payload = {
        'id': job_id,
        'target': target_func,
        'args': args,
        'kwargs': kwargs,
        'attempt': 1,
    }
    with REGISTRY_LOCK:
        JOB_STATUS_REGISTRY[job_id] = {
            'status': 'PENDING',
            'created_at': datetime.now(timezone.utc).isoformat(),
            'updated_at': datetime.now(timezone.utc).isoformat(),
            'error': None
        }
    TASK_QUEUE.put(job_payload)
    print(f"Submitted task {job_id} for {target_func.__name__}")
    return job_id

def get_task_status(job_id: uuid.UUID) -> Dict[str, Any]:
    with REGISTRY_LOCK:
        return JOB_STATUS_REGISTRY.get(job_id, {}).copy()

def start_job_system(worker_count: int = 3):
    threads = []
    for i in range(worker_count):
        thread = threading.Thread(target=worker_thread_main, daemon=True)
        thread.start()
        threads.append(thread)
    
    scheduler = threading.Thread(target=scheduler_thread_main, daemon=True)
    scheduler.start()
    threads.append(scheduler)
    return threads

def stop_job_system(threads: list):
    print("Stopping job system...")
    SHUTDOWN_FLAG.set()
    for _ in range(len(threads)): # One sentinel for each worker
        TASK_QUEUE.put(None)
    for t in threads:
        t.join(timeout=5)
    print("Job system stopped.")

if __name__ == "__main__":
    # --- Setup ---
    worker_threads = start_job_system(worker_count=2)

    # --- Create Mock Data ---
    user_one = User(email="user.one@example.com")
    MOCK_DATA_STORE["users"][user_one.id] = {"email": user_one.email}
    post_one = Post(user_id=user_one.id, title="A Great Adventure")
    MOCK_DATA_STORE["posts"][post_one.id] = {"title": post_one.title}

    # --- Submit Jobs ---
    print("\n--- Submitting initial tasks ---")
    email_task_id = submit_task(send_email_notification, user_id=user_one.id, message="Welcome!")
    thumb_task_id = submit_task(generate_post_thumbnail, post_id=post_one.id)
    
    # --- Monitor Status ---
    print("\n--- Monitoring task statuses ---")
    try:
        for _ in range(15):
            time.sleep(2)
            email_stat = get_task_status(email_task_id).get('status', 'NOT_FOUND')
            thumb_stat = get_task_status(thumb_task_id).get('status', 'NOT_FOUND')
            print(f"STATUS -> Email Task: {email_stat}, Thumbnail Task: {thumb_stat}")
            if email_stat in ('COMPLETED', 'FAILED') and thumb_stat in ('COMPLETED', 'FAILED'):
                print("Both tasks have reached a terminal state.")
                break
    finally:
        # --- Shutdown ---
        stop_job_system(worker_threads)