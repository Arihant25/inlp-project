import uuid
import time
from datetime import datetime, timezone
from enum import Enum
from typing import Dict, Any, Optional, Protocol
from collections import OrderedDict

from fastapi import FastAPI, HTTPException, status, Depends
from pydantic import BaseModel, Field

# --- Domain Models ---

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
    role: UserRoleEnum
    is_active: bool
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))

class PostModel(BaseModel):
    id: uuid.UUID = Field(default_factory=uuid.uuid4)
    user_id: uuid.UUID
    title: str
    content: str
    status: PostStatusEnum

# --- Mock Data Repository ---

class UserRepository:
    _users: Dict[uuid.UUID, UserModel] = {}

    def __init__(self):
        if not self._users: # Seed only once
            user = UserModel(email="test.user@protocol.com", password_hash="abc", role=UserRoleEnum.USER, is_active=True)
            self._users[user.id] = user
            print(f"UserRepository seeded with user: {user.id}")

    def find_by_id(self, user_id: uuid.UUID) -> Optional[UserModel]:
        print(f"REPOSITORY: Querying for user {user_id}")
        return self._users.get(user_id)

    def delete(self, user_id: uuid.UUID):
        if user_id in self._users:
            del self._users[user_id]

# --- Caching Abstraction (Protocol) ---

class CacheProtocol(Protocol):
    def get(self, key: str) -> Optional[Any]: ...
    def set(self, key: str, value: Any) -> None: ...
    def delete(self, key: str) -> None: ...

# --- Concrete Cache Implementation (Manual LRU + TTL) ---

class InMemoryLRUCache:
    def __init__(self, capacity: int = 128, ttl_seconds: int = 300):
        self._cache: OrderedDict[str, Any] = OrderedDict()
        self._expirations: Dict[str, float] = {}
        self._capacity = capacity
        self._ttl = ttl_seconds
        print(f"InMemoryLRUCache created with capacity={capacity}, ttl={self._ttl}s")

    def _is_expired(self, key: str) -> bool:
        return time.time() > self._expirations.get(key, float('inf'))

    def get(self, key: str) -> Optional[Any]:
        if key not in self._cache or self._is_expired(key):
            if key in self._cache: # Clean up expired key
                self.delete(key)
            print(f"CACHE MISS (or expired): {key}")
            return None
        
        self._cache.move_to_end(key)
        print(f"CACHE HIT: {key}")
        return self._cache[key]

    def set(self, key: str, value: Any) -> None:
        if key in self._cache:
            self._cache.move_to_end(key)
        self._cache[key] = value
        self._expirations[key] = time.time() + self._ttl
        
        if len(self._cache) > self._capacity:
            lru_key, _ = self._cache.popitem(last=False)
            del self._expirations[lru_key]
            print(f"CACHE EVICT (LRU): {lru_key}")
        print(f"CACHE SET: {key}")

    def delete(self, key: str) -> None:
        if key in self._cache:
            del self._cache[key]
            del self._expirations[key]
            print(f"CACHE DELETE: {key}")

# --- Dependency Injection ---

_user_repo_instance = UserRepository()
_cache_instance = InMemoryLRUCache()

def get_user_repository() -> UserRepository:
    return _user_repo_instance

def get_cache_service() -> CacheProtocol:
    return _cache_instance

# --- FastAPI Application ---

app = FastAPI(
    title="Variation 3: Protocol-based Caching",
    description="A clean architecture approach using a cache protocol for dependency inversion."
)

# --- API Endpoints ---

@app.get("/v3/users/{user_id}", response_model=UserModel)
def get_user_by_id(
    user_id: uuid.UUID,
    repo: UserRepository = Depends(get_user_repository),
    cache: CacheProtocol = Depends(get_cache_service)
):
    """Implements Cache-Aside using a protocol-based cache service."""
    cache_key = f"user_model:{user_id}"
    
    # 1. Attempt to retrieve from cache
    cached_data = cache.get(cache_key)
    if cached_data:
        return UserModel.model_validate(cached_data)

    # 2. On miss, retrieve from the data source
    user = repo.find_by_id(user_id)
    if not user:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "User not found in repository")

    # 3. Store the result in the cache for future requests
    cache.set(cache_key, user.model_dump())
    
    return user

@app.delete("/v3/users/{user_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_user_by_id(
    user_id: uuid.UUID,
    repo: UserRepository = Depends(get_user_repository),
    cache: CacheProtocol = Depends(get_cache_service)
):
    """Deletes a user and invalidates the cache."""
    # Perform DB operation
    repo.delete(user_id)
    
    # Invalidate cache
    cache_key = f"user_model:{user_id}"
    cache.delete(cache_key)
    
    return

# To run this: uvicorn <filename>:app --reload