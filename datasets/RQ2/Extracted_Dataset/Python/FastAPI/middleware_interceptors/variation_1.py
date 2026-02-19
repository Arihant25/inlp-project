import uuid
import time
import logging
from enum import Enum
from datetime import datetime
from typing import List, Dict, Optional

import uvicorn
from fastapi import FastAPI, Request, HTTPException, Response
from pydantic import BaseModel, Field
from starlette.middleware.cors import CORSMiddleware
from starlette.status import HTTP_429_TOO_MANY_REQUESTS, HTTP_500_INTERNAL_SERVER_ERROR

# --- Configuration ---
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(name)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

# --- Domain Schema ---
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
    created_at: datetime = Field(default_factory=datetime.utcnow)

class Post(BaseModel):
    id: uuid.UUID = Field(default_factory=uuid.uuid4)
    user_id: uuid.UUID
    title: str
    content: str
    status: PostStatus = PostStatus.DRAFT

# --- Mock Database ---
MOCK_USERS_DB: Dict[uuid.UUID, User] = {}
MOCK_POSTS_DB: Dict[uuid.UUID, Post] = {}

def populate_mock_db():
    admin_user = User(email="admin@example.com", password_hash="hashed_admin_pass", role=UserRole.ADMIN)
    regular_user = User(email="user@example.com", password_hash="hashed_user_pass")
    MOCK_USERS_DB[admin_user.id] = admin_user
    MOCK_USERS_DB[regular_user.id] = regular_user

    post1 = Post(user_id=admin_user.id, title="FastAPI Middleware", content="An article about middleware patterns.", status=PostStatus.PUBLISHED)
    post2 = Post(user_id=regular_user.id, title="Draft Post", content="This is a work in progress.")
    MOCK_POSTS_DB[post1.id] = post1
    MOCK_POSTS_DB[post2.id] = post2

# --- FastAPI Application Setup ---
app = FastAPI(
    title="Functional Middleware Demo",
    description="A demonstration of FastAPI middleware using a functional approach.",
    version="1.0.0",
)

# --- Middleware Implementations (Functional Style) ---

# 1. CORS Handling Middleware (Built-in)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # In production, restrict this to specific domains
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# 2. Rate Limiting Middleware (Custom)
RATE_LIMIT_MAX_REQUESTS = 5
RATE_LIMIT_TIMEFRAME_SECONDS = 10
request_counts: Dict[str, List[float]] = {}

@app.middleware("http")
async def rate_limiting_middleware(request: Request, call_next):
    client_ip = request.client.host
    current_time = time.time()

    if client_ip not in request_counts:
        request_counts[client_ip] = []

    # Filter out timestamps outside the current timeframe
    request_counts[client_ip] = [
        t for t in request_counts[client_ip] if current_time - t < RATE_LIMIT_TIMEFRAME_SECONDS
    ]

    if len(request_counts[client_ip]) >= RATE_LIMIT_MAX_REQUESTS:
        return Response("Too Many Requests", status_code=HTTP_429_TOO_MANY_REQUESTS)

    request_counts[client_ip].append(current_time)
    response = await call_next(request)
    return response

# 3. Request Logging & Response Transformation Middleware (Custom)
@app.middleware("http")
async def logging_transform_middleware(request: Request, call_next):
    request_id = str(uuid.uuid4())
    logger.info(f"req_id={request_id} | START request | path={request.url.path} | method={request.method}")
    start_time = time.time()
    
    response = await call_next(request)
    
    process_time = (time.time() - start_time) * 1000
    formatted_process_time = f"{process_time:.2f}ms"
    
    # Response Transformation: Add custom headers
    response.headers["X-Request-ID"] = request_id
    response.headers["X-Process-Time"] = formatted_process_time
    
    logger.info(f"req_id={request_id} | END request | status_code={response.status_code} | process_time={formatted_process_time}")
    return response

# 4. Error Handling Middleware (Custom)
@app.middleware("http")
async def error_handling_middleware(request: Request, call_next):
    try:
        return await call_next(request)
    except Exception as e:
        logger.error(f"Unhandled exception for {request.url.path}: {e}", exc_info=True)
        return Response(
            content='{"error": "Internal Server Error"}',
            status_code=HTTP_500_INTERNAL_SERVER_ERROR,
            media_type="application/json",
        )

# --- API Endpoints ---
@app.get("/users/{user_id}", response_model=User)
async def get_user(user_id: uuid.UUID):
    user = MOCK_USERS_DB.get(user_id)
    if not user:
        raise HTTPException(status_code=404, detail="User not found")
    return user

@app.get("/posts", response_model=List[Post])
async def get_all_posts():
    return list(MOCK_POSTS_DB.values())

@app.get("/error")
async def trigger_error():
    # This endpoint is for demonstrating the error handling middleware
    result = 1 / 0
    return {"result": result}

# --- Application Startup ---
@app.on_event("startup")
async def startup_event():
    populate_mock_db()
    logger.info("Application startup complete. Mock database populated.")

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)