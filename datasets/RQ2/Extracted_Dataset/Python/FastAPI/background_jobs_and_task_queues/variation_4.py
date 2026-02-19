import asyncio
import uuid
from datetime import datetime, timezone
from enum import Enum
from typing import Dict, Any, Optional

import uvicorn
from fastapi import FastAPI, APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, Field, BaseSettings
from arq import create_pool
from arq.connections import RedisSettings as ArqRedisSettings, ArqRedis
from arq.jobs import Job, JobStatus as ArqJobStatus

# This single file simulates a modular, production-grade project structure.
# File structure simulated:
# app/
#  ├── main.py
#  ├── core/
#  │   ├── config.py
#  │   └── worker.py
#  ├── schemas/
#  │   ├── user.py, post.py, job.py
#  ├── db/
#  │   └── session.py (mock)
#  ├── tasks/
#  │   ├── email.py, image.py, periodic.py
#  └── api/
#      └── v1/
#          ├── endpoints/
#          │   ├── users.py, posts.py, jobs.py
#          └── router.py

# --- app/core/config.py ---
class Settings(BaseSettings):
    API_V1_STR: str = "/api/v1"
    PROJECT_NAME: str = "Variation 4: Modular & Advanced"
    REDIS_HOST: str = "localhost"
    REDIS_PORT: int = 6379

    def get_redis_settings(self) -> ArqRedisSettings:
        return ArqRedisSettings(host=self.REDIS_HOST, port=self.REDIS_PORT)

    class Config:
        case_sensitive = True

settings = Settings()

# --- app/schemas/ ---
class UserRole(str, Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(str, Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

class User(BaseModel):
    id: uuid.UUID = Field(default_factory=uuid.uuid4)
    email: str
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))

class Post(BaseModel):
    id: uuid.UUID = Field(default_factory=uuid.uuid4)
    user_id: uuid.UUID
    title: str

class UserCreateRequest(BaseModel):
    email: str
    password: str

class JobDetails(BaseModel):
    job_id: str
    function: str
    status: str
    result: Optional[Any]
    enqueue_time: datetime

class JobSubmissionResponse(BaseModel):
    job_id: str

# --- app/db/session.py ---
class MockRepo:
    _users: Dict[uuid.UUID, User] = {}
    _posts: Dict[uuid.UUID, Post] = {}
    _retry_counters: Dict[str, int] = {}

    def get_user_by_email(self, email: str) -> Optional[User]:
        return next((u for u in self._users.values() if u.email == email), None)
    
    def create_user(self, email: str) -> User:
        user = User(email=email)
        self._users[user.id] = user
        return user

    def get_post(self, post_id: uuid.UUID) -> Optional[Post]:
        return self._posts.get(post_id)

    def create_post(self, post_id: uuid.UUID, user_id: uuid.UUID) -> Post:
        post = Post(id=post_id, user_id=user_id, title=f"Post {post_id}")
        self._posts[post.id] = post
        return post

    def increment_retry_counter(self, job_id: str) -> int:
        self._retry_counters[job_id] = self._retry_counters.get(job_id, 0) + 1
        return self._retry_counters[job_id]

mock_repo = MockRepo()

# --- app/tasks/email.py ---
async def send_registration_email(ctx, user_id: uuid.UUID):
    job_id = ctx['job_id']
    attempt = mock_repo.increment_retry_counter(job_id)
    if attempt == 1:
        raise RuntimeError("Email service is temporarily down")
    
    await asyncio.sleep(2)
    print(f"[{job_id}] Registration email sent for user {user_id}")
    return {"user_id": user_id, "status": "sent"}

# --- app/tasks/image.py ---
async def image_processing_pipeline(ctx, post_id: uuid.UUID):
    print(f"[{ctx['job_id']}] Starting image pipeline for post {post_id}")
    await asyncio.sleep(1)
    print(f"[{ctx['job_id']}] > Resized image")
    await asyncio.sleep(1)
    print(f"[{ctx['job_id']}] > Watermarked image")
    return {"post_id": post_id, "processed": True}

# --- app/tasks/periodic.py ---
async def monthly_data_rollup(ctx):
    print(f"[{ctx['job_id']}] CRON: Performing monthly data rollup.")
    await asyncio.sleep(5)
    print(f"[{ctx['job_id']}] CRON: Rollup complete.")
    return {"users": len(mock_repo._users), "posts": len(mock_repo._posts)}

# --- app/core/worker.py ---
class WorkerSettings:
    functions = [send_registration_email, image_processing_pipeline, monthly_data_rollup]
    redis_settings = settings.get_redis_settings()
    max_tries = 3 # Default max tries for all jobs
    job_retry_delay = 5 # 5 second delay between retries
    cron_jobs = [
        {
            'function': 'monthly_data_rollup',
            'cron': '0 0 1 * *', # At 00:00 on day-of-month 1.
            'run_at_startup': False,
        }
    ]

# --- app/api/dependencies.py ---
def get_arq_redis(request: Request) -> ArqRedis:
    return request.app.state.arq_pool

# --- app/api/v1/endpoints/users.py ---
users_router = APIRouter()

@users_router.post("/", status_code=status.HTTP_202_ACCEPTED, response_model=JobSubmissionResponse)
async def create_user_account(
    user_in: UserCreateRequest,
    arq_pool: ArqRedis = Depends(get_arq_redis)
):
    if mock_repo.get_user_by_email(user_in.email):
        raise HTTPException(status_code=400, detail="Email already registered")
    
    user = mock_repo.create_user(email=user_in.email)
    job = await arq_pool.enqueue_job("send_registration_email", user.id)
    return JobSubmissionResponse(job_id=job.job_id)

# --- app/api/v1/endpoints/posts.py ---
posts_router = APIRouter()

@posts_router.post("/{post_id}/process-image", status_code=status.HTTP_202_ACCEPTED, response_model=JobSubmissionResponse)
async def trigger_image_processing(
    post_id: uuid.UUID,
    arq_pool: ArqRedis = Depends(get_arq_redis)
):
    if not mock_repo.get_post(post_id):
        # Create a mock post if it doesn't exist
        mock_user_id = next(iter(mock_repo._users), uuid.uuid4())
        mock_repo.create_post(post_id, mock_user_id)

    job = await arq_pool.enqueue_job("image_processing_pipeline", post_id)
    return JobSubmissionResponse(job_id=job.job_id)

# --- app/api/v1/endpoints/jobs.py ---
jobs_router = APIRouter()

@jobs_router.get("/{job_id}", response_model=JobDetails)
async def get_job_info(
    job_id: str,
    arq_pool: ArqRedis = Depends(get_arq_redis)
):
    job = Job(job_id, arq_pool)
    info = await job.info()
    if not info:
        raise HTTPException(status_code=404, detail="Job not found")
    
    return JobDetails(
        job_id=job_id,
        function=info.function,
        status=info.status.value,
        result=info.result,
        enqueue_time=info.enqueue_time
    )

# --- app/api/v1/router.py ---
api_router = APIRouter()
api_router.include_router(users_router, prefix="/users", tags=["users"])
api_router.include_router(posts_router, prefix="/posts", tags=["posts"])
api_router.include_router(jobs_router, prefix="/jobs", tags=["jobs"])

# --- app/main.py ---
def create_app() -> FastAPI:
    app = FastAPI(title=settings.PROJECT_NAME)

    @app.on_event("startup")
    async def startup():
        app.state.arq_pool = await create_pool(settings.get_redis_settings())

    @app.on_event("shutdown")
    async def shutdown():
        await app.state.arq_pool.close()

    app.include_router(api_router, prefix=settings.API_V1_STR)
    return app

app = create_app()

if __name__ == "__main__":
    # To run worker: arq variation4.WorkerSettings
    uvicorn.run(app, host="0.0.0.0", port=8000)