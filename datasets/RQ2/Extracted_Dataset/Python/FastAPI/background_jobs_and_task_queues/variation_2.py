import asyncio
import uuid
from datetime import datetime, timezone
from enum import Enum
from typing import Dict, Any, List, Optional, Protocol

import uvicorn
from fastapi import FastAPI, HTTPException, Depends, status
from pydantic import BaseModel, Field
from arq import create_pool
from arq.connections import RedisSettings, ArqRedis
from arq.jobs import Job, JobStatus as ArqJobStatus

# --- Configuration ---
# To run:
# 1. Start Redis server.
# 2. Start FastAPI app: uvicorn variation2:app --reload
# 3. Start ARQ worker: arq variation2.WorkerSettings
REDIS_SETTINGS = RedisSettings(host='localhost', port=6379)
RETRY_ATTEMPTS = {}

# --- Schemas (would be in schemas.py) ---
class UserRole(str, Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(str, Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

class User(BaseModel):
    id: uuid.UUID = Field(default_factory=uuid.uuid4)
    email: str
    password_hash: str
    role: UserRole = UserRole.USER
    is_active: bool = True
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))

class Post(BaseModel):
    id: uuid.UUID = Field(default_factory=uuid.uuid4)
    user_id: uuid.UUID
    title: str
    content: str
    status: PostStatus = PostStatus.DRAFT

class UserRegistrationPayload(BaseModel):
    email: str
    password: str

class JobInfo(BaseModel):
    job_id: str
    status: str
    result: Optional[Any] = None

# --- Mock Database (would be in db.py) ---
class MockDatabase:
    def __init__(self):
        self.users: Dict[uuid.UUID, User] = {}
        self.posts: Dict[uuid.UUID, Post] = {}

    def get_user_by_email(self, email: str) -> Optional[User]:
        return next((u for u in self.users.values() if u.email == email), None)

    def save_user(self, user: User) -> User:
        self.users[user.id] = user
        return user

db_instance = MockDatabase()

# --- Task Definitions (would be in tasks.py) ---
async def send_user_welcome_email_task(ctx, user_id: uuid.UUID):
    job_id = ctx['job_id']
    if job_id not in RETRY_ATTEMPTS:
        RETRY_ATTEMPTS[job_id] = 1
        print(f"TASK: Simulating failure for welcome email to user {user_id}")
        raise ValueError("SMTP server unavailable")
    
    await asyncio.sleep(2)
    # In a real app, you'd fetch the user from the DB
    print(f"TASK: Sent welcome email to user {user_id}")
    del RETRY_ATTEMPTS[job_id]
    return {"user_id": user_id, "delivered": True}

async def process_post_image_pipeline_task(ctx, post_id: uuid.UUID):
    print(f"TASK: Starting image pipeline for post {post_id}")
    await asyncio.sleep(1) # Simulate download
    print(f"TASK: Resizing image for post {post_id}")
    await asyncio.sleep(1) # Simulate resize
    print(f"TASK: Watermarking image for post {post_id}")
    await asyncio.sleep(1) # Simulate watermark
    print(f"TASK: Image pipeline complete for post {post_id}")
    return {"post_id": post_id, "status": "processed"}

async def run_periodic_user_report_task(ctx):
    print("CRON TASK: Generating periodic user report...")
    # In a real app, query the DB
    user_count = len(db_instance.users)
    print(f"CRON TASK: Report complete. Total users: {user_count}")
    return {"total_users": user_count}

# --- Service Layer (would be in services.py) ---
class TaskQueueService:
    def __init__(self, redis: ArqRedis):
        self.redis = redis

    async def enqueue_job(self, function_name: str, *args, **kwargs) -> Job:
        return await self.redis.enqueue_job(function_name, *args, **kwargs)

    async def get_job_info(self, job_id: str) -> JobInfo:
        job = Job(job_id, self.redis)
        status = await job.status()
        result = await job.result(timeout=1) if status == ArqJobStatus.complete else None
        return JobInfo(job_id=job_id, status=status, result=result)

class NotificationService:
    def __init__(self, task_queue: TaskQueueService):
        self.task_queue = task_queue

    async def schedule_welcome_email(self, user: User) -> Job:
        print(f"SERVICE: Scheduling welcome email for {user.email}")
        job = await self.task_queue.enqueue_job("send_user_welcome_email_task", user.id)
        return job

class ImageService:
    def __init__(self, task_queue: TaskQueueService):
        self.task_queue = task_queue

    async def schedule_image_processing(self, post_id: uuid.UUID) -> Job:
        print(f"SERVICE: Scheduling image processing for post {post_id}")
        job = await self.task_queue.enqueue_job("process_post_image_pipeline_task", post_id)
        return job

# --- FastAPI App & Dependency Injection ---
app = FastAPI(title="Variation 2: Service Layer & DI")

async def get_redis_pool() -> ArqRedis:
    return app.state.redis

def get_task_queue_service(redis: ArqRedis = Depends(get_redis_pool)) -> TaskQueueService:
    return TaskQueueService(redis)

def get_notification_service(task_queue: TaskQueueService = Depends(get_task_queue_service)) -> NotificationService:
    return NotificationService(task_queue)

def get_image_service(task_queue: TaskQueueService = Depends(get_task_queue_service)) -> ImageService:
    return ImageService(task_queue)

@app.on_event("startup")
async def startup():
    app.state.redis = await create_pool(REDIS_SETTINGS)

@app.on_event("shutdown")
async def shutdown():
    await app.state.redis.close()

# --- API Endpoints (would be in api/ or routers/) ---
@app.post("/users/register", status_code=status.HTTP_202_ACCEPTED, response_model=JobInfo)
async def register_user(
    payload: UserRegistrationPayload,
    notification_service: NotificationService = Depends(get_notification_service)
):
    if db_instance.get_user_by_email(payload.email):
        raise HTTPException(status_code=409, detail="User with this email already exists")
    
    new_user = User(email=payload.email, password_hash=f"hashed:{payload.password}")
    db_instance.save_user(new_user)
    
    job = await notification_service.schedule_welcome_email(new_user)
    return JobInfo(job_id=job.job_id, status="queued")

@app.post("/posts/{post_id}/process-image", status_code=status.HTTP_202_ACCEPTED, response_model=JobInfo)
async def process_image_for_post(
    post_id: uuid.UUID,
    image_service: ImageService = Depends(get_image_service)
):
    # Mock post creation if not exists
    if post_id not in db_instance.posts:
        mock_user_id = next(iter(db_instance.users), uuid.uuid4())
        db_instance.posts[post_id] = Post(id=post_id, user_id=mock_user_id, title="A Post", content="")

    job = await image_service.schedule_image_processing(post_id)
    return JobInfo(job_id=job.job_id, status="queued")

@app.get("/jobs/{job_id}", response_model=JobInfo)
async def fetch_job_status(
    job_id: str,
    task_queue_service: TaskQueueService = Depends(get_task_queue_service)
):
    return await task_queue_service.get_job_info(job_id)

# --- ARQ Worker Settings ---
class WorkerSettings:
    functions = [
        send_user_welcome_email_task,
        process_post_image_pipeline_task,
        run_periodic_user_report_task
    ]
    redis_settings = REDIS_SETTINGS
    max_jobs = 10
    # Retry with exponential backoff: 2s, 4s, 8s
    job_retry = 3
    job_retry_delay = 2
    job_retry_backoff = True
    cron_jobs = [
        {
            'function': 'variation2.run_periodic_user_report_task',
            'cron': '*/2 * * * *', # Every 2 minutes
        }
    ]

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)