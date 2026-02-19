import uuid
import time
import logging
from enum import Enum
from datetime import datetime
from typing import List, Dict, Optional, Tuple

import uvicorn
from fastapi import FastAPI, Request, HTTPException
from pydantic import BaseModel, Field
from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.middleware.cors import CORSMiddleware
from starlette.responses import Response, JSONResponse
from starlette.status import HTTP_429_TOO_MANY_REQUESTS, HTTP_500_INTERNAL_SERVER_ERROR

# --- Configuration ---
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
log = logging.getLogger(__name__)

# --- Domain Schema ---
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
    created_at: datetime = Field(default_factory=datetime.utcnow)

class PostModel(BaseModel):
    id: uuid.UUID = Field(default_factory=uuid.uuid4)
    user_id: uuid.UUID
    title: str
    content: str
    status: PostStatusEnum = PostStatusEnum.DRAFT

# --- Mock Database ---
DB_USERS: Dict[uuid.UUID, UserModel] = {}
DB_POSTS: Dict[uuid.UUID, PostModel] = {}

# --- Middleware Implementations (OOP / Class-Based Style) ---

class GlobalErrorHandler(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        try:
            response = await call_next(request)
            return response
        except Exception as e:
            log.error(f"An unhandled error occurred: {e}", exc_info=True)
            return JSONResponse(
                status_code=HTTP_500_INTERNAL_SERVER_ERROR,
                content={"error": "An unexpected server error occurred."},
            )

class RateLimiter(BaseHTTPMiddleware):
    def __init__(self, app, max_requests: int = 10, timeframe_seconds: int = 60):
        super().__init__(app)
        self.max_requests = max_requests
        self.timeframe = timeframe_seconds
        self.request_log: Dict[str, List[float]] = {}

    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        client_ip = request.client.host
        now = time.time()

        if client_ip not in self.request_log:
            self.request_log[client_ip] = []
        
        # Evict old timestamps
        self.request_log[client_ip] = [t for t in self.request_log[client_ip] if now - t < self.timeframe]

        if len(self.request_log[client_ip]) >= self.max_requests:
            return JSONResponse(
                status_code=HTTP_429_TOO_MANY_REQUESTS,
                content={"detail": "Rate limit exceeded"},
            )

        self.request_log[client_ip].append(now)
        return await call_next(request)

class RequestContextAndLogging(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        request_id = str(uuid.uuid4())
        
        # Request Transformation: Add context to request state
        request.state.request_id = request_id
        
        start_time = time.monotonic()
        log.info(f"rid={request_id} method={request.method} path={request.url.path} client={request.client.host} - Request started")
        
        response = await call_next(request)
        
        process_time = (time.monotonic() - start_time) * 1000
        
        # Response Transformation: Add custom headers
        response.headers["X-Request-ID"] = request_id
        response.headers["X-Processing-Time-MS"] = f"{process_time:.2f}"
        
        log.info(f"rid={request_id} status={response.status_code} - Request finished in {process_time:.2f}ms")
        return response

# --- FastAPI Application Setup ---
api = FastAPI(
    title="OOP Middleware Demo",
    description="A demonstration of FastAPI middleware using a class-based approach.",
    version="1.0.0",
)

# --- Middleware Registration ---
# The order is important: outer middlewares wrap inner ones.
# Errors are caught from the top down. Requests are processed from top down.
# Responses are processed from bottom up.
api.add_middleware(GlobalErrorHandler)
api.add_middleware(RateLimiter, max_requests=5, timeframe_seconds=10)
api.add_middleware(RequestContextAndLogging)
api.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:3000"],
    allow_credentials=True,
    allow_methods=["GET", "POST"],
    allow_headers=["Authorization", "Content-Type"],
)

# --- API Endpoints ---
@api.get("/users/{user_id}", response_model=UserModel)
async def fetch_user(user_id: uuid.UUID):
    user = DB_USERS.get(user_id)
    if not user:
        raise HTTPException(status_code=404, detail="User not found")
    return user

@api.get("/posts", response_model=List[PostModel])
async def fetch_all_posts():
    return list(DB_POSTS.values())

@api.get("/crash")
async def crash_endpoint():
    raise ValueError("This is a deliberate crash for testing.")

# --- Application Lifecycle ---
@api.on_event("startup")
def on_startup():
    admin = UserModel(email="admin@oop.com", password_hash="hash1", role=UserRoleEnum.ADMIN)
    user = UserModel(email="user@oop.com", password_hash="hash2")
    DB_USERS[admin.id] = admin
    DB_USERS[user.id] = user
    
    post1 = PostModel(user_id=admin.id, title="OOP Patterns", content="Content about OOP.", status=PostStatusEnum.PUBLISHED)
    post2 = PostModel(user_id=user.id, title="My Draft", content="WIP content.")
    DB_POSTS[post1.id] = post1
    DB_POSTS[post2.id] = post2
    log.info("Application ready. Mock data loaded.")

if __name__ == "__main__":
    uvicorn.run(api, host="0.0.0.0", port=8001)