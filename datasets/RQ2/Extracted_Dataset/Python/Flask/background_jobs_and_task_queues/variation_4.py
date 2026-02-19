# variation_4.py
# Style: Modern / Service-Layer Abstraction
# Description: Uses type hints, a service layer to decouple web logic from task logic, and advanced Celery patterns.
# To run this example:
# 1. Install packages: pip install flask celery redis
# 2. Start Redis: docker run -d -p 6379:6379 redis
# 3. Start Celery worker: celery -A variation_4.celery_app worker --loglevel=info
# 4. Start Celery beat scheduler: celery -A variation_4.celery_app beat --loglevel=info
# 5. Start Flask app: flask --app variation_4.app run
# 6. Trigger endpoints:
#    - POST http://127.0.0.1:5000/v1/users
#    - POST http://127.0.0.1:5000/v1/posts
#    - GET http://127.0.0.1:5000/v1/tasks/status/<task_id>

from __future__ import annotations
import uuid
import time
import random
from datetime import datetime
from enum import Enum
from dataclasses import dataclass, field
from typing import List, Dict, Any

from flask import Flask, request, jsonify
from celery import Celery, Task, group, chain
from celery.schedules import timedelta as celery_timedelta

# --- Type-hinted Domain Models (using dataclasses) ---
class UserRole(str, Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(str, Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"
    PROCESSING_FAILED = "PROCESSING_FAILED"

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

# --- In-memory Repository (simulates database access) ---
class Repository:
    _users: Dict[uuid.UUID, User] = {}
    _posts: Dict[uuid.UUID, Post] = {}

    @staticmethod
    def save_user(user: User) -> None:
        Repository._users[user.id] = user

    @staticmethod
    def get_user(user_id: uuid.UUID) -> User | None:
        return Repository._users.get(user_id)
    
    @staticmethod
    def save_post(post: Post) -> None:
        Repository._posts[post.id] = post

    @staticmethod
    def get_post(post_id: uuid.UUID) -> Post | None:
        return Repository._posts.get(post_id)
    
    @staticmethod
    def get_active_user_count() -> int:
        return sum(1 for u in Repository._users.values() if u.is_active)

# --- Celery App Factory ---
def create_celery_app(flask_app: Flask) -> Celery:
    celery = Celery(flask_app.import_name,
                    broker='redis://localhost:6379/3',
                    backend='redis://localhost:6379/3')
    celery.conf.update(flask_app.config)
    
    class ContextTask(Task):
        def __call__(self, *args, **kwargs):
            with flask_app.app_context():
                return self.run(*args, **kwargs)
    
    celery.Task = ContextTask
    return celery

# --- Flask App Initialization ---
app = Flask(__name__)
celery_app = create_celery_app(app)

# --- Task Definitions ---
@celery_app.task(bind=True, max_retries=4)
def task_send_email(self: Task, recipient: str, subject: str, body: str) -> Dict[str, Any]:
    print(f"Attempting to send email to {recipient} with subject '{subject}'")
    try:
        time.sleep(2) # Simulate API call
        if random.random() < 0.5: # 50% chance of transient failure
            raise ConnectionRefusedError("Email gateway is busy")
        print(f"Email to {recipient} sent successfully.")
        return {"recipient": recipient, "status": "DELIVERED"}
    except ConnectionRefusedError as exc:
        # Exponential backoff with jitter
        delay = (2 ** self.request.retries) + random.uniform(0, 1)
        print(f"Email failed. Retrying in {delay:.2f} seconds...")
        self.retry(exc=exc, countdown=delay)

@celery_app.task
def task_resize_image(image_data: bytes, width: int, height: int) -> bytes:
    print(f"Resizing image to {width}x{height}...")
    time.sleep(random.uniform(1, 3)) # Simulate CPU-bound work
    return f"resized_{width}x{height}".encode('utf-8')

@celery_app.task
def task_collect_and_publish(resized_images: List[bytes], post_id_str: str) -> None:
    post_id = uuid.UUID(post_id_str)
    print(f"Collecting {len(resized_images)} resized images for post {post_id}.")
    # Here you would upload to S3/CDN
    post = Repository.get_post(post_id)
    if post:
        post.status = PostStatus.PUBLISHED
        Repository.save_post(post)
        print(f"Post {post_id} is now PUBLISHED.")

@celery_app.task(ignore_result=True)
def task_log_metrics() -> None:
    """Periodic task for logging system metrics."""
    print(f"[METRICS @ {datetime.utcnow().isoformat()}] Active Users: {Repository.get_active_user_count()}")

celery_app.conf.beat_schedule = {
    'log-metrics-every-30-seconds': {
        'task': 'variation_4.task_log_metrics',
        'schedule': celery_timedelta(seconds=30),
    },
}

# --- Service Layer ---
class UserService:
    @staticmethod
    def register_new_user(email: str) -> (User, Task):
        new_user = User(email=email, password_hash=str(uuid.uuid4()))
        Repository.save_user(new_user)
        
        email_task = task_send_email.delay(
            recipient=new_user.email,
            subject="Welcome!",
            body="Thanks for signing up."
        )
        return new_user, email_task

class PostService:
    @staticmethod
    def create_new_post(user_id: uuid.UUID, title: str, content: str) -> (Post, Task):
        new_post = Post(user_id=user_id, title=title, content=content)
        Repository.save_post(new_post)
        
        # Image processing pipeline using a Celery group and a final callback task (chord)
        image_data = b"original_image_bytes"
        sizes_to_process = [(150, 150), (600, 400), (1024, 768)]
        
        # Create a group of parallel resize tasks, then chain the collector task
        processing_pipeline = chain(
            group(task_resize_image.s(image_data, w, h) for w, h in sizes_to_process),
            task_collect_and_publish.s(str(new_post.id))
        )
        pipeline_task = processing_pipeline.apply_async()
        
        return new_post, pipeline_task

# --- API Layer (Controllers) ---
@app.route('/v1/users', methods=['POST'])
def handle_create_user():
    email = request.json.get('email')
    if not email:
        return jsonify({"error": "Email is required"}), 400
    
    user, task = UserService.register_new_user(email)
    
    return jsonify({
        "message": "User registration initiated.",
        "user_id": str(user.id),
        "email_task_id": task.id
    }), 202

@app.route('/v1/posts', methods=['POST'])
def handle_create_post():
    data = request.json
    try:
        user_id = uuid.UUID(data['user_id'])
    except (ValueError, KeyError):
        return jsonify({"error": "Valid user_id is required"}), 400
    
    post, task = PostService.create_new_post(user_id, data['title'], data['content'])
    
    return jsonify({
        "message": "Post created. Image processing pipeline started.",
        "post_id": str(post.id),
        "pipeline_task_id": task.id
    }), 202

@app.route('/v1/tasks/status/<task_id>', methods=['GET'])
def handle_get_task_status(task_id: str):
    result = celery_app.AsyncResult(task_id)
    response = {
        "taskId": result.id,
        "status": result.state,
        "info": result.info if result.state not in ('PENDING', 'SUCCESS') else None,
        "result": result.result if result.successful() else None
    }
    return jsonify(response)

if __name__ == '__main__':
    app.run(debug=True)