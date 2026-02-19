import uuid
import time
from datetime import datetime, timezone
from enum import Enum
from typing import Dict, Any, Optional, List

from fastapi import FastAPI, HTTPException, status
from pydantic import BaseModel, Field

# --- Global State (Mock DB and Cache) ---

MOCK_DB_USERS: Dict[uuid.UUID, Dict[str, Any]] = {}
MOCK_DB_POSTS: Dict[uuid.UUID, Dict[str, Any]] = {}

# Simple in-memory cache with time-based expiration
CACHE: Dict[str, Any] = {}
CACHE_EXPIRATION: Dict[str, float] = {}
DEFAULT_TTL_SECONDS = 60  # 1 minute

# --- Domain Models ---

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

class UserUpdate(BaseModel):
    email: Optional[str] = None
    is_active: Optional[bool] = None

# --- Cache Helper Functions ---

def get_from_cache(key: str) -> Optional[Any]:
    """Gets an item from the cache if it exists and is not expired."""
    if key in CACHE and time.time() < CACHE_EXPIRATION.get(key, 0):
        print(f"CACHE HIT for key: {key}")
        return CACHE[key]
    if key in CACHE:
        # Clean up expired key
        del CACHE[key]
        del CACHE_EXPIRATION[key]
    print(f"CACHE MISS for key: {key}")
    return None

def set_in_cache(key: str, value: Any, ttl: int = DEFAULT_TTL_SECONDS):
    """Sets an item in the cache with a TTL."""
    print(f"CACHE SET for key: {key} with TTL: {ttl}s")
    CACHE[key] = value
    CACHE_EXPIRATION[key] = time.time() + ttl

def delete_from_cache(key: str):
    """Deletes an item from the cache (cache invalidation)."""
    if key in CACHE:
        print(f"CACHE DELETE for key: {key}")
        del CACHE[key]
        if key in CACHE_EXPIRATION:
            del CACHE_EXPIRATION[key]

# --- FastAPI Application ---

app = FastAPI(
    title="Variation 1: Functional Caching",
    description="A simple, functional approach with global cache state."
)

@app.on_event("startup")
def populate_mock_db():
    """Pre-populates the mock database with some data."""
    user_id_1 = uuid.uuid4()
    user_id_2 = uuid.uuid4()
    MOCK_DB_USERS[user_id_1] = {
        "id": user_id_1, "email": "admin@example.com", "password_hash": "hashed_pw_1",
        "role": UserRole.ADMIN, "is_active": True, "created_at": datetime.now(timezone.utc)
    }
    MOCK_DB_USERS[user_id_2] = {
        "id": user_id_2, "email": "user@example.com", "password_hash": "hashed_pw_2",
        "role": UserRole.USER, "is_active": True, "created_at": datetime.now(timezone.utc)
    }
    post_id_1 = uuid.uuid4()
    MOCK_DB_POSTS[post_id_1] = {
        "id": post_id_1, "user_id": user_id_1, "title": "First Post",
        "content": "This is the content of the first post.", "status": PostStatus.PUBLISHED
    }
    print("Mock DB populated.")

# --- API Endpoints ---

@app.get("/users/{user_id}", response_model=User)
def get_user(user_id: uuid.UUID):
    """Implements Cache-Aside pattern for fetching a user."""
    cache_key = f"user:{user_id}"
    
    # 1. Try to get from cache
    cached_user_data = get_from_cache(cache_key)
    if cached_user_data:
        return User(**cached_user_data)

    # 2. Cache miss, get from DB
    print(f"DATABASE: Fetching user {user_id}")
    user_data = MOCK_DB_USERS.get(user_id)
    if not user_data:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")

    # 3. Set in cache
    set_in_cache(cache_key, user_data)
    
    return User(**user_data)

@app.put("/users/{user_id}", response_model=User)
def update_user(user_id: uuid.UUID, user_update: UserUpdate):
    """Updates a user and invalidates the cache."""
    if user_id not in MOCK_DB_USERS:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")

    # Update DB
    user_data = MOCK_DB_USERS[user_id]
    update_data = user_update.model_dump(exclude_unset=True)
    user_data.update(update_data)
    MOCK_DB_USERS[user_id] = user_data
    print(f"DATABASE: Updated user {user_id}")
    
    # Invalidate Cache
    cache_key = f"user:{user_id}"
    delete_from_cache(cache_key)
    
    return User(**user_data)

@app.delete("/users/{user_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_user(user_id: uuid.UUID):
    """Deletes a user and invalidates the cache."""
    if user_id not in MOCK_DB_USERS:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")

    # Delete from DB
    del MOCK_DB_USERS[user_id]
    print(f"DATABASE: Deleted user {user_id}")
    
    # Invalidate Cache
    cache_key = f"user:{user_id}"
    delete_from_cache(cache_key)
    
    return

@app.post("/cache/clear")
def clear_entire_cache():
    """Manually clears the entire cache."""
    global CACHE, CACHE_EXPIRATION
    CACHE.clear()
    CACHE_EXPIRATION.clear()
    print("CACHE CLEARED")
    return {"message": "Cache cleared successfully"}

# To run this: uvicorn <filename>:app --reload