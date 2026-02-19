# Variation 3: The "Service Layer Architect"
# Style: Thin views, business logic abstracted into a separate service layer.
# Dependencies: pip install Django Pillow openpyxl

import uuid
import csv
from io import StringIO
from PIL import Image
import openpyxl
import tempfile

from django.db import models, transaction
from django.http import HttpRequest, HttpResponse, StreamingHttpResponse
from django.core.files.uploadedfile import UploadedFile
from django.core.files.base import File
from django.shortcuts import render, redirect
from django.contrib.auth.decorators import login_required
from django import forms
from django.conf import settings
from django.urls import path

# --- Mock Django Setup (for self-containment) ---
if not settings.configured:
    settings.configure(
        DEBUG=True,
        SECRET_KEY='a-secret-key-for-testing',
        DATABASES={
            'default': {
                'ENGINE': 'django.db.backends.sqlite3',
                'NAME': ':memory:',
            }
        },
        MEDIA_ROOT='/tmp/django_media_service',
    )

# --- Models (models.py) ---
class User(models.Model):
    class Role(models.TextChoices):
        ADMIN = 'ADMIN', 'Admin'
        USER = 'USER', 'User'
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    email = models.EmailField(unique=True)
    password_hash = models.CharField(max_length=128)
    role = models.CharField(max_length=5, choices=Role.choices, default=Role.USER)
    is_active = models.BooleanField(default=True)
    created_at = models.DateTimeField(auto_now_add=True)
    profile_image_path = models.CharField(max_length=255, blank=True)

class Post(models.Model):
    class Status(models.TextChoices):
        DRAFT = 'DRAFT', 'Draft'
        PUBLISHED = 'PUBLISHED', 'Published'
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(User, on_delete=models.CASCADE)
    title = models.CharField(max_length=255)
    content = models.TextField()
    status = models.CharField(max_length=10, choices=Status.choices, default=Status.DRAFT)

# --- Service Layer (services.py) ---
class FileProcessingError(Exception):
    """Custom exception for file handling errors."""
    pass

class PostFileService:
    @staticmethod
    @transaction.atomic
    def import_from_excel(file: UploadedFile, author: User) -> int:
        try:
            workbook = openpyxl.load_workbook(file)
            sheet = workbook.active
            
            # Assuming header in the first row: title, content, status
            header = [cell.value for cell in sheet[1]]
            if header != ['title', 'content', 'status']:
                raise FileProcessingError("Invalid Excel header. Expected: title, content, status")

            posts_to_create = []
            for row in sheet.iter_rows(min_row=2, values_only=True):
                title, content, status = row
                if status and status.upper() in Post.Status.values:
                    posts_to_create.append(
                        Post(user=author, title=title, content=content, status=status.upper())
                    )
            
            Post.objects.bulk_create(posts_to_create)
            return len(posts_to_create)
        except Exception as e:
            raise FileProcessingError(f"Failed to process Excel file: {e}")

    @staticmethod
    def get_posts_csv_generator(queryset):
        buffer = StringIO()
        writer = csv.writer(buffer)
        writer.writerow(['ID', 'Title', 'Status'])
        yield buffer.getvalue()
        buffer.seek(0)
        buffer.truncate(0)

        for post in queryset.iterator():
            writer.writerow([post.id, post.title, post.status])
            yield buffer.getvalue()
            buffer.seek(0)
            buffer.truncate(0)

class UserFileService:
    @staticmethod
    def process_and_save_avatar(user: User, image_file: UploadedFile, size=(128, 128)) -> str:
        try:
            img = Image.open(image_file)
            img.thumbnail(size)
            
            # Use a temporary file to save the processed image before moving to storage
            with tempfile.NamedTemporaryFile(delete=False, suffix='.jpg') as temp_f:
                img.save(temp_f, format='JPEG')
                temp_file_path = temp_f.name

            # In a real app, you'd move this to a proper storage (e.g., S3)
            # Here, we'll just save the path to the temp file
            user.profile_image_path = temp_file_path
            user.save()
            return user.profile_image_path
        except Exception as e:
            raise FileProcessingError(f"Failed to process image: {e}")

# --- Forms (forms.py) ---
class ExcelImportForm(forms.Form):
    excel_file = forms.FileField()

class AvatarUploadForm(forms.Form):
    avatar = forms.ImageField()

# --- Views (views.py) ---
@login_required
def import_posts_view(request: HttpRequest):
    if request.method == 'POST':
        form = ExcelImportForm(request.POST, request.FILES)
        if form.is_valid():
            try:
                count = PostFileService.import_from_excel(request.FILES['excel_file'], request.user)
                # Add success message: f"{count} posts imported."
                return redirect('home')
            except FileProcessingError as e:
                form.add_error('excel_file', str(e))
    else:
        form = ExcelImportForm()
    return render(request, 'import.html', {'form': form})

@login_required
def upload_avatar_view(request: HttpRequest):
    if request.method == 'POST':
        form = AvatarUploadForm(request.POST, request.FILES)
        if form.is_valid():
            try:
                UserFileService.process_and_save_avatar(request.user, request.FILES['avatar'])
                return redirect('profile')
            except FileProcessingError as e:
                form.add_error('avatar', str(e))
    else:
        form = AvatarUploadForm()
    return render(request, 'upload.html', {'form': form})

@login_required
def export_posts_view(request: HttpRequest):
    user_posts = Post.objects.filter(user=request.user)
    generator = PostFileService.get_posts_csv_generator(user_posts)
    response = StreamingHttpResponse(generator, content_type="text/csv")
    response['Content-Disposition'] = 'attachment; filename="posts_export.csv"'
    return response

# --- URL Configuration (urls.py) ---
urlpatterns = [
    path('posts/import-excel/', import_posts_view, name='import-posts-excel'),
    path('user/avatar/', upload_avatar_view, name='upload-avatar-service'),
    path('posts/export-csv/', export_posts_view, name='export-posts-csv'),
]