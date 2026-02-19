# variation_3.py
# Style: Application Factory & Blueprints
# Description: A scalable structure using Flask's application factory pattern and Blueprints.
# To run this example:
# 1. Install packages: pip install flask celery redis
# 2. Start Redis: docker run -d -p 6379:6379 redis
# 3. Set env var: export FLASK_APP=variation_3:create_app()
# 4. Start Celery worker: celery -A variation_3.celery worker --loglevel=info
# 5. Start Celery beat scheduler: celery -A variation_3.celery beat --loglevel=info
# 6. Start Flask app: flask run
# 7. Trigger endpoints:
#    - POST http://127.0.0.1:5000/api/users/
#    - POST http://127.0.0.1:5000/api/posts/
#    - GET http://127.0.0.1:5000/api/tasks/status/<task_id>

import time
import uuid
import random
from datetime import datetime
from enum import Enum

from flask import Flask, Blueprint, jsonify, request
from celery import Celery, shared_task
from celery.schedules import crontab
from kombu.exceptions import OperationalError

# --- Mock Database ---
class MockDB:
    USERS = {}
    POSTS = {}

# --- Celery Initialization (extension-style) ---
celery = Celery(__name__, broker='redis://localhost:6379/2', backend='redis://localhost:6379/2')

# --- Task Definitions (could be in a tasks.py file) ---

class EmailServiceError(Exception):
    pass

@shared_task(autoretry_for=(EmailServiceError, OperationalError), retry_kwargs={'max_retries': 5}, retry_backoff=True)
def send_user_confirmation_email(user_id: str):
    """Sends a confirmation email to a new user."""
    user = MockDB.USERS.get(user_id)
    if not user:
        return f"User {user_id} not found."
    
    print(f"Preparing to send confirmation email to {user['email']}.")
    time.sleep(4) # Simulate network latency
    if random.random() < 0.3: # 30% failure rate
        raise EmailServiceError("Failed to connect to email provider.")
    
    print(f"Email successfully sent to {user['email']}.")
    return {"status": "sent", "email": user['email']}

@shared_task
def image_processing_workflow(post_id: str):
    """A workflow task that calls other sub-tasks."""
    print(f"Starting image workflow for post {post_id}")
    
    # Simulate a chain of operations
    # In a real app, these could be separate @shared_task functions
    _download_image(post_id)
    _generate_thumbnails(post_id)
    _upload_to_cdn(post_id)
    
    MockDB.POSTS[post_id]['status'] = "PUBLISHED"
    print(f"Image workflow for post {post_id} complete. Post is now PUBLISHED.")
    return {"post_id": post_id, "final_status": "PUBLISHED"}

def _download_image(post_id):
    print(f"[{post_id}] Downloading image...")
    time.sleep(2)

def _generate_thumbnails(post_id):
    print(f"[{post_id}] Generating thumbnails (100x100, 640x480)...")
    time.sleep(3)

def _upload_to_cdn(post_id):
    print(f"[{post_id}] Uploading images to CDN...")
    time.sleep(2)

@shared_task
def cleanup_old_drafts():
    """Periodic task to remove old draft posts."""
    print("--- Running periodic task: Cleanup Old Drafts ---")
    one_week_ago = datetime.utcnow() - timedelta(weeks=1)
    posts_to_delete = []
    for post_id, post in MockDB.POSTS.items():
        if post['status'] == "DRAFT" and post['created_at'] < one_week_ago:
            posts_to_delete.append(post_id)
    
    for post_id in posts_to_delete:
        del MockDB.POSTS[post_id]
        print(f"Deleted old draft post: {post_id}")
    
    print(f"--- Cleanup complete. Deleted {len(posts_to_delete)} posts. ---")
    return {"deleted_count": len(posts_to_delete)}

# --- API Blueprint ---
api_bp = Blueprint('api', __name__, url_prefix='/api')

@api_bp.route('/users/', methods=['POST'])
def create_user_endpoint():
    data = request.get_json()
    user_id = str(uuid.uuid4())
    MockDB.USERS[user_id] = {
        "id": user_id, "email": data['email'], "created_at": datetime.utcnow()
    }
    task = send_user_confirmation_email.delay(user_id)
    return jsonify({"message": "User creation in progress", "task_id": task.id}), 202

@api_bp.route('/posts/', methods=['POST'])
def create_post_endpoint():
    data = request.get_json()
    post_id = str(uuid.uuid4())
    MockDB.POSTS[post_id] = {
        "id": post_id, "title": data['title'], "status": "DRAFT", "created_at": datetime.utcnow()
    }
    task = image_processing_workflow.delay(post_id)
    return jsonify({"message": "Post created, processing started", "task_id": task.id}), 202

@api_bp.route('/tasks/status/<task_id>', methods=['GET'])
def task_status_endpoint(task_id):
    task = celery.AsyncResult(task_id)
    return jsonify({
        "id": task.id,
        "state": task.state,
        "meta": task.info if task.state != 'PENDING' else 'No info yet'
    })

# --- Application Factory ---
def create_app():
    app = Flask(__name__)
    app.config.from_mapping(
        SECRET_KEY='dev',
    )
    
    # Update Celery config with Flask app config
    celery.conf.update(
        beat_schedule={
            'cleanup-drafts-daily': {
                'task': 'variation_3.cleanup_old_drafts',
                'schedule': crontab(hour=2, minute=30), # Every day at 2:30 AM
            },
        }
    )
    
    # Finalize Celery-Flask integration
    class FlaskTask(celery.Task):
        def __call__(self, *args, **kwargs):
            with app.app_context():
                return self.run(*args, **kwargs)
    
    celery.Task = FlaskTask
    
    app.register_blueprint(api_bp)
    
    return app

if __name__ == '__main__':
    # This part is for direct execution, not for `flask run`
    app = create_app()
    app.run(debug=True)