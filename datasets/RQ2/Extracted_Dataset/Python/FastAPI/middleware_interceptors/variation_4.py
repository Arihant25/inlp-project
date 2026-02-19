# This variation requires an external package: pip install slowapi
import uuid
import time
import logging
from enum import Enum
from datetime import datetime
from typing import List, Dict

import uvicorn
from fastapi import FastAPI, Request, HTTPException
from pydantic import BaseModel, Field
from starlette.middleware.cors import CORSMiddleware
from starlette.responses import JSONResponse

# --- 3rd Party Library for Rate Limiting ---
from slowapi import Limiter, _rate_limit_exceeded_handler
from slowapi.util import get_remote_address
from slowapi.errors import RateLimitExceeded

# --- Basic Setup ---
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(name)s - %(levelname)s - %(message)s')
log = logging.getLogger(__name__)

# --- Domain Schema ---
class UserRole(str, Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(str, Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

class User(BaseModel):
    id: uuid.UUID
    email: str
    password_hash: str
    role: UserRole
    is_active: bool
    created_at: datetime

class Post(BaseModel):
    id: uuid.UUID
    user_id: uuid.UUID
    title: str
    content: str
    status: PostStatus

# --- Mock Data Store ---
DATASTORE: Dict[str, Dict[uuid.UUID, BaseModel]] = {"users": {}, "posts": {}}

# --- Rate Limiter Setup (Modern approach using a library) ---
limiter = Limiter(key_func=get_remote_address, default_limits=["10/minute"])

# --- FastAPI App Initialization ---
app = FastAPI(title="Concise & Modern Middleware")
app.state.limiter = limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)

# --- Middleware Registration ---

# 1. CORS Middleware (Standard)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# 2. Unified Middleware for Logging, Error Handling, and Transformations
@app.middleware("http")
async def unified_processing_middleware(request: Request, call_next):
    # Request Transformation: Inject a unique ID into the request state
    request_id = str(uuid.uuid4())
    request.state.request_id = request_id
    
    start_time = time.perf_counter()
    
    log.info(f"req_id={request_id} incoming_request path={request.url.path}")
    
    try:
        response = await call_next(request)
        
        # Response Transformation: Add custom headers
        duration_ms = (time.perf_counter() - start_time) * 1000
        response.headers["X-Request-ID"] = request_id
        response.headers["X-Response-Time-ms"] = f"{duration_ms:.2f}"
        
        log.info(f"req_id={request_id} request_finished status={response.status_code} duration={duration_ms:.2f}ms")
        
        return response
        
    except Exception as e:
        # Centralized Error Handling
        log.error(f"req_id={request_id} unhandled_exception error='{e}'", exc_info=True)
        return JSONResponse(
            status_code=500,
            content={
                "error": "Internal Server Error",
                "request_id": request_id
            }
        )

# --- API Endpoints ---
@app.get("/users/{user_id}", response_model=User)
@limiter.limit("5/minute")  # Endpoint-specific rate limit
async def get_user(request: Request, user_id: uuid.UUID):
    user = DATASTORE["users"].get(user_id)
    if not user:
        raise HTTPException(status_code=404, detail="User not found")
    return user

@app.get("/posts", response_model=List[Post])
async def get_posts(request: Request):
    return list(DATASTORE["posts"].values())

@app.get("/critical")
@limiter.limit("2/minute") # Stricter rate limit
async def get_critical_data(request: Request):
    return {"message": "This is critical data, access is highly restricted."}

@app.get("/force-error")
async def force_error(request: Request):
    # Endpoint to test the error handling middleware
    raise ValueError("A deliberate error was triggered.")

# --- App Lifecycle Events ---
@app.on_event("startup")
def load_mock_data():
    user_id_1 = uuid.uuid4()
    DATASTORE["users"][user_id_1] = User(
        id=user_id_1, email="admin@modern.dev", password_hash="...", role=UserRole.ADMIN, is_active=True, created_at=datetime.utcnow()
    )
    post_id_1 = uuid.uuid4()
    DATASTORE["posts"][post_id_1] = Post(
        id=post_id_1, user_id=user_id_1, title="Modern FastAPI", content="...", status=PostStatus.PUBLISHED
    )
    log.info("Application started, mock data loaded.")

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8003)