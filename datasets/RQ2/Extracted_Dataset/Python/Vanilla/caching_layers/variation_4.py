import uuid
import time
from datetime import datetime
from enum import Enum
from dataclasses import dataclass, field
from typing import Dict, Optional, Any

# --- Domain Model ---
class UserRole(Enum):
    ADMIN = "admin"
    USER = "user"

class PostStatus(Enum):
    DRAFT = "draft"
    PUBLISHED = "published"

@dataclass
class User:
    id: uuid.UUID = field(default_factory=uuid.uuid4)
    email: str = ""
    password_hash: str = ""
    role: UserRole = UserRole.USER
    is_active: bool = True
    created_at: datetime = field(default_factory=datetime.utcnow)

@dataclass
class Post:
    id: uuid.UUID = field(default_factory=uuid.uuid4)
    user_id: uuid.UUID = field(default_factory=uuid.uuid4)
    title: str = ""
    content: str = ""
    status: PostStatus = PostStatus.DRAFT

# --- Module-level "Singleton" Resources ---

_DB = {
    "users": {},
    "posts": {}
}

class _LRUCacheImpl:
    """A compact LRU cache implementation, intended for module-level use."""
    class _Node:
        def __init__(self, k, v, exp): self.k, self.v, self.exp, self.p, self.n = k, v, exp, None, None
    
    def __init__(self, cap: int):
        self.cap, self.map = cap, {}
        self.head, self.tail = self._Node(0,0,0), self._Node(0,0,0)
        self.head.n, self.tail.p = self.tail, self.head

    def _rm(self, node): node.p.n, node.n.p = node.n, node.p
    def _add(self, node): node.n, node.p, self.head.n.p, self.head.n = self.head.n, self.head, node, node

    def get(self, key: Any) -> Optional[Any]:
        if key not in self.map: return None
        node = self.map[key]
        if time.time() > node.exp:
            self._rm(node); del self.map[key]
            return None
        self._rm(node); self._add(node)
        return node.v

    def set(self, key: Any, val: Any, ttl: int):
        if key in self.map: self._rm(self.map[key])
        node = self._Node(key, val, time.time() + ttl)
        self._add(node); self.map[key] = node
        if len(self.map) > self.cap:
            lru = self.tail.p
            self._rm(lru); del self.map[lru.k]

    def delete(self, key: Any):
        if key in self.map: self._rm(self.map[key]); del self.map[key]

# The single, shared cache instance for the application module.
_CACHE = _LRUCacheImpl(cap=50)

# --- Data Access Layer (as a collection of functions) ---

def _fetch_user_from_db(uid: uuid.UUID) -> Optional[User]:
    print(f"PERSISTENCE: Querying DB for user {uid}")
    time.sleep(0.5) # Simulate latency
    return _DB["users"].get(uid)

def _write_user_to_db(usr: User):
    print(f"PERSISTENCE: Writing user {usr.id} to DB")
    time.sleep(0.2)
    _DB["users"][usr.id] = usr

# --- Service Logic ---

def find_user(user_id: uuid.UUID) -> Optional[User]:
    """Applies cache-aside logic using module-level resources."""
    cache_key = f"user|{user_id}"
    
    # 1. Attempt to retrieve from the global cache
    user = _CACHE.get(cache_key)
    if user:
        print(f"SERVICE: Cache HIT for user {user_id}")
        return user
    
    print(f"SERVICE: Cache MISS for user {user_id}")
    
    # 2. On miss, retrieve from the global DB
    user = _fetch_user_from_db(user_id)
    
    # 3. If found, populate the global cache
    if user:
        print(f"SERVICE: Caching user {user_id}")
        _CACHE.set(cache_key, user, ttl=60)
        
    return user

def modify_user(user: User):
    """Updates a user in the DB and invalidates the cache."""
    _write_user_to_db(user)
    cache_key = f"user|{user.id}"
    print(f"SERVICE: Invalidating cache for user {user.id}")
    _CACHE.delete(cache_key)

# --- Main Execution ---
if __name__ == "__main__":
    print("--- Variation 4: Module-level Singleton Cache ---")

    # Setup: Create a user and put it directly into the mock DB
    test_user = User(email="singleton@example.com", password_hash="456")
    _write_user_to_db(test_user)

    print("\n1. First call to find_user (will miss cache):")
    u = find_user(test_user.id)
    print(f"   -> Got: {u.email if u else 'N/A'}")

    print("\n2. Second call to find_user (will hit cache):")
    u = find_user(test_user.id)
    print(f"   -> Got: {u.email if u else 'N/A'}")

    print("\n3. Modifying user data (will invalidate cache):")
    test_user.email = "new.singleton@example.com"
    modify_user(test_user)

    print("\n4. Third call to find_user (will miss, then fetch new data):")
    u = find_user(test_user.id)
    print(f"   -> Got: {u.email if u else 'N/A'}")

    print("\n5. Fourth call to find_user (will hit with new data):")
    u = find_user(test_user.id)
    print(f"   -> Got: {u.email if u else 'N/A'}")

    print("\n6. Testing TTL:")
    print("   Setting user in cache with 1-second TTL...")
    _CACHE.set(f"user|{test_user.id}", test_user, ttl=1)
    print("   Waiting 2 seconds...")
    time.sleep(2)
    print("   Requesting user again (should miss):")
    find_user(test_user.id)