# Variation 1: The "DRF Standard" Developer
# This developer uses Django Rest Framework (DRF) with Class-Based Views (CBVs),
# standard DRF/SimpleJWT authentication, and custom permission classes. This is a
# very common, idiomatic, and robust approach for building APIs with Django.

# --- Necessary Packages ---
# pip install django djangorestframework djangorestframework-simplejwt requests

import os
import uuid
from datetime import datetime, timedelta
from enum import Enum

# --- Mock Django Environment Setup ---
# This setup simulates the Django environment for the code to be self-contained.
os.environ.setdefault("DJANGO_SETTINGS_MODULE", "settings")
from django.conf import settings
if not settings.configured:
    settings.configure(
        SECRET_KEY='a-secret-key-for-testing-only',
        INSTALLED_APPS=[
            'django.contrib.auth',
            'django.contrib.contenttypes',
            'rest_framework',
            'rest_framework_simplejwt',
        ],
        AUTH_USER_MODEL='__main__.User',
        DATABASES={'default': {'ENGINE': 'django.db.backends.sqlite3', 'NAME': ':memory:'}},
        REST_FRAMEWORK={
            'DEFAULT_AUTHENTICATION_CLASSES': (
                'rest_framework_simplejwt.authentication.JWTAuthentication',
            ),
        },
        SIMPLE_JWT={
            'ACCESS_TOKEN_LIFETIME': timedelta(minutes=60),
            'REFRESH_TOKEN_LIFETIME': timedelta(days=1),
        }
    )
import django
django.setup()
# --- End Mock Django Environment Setup ---

from django.db import models
from django.contrib.auth.models import AbstractBaseUser, BaseUserManager, PermissionsMixin
from django.utils import timezone

from rest_framework import generics, permissions, status, views
from rest_framework.response import Response
from rest_framework.serializers import ModelSerializer, EmailField
from rest_framework_simplejwt.views import TokenObtainPairView
from rest_framework_simplejwt.serializers import TokenObtainPairSerializer

# --- models.py ---

class UserRole(models.TextChoices):
    ADMIN = 'ADMIN', 'Admin'
    USER = 'USER', 'User'

class PostStatus(models.TextChoices):
    DRAFT = 'DRAFT', 'Draft'
    PUBLISHED = 'PUBLISHED', 'Published'

class CustomUserManager(BaseUserManager):
    def create_user(self, email, password=None, **extra_fields):
        if not email:
            raise ValueError('The Email field must be set')
        email = self.normalize_email(email)
        user = self.model(email=email, **extra_fields)
        user.set_password(password)
        user.save(using=self._db)
        return user

    def create_superuser(self, email, password=None, **extra_fields):
        extra_fields.setdefault('role', UserRole.ADMIN)
        extra_fields.setdefault('is_staff', True)
        extra_fields.setdefault('is_superuser', True)
        return self.create_user(email, password, **extra_fields)

class User(AbstractBaseUser, PermissionsMixin):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    email = models.EmailField(unique=True)
    password_hash = None  # Let AbstractBaseUser handle the 'password' field
    role = models.CharField(max_length=10, choices=UserRole.choices, default=UserRole.USER)
    is_active = models.BooleanField(default=True)
    is_staff = models.BooleanField(default=False)
    created_at = models.DateTimeField(default=timezone.now)

    objects = CustomUserManager()

    USERNAME_FIELD = 'email'
    REQUIRED_FIELDS = []

    def __str__(self):
        return self.email

class Post(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name='posts')
    title = models.CharField(max_length=255)
    content = models.TextField()
    status = models.CharField(max_length=10, choices=PostStatus.choices, default=PostStatus.DRAFT)

    def __str__(self):
        return self.title

# --- permissions.py ---

class IsAdminOrReadOnly(permissions.BasePermission):
    """
    Custom permission to only allow admins to edit objects.
    """
    def has_permission(self, request, view):
        if request.method in permissions.SAFE_METHODS:
            return True
        return request.user and request.user.role == UserRole.ADMIN

class IsOwnerOrAdmin(permissions.BasePermission):
    """
    Custom permission to only allow owners of an object or admins to edit it.
    """
    def has_object_permission(self, request, view, obj):
        return obj.user == request.user or request.user.role == UserRole.ADMIN

# --- serializers.py ---

class UserSerializer(ModelSerializer):
    class Meta:
        model = User
        fields = ('id', 'email', 'role', 'created_at')

class PostSerializer(ModelSerializer):
    class Meta:
        model = Post
        fields = ('id', 'user', 'title', 'content', 'status')
        read_only_fields = ('user',)

# --- views.py ---

class CustomTokenObtainPairSerializer(TokenObtainPairSerializer):
    @classmethod
    def get_token(cls, user):
        token = super().get_token(user)
        # Add custom claims
        token['email'] = user.email
        token['role'] = user.role
        return token

class LoginView(TokenObtainPairView):
    serializer_class = CustomTokenObtainPairSerializer

class PostListCreateView(generics.ListCreateAPIView):
    queryset = Post.objects.filter(status=PostStatus.PUBLISHED)
    serializer_class = PostSerializer
    permission_classes = [permissions.IsAuthenticatedOrReadOnly]

    def perform_create(self, serializer):
        serializer.save(user=self.request.user)

class PostDetailView(generics.RetrieveUpdateDestroyAPIView):
    queryset = Post.objects.all()
    serializer_class = PostSerializer
    permission_classes = [permissions.IsAuthenticated, IsOwnerOrAdmin]

class GoogleOAuthCallbackView(views.APIView):
    permission_classes = [permissions.AllowAny]

    def post(self, request, *args, **kwargs):
        # In a real app, you'd exchange this code with Google for an access token,
        # then get user info. Here we mock it.
        auth_code = request.data.get("code")
        if not auth_code:
            return Response({"error": "Authorization code not provided"}, status=status.HTTP_400_BAD_REQUEST)

        # MOCK: Get user info from OAuth provider
        mock_user_info = {
            "email": "user.from.google@example.com",
            "given_name": "Google",
            "family_name": "User",
        }
        
        user, created = User.objects.get_or_create(
            email=mock_user_info["email"],
            defaults={'role': UserRole.USER, 'is_active': True}
        )
        
        if created:
            # OAuth users don't have a usable password
            user.set_unusable_password()
            user.save()

        # Generate JWT for the user
        token_serializer = CustomTokenObtainPairSerializer()
        refresh = token_serializer.get_token(user)
        
        return Response({
            'refresh': str(refresh),
            'access': str(refresh.access_token),
            'user': UserSerializer(user).data
        })

# --- urls.py ---
from django.urls import path

urlpatterns = [
    path('api/auth/login/', LoginView.as_view(), name='token_obtain_pair'),
    path('api/auth/oauth/google/callback/', GoogleOAuthCallbackView.as_view(), name='oauth_callback'),
    path('api/posts/', PostListCreateView.as_view(), name='post-list-create'),
    path('api/posts/<uuid:pk>/', PostDetailView.as_view(), name='post-detail'),
]

# Example of how to use this code would be in a Django project with these files
# in their respective locations within an app.
print("Variation 1: DRF Standard with CBVs loaded.")