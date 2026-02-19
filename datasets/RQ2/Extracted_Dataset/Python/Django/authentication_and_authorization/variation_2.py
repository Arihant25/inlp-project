# Variation 2: The "Functional View" Developer
# This developer prefers function-based views (FBVs) with decorators for clarity
# and simplicity on smaller endpoints. They might write custom decorators for
# authentication and authorization logic, keeping views concise.

# --- Necessary Packages ---
# pip install django djangorestframework pyjwt

import os
import uuid
import jwt
from datetime import datetime, timedelta
from functools import wraps

# --- Mock Django Environment Setup ---
os.environ.setdefault("DJANGO_SETTINGS_MODULE", "settings")
from django.conf import settings
if not settings.configured:
    settings.configure(
        SECRET_KEY='a-secret-key-for-testing-only',
        INSTALLED_APPS=['django.contrib.auth', 'django.contrib.contenttypes', 'rest_framework'],
        AUTH_USER_MODEL='__main__.User',
        DATABASES={'default': {'ENGINE': 'django.db.backends.sqlite3', 'NAME': ':memory:'}},
    )
import django
django.setup()
# --- End Mock Django Environment Setup ---

from django.db import models
from django.contrib.auth.hashers import make_password, check_password
from django.contrib.auth.models import AbstractBaseUser, BaseUserManager, PermissionsMixin
from django.utils import timezone
from django.http import JsonResponse
from django.core.exceptions import ObjectDoesNotExist

from rest_framework.decorators import api_view, permission_classes
from rest_framework.permissions import AllowAny
from rest_framework.response import Response
from rest_framework import status
from rest_framework.serializers import ModelSerializer

# --- models.py ---
# (Re-using model definitions for consistency)
class UserRole(models.TextChoices):
    ADMIN = 'ADMIN', 'Admin'
    USER = 'USER', 'User'

class PostStatus(models.TextChoices):
    DRAFT = 'DRAFT', 'Draft'
    PUBLISHED = 'PUBLISHED', 'Published'

class CustomUserManager(BaseUserManager):
    def create_user(self, email, password=None, **extra_fields):
        email = self.normalize_email(email)
        user = self.model(email=email, **extra_fields)
        user.password = make_password(password)
        user.save(using=self._db)
        return user

class User(AbstractBaseUser):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    email = models.EmailField(unique=True)
    password = models.CharField(max_length=128)
    role = models.CharField(max_length=10, choices=UserRole.choices, default=UserRole.USER)
    is_active = models.BooleanField(default=True)
    created_at = models.DateTimeField(default=timezone.now)
    
    objects = CustomUserManager()
    USERNAME_FIELD = 'email'
    
    # Simplified check_password
    def check_password(self, raw_password):
        return check_password(raw_password, self.password)

class Post(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(User, on_delete=models.CASCADE)
    title = models.CharField(max_length=255)
    content = models.TextField()
    status = models.CharField(max_length=10, choices=PostStatus.choices, default=PostStatus.DRAFT)

# --- serializers.py ---
class PostSerializer(ModelSerializer):
    class Meta:
        model = Post
        fields = '__all__'

# --- auth_utils.py ---
def generate_jwt(user):
    payload = {
        'id': str(user.id),
        'role': user.role,
        'exp': datetime.utcnow() + timedelta(hours=1),
        'iat': datetime.utcnow()
    }
    return jwt.encode(payload, settings.SECRET_KEY, algorithm='HS256')

# --- decorators.py ---
def jwt_required(view_func):
    @wraps(view_func)
    def _wrapped_view(request, *args, **kwargs):
        auth_header = request.headers.get('Authorization')
        if not auth_header or not auth_header.startswith('Bearer '):
            return JsonResponse({'error': 'Authorization header missing or invalid'}, status=401)
        
        token = auth_header.split(' ')[1]
        try:
            payload = jwt.decode(token, settings.SECRET_KEY, algorithms=['HS256'])
            request.user = User.objects.get(id=payload['id'])
        except (jwt.ExpiredSignatureError, jwt.InvalidTokenError, ObjectDoesNotExist):
            return JsonResponse({'error': 'Invalid or expired token'}, status=401)
        
        return view_func(request, *args, **kwargs)
    return _wrapped_view

def role_required(allowed_roles):
    def decorator(view_func):
        @wraps(view_func)
        def _wrapped_view(request, *args, **kwargs):
            if not request.user or request.user.role not in allowed_roles:
                return JsonResponse({'error': 'Permission denied'}, status=403)
            return view_func(request, *args, **kwargs)
        return _wrapped_view
    return decorator

# --- views.py ---
@api_view(['POST'])
@permission_classes([AllowAny])
def login_view(request):
    email = request.data.get('email')
    password = request.data.get('password')
    try:
        user = User.objects.get(email=email)
        if not user.check_password(password):
            raise ObjectDoesNotExist
    except ObjectDoesNotExist:
        return Response({'error': 'Invalid credentials'}, status=status.HTTP_401_UNAUTHORIZED)
    
    token = generate_jwt(user)
    return Response({'token': token})

@api_view(['GET', 'POST'])
@jwt_required
def post_list_create_view(request):
    if request.method == 'GET':
        posts = Post.objects.filter(status=PostStatus.PUBLISHED)
        serializer = PostSerializer(posts, many=True)
        return Response(serializer.data)

    elif request.method == 'POST':
        serializer = PostSerializer(data=request.data)
        if serializer.is_valid():
            serializer.save(user=request.user)
            return Response(serializer.data, status=status.HTTP_201_CREATED)
        return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)

@api_view(['GET', 'PUT', 'DELETE'])
@jwt_required
def post_detail_view(request, post_id):
    try:
        post = Post.objects.get(id=post_id)
    except Post.DoesNotExist:
        return Response(status=status.HTTP_404_NOT_FOUND)

    # RBAC check
    is_owner = post.user == request.user
    is_admin = request.user.role == UserRole.ADMIN

    if request.method == 'GET':
        if post.status == PostStatus.PUBLISHED or is_owner or is_admin:
            serializer = PostSerializer(post)
            return Response(serializer.data)
        else:
            return Response({'error': 'Not found'}, status=status.HTTP_404_NOT_FOUND)

    if not (is_owner or is_admin):
        return Response({'error': 'Permission denied'}, status=status.HTTP_403_FORBIDDEN)

    if request.method == 'PUT':
        serializer = PostSerializer(post, data=request.data, partial=True)
        if serializer.is_valid():
            serializer.save()
            return Response(serializer.data)
        return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)

    elif request.method == 'DELETE':
        post.delete()
        return Response(status=status.HTTP_204_NO_CONTENT)

# --- urls.py ---
from django.urls import path

urlpatterns = [
    path('api/auth/login/', login_view),
    path('api/posts/', post_list_create_view),
    path('api/posts/<uuid:post_id>/', post_detail_view),
]

print("Variation 2: Functional Views with Decorators loaded.")