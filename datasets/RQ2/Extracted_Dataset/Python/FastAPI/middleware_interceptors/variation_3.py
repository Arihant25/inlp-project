import uuid
import time
import logging
import json
from enum import Enum
from datetime import datetime
from typing import List, Dict, Any

import uvicorn
from fastapi import FastAPI, Request, Depends, HTTPException
from pydantic import BaseModel, Field, ValidationError
from starlette.middleware.cors import CORSMiddleware
from starlette.responses import JSONResponse
from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.requests import Request
from starlette.responses import Response

# --- Configuration ---
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
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
MOCK_DB = {"users": {}, "posts": {}}

# --- Dependencies & Services ---
class RateLimiterService:
    def __init__(self, requests_per_minute: int):
        self.requests_per_minute = requests_per_minute
        self.history: Dict[str, List[float]] = {}

    def is_rate_limited(self, client_id: str) -> bool:
        now = time.time()
        if client_id not in self.history:
            self.history[client_id] = []
        
        # Remove timestamps older than a minute
        self.history[client_id] = [t for t in self.history[client_id] if now - t < 60]
        
        if len(self.history[client_id]) >= self.requests_per_minute:
            return True
        
        self.history[client_id].append(now)
        return False

# Singleton instance of the service
rate_limiter = RateLimiterService(requests_per_minute=20)

# --- FastAPI Application Setup ---
application = FastAPI(title="Modular Middleware Demo")

# --- Middleware Implementations (Modular Style) ---

# 1. CORS Middleware
application.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# 2. Centralized Middleware for Logging, Rate Limiting, and Response Wrapping
class UnifiedProcessingMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        # Rate Limiting
        client_ip = request.client.host
        if rate_limiter.is_rate_limited(client_ip):
            return JSONResponse(
                status_code=429,
                content={"error": "Too many requests. Please try again later."}
            )

        # Request Transformation: Add a unique ID to the request state
        request.state.request_id = str(uuid.uuid4())
        
        start_time = time.time()
        
        try:
            response = await call_next(request)
            process_time = time.time() - start_time
            response.headers["X-Process-Time-Seconds"] = str(process_time)
            response.headers["X-Request-ID"] = request.state.request_id

            # Response Transformation: Wrap successful responses
            if 200 <= response.status_code < 300 and "application/json" in response.headers.get("content-type", ""):
                response_body = b""
                async for chunk in response.body_iterator:
                    response_body += chunk
                
                data = json.loads(response_body)
                wrapped_data = {"status": "success", "data": data}
                
                return JSONResponse(
                    content=wrapped_data,
                    status_code=response.status_code,
                    headers=dict(response.headers)
                )
            
            return response
        
        finally:
            # Logging
            end_time = time.time()
            logger.info(
                f"rid={request.state.request_id} client={client_ip} method={request.method} "
                f"path={request.url.path} completed_in={end_time - start_time:.4f}s"
            )

application.add_middleware(UnifiedProcessingMiddleware)

# 3. Error Handling (Using Exception Handlers for Granularity)
@application.exception_handler(HTTPException)
async def http_exception_handler(request: Request, exc: HTTPException):
    return JSONResponse(
        status_code=exc.status_code,
        content={"status": "error", "message": exc.detail},
        headers=getattr(exc, "headers", None),
    )

@application.exception_handler(ValidationError)
async def validation_exception_handler(request: Request, exc: ValidationError):
    return JSONResponse(
        status_code=422,
        content={"status": "error", "message": "Validation failed", "details": exc.errors()},
    )

@application.exception_handler(Exception)
async def generic_exception_handler(request: Request, exc: Exception):
    request_id = getattr(request.state, "request_id", "N/A")
    logger.error(f"rid={request_id} - Unhandled exception: {exc}", exc_info=True)
    return JSONResponse(
        status_code=500,
        content={"status": "error", "message": f"An internal error occurred. Please contact support with ID: {request_id}"},
    )

# --- API Endpoints ---
@application.get("/users/{user_id}", response_model=User)
def get_user_by_id(user_id: uuid.UUID):
    user = MOCK_DB["users"].get(user_id)
    if not user:
        raise HTTPException(status_code=404, detail=f"User with ID {user_id} not found.")
    return user

@application.get("/posts", response_model=List[Post])
def get_posts():
    return list(MOCK_DB["posts"].values())

@application.get("/fail")
def fail_endpoint():
    raise RuntimeError("This is a test failure.")

# --- Application Startup ---
@application.on_event("startup")
def initialize_db():
    user_1 = User(email="test1@modular.com", password_hash="abc")
    user_2 = User(email="test2@modular.com", password_hash="def", role=UserRole.ADMIN)
    MOCK_DB["users"][user_1.id] = user_1
    MOCK_DB["users"][user_2.id] = user_2
    
    post_1 = Post(user_id=user_1.id, title="Modular Post", content="...", status=PostStatus.PUBLISHED)
    MOCK_DB["posts"][post_1.id] = post_1
    logger.info("Modular application started and DB initialized.")

if __name__ == "__main__":
    uvicorn.run(application, host="0.0.0.0", port=8002)