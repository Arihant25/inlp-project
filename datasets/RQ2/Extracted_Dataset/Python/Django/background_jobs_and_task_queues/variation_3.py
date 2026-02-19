# VARIATION 3: The "Modern & Type-Hinted" Developer
# STYLE: Uses modern Python (type hints), custom base task classes, and a modular task structure.
# Leverages Celery's 'chain' for pipelines and `autoretry_for` for clean retry logic.
# Configuration is handled in a dedicated celery.py file.

from __future__ import annotations

import os
import uuid
from datetime import timedelta
from enum import Enum
from typing import Any, Dict, Type

from django.db import models
from django.conf import settings
from django.core.mail import send_mail as django_send_mail
from django.http import JsonResponse
from django.views import View

# --- Mock Django Setup ---
def configure_django():
    settings.configure(
        SECRET_KEY='a-secret-key',
        INSTALLED_APPS=['__main__'],
        DATABASES={'default': {'ENGINE': 'django.db.backends.sqlite3', 'NAME': ':memory:'}},
        CELERY_BROKER_URL='redis://localhost:6379/0',
        CELERY_RESULT_BACKEND='redis://localhost:6379/0',
    )
    import django
    django.setup()

# --- Celery App Definition (myproject/celery.py) ---
from celery import Celery, Task, chain
from celery.schedules import crontab

os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'myproject.settings')
app = Celery('myproject')
app.config_from_object('django.conf:settings', namespace='CELERY')

# --- Models (core/models.py) ---
class UserRole(str, Enum): ADMIN = "ADMIN"; USER = "USER"
class PostStatus(str, Enum): DRAFT = "DRAFT"; PUBLISHED = "PUBLISHED"

class User(models.Model):
    id: models.UUIDField = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    email: models.EmailField = models.EmailField(unique=True)
    password_hash: models.CharField = models.CharField(max_length=128)
    role: models.CharField = models.CharField(max_length=10, choices=[(r.name, r.value) for r in UserRole])
    is_active: models.BooleanField = models.BooleanField(default=True)
    created_at: models.DateTimeField = models.DateTimeField(auto_now_add=True)

class Post(models.Model):
    id: models.UUIDField = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user: models.ForeignKey = models.ForeignKey(User, on_delete=models.CASCADE)
    title: models.CharField = models.CharField(max_length=255)
    content: models.TextField = models.TextField()
    status: models.CharField = models.CharField(max_length=10, choices=[(s.name, s.value) for s in PostStatus])
    final_image_url: models.URLField = models.URLField(blank=True, null=True)

# --- Custom Base Task (core/tasks/base.py) ---
class LoggingTask(Task):
    def on_failure(self, exc: Exception, task_id: str, args: tuple, kwargs: dict, einfo: Any) -> None:
        print(f"TASK FAILED: {self.name}[{task_id}] with error: {exc}")
        super().on_failure(exc, task_id, args, kwargs, einfo)

    def on_success(self, retval: Any, task_id: str, args: tuple, kwargs: dict) -> None:
        print(f"TASK SUCCEEDED: {self.name}[{task_id}] with result: {retval}")
        super().on_success(retval, task_id, args, kwargs)

# --- Tasks (core/tasks/email_tasks.py) ---
@app.task(
    base=LoggingTask,
    autoretry_for=(IOError,),  # Example of a transient error
    retry_backoff=2,           # Exponential backoff factor
    retry_jitter=True,         # Adds randomness to avoid thundering herd
    max_retries=5
)
def send_user_email(user_id: uuid.UUID, subject: str, message: str) -> None:
    user = User.objects.get(pk=user_id)
    print(f"Attempting to send email to {user.email}")
    django_send_mail(subject, message, 'system@example.com', [user.email])

# --- Tasks (core/tasks/image_tasks.py) ---
from PIL import Image, ImageDraw
import io, base64

@app.task(base=LoggingTask)
def resize_image(image_b64: str, size: tuple[int, int] = (1200, 630)) -> str:
    image_bytes = base64.b64decode(image_b64)
    image = Image.open(io.BytesIO(image_bytes))
    image.thumbnail(size)
    
    buffer = io.BytesIO()
    image.save(buffer, format='JPEG')
    return base64.b64encode(buffer.getvalue()).decode('utf-8')

@app.task(base=LoggingTask)
def apply_watermark(image_b64: str, text: str) -> str:
    image_bytes = base64.b64decode(image_b64)
    image = Image.open(io.BytesIO(image_bytes))
    draw = ImageDraw.Draw(image)
    draw.text((20, 20), text, fill="rgba(255, 255, 255, 0.7)")
    
    buffer = io.BytesIO()
    image.save(buffer, format='JPEG')
    return base64.b64encode(buffer.getvalue()).decode('utf-8')

@app.task(base=LoggingTask)
def store_final_image(image_b64: str, post_id: uuid.UUID) -> Dict[str, str]:
    # In a real app, this would upload to S3 or another storage service
    # and return the public URL.
    final_url = f"https://cdn.example.com/images/{post_id}.jpg"
    post = Post.objects.get(pk=post_id)
    post.final_image_url = final_url
    post.save(update_fields=['final_image_url'])
    return {'post_id': str(post_id), 'url': final_url}

# --- Tasks (core/tasks/user_tasks.py) ---
from django.utils import timezone

@app.task(base=LoggingTask)
def deactivate_stale_users() -> str:
    ninety_days_ago = timezone.now() - timedelta(days=90)
    count, _ = User.objects.filter(
        is_active=True,
        created_at__lt=ninety_days_ago
    ).update(is_active=False)
    return f"Deactivated {count} stale users."

# --- Celery Beat Schedule (in myproject/celery.py or settings) ---
app.conf.beat_schedule = {
    'deactivate-stale-users-nightly': {
        'task': '__main__.deactivate_stale_users',
        'schedule': crontab(hour=2, minute=30), # Runs at 2:30 AM UTC
    },
}
app.autodiscover_tasks(packages=['__main__'])

# --- Views (core/views.py) ---
class UserSignupView(View):
    def post(self, request, *args, **kwargs) -> JsonResponse:
        user = User.objects.create(email=f'user-{uuid.uuid4()}@example.com')
        send_user_email.delay(
            user_id=user.id,
            subject="Welcome!",
            message=f"Hello {user.email}, welcome to our service."
        )
        return JsonResponse({"status": "user created"}, status=201)

class PostCreateView(View):
    def post(self, request, *args, **kwargs) -> JsonResponse:
        user, _ = User.objects.get_or_create(email='author@example.com')
        post = Post.objects.create(user=user, title="My Awesome Post")
        
        # Create dummy image
        img = Image.new('RGB', (1920, 1080), color='purple')
        buffer = io.BytesIO()
        img.save(buffer, format='JPEG')
        image_b64 = base64.b64encode(buffer.getvalue()).decode('utf-8')
        
        # Define the image processing pipeline using a chain
        pipeline = chain(
            resize_image.s(image_b64),
            apply_watermark.s(f"© {user.email}"),
            store_final_image.s(post_id=post.id)
        )
        task_result = pipeline.apply_async()
        
        return JsonResponse({
            "message": "Post created, image processing pipeline initiated.",
            "pipeline_id": task_result.id
        }, status=202)

class TaskStatusView(View):
    def get(self, request, *args, **kwargs) -> JsonResponse:
        task_id = request.GET.get('task_id')
        result = app.AsyncResult(task_id)
        response_data = {
            'task_id': task_id,
            'status': result.status,
            'result': result.result if result.ready() else None
        }
        return JsonResponse(response_data)