import uuid
import time
import functools
from datetime import datetime, timezone
from enum import Enum
from typing import Dict, Any, Optional, Callable

from fastapi import FastAPI, HTTPException, status
from pydantic import BaseModel

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

class PostCreate(BaseModel):
    user_id: uuid.UUID
    title: str
    content: str

# --- Mock Database ---

class MockDB:
    USERS: Dict[uuid.UUID, User] = {}
    POSTS: Dict[uuid.UUID, Post] = {}

    @staticmethod
    def initialize():
        user_id = uuid.uuid4()
        MockDB.USERS[user_id] = User(
            id=user_id, email="decorator.user@example.com", password_hash="secret",
            role=UserRole.USER, is_active=True, created_at=datetime.now(timezone.utc)
        )
        print("MockDB initialized.")

# --- Caching Logic (Decorator-based) ---

_CACHE_STORAGE: Dict[str, Any] = {}
_CACHE_EXPIRY: Dict[str, float] = {}

def _generate_cache_key(func: Callable, *args, **kwargs) -> str:
    """Generates a unique key based on function and arguments."""
    key_parts = [func.__module__, func.__name__]
    key_parts.extend(str(arg) for arg in args)
    key_parts.extend(f"{k}={v}" for k, v in sorted(kwargs.items()))
    return ":".join(key_parts)

def cache(ttl_seconds: int = 120):
    """Decorator for implementing the cache-aside pattern."""
    def decorator(func: Callable):
        @functools.wraps(func)
        def wrapper(*args, **kwargs):
            key = _generate_cache_key(func, *args, **kwargs)
            
            # 1. Check cache
            if key in _CACHE_STORAGE and time.time() < _CACHE_EXPIRY.get(key, 0):
                print(f"CACHE HIT on key: {key}")
                return _CACHE_STORAGE[key]
            
            print(f"CACHE MISS on key: {key}")
            
            # 2. On miss, call the original function
            result = func(*args, **kwargs)
            
            # 3. Store result in cache
            if result is not None:
                print(f"CACHE SET on key: {key}")
                _CACHE_STORAGE[key] = result
                _CACHE_EXPIRY[key] = time.time() + ttl_seconds
                
            return result
        return wrapper
    return decorator

def invalidate_cache(func: Callable, *args, **kwargs):
    """Invalidates the cache for a specific function and arguments."""
    key = _generate_cache_key(func, *args, **kwargs)
    if key in _CACHE_STORAGE:
        del _CACHE_STORAGE[key]
        if key in _CACHE_EXPIRY:
            del _CACHE_EXPIRY[key]
        print(f"CACHE INVALIDATED for key: {key}")

# --- Data Access Layer ---

@cache(ttl_seconds=60)
def get_post_from_db(post_id: uuid.UUID) -> Optional[Post]:
    """This function simulates a slow database query."""
    print(f"DATABASE: Querying for post {post_id}...")
    time.sleep(0.5) # Simulate I/O latency
    return MockDB.POSTS.get(post_id)

def create_post_in_db(post_data: PostCreate) -> Post:
    """Creates a post in the database."""
    new_post = Post(
        id=uuid.uuid4(),
        status=PostStatus.DRAFT,
        **post_data.model_dump()
    )
    MockDB.POSTS[new_post.id] = new_post
    print(f"DATABASE: Created post {new_post.id}")
    return new_post

def delete_post_from_db(post_id: uuid.UUID) -> bool:
    """Deletes a post from the database."""
    if post_id in MockDB.POSTS:
        del MockDB.POSTS[post_id]
        print(f"DATABASE: Deleted post {post_id}")
        return True
    return False

# --- FastAPI Application ---

app = FastAPI(
    title="Variation 4: Decorator-based Caching",
    description="A declarative approach using a custom @cache decorator to abstract away caching logic."
)

@app.on_event("startup")
def startup_event():
    MockDB.initialize()

# --- API Endpoints ---

@app.post("/posts", response_model=Post, status_code=status.HTTP_201_CREATED)
def create_new_post(post_data: PostCreate):
    """Creates a new post."""
    return create_post_in_db(post_data)

@app.get("/posts/{post_id}", response_model=Post)
def get_single_post(post_id: uuid.UUID):
    """
    Gets a post by its ID. The caching logic is handled entirely
    by the @cache decorator on the `get_post_from_db` function.
    """
    post = get_post_from_db(post_id=post_id)
    if not post:
        raise HTTPException(status_code=404, detail="Post not found")
    return post

@app.delete("/posts/{post_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_existing_post(post_id: uuid.UUID):
    """Deletes a post and invalidates its cache entry."""
    # Invalidate cache before the DB operation to avoid race conditions
    invalidate_cache(get_post_from_db, post_id=post_id)
    
    if not delete_post_from_db(post_id):
        # If the post didn't exist in the DB, we raise a 404.
        # The cache was already invalidated, which is harmless.
        raise HTTPException(status_code=404, detail="Post not found")
    
    return

# To run this: uvicorn <filename>:app --reload