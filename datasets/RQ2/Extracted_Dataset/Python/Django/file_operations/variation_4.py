# Variation 4: The "DRF API-First" Developer
# Style: Uses Django Rest Framework for API endpoints, serializers for validation.
# Dependencies: pip install Django djangorestframework Pillow pandas

import uuid
import pandas as pd
from io import BytesIO, StringIO
from PIL import Image

from django.db import models, transaction
from django.http import StreamingHttpResponse
from django.conf import settings
from django.urls import path
from django.core.files.base import ContentFile

from rest_framework import serializers, status
from rest_framework.views import APIView
from rest_framework.response import Response
from rest_framework.parsers import MultiPartParser
from rest_framework.permissions import IsAuthenticated

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
        INSTALLED_APPS=['rest_framework'],
        MEDIA_ROOT='/tmp/django_media_drf',
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
    avatar = models.ImageField(upload_to='user_avatars/', null=True, blank=True)

class Post(models.Model):
    class Status(models.TextChoices):
        DRAFT = 'DRAFT', 'Draft'
        PUBLISHED = 'PUBLISHED', 'Published'
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(User, on_delete=models.CASCADE)
    title = models.CharField(max_length=255)
    content = models.TextField()
    status = models.CharField(max_length=10, choices=Status.choices, default=Status.DRAFT)

# --- Serializers (serializers.py) ---
class PostImportSerializer(serializers.Serializer):
    file = serializers.FileField(help_text="CSV or Excel file with 'title', 'content', 'status' columns.")

    def validate_file(self, value):
        if not (value.name.endswith('.csv') or value.name.endswith('.xlsx')):
            raise serializers.ValidationError("Unsupported file type. Please upload a CSV or XLSX file.")
        return value

class UserAvatarSerializer(serializers.Serializer):
    avatar = serializers.ImageField()

# --- API Views (api/views.py) ---
class PostBulkImportAPIView(APIView):
    parser_classes = [MultiPartParser]
    permission_classes = [IsAuthenticated]

    def post(self, request, *args, **kwargs):
        serializer = PostImportSerializer(data=request.data)
        if not serializer.is_valid():
            return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)

        file_obj = serializer.validated_data['file']
        
        try:
            # Use in-memory buffer to avoid disk I/O for parsing
            file_buffer = BytesIO(file_obj.read())
            df = pd.read_csv(file_buffer) if file_obj.name.endswith('.csv') else pd.read_excel(file_buffer)
            
            posts = [
                Post(user=request.user, title=row.title, content=row.content, status=row.status.upper())
                for row in df.itertuples()
            ]
            with transaction.atomic():
                Post.objects.bulk_create(posts)
            
            return Response({"message": f"{len(posts)} posts created successfully."}, status=status.HTTP_201_CREATED)
        except Exception as e:
            return Response({"error": f"Failed to process file: {str(e)}"}, status=status.HTTP_400_BAD_REQUEST)

class UserAvatarAPIView(APIView):
    parser_classes = [MultiPartParser]
    permission_classes = [IsAuthenticated]

    def put(self, request, *args, **kwargs):
        serializer = UserAvatarSerializer(data=request.data)
        if not serializer.is_valid():
            return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)

        image_file = serializer.validated_data['avatar']
        
        # In-memory image processing
        img = Image.open(image_file)
        img.thumbnail((200, 200))
        
        thumb_io = BytesIO()
        img.save(thumb_io, format='JPEG', quality=85)
        
        user = request.user
        user.avatar.save(f'{user.id}_avatar.jpg', ContentFile(thumb_io.getvalue()), save=True)
        
        return Response({"avatar_url": user.avatar.url}, status=status.HTTP_200_OK)

class PostExportAPIView(APIView):
    permission_classes = [IsAuthenticated]

    def get(self, request, *args, **kwargs):
        def generate_csv():
            # Use an in-memory text buffer
            buffer = StringIO()
            writer = pd.DataFrame(
                list(Post.objects.filter(user=request.user).values('title', 'content', 'status'))
            ).to_csv(index=False)
            buffer.write(writer)
            buffer.seek(0)
            yield buffer.read()

        response = StreamingHttpResponse(generate_csv(), content_type="text/csv")
        response['Content-Disposition'] = 'attachment; filename="posts_export.csv"'
        return response

# --- URL Configuration (urls.py) ---
urlpatterns = [
    path('api/v1/posts/bulk-import/', PostBulkImportAPIView.as_view(), name='api-post-import'),
    path('api/v1/user/avatar/', UserAvatarAPIView.as_view(), name='api-user-avatar'),
    path('api/v1/posts/export/', PostExportAPIView.as_view(), name='api-post-export'),
]