import uuid
import time
from datetime import datetime, timedelta
from enum import Enum
from dataclasses import dataclass, field
from typing import Dict, Optional, Any, Tuple

# --- Domain Schema ---

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

# --- Mock Data Source ---

class MockDatabase:
    """A singleton class to simulate a slow database connection."""
    _instance = None
    
    def __new__(cls):
        if cls._instance is None:
            cls._instance = super(MockDatabase, cls).__new__(cls)
            cls._instance._users = {}
            cls._instance._posts = {}
        return cls._instance

    def find_user(self, user_id: uuid.UUID) -> Optional[User]:
        print(f"DATABASE: Querying for user {user_id}...")
        time.sleep(0.5)  # Simulate network latency
        return self._users.get(user_id)

    def save_user(self, user: User) -> None:
        print(f"DATABASE: Saving user {user.id}...")
        time.sleep(0.2)
        self._users[user.id] = user

    def delete_user(self, user_id: uuid.UUID) -> None:
        print(f"DATABASE: Deleting user {user_id}...")
        time.sleep(0.2)
        if user_id in self._users:
            del self._users[user_id]

# --- Caching Implementation (LRU from scratch) ---

class _CacheNode:
    """Node for the doubly-linked list used in the LRU cache."""
    def __init__(self, key: Any, value: Any, expires_at: float):
        self.key = key
        self.value = value
        self.expires_at = expires_at
        self.prev: Optional['_CacheNode'] = None
        self.next: Optional['_CacheNode'] = None

class LRUCache:
    """A thread-unsafe LRU Cache with Time-To-Live (TTL) expiration."""
    def __init__(self, capacity: int):
        if capacity <= 0:
            raise ValueError("Capacity must be a positive integer.")
        self.capacity = capacity
        self.cache: Dict[Any, _CacheNode] = {}
        self.head = _CacheNode(None, None, 0) # Sentinel head
        self.tail = _CacheNode(None, None, 0) # Sentinel tail
        self.head.next = self.tail
        self.tail.prev = self.head

    def _remove_node(self, node: _CacheNode):
        """Removes a node from the linked list."""
        prev_node = node.prev
        next_node = node.next
        prev_node.next = next_node
        next_node.prev = prev_node

    def _add_to_head(self, node: _CacheNode):
        """Adds a node to the front of the linked list."""
        node.next = self.head.next
        node.prev = self.head
        self.head.next.prev = node
        self.head.next = node

    def _evict_if_expired(self, node: _CacheNode) -> bool:
        """Checks for expiration and evicts if necessary. Returns True if evicted."""
        if time.time() > node.expires_at:
            self._remove_node(node)
            del self.cache[node.key]
            print(f"CACHE: Expired and evicted key '{node.key}'.")
            return True
        return False

    def get(self, key: Any) -> Optional[Any]:
        if key in self.cache:
            node = self.cache[key]
            
            if self._evict_if_expired(node):
                return None

            # Move to head as it's recently used
            self._remove_node(node)
            self._add_to_head(node)
            return node.value
        return None

    def set(self, key: Any, value: Any, ttl_seconds: int = 300):
        expires_at = time.time() + ttl_seconds
        if key in self.cache:
            node = self.cache[key]
            node.value = value
            node.expires_at = expires_at
            self._remove_node(node)
            self._add_to_head(node)
        else:
            if len(self.cache) >= self.capacity:
                # Evict least recently used item (at the tail)
                lru_node = self.tail.prev
                self._remove_node(lru_node)
                del self.cache[lru_node.key]
                print(f"CACHE: Capacity reached. Evicted LRU key '{lru_node.key}'.")

            new_node = _CacheNode(key, value, expires_at)
            self.cache[key] = new_node
            self._add_to_head(new_node)

    def delete(self, key: Any):
        if key in self.cache:
            node = self.cache[key]
            self._remove_node(node)
            del self.cache[key]
            print(f"CACHE: Explicitly deleted key '{key}'.")

# --- Service Layer with Cache-Aside Pattern ---

class UserService:
    def __init__(self, db: MockDatabase, cache: LRUCache):
        self._db = db
        self._cache = cache

    def get_user_by_id(self, user_id: uuid.UUID) -> Optional[User]:
        cache_key = f"user:{user_id}"
        
        # 1. Try to get from cache
        cached_user = self._cache.get(cache_key)
        if cached_user:
            print(f"SERVICE: Cache HIT for user {user_id}.")
            return cached_user
        
        print(f"SERVICE: Cache MISS for user {user_id}.")
        
        # 2. If miss, get from database
        user = self._db.find_user(user_id)
        
        # 3. Put into cache
        if user:
            print(f"SERVICE: Populating cache for user {user_id}.")
            self._cache.set(cache_key, user, ttl_seconds=60)
            
        return user

    def update_user(self, user: User) -> None:
        # Update the database first
        self._db.save_user(user)
        
        # Invalidate the cache
        cache_key = f"user:{user.id}"
        print(f"SERVICE: Invalidating cache for user {user.id}.")
        self._cache.delete(cache_key)

# --- Main Execution ---

if __name__ == "__main__":
    print("--- Variation 1: Classic OOP Approach ---")
    
    # Setup
    db = MockDatabase()
    cache = LRUCache(capacity=10)
    user_service = UserService(db, cache)
    
    # Create a user and save to DB
    test_user = User(email="test@example.com", password_hash="abc")
    db.save_user(test_user)
    
    print("\n1. First request for user (should be a cache miss):")
    user = user_service.get_user_by_id(test_user.id)
    print(f"   -> Retrieved: {user.email if user else 'Not Found'}")
    
    print("\n2. Second request for user (should be a cache hit):")
    user = user_service.get_user_by_id(test_user.id)
    print(f"   -> Retrieved: {user.email if user else 'Not Found'}")
    
    print("\n3. Update user's email (should invalidate cache):")
    test_user.email = "updated@example.com"
    user_service.update_user(test_user)
    
    print("\n4. Third request for user (should be a miss, then fetch updated data):")
    user = user_service.get_user_by_id(test_user.id)
    print(f"   -> Retrieved: {user.email if user else 'Not Found'}")
    
    print("\n5. Final request for user (should be a hit with new data):")
    user = user_service.get_user_by_id(test_user.id)
    print(f"   -> Retrieved: {user.email if user else 'Not Found'}")

    print("\n6. Testing time-based expiration:")
    user_service.get_user_by_id(test_user.id) # Reset TTL
    print("   Waiting for 2 seconds (TTL is 60s, so it should still be a hit)...")
    time.sleep(2)
    user_service.get_user_by_id(test_user.id)

    # Re-set with a very short TTL
    print("   Setting user in cache with 1-second TTL...")
    cache.set(f"user:{test_user.id}", test_user, ttl_seconds=1)
    print("   Waiting for 2 seconds...")
    time.sleep(2)
    print("   Requesting user again (should be a miss due to expiration):")
    user_service.get_user_by_id(test_user.id)