import asyncio
import uuid
from datetime import datetime, timezone
from enum import Enum
from typing import Dict, Any, List, Optional
from PIL import Image, ImageDraw

import uvicorn
from fastapi import FastAPI, HTTPException, BackgroundTasks, status
from pydantic import BaseModel, Field
from arq import create_pool
from arq.connections import RedisSettings, ArqRedis
from arq.jobs import Job

# --- Configuration ---
# To run:
# 1. Start Redis server.
# 2. Start FastAPI app: uvicorn variation1:app --reload
# 3. Start ARQ worker: arq variation1.WorkerSettings
REDIS_SETTINGS = RedisSettings(host='localhost', port=6379)
RETRY_ATTEMPTS = {} # In-memory dict to simulate transient failures for retry demo

# --- Domain Models & Schemas ---
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

class UserCreate(BaseModel):
    email: str
    password: str

class JobStatus(BaseModel):
    job_id: str
    status: str
    result: Any

# --- Mock Database ---
DB: Dict[str, Dict[uuid.UUID, Any]] = {
    "users": {},
    "posts": {},
}

# --- Background Task Definitions ---
async def send_welcome_email(ctx, user_id: uuid.UUID):
    """
    Sends a welcome email to the user.
    Demonstrates retry logic with exponential backoff.
    """
    arq_pool: ArqRedis = ctx['redis']
    job_id = ctx['job_id']
    
    # Simulate a transient error on the first attempt
    if job_id not in RETRY_ATTEMPTS:
        RETRY_ATTEMPTS[job_id] = 1
        print(f"Job {job_id}: Simulating email failure for user {user_id}. Retrying...")
        raise ConnectionError("Failed to connect to SMTP server")

    await asyncio.sleep(2)  # Simulate network latency
    user_email = DB["users"].get(user_id).email
    print(f"Job {job_id}: Successfully sent welcome email to {user_email}")
    
    # Clean up retry tracker on success
    if job_id in RETRY_ATTEMPTS:
        del RETRY_ATTEMPTS[job_id]
        
    return {"email_to": user_email, "status": "sent"}

async def process_post_image(ctx, post_id: uuid.UUID, image_path: str):
    """
    A pipeline of image processing tasks.
    """
    print(f"Job {ctx['job_id']}: Starting image processing pipeline for post {post_id}")
    
    # Step 1: Resize image
    await resize_image(image_path, (800, 600))
    print(f"Job {ctx['job_id']}: Resized image {image_path}")
    
    # Step 2: Watermark image
    await watermark_image(image_path, "MyAwesomeApp")
    print(f"Job {ctx['job_id']}: Watermarked image {image_path}")
    
    print(f"Job {ctx['job_id']}: Image processing pipeline complete for post {post_id}")
    return {"post_id": post_id, "processed_image": image_path, "status": "completed"}

async def resize_image(path: str, size: tuple):
    await asyncio.sleep(1) # Simulate I/O
    with Image.open(path) as img:
        img.thumbnail(size)
        img.save(path)

async def watermark_image(path: str, text: str):
    await asyncio.sleep(1) # Simulate I/O
    with Image.open(path) as img:
        draw = ImageDraw.Draw(img)
        draw.text((10, 10), text, fill=(255, 255, 255, 128))
        img.save(path)

async def cleanup_inactive_users(ctx):
    """
    A periodic task to clean up inactive users.
    """
    print(f"Cron Job {ctx['job_id']}: Running periodic cleanup of inactive users...")
    inactive_users = [u for u in DB["users"].values() if not u.is_active]
    # In a real app, you would delete them from the DB
    print(f"Cron Job {ctx['job_id']}: Found {len(inactive_users)} inactive users to cleanup.")
    return {"cleaned_count": len(inactive_users)}

# --- FastAPI Application ---
app = FastAPI(title="Variation 1: Functional Style")

@app.on_event("startup")
async def startup():
    app.state.redis = await create_pool(REDIS_SETTINGS)

@app.on_event("shutdown")
async def shutdown():
    await app.state.redis.close()

# --- API Endpoints ---
@app.post("/users", status_code=status.HTTP_202_ACCEPTED, response_model=JobStatus)
async def create_user(user_data: UserCreate):
    """
    Creates a user and enqueues a background job to send a welcome email.
    """
    if any(u.email == user_data.email for u in DB["users"].values()):
        raise HTTPException(status_code=400, detail="Email already registered")
    
    new_user = User(email=user_data.email, password_hash="hashed_" + user_data.password)
    DB["users"][new_user.id] = new_user

    redis: ArqRedis = app.state.redis
    job = await redis.enqueue_job("send_welcome_email", new_user.id)
    
    return JobStatus(job_id=job.job_id, status="queued", result=None)

@app.post("/posts/{post_id}/image", status_code=status.HTTP_202_ACCEPTED, response_model=JobStatus)
async def upload_post_image(post_id: uuid.UUID):
    """
    "Uploads" an image for a post and starts a processing pipeline.
    """
    if post_id not in DB["posts"]:
        # Create a mock post for demonstration
        mock_user_id = next(iter(DB["users"]), None)
        if not mock_user_id:
            mock_user = User(email="mock@example.com", password_hash="mock")
            DB["users"][mock_user.id] = mock_user
            mock_user_id = mock_user.id
        DB["posts"][post_id] = Post(id=post_id, user_id=mock_user_id, title="Mock Post", content="...")

    # Create a dummy image file for processing
    image_path = f"/tmp/{uuid.uuid4()}.png"
    img = Image.new('RGB', (1024, 768), color = 'red')
    img.save(image_path)

    redis: ArqRedis = app.state.redis
    job = await redis.enqueue_job("process_post_image", post_id, image_path)
    
    return JobStatus(job_id=job.job_id, status="queued", result=None)

@app.get("/jobs/{job_id}", response_model=JobStatus)
async def get_job_status(job_id: str):
    """
    Tracks the status of a background job.
    """
    redis: ArqRedis = app.state.redis
    job = Job(job_id, redis)
    job_status = await job.status()
    result = None
    if job_status == 'complete':
        result = await job.result()

    return JobStatus(job_id=job_id, status=job_status, result=result)

# --- ARQ Worker Settings ---
class WorkerSettings:
    functions = [send_welcome_email, process_post_image, cleanup_inactive_users]
    redis_settings = REDIS_SETTINGS
    job_timeout = 60
    # Exponential backoff: 5s, 10s, 15s
    job_retry_delay = 5 
    # Cron job to run every minute for demonstration
    cron_jobs = [
        {
            'function': 'variation1.cleanup_inactive_users',
            'cron': '* * * * *',
            'run_at_startup': True,
        }
    ]
    
    async def on_job_failure(self, ctx, exc):
        print(f"Job {ctx['job_id']} failed with exception: {exc}")

    async def on_job_success(self, ctx, result):
        print(f"Job {ctx['job_id']} completed successfully.")

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)