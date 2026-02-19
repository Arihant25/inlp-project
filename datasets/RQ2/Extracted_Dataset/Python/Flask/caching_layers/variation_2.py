# Variation 2: The "Service Layer" OOP Approach
# Developer: A backend specialist who values separation of concerns, testability, and pure Python solutions.
# Style: Encapsulates logic in service classes. Uses Python's built-in `functools.lru_cache`.
# Pros: Excellent separation of web and business logic, highly testable, no external dependencies for caching.
# Cons: `lru_cache` does not support time-based expiration natively, requiring a custom wrapper. Invalidation is less flexible (clears entire function cache).

# To run:
# 1. pip install Flask
# 2. python <filename>.py

import uuid
import time
from datetime import datetime, timezone, timedelta
from enum import Enum
from dataclasses import dataclass, asdict
import json
from typing import Dict, Optional, Callable, Any
from functools import lru_cache, wraps

from flask import Flask, jsonify, request

# --- Domain Schema ---
class UserRole(Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

@dataclass
class User:
    id: uuid.UUID
    email: str
    password_hash: str
    role: UserRole
    is_active: bool
    created_at: datetime

@dataclass
class Post:
    id: uuid.UUID
    user_id: uuid.UUID
    title: str
    content: str
    status: PostStatus

# --- Custom JSON Encoder ---
class DomainJSONEncoder(json.JSONEncoder):
    def default(self, o):
        if isinstance(o, (datetime)):
            return o.isoformat()
        if isinstance(o, uuid.UUID):
            return str(o)
        if isinstance(o, Enum):
            return o.value
        if dataclasses.is_dataclass(o):
            return asdict(o)
        return super().default(o)

# --- Mock "Data Access Objects" (DAOs) ---
class Database:
    _users: Dict[uuid.UUID, User] = {}
    _posts: Dict[uuid.UUID, Post] = {}

    def __init__(self):
        admin_user_id = uuid.uuid4()
        self._users[admin_user_id] = User(id=admin_user_id, email="admin@example.com", password_hash="hash1", role=UserRole.ADMIN, is_active=True, created_at=datetime.now(timezone.utc))
        user_id = uuid.uuid4()
        self._users[user_id] = User(id=user_id, email="user@example.com", password_hash="hash2", role=UserRole.USER, is_active=True, created_at=datetime.now(timezone.utc))
        self._posts[uuid.uuid4()] = Post(id=uuid.uuid4(), user_id=user_id, title="First Post", content="Content here", status=PostStatus.PUBLISHED)

    def get_user(self, user_id: uuid.UUID) -> Optional[User]:
        print(f"--- DB QUERY: SELECT * FROM users WHERE id = {user_id} ---")
        time.sleep(0.5)
        return self._users.get(user_id)

    def update_user_email(self, user_id: uuid.UUID, new_email: str) -> Optional[User]:
        if user := self._users.get(user_id):
            user.email = new_email
            return user
        return None

# --- Caching Layer ---
# Custom decorator to add time-based expiration to lru_cache
def timed_lru_cache(seconds: int, maxsize: int = 128):
    def wrapper_cache(func: Callable) -> Callable:
        func = lru_cache(maxsize=maxsize)(func)
        func.lifetime = timedelta(seconds=seconds)
        func.expiration = datetime.now(timezone.utc) + func.lifetime

        @wraps(func)
        def wrapped_func(*args, **kwargs) -> Any:
            if datetime.now(timezone.utc) >= func.expiration:
                func.cache_clear()
                func.expiration = datetime.now(timezone.utc) + func.lifetime
                print("--- TIMED LRU CACHE: Expired, clearing cache. ---")
            return func(*args, **kwargs)
        return wrapped_func
    return wrapper_cache

# --- Service Layer ---
class UserService:
    def __init__(self, db: Database):
        self.db = db

    # Cache-Aside is implemented by the decorator.
    # LRU is provided by `lru_cache`.
    # Time-based expiration is handled by our custom `timed_lru_cache` wrapper.
    @timed_lru_cache(seconds=60)
    def get_user_by_id(self, user_id: uuid.UUID) -> Optional[User]:
        print(f"--- SERVICE CALL: get_user_by_id({user_id}) ---")
        return self.db.get_user(user_id)

    def update_user(self, user_id: uuid.UUID, email: str) -> Optional[User]:
        user = self.db.update_user_email(user_id, email)
        if user:
            # Invalidation strategy: clear the entire cache for the decorated function.
            # This is a limitation of `lru_cache` but effective.
            print(f"--- CACHE INVALIDATION: Clearing get_user_by_id cache. ---")
            self.get_user_by_id.cache_clear()
        return user

# --- Flask App Setup ---
app = Flask(__name__)
app.json_encoder = DomainJSONEncoder

# Dependency Injection (simplified)
db_instance = Database()
user_service = UserService(db_instance)

# --- API Routes (Controller Layer) ---
@app.route("/users/<uuid:user_id>", methods=["GET"])
def get_user_endpoint(user_id: uuid.UUID):
    user = user_service.get_user_by_id(user_id)
    if not user:
        return jsonify({"error": "User not found"}), 404
    return jsonify(user)

@app.route("/users/<uuid:user_id>", methods=["PUT"])
def update_user_endpoint(user_id: uuid.UUID):
    data = request.get_json()
    if not data or 'email' not in data:
        return jsonify({"error": "Email is required"}), 400
    
    updated_user = user_service.update_user(user_id, data['email'])
    if not updated_user:
        return jsonify({"error": "User not found"}), 404
    
    return jsonify(updated_user)

if __name__ == "__main__":
    app.run(debug=True, port=5001)