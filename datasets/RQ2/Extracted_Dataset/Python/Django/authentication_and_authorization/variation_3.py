# Variation 3: The "Service Layer" Developer
# This developer abstracts business logic into a dedicated service layer.
# Views become thin controllers that delegate tasks to services. This improves
# separation of concerns, testability, and reusability of business logic.

# --- Necessary Packages ---
# pip install django djangorestframework djangorestframework-simplejwt

import os
import uuid
from datetime import timedelta

# --- Mock Django Environment Setup ---
os.environ.setdefault("DJANGO_SETTINGS_MODULE", "settings")
from django.conf import settings
if not settings.configured:
    settings.configure(
        SECRET_KEY='a-secret-key-for-testing-only',
        INSTALLED_APPS=['django.contrib.auth', 'django.contrib.contenttypes', 'rest_framework'],
        AUTH_USER_MODEL='__main__.User',
        DATABASES={'default': {'ENGINE': 'django.db.backends.sqlite3', 'NAME': ':memory:'}},
        REST_FRAMEWORK={
            'DEFAULT_AUTHENTICATION_CLASSES': ('rest_framework_simplejwt.authentication.JWTAuthentication',),
        },
        SIMPLE_JWT={'ACCESS_TOKEN_LIFETIME': timedelta(minutes=60)}
    )
import django
django.setup()
# --- End Mock Django Environment Setup ---

from django.db import models
from django.contrib.auth.models import AbstractBaseUser, BaseUserManager, PermissionsMixin
from django.utils import timezone
from django.core.exceptions import PermissionDenied, ObjectDoesNotExist

from rest_framework import views, status, generics
from rest_framework.response import Response
from rest_framework.permissions import IsAuthenticated, AllowAny
from rest_framework.serializers import ModelSerializer
from rest_framework_simplejwt.tokens import RefreshToken

# --- models.py ---
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
        user.set_password(password)
        user.save(using=self._db)
        return user

class User(AbstractBaseUser, PermissionsMixin):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    email = models.EmailField(unique=True)
    role = models.CharField(max_length=10, choices=UserRole.choices, default=UserRole.USER)
    is_active = models.BooleanField(default=True)
    created_at = models.DateTimeField(default=timezone.now)
    objects = CustomUserManager()
    USERNAME_FIELD = 'email'

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
        fields = ('id', 'user', 'title', 'content', 'status')
        read_only_fields = ('user',)

# --- services.py ---
class AuthenticationService:
    @staticmethod
    def authenticate_user(email, password):
        try:
            user = User.objects.get(email=email)
            if not user.check_password(password) or not user.is_active:
                return None
            return user
        except User.DoesNotExist:
            return None

    @staticmethod
    def get_jwt_for_user(user):
        refresh = RefreshToken.for_user(user)
        return {'refresh': str(refresh), 'access': str(refresh.access_token)}

class PostService:
    @staticmethod
    def create_post(user, data):
        serializer = PostSerializer(data=data)
        serializer.is_valid(raise_exception=True)
        # The user is passed from the authenticated request context
        return serializer.save(user=user)

    @staticmethod
    def update_post(requesting_user, post_id, data):
        post = Post.objects.get(id=post_id)
        if post.user != requesting_user and requesting_user.role != UserRole.ADMIN:
            raise PermissionDenied("You do not have permission to edit this post.")
        
        serializer = PostSerializer(post, data=data, partial=True)
        serializer.is_valid(raise_exception=True)
        return serializer.save()

    @staticmethod
    def delete_post(requesting_user, post_id):
        post = Post.objects.get(id=post_id)
        if post.user != requesting_user and requesting_user.role != UserRole.ADMIN:
            raise PermissionDenied("You do not have permission to delete this post.")
        post.delete()

# --- views.py ---
class LoginAPIView(views.APIView):
    permission_classes = [AllowAny]

    def post(self, request, *args, **kwargs):
        email = request.data.get('email')
        password = request.data.get('password')
        
        user = AuthenticationService.authenticate_user(email, password)
        
        if user:
            tokens = AuthenticationService.get_jwt_for_user(user)
            return Response(tokens, status=status.HTTP_200_OK)
        
        return Response({'error': 'Invalid Credentials'}, status=status.HTTP_401_UNAUTHORIZED)

class PostListCreateAPIView(views.APIView):
    permission_classes = [IsAuthenticated]

    def get(self, request, *args, **kwargs):
        posts = Post.objects.filter(status=PostStatus.PUBLISHED)
        serializer = PostSerializer(posts, many=True)
        return Response(serializer.data)

    def post(self, request, *args, **kwargs):
        post = PostService.create_post(user=request.user, data=request.data)
        serializer = PostSerializer(post)
        return Response(serializer.data, status=status.HTTP_201_CREATED)

class PostDetailAPIView(views.APIView):
    permission_classes = [IsAuthenticated]

    def _get_object(self, pk):
        try:
            return Post.objects.get(pk=pk)
        except Post.DoesNotExist:
            raise ObjectDoesNotExist

    def get(self, request, pk, *args, **kwargs):
        try:
            post = self._get_object(pk)
            return Response(PostSerializer(post).data)
        except ObjectDoesNotExist:
            return Response(status=status.HTTP_404_NOT_FOUND)

    def put(self, request, pk, *args, **kwargs):
        try:
            updated_post = PostService.update_post(request.user, pk, request.data)
            return Response(PostSerializer(updated_post).data)
        except ObjectDoesNotExist:
            return Response(status=status.HTTP_404_NOT_FOUND)
        except PermissionDenied as e:
            return Response({'error': str(e)}, status=status.HTTP_403_FORBIDDEN)

    def delete(self, request, pk, *args, **kwargs):
        try:
            PostService.delete_post(request.user, pk)
            return Response(status=status.HTTP_204_NO_CONTENT)
        except ObjectDoesNotExist:
            return Response(status=status.HTTP_404_NOT_FOUND)
        except PermissionDenied as e:
            return Response({'error': str(e)}, status=status.HTTP_403_FORBIDDEN)

# --- urls.py ---
from django.urls import path

urlpatterns = [
    path('api/auth/login/', LoginAPIView.as_view()),
    path('api/posts/', PostListCreateAPIView.as_view()),
    path('api/posts/<uuid:pk>/', PostDetailAPIView.as_view()),
]

print("Variation 3: Service Layer Abstraction loaded.")