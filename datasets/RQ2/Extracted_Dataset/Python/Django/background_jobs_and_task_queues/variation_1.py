# VARIATION 1: The "Classic & Explicit" Developer
# STYLE: Functional, explicit, verbose naming. All tasks in a single tasks.py.
# Best practice of passing IDs instead of full objects is followed.
# Uses django-celery-results for status tracking.

import os
import uuid
from datetime import timedelta
from enum import Enum

from django.db import models
from django.conf import settings
from django.core.mail import send_mail as django_send_mail
from django.utils import timezone
from django.http import JsonResponse
from django.views import View

# --- Mock Django Setup (for standalone execution) ---

# This setup simulates a Django environment for the snippet to be self-contained.
# In a real project, this would be handled by manage.py.

def configure_django():
    settings.configure(
        SECRET_KEY='a-secret-key',
        INSTALLED_APPS=[
            'django.contrib.auth',
            'django.contrib.contenttypes',
            'django_celery_results',
            '__main__', # Refers to the current script as an app
        ],
        DATABASES={
            'default': {
                'ENGINE': 'django.db.backends.sqlite3',
                'NAME': ':memory:',
            }
        },
        # --- Celery Configuration ---
        CELERY_BROKER_URL='redis://localhost:6379/0',
        CELERY_RESULT_BACKEND='django-db',
        CELERY_ACCEPT_CONTENT=['json'],
        CELERY_TASK_SERIALIZER='json',
        CELERY_RESULT_SERIALIZER='json',
        CELERY_TIMEZONE='UTC',
        CELERY_BEAT_SCHEDULE={
            'deactivate_inactive_users_every_day': {
                'task': '__main__.deactivate_inactive_users_periodic_task',
                'schedule': timedelta(days=1),
            },
        }
    )
    import django
    django.setup()

# --- Celery App Definition (myproject/celery.py) ---

from celery import Celery
from celery.utils.log import get_task_logger

# Set the default Django settings module for the 'celery' program.
os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'myproject.settings')

celery_app = Celery('myproject')
celery_app.config_from_object('django.conf:settings', namespace='CELERY')
celery_app.autodiscover_tasks()

logger = get_task_logger(__name__)

# --- Models (core/models.py) ---

class UserRole(Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

class User(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    email = models.EmailField(unique=True)
    password_hash = models.CharField(max_length=128)
    role = models.CharField(max_length=10, choices=[(role.name, role.value) for role in UserRole])
    is_active = models.BooleanField(default=True)
    created_at = models.DateTimeField(auto_now_add=True)

class Post(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(User, on_delete=models.CASCADE)
    title = models.CharField(max_length=255)
    content = models.TextField()
    status = models.CharField(max_length=10, choices=[(status.name, status.value) for status in PostStatus])
    processed_image_path = models.CharField(max_length=255, blank=True, null=True)

# --- Tasks (core/tasks.py) ---

from celery import shared_task
from PIL import Image, ImageDraw, ImageFont
import io

@shared_task(bind=True)
def send_welcome_email_task(self, user_id):
    """
    Sends a welcome email to a new user.
    Retries with exponential backoff on failure.
    """
    try:
        user = User.objects.get(id=user_id)
        logger.info(f"Sending welcome email to {user.email}")
        # Simulate sending email
        django_send_mail(
            'Welcome to Our Platform!',
            f'Hi {user.email},\n\nWelcome! We are glad to have you.',
            'from@example.com',
            [user.email],
            fail_silently=False,
        )
        return f"Email sent to {user.email}"
    except User.DoesNotExist:
        logger.error(f"User with id {user_id} not found. Not retrying.")
        return "User not found."
    except Exception as exc:
        logger.warning(f"Email sending failed for user {user_id}. Retrying...")
        # Exponential backoff: 10s, 20s, 40s, ...
        raise self.retry(exc=exc, countdown=10 * (2 ** self.request.retries), max_retries=5)

@shared_task
def process_post_image_pipeline_task(post_id, image_bytes_b64):
    """
    A multi-step image processing pipeline for a post.
    1. Resizes image
    2. Applies a watermark
    3. Saves the result (simulated)
    """
    import base64
    
    try:
        post = Post.objects.get(id=post_id)
        logger.info(f"Starting image processing pipeline for Post ID: {post.id}")

        # Decode image
        image_bytes = base64.b64decode(image_bytes_b64)
        image = Image.open(io.BytesIO(image_bytes))

        # Step 1: Resize
        image.thumbnail((800, 800))
        logger.info(f"Resized image for Post ID: {post.id}")

        # Step 2: Apply watermark
        draw = ImageDraw.Draw(image)
        text = f"© MyApp | Post by {post.user.email}"
        # In a real app, load a font file. Here we use the default.
        try:
            font = ImageFont.truetype("arial.ttf", 15)
        except IOError:
            font = ImageFont.load_default()
        draw.text((10, 10), text, font=font, fill=(255, 255, 255, 128))
        logger.info(f"Watermarked image for Post ID: {post.id}")

        # Step 3: Save (simulated)
        output_path = f"processed_images/{post.id}.jpg"
        # In a real app: image.save(output_path, 'JPEG')
        post.processed_image_path = output_path
        post.save()
        logger.info(f"Saved processed image for Post ID: {post.id} to {output_path}")

        return {"status": "success", "path": output_path}
    except Post.DoesNotExist:
        logger.error(f"Post with id {post_id} not found.")
        return {"status": "error", "message": "Post not found."}
    except Exception as e:
        logger.error(f"Image processing failed for Post ID {post_id}: {e}")
        return {"status": "error", "message": str(e)}

@shared_task
def deactivate_inactive_users_periodic_task():
    """
    A periodic task that runs daily to deactivate users who haven't
    logged in for 90 days. (Simulated by created_at for this example).
    """
    ninety_days_ago = timezone.now() - timedelta(days=90)
    inactive_users = User.objects.filter(
        is_active=True,
        created_at__lt=ninety_days_ago
    )
    deactivated_count = inactive_users.update(is_active=False)
    logger.info(f"Periodic Task: Deactivated {deactivated_count} inactive users.")
    return f"Deactivated {deactivated_count} users."

# --- Views (core/views.py) ---
# Example of how to trigger and track tasks

class UserSignupView(View):
    def post(self, request, *args, **kwargs):
        # In a real view, you'd create a user from request.POST data
        user = User.objects.create(email='newuser@example.com')
        
        # Schedule the async email task
        send_welcome_email_task.delay(user.id)
        
        return JsonResponse({"status": "User created, welcome email is being sent."}, status=201)

class PostCreateView(View):
    def post(self, request, *args, **kwargs):
        import base64
        # Create a dummy user and post
        user, _ = User.objects.get_or_create(email='author@example.com')
        post = Post.objects.create(user=user, title="My New Post", content="...")

        # Create a dummy image and schedule processing
        img = Image.new('RGB', (200, 150), color = 'red')
        buffer = io.BytesIO()
        img.save(buffer, format='PNG')
        image_bytes_b64 = base64.b64encode(buffer.getvalue()).decode('utf-8')

        task = process_post_image_pipeline_task.delay(post.id, image_bytes_b64)
        
        return JsonResponse({
            "status": "Post created, image processing started.",
            "task_id": task.id
        }, status=202)

class TaskStatusView(View):
    def get(self, request, *args, **kwargs):
        task_id = request.GET.get('task_id')
        if not task_id:
            return JsonResponse({"error": "task_id parameter is required"}, status=400)
        
        # Use django-celery-results to get task status
        from django_celery_results.models import TaskResult
        try:
            result = TaskResult.objects.get(task_id=task_id)
            return JsonResponse({
                "task_id": result.task_id,
                "status": result.status,
                "result": result.result,
                "date_done": result.date_done,
            })
        except TaskResult.DoesNotExist:
            return JsonResponse({"status": "PENDING"}, status=202)

# To run this snippet:
# 1. Install packages: pip install django celery redis Pillow django-celery-results
# 2. Run a Redis server.
# 3. Run a Celery worker: celery -A your_script_name worker -l info
# 4. Run a Celery beat scheduler: celery -A your_script_name beat -l info
# 5. In a Python shell, call configure_django() and then trigger views.