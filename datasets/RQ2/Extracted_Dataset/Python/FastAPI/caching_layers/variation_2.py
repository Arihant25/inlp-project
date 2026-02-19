# Required package: pip install cachetools
import uuid
from datetime import datetime, timezone
from enum import Enum
from typing import Dict, Any, Optional, Generator

from fastapi import FastAPI, HTTPException, status, Depends
from pydantic import BaseModel
from cachetools import TTLCache

# --- Domain Models ---

class UserRole(str, Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(str, Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

class UserSchema(BaseModel):
    id: uuid.UUID
    email: str
    password_hash: str
    role: UserRole
    is_active: bool
    created_at: datetime

class PostSchema(BaseModel):
    id: uuid.UUID
    user_id: uuid.UUID
    title: str
    content: str
    status: PostStatus

class PostUpdateSchema(BaseModel):
    title: Optional[str] = None
    content: Optional[str] = None
    status: Optional[PostStatus] = None

# --- Mock Database Service ---

class DatabaseService:
    def __init__(self):
        self._users: Dict[uuid.UUID, UserSchema] = {}
        self._posts: Dict[uuid.UUID, PostSchema] = {}

    def seed_data(self):
        user_id_1 = uuid.uuid4()
        self._users[user_id_1] = UserSchema(
            id=user_id_1, email="admin.dev@example.com", password_hash="hash1",
            role=UserRole.ADMIN, is_active=True, created_at=datetime.now(timezone.utc)
        )
        post_id_1 = uuid.uuid4()
        self._posts[post_id_1] = PostSchema(
            id=post_id_1, user_id=user_id_1, title="OOP Caching",
            content="Content about OOP caching patterns.", status=PostStatus.PUBLISHED
        )
        print("Database seeded.")

    def get_post_by_id(self, post_id: uuid.UUID) -> Optional[PostSchema]:
        print(f"DATABASE: Fetching post {post_id}")
        return self._posts.get(post_id)

    def update_post(self, post_id: uuid.UUID, update_data: PostUpdateSchema) -> Optional[PostSchema]:
        post = self._posts.get(post_id)
        if post:
            update_dict = update_data.model_dump(exclude_unset=True)
            updated_post = post.model_copy(update=update_dict)
            self._posts[post_id] = updated_post
            return updated_post
        return None

    def delete_post(self, post_id: uuid.UUID) -> bool:
        if post_id in self._posts:
            del self._posts[post_id]
            return True
        return False

# --- Caching Service (OOP) ---

class CacheManager:
    def __init__(self, max_size: int = 128, ttl: int = 300):
        # Using cachetools for a production-grade LRU + TTL cache
        self.cache: TTLCache = TTLCache(maxsize=max_size, ttl=ttl)
        print(f"CacheManager initialized with max_size={max_size}, ttl={ttl}s")

    def get(self, key: str) -> Optional[Any]:
        item = self.cache.get(key)
        if item:
            print(f"CACHE HIT: key='{key}'")
            return item
        print(f"CACHE MISS: key='{key}'")
        return None

    def set(self, key: str, value: Any):
        print(f"CACHE SET: key='{key}'")
        self.cache[key] = value

    def invalidate(self, key: str):
        if key in self.cache:
            print(f"CACHE INVALIDATE: key='{key}'")
            del self.cache[key]

# --- Dependency Injection Setup ---

db_service = DatabaseService()
cache_manager = CacheManager(max_size=256, ttl=120)

def get_db() -> DatabaseService:
    return db_service

def get_cache() -> CacheManager:
    return cache_manager

# --- FastAPI Application ---

app = FastAPI(
    title="Variation 2: OOP Caching with DI",
    description="An object-oriented approach using a CacheManager class and dependency injection."
)

@app.on_event("startup")
def on_startup():
    db_service.seed_data()

# --- API Endpoints ---

@app.get("/posts/{post_id}", response_model=PostSchema)
def read_post(
    post_id: uuid.UUID,
    db: DatabaseService = Depends(get_db),
    cache: CacheManager = Depends(get_cache)
):
    """Implements Cache-Aside pattern for fetching a post."""
    cache_key = f"post:{post_id}"
    
    # 1. Check cache
    cached_post_data = cache.get(cache_key)
    if cached_post_data:
        return PostSchema.model_validate(cached_post_data)

    # 2. On miss, fetch from DB
    post_from_db = db.get_post_by_id(post_id)
    if not post_from_db:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Post not found")

    # 3. Populate cache
    cache.set(cache_key, post_from_db.model_dump())
    
    return post_from_db

@app.patch("/posts/{post_id}", response_model=PostSchema)
def modify_post(
    post_id: uuid.UUID,
    post_update: PostUpdateSchema,
    db: DatabaseService = Depends(get_db),
    cache: CacheManager = Depends(get_cache)
):
    """Updates a post and invalidates its cache entry."""
    # Update DB first
    updated_post = db.update_post(post_id, post_update)
    if not updated_post:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Post not found")

    # Invalidate cache
    cache_key = f"post:{post_id}"
    cache.invalidate(cache_key)
    
    return updated_post

@app.delete("/posts/{post_id}", status_code=status.HTTP_204_NO_CONTENT)
def remove_post(
    post_id: uuid.UUID,
    db: DatabaseService = Depends(get_db),
    cache: CacheManager = Depends(get_cache)
):
    """Deletes a post and invalidates its cache entry."""
    # Delete from DB
    success = db.delete_post(post_id)
    if not success:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Post not found")

    # Invalidate cache
    cache_key = f"post:{post_id}"
    cache.invalidate(cache_key)
    return

# To run this: uvicorn <filename>:app --reload