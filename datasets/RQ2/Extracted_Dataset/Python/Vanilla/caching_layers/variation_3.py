import uuid
import time
from datetime import datetime
from enum import Enum
from dataclasses import dataclass, field
from typing import Dict, Optional, Any
from functools import wraps

# --- Domain Schema ---
class UserRole(Enum): ADMIN = "admin"; USER = "user"
class PostStatus(Enum): DRAFT = "draft"; PUBLISHED = "published"

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

# --- LRU Cache Implementation ---
class LRUCache:
    class _Node:
        __slots__ = 'key', 'value', 'expires_at', 'prev', 'next'
        def __init__(self, key, value, expires_at):
            self.key, self.value, self.expires_at = key, value, expires_at
            self.prev = self.next = None

    def __init__(self, capacity: int):
        self.capacity = capacity
        self.map: Dict[Any, LRUCache._Node] = {}
        self.head = self._Node(None, None, 0)
        self.tail = self._Node(None, None, 0)
        self.head.next, self.tail.prev = self.tail, self.head

    def _remove(self, node: _Node):
        node.prev.next, node.next.prev = node.next, node.prev

    def _add(self, node: _Node):
        node.next, node.prev = self.head.next, self.head
        self.head.next.prev = node
        self.head.next = node

    def get(self, key: Any) -> Optional[Any]:
        if key not in self.map: return None
        node = self.map[key]
        if time.time() > node.expires_at:
            self._remove(node)
            del self.map[key]
            return None
        self._remove(node)
        self._add(node)
        return node.value

    def set(self, key: Any, value: Any, ttl: int):
        if key in self.map: self._remove(self.map[key])
        node = self._Node(key, value, time.time() + ttl)
        self._add(node)
        self.map[key] = node
        if len(self.map) > self.capacity:
            lru = self.tail.prev
            self._remove(lru)
            del self.map[lru.key]

    def delete(self, key: Any):
        if key in self.map:
            self._remove(self.map[key])
            del self.map[key]

# --- Caching Decorator ---
def cache_aside(cache: LRUCache, key_prefix: str, ttl: int):
    def decorator(func):
        @wraps(func)
        def wrapper(repo_instance, entity_id: uuid.UUID, *args, **kwargs):
            cache_key = f"{key_prefix}:{entity_id}"
            
            # 1. Try cache
            cached_entity = cache.get(cache_key)
            if cached_entity:
                print(f"DECORATOR: Cache HIT for key '{cache_key}'.")
                return cached_entity
            
            print(f"DECORATOR: Cache MISS for key '{cache_key}'.")
            
            # 2. On miss, call original function (DB fetch)
            entity = func(repo_instance, entity_id, *args, **kwargs)
            
            # 3. Populate cache
            if entity:
                print(f"DECORATOR: Populating cache for key '{cache_key}'.")
                cache.set(cache_key, entity, ttl)
            
            return entity
        return wrapper
    return decorator

# --- Repository Layer ---
class MockDataSource:
    _data = {"users": {}, "posts": {}}
    def get_user(self, user_id): 
        print(f"DATASOURCE: Fetching user {user_id}...")
        time.sleep(0.5); return self._data["users"].get(user_id)
    def save_user(self, user): 
        print(f"DATASOURCE: Saving user {user.id}...")
        time.sleep(0.2); self._data["users"][user.id] = user

class UserRepository:
    def __init__(self, data_source: MockDataSource, cache: LRUCache):
        self._db = data_source
        self._cache = cache
        self._key_prefix = "user"

    @cache_aside(cache=LRUCache(10), key_prefix="user", ttl=60)
    def find_by_id(self, user_id: uuid.UUID) -> Optional[User]:
        # This is a bit of a hack to make the decorator self-contained
        # In a real app, the cache instance would be passed in properly
        if not hasattr(self.find_by_id, '__closure__') or self.find_by_id.__closure__[0].cell_contents != self._cache:
             self.find_by_id = cache_aside(self._cache, self._key_prefix, 60)(self._db.get_user.__get__(self._db))
        
        return self.find_by_id(user_id)

    def save(self, user: User):
        self._db.save_user(user)
        cache_key = f"{self._key_prefix}:{user.id}"
        print(f"REPOSITORY: Invalidating cache for key '{cache_key}'.")
        self._cache.delete(cache_key)

# --- Main Execution ---
if __name__ == "__main__":
    print("--- Variation 3: Repository Pattern with Decorator ---")
    
    # Setup
    data_source = MockDataSource()
    entity_cache = LRUCache(capacity=10)
    
    # In a real DI framework, this would be cleaner. Here we manually wire them.
    # The decorator needs access to the cache, and the repo needs it for invalidation.
    def find_user_from_db(repo, user_id):
        return data_source.get_user(user_id)

    user_repo = UserRepository(data_source, entity_cache)
    # Bind the decorated method to the instance's cache and db call
    user_repo.find_by_id = cache_aside(entity_cache, "user", 60)(find_user_from_db.__get__(user_repo))

    # Create a user
    user_to_test = User(email="decorator@example.com", password_hash="123")
    data_source.save_user(user_to_test)

    print("\n1. First find_by_id call (should miss):")
    user = user_repo.find_by_id(user_to_test.id)
    print(f"   -> Found: {user.email if user else 'None'}")

    print("\n2. Second find_by_id call (should hit):")
    user = user_repo.find_by_id(user_to_test.id)
    print(f"   -> Found: {user.email if user else 'None'}")

    print("\n3. Saving the user with new data (should invalidate):")
    user.email = "new.decorator@example.com"
    user_repo.save(user)

    print("\n4. Third find_by_id call (should miss again):")
    user = user_repo.find_by_id(user_to_test.id)
    print(f"   -> Found: {user.email if user else 'None'}")

    print("\n5. Fourth find_by_id call (should hit with new data):")
    user = user_repo.find_by_id(user_to_test.id)
    print(f"   -> Found: {user.email if user else 'None'}")