import asyncio
import uuid
from datetime import datetime, timezone
from enum import Enum
from typing import Dict, Any, Optional

import uvicorn
from fastapi import FastAPI, HTTPException, Request, status
from pydantic import BaseModel, Field
from arq import create_pool
from arq.connections import RedisSettings, ArqRedis
from arq.worker import Job

# --- Configuration ---
# To run:
# 1. Start Redis server.
# 2. Start FastAPI app: uvicorn variation3:app --reload
# 3. Start ARQ worker: arq variation3.WorkerSettings
REDIS_SETTINGS = RedisSettings(host='localhost', port=6379)

# --- Domain Schemas ---
class UserRoleEnum(str, Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatusEnum(str, Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

class UserModel(BaseModel):
    id: uuid.UUID = Field(default_factory=uuid.uuid4)
    email: str
    password_hash: str
    role: UserRoleEnum = UserRoleEnum.USER
    is_active: bool = True
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))

class PostModel(BaseModel):
    id: uuid.UUID = Field(default_factory=uuid.uuid4)
    user_id: uuid.UUID
    title: str
    content: str
    status: PostStatusEnum = PostStatusEnum.DRAFT

class UserCreateSchema(BaseModel):
    email: str
    password: str

class JobStatusResponse(BaseModel):
    job_id: str
    is_queued: bool
    details: Optional[Dict[str, Any]] = None

# --- Mock Data Store ---
class DataStore:
    USERS: Dict[uuid.UUID, UserModel] = {}
    POSTS: Dict[uuid.UUID, PostModel] = {}
    RETRY_TRACKER: Dict[str, int] = {}

DB = DataStore()

# --- OOP Task Classes ---
class EmailTasks:
    """Encapsulates all email-related background tasks."""
    
    @staticmethod
    async def send_welcome(ctx, user_id: uuid.UUID):
        job_id = ctx['job_id']
        print(f"JOB [{job_id}] - Attempting to send welcome email to user {user_id}")

        # Simulate a failure that requires a retry
        if DB.RETRY_TRACKER.get(job_id, 0) < 2:
            DB.RETRY_TRACKER[job_id] = DB.RETRY_TRACKER.get(job_id, 0) + 1
            raise InterruptedError(f"SMTP connection timed out on attempt {DB.RETRY_TRACKER[job_id]}")

        user = DB.USERS.get(user_id)
        if not user:
            print(f"JOB [{job_id}] - User {user_id} not found. Cannot send email.")
            return {"status": "failed", "reason": "user_not_found"}
        
        await asyncio.sleep(3) # Simulate email sending
        print(f"JOB [{job_id}] - Successfully sent welcome email to {user.email}")
        del DB.RETRY_TRACKER[job_id]
        return {"status": "success", "email": user.email}

class ImageProcessingTasks:
    """A pipeline for processing images in the background."""
    
    @staticmethod
    async def process_pipeline(ctx, post_id: uuid.UUID):
        job_id = ctx['job_id']
        print(f"JOB [{job_id}] - Starting image pipeline for post {post_id}")
        
        # Step 1
        await ImageProcessingTasks._resize(post_id)
        
        # Step 2
        await ImageProcessingTasks._apply_watermark(post_id)
        
        print(f"JOB [{job_id}] - Image pipeline finished for post {post_id}")
        return {"post_id": post_id, "pipeline_status": "complete"}

    @staticmethod
    async def _resize(post_id: uuid.UUID):
        await asyncio.sleep(1.5)
        print(f"  - Resized image for post {post_id}")

    @staticmethod
    async def _apply_watermark(post_id: uuid.UUID):
        await asyncio.sleep(1.5)
        print(f"  - Applied watermark to image for post {post_id}")

class PeriodicTasks:
    """Container for cron-style jobs."""
    
    @staticmethod
    async def audit_published_posts(ctx):
        job_id = ctx['job_id']
        print(f"CRON [{job_id}] - Running periodic audit of published posts.")
        published_count = sum(1 for p in DB.POSTS.values() if p.status == PostStatusEnum.PUBLISHED)
        print(f"CRON [{job_id}] - Found {published_count} published posts.")
        return {"published_posts": published_count}

# --- FastAPI Application ---
app = FastAPI(title="Variation 3: OOP/Class-Based Tasks")

@app.on_event("startup")
async def startup_event():
    app.state.arq_pool = await create_pool(REDIS_SETTINGS)

@app.on_event("shutdown")
async def shutdown_event():
    await app.state.arq_pool.close()

def get_arq_pool(request: Request) -> ArqRedis:
    return request.app.state.arq_pool

# --- API Endpoints ---
@app.post("/users", status_code=status.HTTP_202_ACCEPTED, response_model=JobStatusResponse)
async def register_user(payload: UserCreateSchema, arq_pool: ArqRedis = Depends(get_arq_pool)):
    if any(u.email == payload.email for u in DB.USERS.values()):
        raise HTTPException(status_code=400, detail="Email is already in use.")
    
    new_user = UserModel(email=payload.email, password_hash=f"hash({payload.password})")
    DB.USERS[new_user.id] = new_user
    
    job = await arq_pool.enqueue_job("send_welcome", new_user.id)
    return JobStatusResponse(job_id=job.job_id, is_queued=True)

@app.post("/posts/{post_id}/image", status_code=status.HTTP_202_ACCEPTED, response_model=JobStatusResponse)
async def schedule_image_processing(post_id: uuid.UUID, arq_pool: ArqRedis = Depends(get_arq_pool)):
    if post_id not in DB.POSTS:
        # Create a mock post for the demo
        mock_user_id = next(iter(DB.USERS), uuid.uuid4())
        DB.POSTS[post_id] = PostModel(id=post_id, user_id=mock_user_id, title="Demo Post", content="...")
    
    job = await arq_pool.enqueue_job("process_pipeline", post_id)
    return JobStatusResponse(job_id=job.job_id, is_queued=True)

@app.get("/jobs/{job_id}")
async def get_job_details(job_id: str, arq_pool: ArqRedis = Depends(get_arq_pool)):
    job = Job(job_id, arq_pool)
    info = await job.info()
    return info

# --- ARQ Worker Settings ---
class WorkerSettings:
    # ARQ discovers methods from the classes
    functions = [EmailTasks, ImageProcessingTasks, PeriodicTasks]
    redis_settings = REDIS_SETTINGS
    
    # Retry with exponential backoff for all jobs in EmailTasks
    task_retry_delay = {
        'EmailTasks.send_welcome': 3, # delay in seconds
    }
    task_retry_backoff = {
        'EmailTasks.send_welcome': True,
    }
    task_max_tries = {
        'EmailTasks.send_welcome': 3,
    }
    
    # Cron job for auditing posts every 90 seconds
    cron_jobs = [
        {
            'function': 'audit_published_posts', # Method name is used directly
            'cron': '*/90 * * * * *', # Every 90 seconds
            'run_at_startup': True,
        }
    ]

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)