# VARIATION 2: The "Service-Oriented" Developer
# STYLE: OOP, logic encapsulated in service classes. Tasks are thin wrappers.
# Promotes separation of concerns and testability of business logic.
# Uses a custom Job model for more granular status tracking.

import os
import uuid
from datetime import timedelta
from enum import Enum

from django.db import models, transaction
from django.conf import settings
from django.core.mail import send_mail as django_send_mail
from django.utils import timezone
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
            'cleanup_stale_accounts_daily': {
                'task': '__main__.run_user_account_service_cleanup',
                'schedule': timedelta(days=1),
            },
        }
    )
    import django
    django.setup()

# --- Celery App Definition (myproject/celery.py) ---
from celery import Celery
celery_app = Celery('myproject')
celery_app.config_from_object('django.conf:settings', namespace='CELERY')
celery_app.autodiscover_tasks()

# --- Models (core/models.py) ---
class UserRole(Enum): ADMIN = "ADMIN"; USER = "USER"
class PostStatus(Enum): DRAFT = "DRAFT"; PUBLISHED = "PUBLISHED"
class JobStatus(Enum): PENDING = "PENDING"; RUNNING = "RUNNING"; SUCCESS = "SUCCESS"; FAILED = "FAILED"

class User(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4)
    email = models.EmailField(unique=True)
    password_hash = models.CharField(max_length=128)
    role = models.CharField(max_length=10, choices=[(r.name, r.value) for r in UserRole])
    is_active = models.BooleanField(default=True)
    created_at = models.DateTimeField(auto_now_add=True)

class Post(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4)
    user = models.ForeignKey(User, on_delete=models.CASCADE)
    title = models.CharField(max_length=255)
    content = models.TextField()
    status = models.CharField(max_length=10, choices=[(s.name, s.value) for s in PostStatus])

class Job(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4)
    celery_task_id = models.CharField(max_length=255, null=True, db_index=True)
    job_type = models.CharField(max_length=100)
    status = models.CharField(max_length=10, choices=[(s.name, s.value) for s in JobStatus], default=JobStatus.PENDING.name)
    related_object_id = models.UUIDField()
    result_metadata = models.JSONField(null=True, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

# --- Services (core/services.py) ---
from PIL import Image, ImageDraw, ImageFont
import io

class NotificationService:
    def send_welcome_email(self, user: User):
        print(f"SERVICE: Sending welcome email to {user.email}")
        # This could raise an exception if the mail server is down
        django_send_mail(
            'Welcome!',
            f'Hi {user.email}, welcome aboard.',
            'noreply@example.com',
            [user.email],
            fail_silently=False
        )

class ImageProcessingService:
    def process_and_store_image(self, post: Post, image_bytes: bytes) -> str:
        print(f"SERVICE: Processing image for post {post.id}")
        image = Image.open(io.BytesIO(image_bytes))
        
        # Resize
        image.thumbnail((1024, 1024))
        
        # Watermark
        draw = ImageDraw.Draw(image)
        try: font = ImageFont.truetype("arial.ttf", 20)
        except IOError: font = ImageFont.load_default()
        draw.text((15, 15), f"Post ID: {post.id}", font=font, fill="#FFF")
        
        # Save (simulated)
        output_path = f"processed/{post.id}.jpg"
        print(f"SERVICE: Saving image to {output_path}")
        return output_path

class UserAccountService:
    def cleanup_inactive_accounts(self):
        ninety_days_ago = timezone.now() - timedelta(days=90)
        users_to_deactivate = User.objects.filter(is_active=True, created_at__lt=ninety_days_ago)
        count = users_to_deactivate.count()
        if count > 0:
            users_to_deactivate.update(is_active=False)
        print(f"SERVICE: Deactivated {count} inactive accounts.")
        return count

# --- Tasks (core/tasks.py) ---
@celery_app.task(bind=True, autoretry_for=(Exception,), retry_backoff=True, retry_kwargs={'max_retries': 3})
def send_email_notification_task(self, user_id: uuid.UUID):
    user = User.objects.get(id=user_id)
    service = NotificationService()
    service.send_welcome_email(user)

@celery_app.task(bind=True)
def run_image_processing_pipeline(self, job_id: uuid.UUID, image_b64: str):
    job = Job.objects.get(id=job_id)
    try:
        with transaction.atomic():
            job.status = JobStatus.RUNNING.name
            job.celery_task_id = self.request.id
            job.save()
        
        post = Post.objects.get(id=job.related_object_id)
        import base64
        image_bytes = base64.b64decode(image_b64)
        
        service = ImageProcessingService()
        result_path = service.process_and_store_image(post, image_bytes)
        
        with transaction.atomic():
            job.status = JobStatus.SUCCESS.name
            job.result_metadata = {'path': result_path}
            job.save()
    except Exception as e:
        with transaction.atomic():
            job.status = JobStatus.FAILED.name
            job.result_metadata = {'error': str(e)}
            job.save()
        raise

@celery_app.task
def run_user_account_service_cleanup():
    service = UserAccountService()
    service.cleanup_inactive_accounts()

# --- Views (core/views.py) ---
class UserSignupView(View):
    def post(self, request, *args, **kwargs):
        user = User.objects.create(email=f'user_{uuid.uuid4()}@example.com')
        send_email_notification_task.delay(user.id)
        return JsonResponse({"message": "User created, welcome email queued."}, status=201)

class PostCreateView(View):
    def post(self, request, *args, **kwargs):
        import base64
        user, _ = User.objects.get_or_create(email='author@example.com')
        post = Post.objects.create(user=user, title="A new post")
        
        # Create a Job record to track this operation
        job = Job.objects.create(
            job_type="IMAGE_PROCESSING",
            related_object_id=post.id
        )
        
        # Create dummy image
        img = Image.new('RGB', (600, 400), color='blue')
        buffer = io.BytesIO()
        img.save(buffer, format='PNG')
        image_b64 = base64.b64encode(buffer.getvalue()).decode('utf-8')
        
        run_image_processing_pipeline.delay(job.id, image_b64)
        
        return JsonResponse({
            "message": "Post created, image processing job started.",
            "job_id": job.id
        }, status=202)

class JobStatusView(View):
    def get(self, request, *args, **kwargs):
        job_id = request.GET.get('job_id')
        try:
            job = Job.objects.get(id=job_id)
            return JsonResponse({
                "job_id": job.id,
                "job_type": job.job_type,
                "status": job.status,
                "celery_task_id": job.celery_task_id,
                "result": job.result_metadata,
                "updated_at": job.updated_at,
            })
        except Job.DoesNotExist:
            return JsonResponse({"error": "Job not found"}, status=404)