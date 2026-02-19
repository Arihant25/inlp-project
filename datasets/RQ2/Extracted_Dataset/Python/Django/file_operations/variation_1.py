# Variation 1: The "Pragmatic Functional" Developer
# Style: Function-Based Views (FBVs), explicit logic, standard libraries.
# Dependencies: pip install Django Pillow

import csv
import uuid
import tempfile
from datetime import datetime
from PIL import Image

from django.db import models, transaction
from django.http import HttpRequest, HttpResponse, StreamingHttpResponse
from django.core.files.base import ContentFile
from django.core.files.storage import default_storage
from django.shortcuts import render, redirect
from django import forms
from django.contrib.auth.decorators import login_required
from django.conf import settings
from django.urls import path

# --- Mock Django Setup (for self-containment) ---
# In a real project, this would be in settings.py
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
        # In a real project, MEDIA_ROOT would be a persistent directory
        MEDIA_ROOT=tempfile.gettempdir(),
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
    profile_picture = models.ImageField(upload_to='avatars/', null=True, blank=True)

class Post(models.Model):
    class Status(models.TextChoices):
        DRAFT = 'DRAFT', 'Draft'
        PUBLISHED = 'PUBLISHED', 'Published'
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(User, on_delete=models.CASCADE)
    title = models.CharField(max_length=255)
    content = models.TextField()
    status = models.CharField(max_length=10, choices=Status.choices, default=Status.DRAFT)

# --- Forms (forms.py) ---
class CsvUploadForm(forms.Form):
    csv_file = forms.FileField(label="Upload Posts CSV")

class ImageUploadForm(forms.Form):
    profile_image = forms.ImageField(label="Upload Profile Image")

# --- Views (views.py) ---
@login_required
def handle_posts_upload_view(request: HttpRequest) -> HttpResponse:
    """
    Handles uploading a CSV file to bulk-create Post objects.
    """
    if request.method == 'POST':
        form = CsvUploadForm(request.POST, request.FILES)
        if form.is_valid():
            uploaded_csv_file = request.FILES['csv_file']
            
            # Use a temporary file for potentially large uploads
            with tempfile.NamedTemporaryFile(mode='w+', delete=True, suffix='.csv', newline='', encoding='utf-8') as temp_f:
                # Stream content to temp file
                for chunk in uploaded_csv_file.chunks():
                    temp_f.write(chunk.decode('utf-8'))
                temp_f.seek(0) # Rewind to the beginning of the file

                # Process the CSV
                reader = csv.reader(temp_f)
                next(reader)  # Skip header row

                posts_to_create = []
                for row in reader:
                    title, content, status_str = row
                    if status_str.upper() in Post.Status.values:
                        posts_to_create.append(
                            Post(user=request.user, title=title, content=content, status=status_str.upper())
                        )
                
                if posts_to_create:
                    Post.objects.bulk_create(posts_to_create)
            
            return redirect('some_success_url')
    else:
        form = CsvUploadForm()
    return render(request, 'upload.html', {'form': form})

@login_required
def handle_profile_image_upload_view(request: HttpRequest) -> HttpResponse:
    """
    Handles uploading and resizing a user's profile image.
    """
    if request.method == 'POST':
        form = ImageUploadForm(request.POST, request.FILES)
        if form.is_valid():
            image_file = form.cleaned_data['profile_image']
            
            # Open image with Pillow
            img = Image.open(image_file)
            
            # Resize logic
            target_size = (256, 256)
            img.thumbnail(target_size)
            
            # Save the processed image to a temporary in-memory file
            temp_thumb = ContentFile(b'')
            img.save(temp_thumb, format='JPEG')
            temp_thumb.seek(0)

            # Save to user model
            current_user = request.user
            current_user.profile_picture.save(f'{current_user.id}_avatar.jpg', temp_thumb, save=True)
            
            return redirect('some_profile_url')
    else:
        form = ImageUploadForm()
    return render(request, 'upload_image.html', {'form': form})

class Echo:
    """An object that implements just the write method of the file-like interface."""
    def write(self, value):
        return value

@login_required
def download_posts_as_csv_view(request: HttpRequest) -> StreamingHttpResponse:
    """
    Streams a CSV file of all published posts.
    """
    posts = Post.objects.filter(status=Post.Status.PUBLISHED).iterator()
    pseudo_buffer = Echo()
    writer = csv.writer(pseudo_buffer)

    def row_generator():
        yield writer.writerow(['Title', 'Content', 'Author Email'])
        for post in posts:
            yield writer.writerow([post.title, post.content, post.user.email])

    response = StreamingHttpResponse(row_generator(), content_type="text/csv")
    response['Content-Disposition'] = f'attachment; filename="published_posts_{datetime.now().strftime("%Y%m%d")}.csv"'
    return response

# --- URL Configuration (urls.py) ---
urlpatterns = [
    path('upload/posts/', handle_posts_upload_view, name='upload_posts'),
    path('upload/avatar/', handle_profile_image_upload_view, name='upload_avatar'),
    path('download/posts/', download_posts_as_csv_view, name='download_posts'),
]