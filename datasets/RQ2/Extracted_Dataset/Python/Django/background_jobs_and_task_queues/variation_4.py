# VARIATION 4: The "Pragmatic & Minimalist" Developer
# STYLE: Concise, direct, and leverages built-in Celery features with minimal boilerplate.
# Uses Celery's `chain` for pipelines and simple `autoretry_for` for retries.
# Status tracking relies purely on the standard Celery Result Backend.

import os
import uuid
from datetime import timedelta
from enum import Enum

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
        CELERY_BEAT_SCHEDULE={
            'daily-user-cleanup': {
                'task': '__main__.cleanup_users',
                'schedule': timedelta(hours=24),
            },
        }
    )
    import django
    django.setup()

# --- Celery App Definition (myproject/celery.py) ---
from celery import Celery, shared_task, chain
celery_app = Celery('myproject')
celery_app.config_from_object('django.conf:settings', namespace='CELERY')
celery_app.autodiscover_tasks()

# --- Models (core/models.py) ---
class User(models.Model):
    class Role(models.TextChoices): ADMIN = 'ADMIN'; USER = 'USER'
    id = models.UUIDField(primary_key=True, default=uuid.uuid4)
    email = models.EmailField(unique=True)
    password_hash = models.CharField(max_length=128)
    role = models.CharField(max_length=10, choices=Role.choices, default=Role.USER)
    is_active = models.BooleanField(default=True)
    created_at = models.DateTimeField(auto_now_add=True)

class Post(models.Model):
    class Status(models.TextChoices): DRAFT = 'DRAFT'; PUBLISHED = 'PUBLISHED'
    id = models.UUIDField(primary_key=True, default=uuid.uuid4)
    user = models.ForeignKey(User, on_delete=models.CASCADE)
    title = models.CharField(max_length=255)
    content = models.TextField()
    status = models.CharField(max_length=10, choices=Status.choices, default=Status.DRAFT)
    image_url = models.CharField(max_length=255, blank=True)

# --- Tasks (core/tasks.py) ---
from PIL import Image, ImageDraw
import io, base64
from django.utils import timezone

@shared_task(autoretry_for=(Exception,), retry_kwargs={'max_retries': 5, 'countdown': 30})
def notify(user_id, subject, message):
    """Sends an email, retries on any failure."""
    user = User.objects.get(id=user_id)
    print(f"Notifying {user.email} with subject: {subject}")
    django_send_mail(subject, message, 'noreply@example.com', [user.email])
    return {'status': 'ok', 'user_id': str(user_id)}

# Image processing pipeline is broken into small, chainable tasks
@shared_task
def resize(img_b64, width=800, height=800):
    img = Image.open(io.BytesIO(base64.b64decode(img_b64)))
    img.thumbnail((width, height))
    buf = io.BytesIO()
    img.save(buf, format='PNG')
    return base64.b64encode(buf.getvalue()).decode('utf-8')

@shared_task
def watermark(img_b64, text):
    img = Image.open(io.BytesIO(base64.b64decode(img_b64)))
    draw = ImageDraw.Draw(img)
    draw.text((10, 10), text, fill='white')
    buf = io.BytesIO()
    img.save(buf, format='PNG')
    return base64.b64encode(buf.getvalue()).decode('utf-8')

@shared_task
def save_img(img_b64, post_id):
    # Simulate saving to a CDN and updating the Post model
    url = f"https://cdn.example.com/{post_id}.png"
    Post.objects.filter(id=post_id).update(image_url=url)
    return {'url': url}

@shared_task
def cleanup_users():
    """Periodic task to deactivate old users."""
    stale_date = timezone.now() - timedelta(days=90)
    num_deleted = User.objects.filter(is_active=True, created_at__lt=stale_date).update(is_active=False)
    print(f"Cleaned up {num_deleted} stale users.")
    return f"Deactivated {num_deleted} users."

# --- Views (core/views.py) ---
class UserSignupView(View):
    def post(self, request, *args, **kwargs):
        user = User.objects.create(email=f'user.{uuid.uuid4().hex[:6]}@example.com')
        notify.delay(user.id, "Welcome!", "Thanks for signing up.")
        return JsonResponse({'status': 'user created'}, status=201)

class PostCreateView(View):
    def post(self, request, *args, **kwargs):
        user, _ = User.objects.get_or_create(email='author@example.com')
        post = Post.objects.create(user=user, title="Chaining Tasks")

        # Create dummy image
        img = Image.new('RGB', (1024, 768), color='green')
        buf = io.BytesIO()
        img.save(buf, format='PNG')
        img_b64 = base64.b64encode(buf.getvalue()).decode('utf-8')

        # Create and run the processing pipeline using a chain
        pipeline = chain(
            resize.s(img_b64),
            watermark.s(f"Post by {user.email}"),
            save_img.s(post.id)
        )
        result = pipeline.apply_async()

        return JsonResponse({
            'message': 'Image processing started.',
            'task_id': result.id
        }, status=202)

class TaskStatusView(View):
    def get(self, request, *args, **kwargs):
        task_id = request.GET.get('task_id')
        if not task_id:
            return JsonResponse({'error': 'missing task_id'}, status=400)
        
        # Use Celery's native result object for status tracking
        result = celery_app.AsyncResult(task_id)
        
        # For a chain, the result is the result of the *last* task in the chain.
        # The state will reflect the overall state of the chain.
        response = {
            'id': result.id,
            'status': result.state,
            'info': result.info if result.state != 'FAILURE' else str(result.info)
        }
        return JsonResponse(response)