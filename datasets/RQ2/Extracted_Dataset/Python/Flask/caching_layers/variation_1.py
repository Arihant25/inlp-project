# Variation 1: The "Classic" Flask Extension Approach
# Developer: A senior engineer who prefers well-supported extensions and declarative syntax.
# Style: Utilizes Flask-Caching for a clean, decorator-based implementation.
# Pros: Highly readable, minimal boilerplate, production-tested.
# Cons: Relies on an external dependency; less granular control than manual implementation.

# To run:
# 1. pip install Flask Flask-Caching
# 2. python <filename>.py

import uuid
import time
from datetime import datetime, timezone
from enum import Enum
from dataclasses import dataclass, asdict
import json
from typing import Dict, Optional

from flask import Flask, jsonify, request
from flask_caching import Cache

# --- Configuration ---
class Config:
    CACHE_TYPE = "SimpleCache"  # In-memory cache
    CACHE_DEFAULT_TIMEOUT = 300  # 5 minutes
    CACHE_THRESHOLD = 500      # Max number of items

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

# --- Custom JSON Encoder for Dataclasses and Enums ---
class CustomJSONEncoder(json.JSONEncoder):
    def default(self, o):
        if isinstance(o, (datetime)):
            return o.isoformat()
        if isinstance(o, uuid.UUID):
            return str(o)
        if isinstance(o, Enum):
            return o.value
        if hasattr(o, '__dict__'):
            return o.__dict__
        return super().default(o)

# --- Mock Database Layer ---
MOCK_USERS: Dict[uuid.UUID, User] = {}
MOCK_POSTS: Dict[uuid.UUID, Post] = {}

def _init_mock_db():
    admin_user_id = uuid.uuid4()
    MOCK_USERS[admin_user_id] = User(
        id=admin_user_id,
        email="admin@example.com",
        password_hash="hashed_admin_pass",
        role=UserRole.ADMIN,
        is_active=True,
        created_at=datetime.now(timezone.utc)
    )
    for i in range(5):
        user_id = uuid.uuid4()
        MOCK_USERS[user_id] = User(
            id=user_id,
            email=f"user{i}@example.com",
            password_hash=f"hashed_pass_{i}",
            role=UserRole.USER,
            is_active=True,
            created_at=datetime.now(timezone.utc)
        )
        post_id = uuid.uuid4()
        MOCK_POSTS[post_id] = Post(
            id=post_id,
            user_id=user_id,
            title=f"Post {i} by User {i}",
            content="This is some sample content.",
            status=PostStatus.PUBLISHED
        )

def find_user_by_id_from_db(user_id: uuid.UUID) -> Optional[User]:
    print(f"--- DATABASE HIT: Fetching user {user_id} ---")
    time.sleep(0.5)  # Simulate DB latency
    return MOCK_USERS.get(user_id)

def find_post_by_id_from_db(post_id: uuid.UUID) -> Optional[Post]:
    print(f"--- DATABASE HIT: Fetching post {post_id} ---")
    time.sleep(0.5)  # Simulate DB latency
    return MOCK_POSTS.get(post_id)

# --- Flask App Initialization ---
app = Flask(__name__)
app.config.from_object(Config)
app.json_encoder = CustomJSONEncoder
cache = Cache(app)

# --- API Endpoints ---

# Cache-Aside pattern is implemented by the @cache.cached decorator.
# It checks the cache first. If the key exists, it returns the cached value.
# If not, it executes the function, caches the result, and then returns it.
@app.route("/users/<uuid:user_id>", methods=["GET"])
@cache.cached(key_prefix='user_%s')
def get_user(user_id: uuid.UUID):
    user = find_user_by_id_from_db(user_id)
    if not user:
        return jsonify({"error": "User not found"}), 404
    return jsonify(asdict(user))

@app.route("/posts/<uuid:post_id>", methods=["GET"])
@cache.cached(key_prefix='post_%s')
def get_post(post_id: uuid.UUID):
    post = find_post_by_id_from_db(post_id)
    if not post:
        return jsonify({"error": "Post not found"}), 404
    return jsonify(asdict(post))

# Cache Invalidation: When data is updated, we must invalidate the cache.
@app.route("/users/<uuid:user_id>", methods=["PUT"])
def update_user(user_id: uuid.UUID):
    if user_id not in MOCK_USERS:
        return jsonify({"error": "User not found"}), 404
    
    update_data = request.json
    user = MOCK_USERS[user_id]
    if 'email' in update_data:
        user.email = update_data['email']
    
    # Invalidate the cache for this specific user
    cache.delete(f'user_{user_id}')
    print(f"--- CACHE INVALIDATED: Deleted key 'user_{user_id}' ---")
    
    return jsonify(asdict(user))

@app.route("/users/<uuid:user_id>", methods=["DELETE"])
def delete_user(user_id: uuid.UUID):
    if user_id not in MOCK_USERS:
        return jsonify({"error": "User not found"}), 404
        
    del MOCK_USERS[user_id]
    
    # Invalidate the cache
    cache.delete(f'user_{user_id}')
    print(f"--- CACHE INVALIDATED: Deleted key 'user_{user_id}' ---")
    
    return "", 204

if __name__ == "__main__":
    _init_mock_db()
    # Example usage:
    # GET http://127.0.0.1:5000/users/<some_uuid> (first time is slow, second is fast)
    # PUT http://127.0.0.1:5000/users/<some_uuid> (invalidates cache)
    # GET http://127.0.0.1:5000/users/<some_uuid> (slow again)
    app.run(debug=True)