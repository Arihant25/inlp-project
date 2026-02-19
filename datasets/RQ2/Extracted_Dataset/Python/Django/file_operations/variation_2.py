# Variation 2: The "Class-Based View (CBV) Enthusiast"
# Style: Uses generic Class-Based Views, encapsulates logic in methods, uses pandas.
# Dependencies: pip install Django Pillow pandas

import uuid
import pandas as pd
from io import BytesIO
from PIL import Image

from django.db import models
from django.http import HttpRequest, StreamingHttpResponse
from django.core.files.base import ContentFile
from django.views.generic import FormView, View
from django.urls import reverse_lazy, path
from django import forms
from django.conf import settings
from django.contrib.auth.mixins import LoginRequiredMixin

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
        MEDIA_ROOT='/tmp/django_media_cbv',
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
    avatar = models.ImageField(upload_to='avatars/', null=True, blank=True)

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
class DataImportForm(forms.Form):
    data_file = forms.FileField(
        label="Upload CSV or Excel File",
        help_text="Must contain 'title', 'content', and 'status' columns."
    )

# --- Views (views.py) ---
class PostBulkImportView(LoginRequiredMixin, FormView):
    template_name = 'import.html'
    form_class = DataImportForm
    success_url = reverse_lazy('import-success')

    def form_valid(self, form):
        data_file = form.cleaned_data['data_file']
        self._process_file(data_file, self.request.user)
        return super().form_valid(form)

    def _process_file(self, file_obj, user_obj):
        try:
            if file_obj.name.endswith('.csv'):
                df = pd.read_csv(file_obj)
            elif file_obj.name.endswith(('.xls', '.xlsx')):
                df = pd.read_excel(file_obj)
            else:
                self.form_invalid(self.get_form())
                return

            df.columns = df.columns.str.lower()
            required_cols = {'title', 'content', 'status'}
            if not required_cols.issubset(df.columns):
                raise ValueError("Missing required columns.")

            posts = [
                Post(
                    user=user_obj,
                    title=row['title'],
                    content=row['content'],
                    status=row['status'].upper()
                )
                for index, row in df.iterrows()
                if row['status'].upper() in Post.Status.values
            ]
            Post.objects.bulk_create(posts)
        except Exception as e:
            # In a real app, add more robust error handling/logging
            form = self.get_form()
            form.add_error('data_file', f"File processing failed: {e}")
            self.form_invalid(form)

class UserAvatarUploadView(LoginRequiredMixin, View):
    def post(self, request: HttpRequest, *args, **kwargs):
        image_file = request.FILES.get('avatar')
        if not image_file:
            return # Handle error response
        
        processed_image_content = self._resize_image(image_file)
        
        user = request.user
        user.avatar.save(f"{user.id}.jpg", processed_image_content, save=True)
        
        return # Redirect or success response

    def _resize_image(self, image_file, size=(300, 300)):
        img = Image.open(image_file)
        img.thumbnail(size)
        
        buffer = BytesIO()
        img.save(buffer, format='JPEG', quality=90)
        return ContentFile(buffer.getvalue())

class PostExportStreamView(LoginRequiredMixin, View):
    def get(self, request: HttpRequest, *args, **kwargs):
        
        def stream_response_generator():
            # Use pandas to create CSV in-memory and stream it
            posts_qs = Post.objects.filter(user=request.user).values('title', 'content', 'status')
            if not posts_qs.exists():
                yield "No posts found."
                return
            
            df = pd.DataFrame(list(posts_qs))
            
            # Stream the dataframe to CSV format
            buffer = BytesIO()
            df.to_csv(buffer, index=False)
            buffer.seek(0)
            yield buffer.read()

        response = StreamingHttpResponse(
            stream_response_generator(),
            content_type="text/csv"
        )
        response['Content-Disposition'] = 'attachment; filename="my_posts.csv"'
        return response

# --- URL Configuration (urls.py) ---
urlpatterns = [
    path('posts/import/', PostBulkImportView.as_view(), name='post-import'),
    path('profile/avatar/', UserAvatarUploadView.as_view(), name='avatar-upload'),
    path('posts/export/', PostExportStreamView.as_view(), name='post-export'),
]