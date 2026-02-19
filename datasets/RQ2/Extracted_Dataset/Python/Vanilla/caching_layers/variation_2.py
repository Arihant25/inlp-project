import uuid
import time
from datetime import datetime
from enum import Enum
from dataclasses import dataclass, field
from typing import Dict, Optional, Any, Callable

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

# --- In-Memory Data Store (simulates DB) ---
DB_STORE = {
    "users": {},
    "posts": {}
}

def fetch_user_from_db(user_id: uuid.UUID) -> Optional[User]:
    print(f"DB_ACCESS: Reading user {user_id}")
    time.sleep(0.5) # Simulate I/O latency
    return DB_STORE["users"].get(user_id)

def persist_user_to_db(user: User):
    print(f"DB_ACCESS: Writing user {user.id}")
    time.sleep(0.2)
    DB_STORE["users"][user.id] = user

# --- LRU Cache Implementation ---
class LRUCacheWithTTL:
    
    class _Node:
        def __init__(self, key, val, expire_ts):
            self.key = key
            self.val = val
            self.expire_ts = expire_ts
            self.prev = self.next = None

    def __init__(self, capacity: int):
        self.capacity = capacity
        self.map: Dict[Any, LRUCacheWithTTL._Node] = {}
        self.head = self._Node(0, 0, 0)
        self.tail = self._Node(0, 0, 0)
        self.head.next = self.tail
        self.tail.prev = self.head

    def _remove(self, node: _Node):
        p, n = node.prev, node.next
        p.next = n
        n.prev = p

    def _add(self, node: _Node):
        n = self.head.next
        self.head.next = node
        node.prev = self.head
        node.next = n
        n.prev = node

    def get_val(self, key: Any) -> Optional[Any]:
        if key not in self.map:
            return None
        
        node = self.map[key]
        
        if time.time() > node.expire_ts:
            print(f"CACHE_SYS: Key '{key}' expired. Removing.")
            self._remove(node)
            del self.map[key]
            return None
            
        self._remove(node)
        self._add(node)
        return node.val

    def set_val(self, key: Any, value: Any, ttl: int = 300):
        expire_ts = time.time() + ttl
        if key in self.map:
            self._remove(self.map[key])
        
        node = self._Node(key, value, expire_ts)
        self._add(node)
        self.map[key] = node
        
        if len(self.map) > self.capacity:
            lru_node = self.tail.prev
            print(f"CACHE_SYS: Capacity overflow. Evicting '{lru_node.key}'.")
            self._remove(lru_node)
            del self.map[lru_node.key]

    def del_val(self, key: Any):
        if key in self.map:
            print(f"CACHE_SYS: Invalidating key '{key}'.")
            node = self.map[key]
            self._remove(node)
            del self.map[key]

# --- Functional Service Layer ---

def get_user(user_id: uuid.UUID, cache: LRUCacheWithTTL, db_reader: Callable[[uuid.UUID], Optional[User]]) -> Optional[User]:
    """Implements cache-aside pattern for fetching a user."""
    cache_key = f"user::{user_id}"
    
    # 1. Check cache
    user_from_cache = cache.get_val(cache_key)
    if user_from_cache is not None:
        print(f"LOGIC: User {user_id} found in cache.")
        return user_from_cache
    
    print(f"LOGIC: User {user_id} not in cache. Querying database.")
    
    # 2. On miss, query DB
    user_from_db = db_reader(user_id)
    
    # 3. Populate cache
    if user_from_db:
        print(f"LOGIC: Storing user {user_id} in cache.")
        cache.set_val(cache_key, user_from_db, ttl=60)
        
    return user_from_db

def update_user_email(user_id: uuid.UUID, new_email: str, cache: LRUCacheWithTTL):
    """Updates a user and invalidates the cache."""
    user = DB_STORE["users"].get(user_id)
    if not user:
        print(f"LOGIC: Cannot update non-existent user {user_id}.")
        return

    user.email = new_email
    persist_user_to_db(user)
    
    # Invalidate cache entry
    cache_key = f"user::{user_id}"
    cache.del_val(cache_key)
    print(f"LOGIC: User {user_id} updated. Cache invalidated.")

# --- Main Execution ---
if __name__ == "__main__":
    print("--- Variation 2: Functional Approach ---")

    # Setup
    mem_cache = LRUCacheWithTTL(capacity=10)
    
    # Create and persist a user directly
    test_user_obj = User(email="functional@example.com", password_hash="xyz")
    persist_user_to_db(test_user_obj)
    
    print("\n1. First call to get_user (cache miss):")
    retrieved_user = get_user(test_user_obj.id, mem_cache, fetch_user_from_db)
    print(f"   -> Result: {retrieved_user.email if retrieved_user else 'None'}")

    print("\n2. Second call to get_user (cache hit):")
    retrieved_user = get_user(test_user_obj.id, mem_cache, fetch_user_from_db)
    print(f"   -> Result: {retrieved_user.email if retrieved_user else 'None'}")

    print("\n3. Update user's email (invalidates cache):")
    update_user_email(test_user_obj.id, "new.functional@example.com", mem_cache)

    print("\n4. Third call to get_user (cache miss, fetches new data):")
    retrieved_user = get_user(test_user_obj.id, mem_cache, fetch_user_from_db)
    print(f"   -> Result: {retrieved_user.email if retrieved_user else 'None'}")

    print("\n5. Fourth call to get_user (cache hit with new data):")
    retrieved_user = get_user(test_user_obj.id, mem_cache, fetch_user_from_db)
    print(f"   -> Result: {retrieved_user.email if retrieved_user else 'None'}")

    print("\n6. Testing TTL expiration:")
    print("   Setting user in cache with 1-second TTL...")
    mem_cache.set_val(f"user::{test_user_obj.id}", retrieved_user, ttl=1)
    print("   Waiting 2 seconds...")
    time.sleep(2)
    print("   Requesting user again (should miss due to expiration):")
    get_user(test_user_obj.id, mem_cache, fetch_user_from_db)