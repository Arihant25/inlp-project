# Variation 4: The "Modern & Modular" Approach
# Developer: A Pythonista who prefers modern, clean, and decoupled code using specialized libraries.
# Style: Uses the `cachetools` library for a robust cache implementation. Logic is separated into a data access layer.
# Pros: Uses a powerful, dedicated caching library (`cachetools`). Clean separation of concerns (API vs. Data Layer). `TTLCache` handles both size and time expiration elegantly.
# Cons: Adds another dependency. The indirection of a data layer might be overkill for very simple apps.

# To run:
# 1. pip install Flask cachetools
# 2. python <filename>.py

import uuid
import time
from datetime import datetime, timezone
from enum import Enum
from dataclasses import dataclass, asdict
import json
from typing import Dict, Optional

from flask import Flask, jsonify, request
from cachetools import TTLCache, cached

# --- Cache Module ---
# `TTLCache` (Time-To-Live Cache) automatically handles time-based expiration.
# `maxsize` provides LRU-style eviction when the cache is full.
# This is a global, thread-safe cache instance.
user_cache = TTLCache(maxsize=100, ttl=300)
post_cache = TTLCache(maxsize=500, ttl=180)

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
class DataclassJSONEncoder(json.JSONEncoder):
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

# --- Mock Persistence Layer ---
class Persistence:
    _users: Dict[uuid.UUID, User] = {}
    _posts: Dict[uuid.UUID, Post] = {}

    @classmethod
    def initialize(cls):
        user_id = uuid.uuid4()
        cls._users[user_id] = User(id=user_id, email="persistent.user@example.com", password_hash="... ", role=UserRole.USER, is_active=True, created_at=datetime.now(timezone.utc))
        post_id = uuid.uuid4()
        cls._posts[post_id] = Post(id=post_id, user_id=user_id, title="A Cached Post", content="Hello world", status=PostStatus.PUBLISHED)

    @classmethod
    def get_user(cls, user_id: uuid.UUID) -> Optional[User]:
        print(f"--- DATABASE READ: User {user_id} ---")
        time.sleep(0.5)
        return cls._users.get(user_id)

    @classmethod
    def update_user(cls, user: User) -> None:
        print(f"--- DATABASE WRITE: User {user.id} ---")
        cls._users[user.id] = user

# --- Data Access Layer (with Caching Logic) ---
# The `@cached` decorator from `cachetools` implements the Cache-Aside pattern.
@cached(cache=user_cache)
def fetch_user_by_id(user_id: uuid.UUID) -> Optional[User]:
    print(f"--- DATA LAYER: Fetching user {user_id} from persistence. ---")
    return Persistence.get_user(user_id)

def update_user_email(user_id: uuid.UUID, new_email: str) -> Optional[User]:
    user = fetch_user_by_id(user_id)
    if user:
        user.email = new_email
        Persistence.update_user(user)
        # Cache Invalidation: Manually remove the item from the cache.
        # `cachetools` makes this easy.
        try:
            del user_cache[(user_id,)] # The key is a tuple of args
            print(f"--- CACHE INVALIDATION: User {user_id} removed from cache. ---")
        except KeyError:
            pass # Key might have already expired
        return user
    return None

# --- Flask Application (API Layer) ---
app = Flask(__name__)
app.json_encoder = DataclassJSONEncoder

@app.route("/users/<uuid:user_id>", methods=["GET"])
def handle_get_user(user_id: uuid.UUID):
    user = fetch_user_by_id(user_id)
    if user is None:
        return jsonify({"message": "User not found"}), 404
    return jsonify(user)

@app.route("/users/<uuid:user_id>", methods=["PUT"])
def handle_update_user(user_id: uuid.UUID):
    payload = request.get_json()
    if not payload or "email" not in payload:
        return jsonify({"message": "Missing 'email' in request body"}), 400
    
    updated_user = update_user_email(user_id, payload["email"])
    if updated_user is None:
        return jsonify({"message": "User not found"}), 404
        
    return jsonify(updated_user)

if __name__ == "__main__":
    Persistence.initialize()
    app.run(debug=True, port=5003)