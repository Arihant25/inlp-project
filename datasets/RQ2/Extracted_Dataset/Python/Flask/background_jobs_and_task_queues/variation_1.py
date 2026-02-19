# variation_1.py
# Style: Classic Functional Approach
# Description: A single-file, straightforward implementation using functional task definitions.
# To run this example:
# 1. Install packages: pip install flask celery redis
# 2. Start Redis: docker run -d -p 6379:6379 redis
# 3. Start Celery worker: celery -A variation_1.celery_app worker --loglevel=info
# 4. Start Celery beat scheduler: celery -A variation_1.celery_app beat --loglevel=info
# 5. Start Flask app: flask --app variation_1 run
# 6. Trigger endpoints:
#    - POST http://127.0.0.1:5000/register
#    - POST http://127.0.0.1:5000/posts
#    - GET http://127.0.0.1:5000/tasks/<task_id>

import os
import time
import uuid
import random
from datetime import datetime, timedelta
from enum import Enum

from flask import Flask, jsonify, request
from celery import Celery, Task
from celery.schedules import crontab

# --- Domain Models (Mocked) ---

class UserRole(Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

# In-memory "database" for demonstration
DB = {
    "users": {},
    "posts": {},
}

# --- Flask & Celery Configuration ---

def make_celery(app: Flask) -> Celery:
    """Flask-Celery integration helper."""
    celery = Celery(
        app.import_name,
        backend=app.config['CELERY_RESULT_BACKEND'],
        broker=app.config['CELERY_BROKER_URL'],
        include=['variation_1']
    )
    celery.conf.update(app.config)

    class ContextTask(Task):
        def __call__(self, *args, **kwargs):
            with app.app_context():
                return self.run(*args, **kwargs)

    celery.Task = ContextTask
    return celery

flask_app = Flask(__name__)
flask_app.config.update(
    CELERY_BROKER_URL='redis://localhost:6379/0',
    CELERY_RESULT_BACKEND='redis://localhost:6379/0',
    CELERY_BEAT_SCHEDULE={
        'generate-daily-report': {
            'task': 'variation_1.generate_daily_report',
            'schedule': crontab(hour=0, minute=0), # Run daily at midnight
        },
    }
)

celery_app = make_celery(flask_app)

# --- Celery Tasks ---

@celery_app.task(bind=True)
def send_welcome_email(self, user_id: str):
    """Async task to send a welcome email."""
    user = DB["users"].get(user_id)
    if not user:
        print(f"User with ID {user_id} not found. Cannot send email.")
        return
    
    print(f"Starting to send welcome email to {user['email']}...")
    try:
        # Simulate a network call to an email service
        time.sleep(5)
        if random.random() < 0.2: # 20% chance of failure
            raise ConnectionError("SMTP server is down")
        print(f"Successfully sent welcome email to {user['email']}.")
        return {"status": "success", "email": user['email']}
    except ConnectionError as exc:
        print(f"Email sending failed for {user['email']}. Retrying...")
        # Retry with exponential backoff: 2s, 4s, 8s, ...
        raise self.retry(exc=exc, countdown=2 ** self.request.retries, max_retries=5)

@celery_app.task
def process_post_image_pipeline(post_id: str, image_url: str):
    """Async task simulating a multi-step image processing pipeline."""
    print(f"Starting image pipeline for post {post_id} from URL: {image_url}")
    
    # Step 1: Download image
    time.sleep(3)
    print(f"Step 1/3: Image downloaded for post {post_id}")
    
    # Step 2: Resize to multiple formats
    time.sleep(5)
    print(f"Step 2/3: Image resized (thumbnail, medium, large) for post {post_id}")
    
    # Step 3: Upload to a CDN
    time.sleep(4)
    print(f"Step 3/3: Resized images uploaded to CDN for post {post_id}")
    
    # Final step: Update post status in DB (mock)
    if post_id in DB["posts"]:
        DB["posts"][post_id]["status"] = PostStatus.PUBLISHED.value
        print(f"Post {post_id} status updated to PUBLISHED.")
    
    return {"status": "complete", "post_id": post_id}

@celery_app.task
def generate_daily_report():
    """Periodic task to generate a daily report."""
    print("\n--- Generating Daily Report ---")
    user_count = len(DB["users"])
    post_count = len(DB["posts"])
    print(f"Report Time: {datetime.utcnow()}")
    print(f"Total Users: {user_count}")
    print(f"Total Posts: {post_count}")
    print("--- Daily Report Generation Complete ---\n")
    return {"user_count": user_count, "post_count": post_count}

# --- Flask API Endpoints ---

@flask_app.route('/register', methods=['POST'])
def register_user():
    """Endpoint to register a new user and trigger a welcome email."""
    data = request.json
    user_id = str(uuid.uuid4())
    new_user = {
        "id": user_id,
        "email": data.get("email"),
        "password_hash": "mock_hash_" + str(random.randint(1000, 9999)),
        "role": UserRole.USER.value,
        "is_active": True,
        "created_at": datetime.utcnow().isoformat()
    }
    DB["users"][user_id] = new_user
    
    # Enqueue the async email task
    task = send_welcome_email.delay(user_id)
    
    return jsonify({
        "message": "User registered successfully. Welcome email is being sent.",
        "user_id": user_id,
        "task_id": task.id
    }), 202

@flask_app.route('/posts', methods=['POST'])
def create_post():
    """Endpoint to create a post and trigger an image processing pipeline."""
    data = request.json
    post_id = str(uuid.uuid4())
    new_post = {
        "id": post_id,
        "user_id": data.get("user_id"),
        "title": data.get("title"),
        "content": data.get("content"),
        "status": PostStatus.DRAFT.value
    }
    DB["posts"][post_id] = new_post
    
    # Enqueue the image processing task
    image_url = data.get("image_url", "https://example.com/default.jpg")
    task = process_post_image_pipeline.delay(post_id, image_url)
    
    return jsonify({
        "message": "Post created. Image processing has started.",
        "post_id": post_id,
        "task_id": task.id
    }), 202

@flask_app.route('/tasks/<task_id>', methods=['GET'])
def get_task_status(task_id: str):
    """Endpoint to check the status of a Celery task."""
    task = celery_app.AsyncResult(task_id)
    
    response = {
        "task_id": task_id,
        "status": task.state,
    }
    if task.state == 'PENDING':
        response['info'] = 'Task is waiting in the queue or not yet started.'
    elif task.state == 'FAILURE':
        response['info'] = str(task.info)  # Exception information
    elif task.state == 'SUCCESS':
        response['result'] = task.result
        
    return jsonify(response)

if __name__ == '__main__':
    flask_app.run(debug=True)