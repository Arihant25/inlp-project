# variation_2.py
# Style: OOP / Class-Based Tasks
# Description: Uses Celery's class-based Tasks for better organization and shared behavior.
# To run this example:
# 1. Install packages: pip install flask celery redis
# 2. Start Redis: docker run -d -p 6379:6379 redis
# 3. Start Celery worker: celery -A variation_2.celery_instance worker --loglevel=info
# 4. Start Celery beat scheduler: celery -A variation_2.celery_instance beat --loglevel=info
# 5. Start Flask app: flask --app variation_2 run
# 6. Trigger endpoints:
#    - POST http://127.0.0.1:5000/users
#    - POST http://127.0.0.1:5000/posts
#    - GET http://127.0.0.1:5000/job-status/<job_id>

import uuid
import time
import random
from datetime import datetime
from enum import Enum
from typing import Dict, Any

from flask import Flask, request, jsonify
from celery import Celery, Task
from celery.schedules import crontab
from celery.utils.log import get_task_logger

# --- Mock Data Store ---
class MockDataStore:
    _users: Dict[str, Dict] = {}
    _posts: Dict[str, Dict] = {}

    @classmethod
    def get_user(cls, user_id: str) -> Dict:
        return cls._users.get(user_id)

    @classmethod
    def add_user(cls, user_data: Dict) -> None:
        cls._users[user_data['id']] = user_data

    @classmethod
    def get_post(cls, post_id: str) -> Dict:
        return cls._posts.get(post_id)

    @classmethod
    def add_post(cls, post_data: Dict) -> None:
        cls._posts[post_data['id']] = post_data

    @classmethod
    def update_post_status(cls, post_id: str, status: str) -> None:
        if post := cls._posts.get(post_id):
            post['status'] = status

    @classmethod
    def count_all(cls) -> Dict[str, int]:
        return {"users": len(cls._users), "posts": len(cls._posts)}

# --- Flask App and Celery Initialization ---
flask_app_instance = Flask(__name__)
flask_app_instance.config.from_mapping(
    CELERY_BROKER_URL='redis://localhost:6379/1',
    CELERY_RESULT_BACKEND='redis://localhost:6379/1',
    CELERY_TASK_SERIALIZER='json',
    CELERY_RESULT_SERIALIZER='json',
    CELERY_ACCEPT_CONTENT=['json'],
    CELERY_TIMEZONE='UTC',
    CELERY_ENABLE_UTC=True,
)

celery_instance = Celery(flask_app_instance.name, broker=flask_app_instance.config['CELERY_BROKER_URL'])
celery_instance.conf.update(flask_app_instance.config)
celery_instance.conf.beat_schedule = {
    'run-periodic-audit-every-2-minutes': {
        'task': 'variation_2.SystemAuditTask',
        'schedule': crontab(minute='*/2'),
    },
}

# --- Base Task Class ---
class BaseTask(Task):
    abstract = True
    _logger = None

    @property
    def logger(self):
        if self._logger is None:
            self._logger = get_task_logger(self.name)
        return self._logger

    def on_failure(self, exc, task_id, args, kwargs, einfo):
        self.logger.error(f'Task {task_id} failed: {exc}', exc_info=einfo)

    def on_success(self, retval, task_id, args, kwargs):
        self.logger.info(f'Task {task_id} completed successfully.')

# --- Class-Based Task Definitions ---
class UserNotificationTask(BaseTask):
    name = 'variation_2.UserNotificationTask'
    max_retries = 3
    default_retry_delay = 60  # 1 minute

    def run(self, user_id: str):
        user_record = MockDataStore.get_user(user_id)
        if not user_record:
            self.logger.warning(f"User {user_id} not found for notification.")
            return
        
        self.logger.info(f"Sending registration email to {user_record['email']}...")
        try:
            # Simulate external service call
            time.sleep(3)
            if random.random() > 0.8:
                raise IOError("Email service connection timed out")
            self.logger.info(f"Email sent to {user_record['email']}.")
            return {"message": "Email sent", "recipient": user_record['email']}
        except IOError as e:
            self.logger.warning(f"Retrying email for {user_id} due to: {e}")
            self.retry(exc=e, countdown=int(self.default_retry_delay * (1.5 ** self.request.retries)))

class ImageProcessingPipelineTask(BaseTask):
    name = 'variation_2.ImageProcessingPipelineTask'

    def run(self, post_id: str, image_url: str):
        self.logger.info(f"Starting image pipeline for post {post_id}")
        
        # Using Celery chains for a clear pipeline
        pipeline = (
            self.download_image.s(image_url) |
            self.resize_variants.s() |
            self.upload_to_storage.s(post_id)
        )
        result = pipeline.apply_async()
        return {"pipeline_status": "started", "chain_id": result.id}

    @celery_instance.task(base=BaseTask)
    def download_image(image_url: str) -> bytes:
        print(f"Downloading from {image_url}...")
        time.sleep(2)
        return b"imagedata"

    @celery_instance.task(base=BaseTask)
    def resize_variants(image_data: bytes) -> Dict[str, bytes]:
        print("Resizing image to thumbnail and full...")
        time.sleep(4)
        return {"thumb": b"thumbdata", "full": b"fulldata"}

    @celery_instance.task(base=BaseTask)
    def upload_to_storage(variants: Dict[str, bytes], post_id: str) -> str:
        print(f"Uploading {len(variants)} variants for post {post_id}...")
        time.sleep(3)
        MockDataStore.update_post_status(post_id, "PUBLISHED")
        print(f"Post {post_id} is now PUBLISHED.")
        return f"cdn.com/{post_id}"

class SystemAuditTask(BaseTask):
    name = 'variation_2.SystemAuditTask'

    def run(self, *args, **kwargs):
        self.logger.info("--- Running Periodic System Audit ---")
        counts = MockDataStore.count_all()
        self.logger.info(f"Current State: {counts['users']} users, {counts['posts']} posts.")
        self.logger.info("--- System Audit Complete ---")
        return counts

# Register tasks
user_notification_task = celery_instance.register_task(UserNotificationTask())
image_pipeline_task = celery_instance.register_task(ImageProcessingPipelineTask())
system_audit_task = celery_instance.register_task(SystemAuditTask())

# --- Flask API Routes ---
@flask_app_instance.route('/users', methods=['POST'])
def create_user():
    user_id = str(uuid.uuid4())
    user_data = {
        "id": user_id, "email": request.json['email'], "password_hash": "...",
        "role": "USER", "is_active": True, "created_at": datetime.utcnow()
    }
    MockDataStore.add_user(user_data)
    
    task = user_notification_task.delay(user_id=user_id)
    return jsonify({"status": "user created", "user_id": user_id, "job_id": task.id}), 202

@flask_app_instance.route('/posts', methods=['POST'])
def create_post():
    post_id = str(uuid.uuid4())
    post_data = {
        "id": post_id, "user_id": request.json['user_id'], "title": request.json['title'],
        "content": "...", "status": "DRAFT"
    }
    MockDataStore.add_post(post_data)
    
    task = image_pipeline_task.delay(post_id=post_id, image_url=request.json['image_url'])
    return jsonify({"status": "post created", "post_id": post_id, "job_id": task.id}), 202

@flask_app_instance.route('/job-status/<job_id>', methods=['GET'])
def get_job_status(job_id: str):
    task = celery_instance.AsyncResult(job_id)
    return jsonify({
        "jobId": job_id,
        "state": task.state,
        "result": task.result if task.successful() else str(task.info)
    })

if __name__ == '__main__':
    flask_app_instance.run(port=5000)