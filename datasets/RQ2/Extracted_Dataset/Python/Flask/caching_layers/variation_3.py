# Variation 3: The "Manual & Explicit" Approach with Blueprints
# Developer: A systems programmer who wants full control and transparency, avoiding "magic".
# Style: A custom-built, thread-safe LRU cache class. Logic is explicitly written in route handlers. Blueprints organize the code.
# Pros: Complete control over cache behavior (eviction, expiration), no dependencies, logic is very clear.
# Cons: More verbose, higher chance of implementation errors, requires manual thread-safety management.

# To run:
# 1. pip install Flask
# 2. python <filename>.py

import uuid
import time
from datetime import datetime, timezone, timedelta
from enum import Enum
from dataclasses import dataclass, asdict
import json
from typing import Dict, Optional, Any
from collections import OrderedDict
from threading import Lock

from flask import Flask, jsonify, request, Blueprint

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
class AppJsonEncoder(json.JSONEncoder):
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

# --- Custom In-Memory LRU Cache Implementation ---
class ManualLRUCache:
    def __init__(self, capacity: int = 128):
        self.cache = OrderedDict()
        self.capacity = capacity
        self.lock = Lock()

    def get(self, key: str) -> Optional[Any]:
        with self.lock:
            if key not in self.cache:
                return None
            
            # Check for time-based expiration
            value, expiry = self.cache[key]
            if datetime.now(timezone.utc) > expiry:
                self.cache.pop(key)
                print(f"--- CACHE EXPIRED: Key '{key}' removed. ---")
                return None

            # Move to end to signify recent use (for LRU)
            self.cache.move_to_end(key)
            return value

    def set(self, key: str, value: Any, ttl_seconds: int = 300):
        with self.lock:
            expiry = datetime.now(timezone.utc) + timedelta(seconds=ttl_seconds)
            self.cache[key] = (value, expiry)
            self.cache.move_to_end(key)
            if len(self.cache) > self.capacity:
                # Evict the least recently used item
                lru_key, _ = self.cache.popitem(last=False)
                print(f"--- CACHE EVICTED (LRU): Key '{lru_key}' removed. ---")

    def delete(self, key: str):
        with self.lock:
            if key in self.cache:
                del self.cache[key]
                print(f"--- CACHE DELETED: Key '{key}' removed. ---")

# --- Mock Database ---
DB_USERS: Dict[uuid.UUID, User] = {}
DB_POSTS: Dict[uuid.UUID, Post] = {}
user_id_1 = uuid.uuid4()
DB_USERS[user_id_1] = User(id=user_id_1, email="test@test.com", password_hash="abc", role=UserRole.USER, is_active=True, created_at=datetime.now(timezone.utc))

# --- App Factory and Blueprint Setup ---
# Global cache instance
app_cache = ManualLRUCache(capacity=100)

user_bp = Blueprint('user_bp', __name__)

@user_bp.route("/users/<uuid:user_id>", methods=["GET"])
def get_user_by_id(user_id: uuid.UUID):
    cache_key = f"user::{user_id}"
    
    # 1. Cache-Aside: Check cache first
    cached_user_dict = app_cache.get(cache_key)
    if cached_user_dict:
        print(f"--- CACHE HIT: Found user {user_id} in cache. ---")
        return jsonify(cached_user_dict)
    
    # 2. Cache Miss: Fetch from "database"
    print(f"--- CACHE MISS: Fetching user {user_id} from DB. ---")
    time.sleep(0.5) # Simulate latency
    user = DB_USERS.get(user_id)
    
    if not user:
        return jsonify({"error": "User not found"}), 404
        
    user_dict = asdict(user)
    
    # 3. Populate cache
    app_cache.set(cache_key, user_dict, ttl_seconds=60)
    
    return jsonify(user_dict)

@user_bp.route("/users/<uuid:user_id>", methods=["DELETE"])
def delete_user_by_id(user_id: uuid.UUID):
    if user_id in DB_USERS:
        del DB_USERS[user_id]
        # Cache Invalidation
        cache_key = f"user::{user_id}"
        app_cache.delete(cache_key)
        return "", 204
    return jsonify({"error": "User not found"}), 404

def create_app():
    app = Flask(__name__)
    app.json_encoder = AppJsonEncoder
    app.register_blueprint(user_bp)
    return app

if __name__ == "__main__":
    flask_app = create_app()
    flask_app.run(debug=True, port=5002)