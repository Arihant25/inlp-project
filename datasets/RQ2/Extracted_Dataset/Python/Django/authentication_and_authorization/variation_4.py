# Variation 4: The "Modern & Explicit" Developer
# This developer uses a modern, FastAPI-inspired framework like Django Ninja,
# which leverages Python type hints for automatic validation, serialization,
# and OpenAPI documentation. The code is explicit and relies on dependency
# injection for authentication and authorization.

# --- Necessary Packages ---
# pip install django django-ninja pydantic pyjwt

import os
import uuid
import jwt
from datetime import datetime, timedelta
from typing import List

# --- Mock Django Environment Setup ---
os.environ.setdefault("DJANGO_SETTINGS_MODULE", "settings")
from django.conf import settings
if not settings.configured:
    settings.configure(
        SECRET_KEY='a-secret-key-for-testing-only',
        INSTALLED_APPS=['django.contrib.auth', 'django.contrib.contenttypes'],
        AUTH_USER_MODEL='__main__.User',
        DATABASES={'default': {'ENGINE': 'django.db.backends.sqlite3', 'NAME': ':memory:'}},
    )
import django
django.setup()
# --- End Mock Django Environment Setup ---

from django.db import models
from django.contrib.auth.models import AbstractBaseUser, BaseUserManager
from django.contrib.auth.hashers import make_password, check_password
from django.utils import timezone
from django.http import HttpRequest

from ninja import NinjaAPI, Schema
from ninja.security import HttpBearer

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
    
    def check_password(self, raw_password):
        return check_password(raw_password, self.password)

class Post(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(User, on_delete=models.CASCADE)
    title = models.CharField(max_length=255)
    content = models.TextField()
    status = models.CharField(max_length=10, choices=PostStatus.choices, default=PostStatus.DRAFT)

# --- schemas.py ---
class UserSchema(Schema):
    id: uuid.UUID
    email: str
    role: str

class PostSchemaIn(Schema):
    title: str
    content: str
    status: PostStatus = PostStatus.DRAFT

class PostSchemaOut(Schema):
    id: uuid.UUID
    user_id: uuid.UUID
    title: str
    content: str
    status: str

class LoginSchema(Schema):
    email: str
    password: str

class TokenSchema(Schema):
    token: str

class ErrorSchema(Schema):
    message: str

# --- auth.py ---
class JWTAuth(HttpBearer):
    def authenticate(self, request: HttpRequest, token: str) -> User | None:
        try:
            payload = jwt.decode(token, settings.SECRET_KEY, algorithms=['HS256'])
            user = User.objects.get(id=payload['id'])
            return user
        except (jwt.PyJWTError, User.DoesNotExist):
            return None

def is_admin(request: HttpRequest) -> bool:
    return request.auth and request.auth.role == UserRole.ADMIN

# --- api.py (replaces views.py and urls.py) ---
api = NinjaAPI(auth=JWTAuth())

@api.post("/auth/login", auth=None, response={200: TokenSchema, 401: ErrorSchema})
def login(request, payload: LoginSchema):
    try:
        user = User.objects.get(email=payload.email)
        if not user.check_password(payload.password):
            return 401, {"message": "Invalid credentials"}
    except User.DoesNotExist:
        return 401, {"message": "Invalid credentials"}
    
    token_payload = {'id': str(user.id), 'exp': datetime.utcnow() + timedelta(days=1)}
    token = jwt.encode(token_payload, settings.SECRET_KEY, algorithm='HS256')
    return 200, {"token": token}

@api.get("/posts", auth=None, response=List[PostSchemaOut])
def list_posts(request):
    return Post.objects.filter(status=PostStatus.PUBLISHED)

@api.post("/posts", response={201: PostSchemaOut})
def create_post(request, payload: PostSchemaIn):
    # request.auth is the user object from JWTAuth
    post = Post.objects.create(user=request.auth, **payload.dict())
    return 201, post

@api.put("/posts/{post_id}", response={200: PostSchemaOut, 403: ErrorSchema, 404: ErrorSchema})
def update_post(request, post_id: uuid.UUID, payload: PostSchemaIn):
    try:
        post = Post.objects.get(id=post_id)
    except Post.DoesNotExist:
        return 404, {"message": "Post not found"}

    # RBAC check
    if post.user != request.auth and request.auth.role != UserRole.ADMIN:
        return 403, {"message": "You do not have permission to edit this post"}

    for attr, value in payload.dict().items():
        setattr(post, attr, value)
    post.save()
    return 200, post

@api.delete("/posts/{post_id}", response={204: None, 403: ErrorSchema, 404: ErrorSchema})
def delete_post(request, post_id: uuid.UUID):
    try:
        post = Post.objects.get(id=post_id)
    except Post.DoesNotExist:
        return 404, {"message": "Post not found"}

    if post.user != request.auth and not is_admin(request): # Using the RBAC function
        return 403, {"message": "You do not have permission to delete this post"}
    
    post.delete()
    return 204, None

# --- urls.py ---
# In a real project, you would have a root urls.py that includes this api
# from django.urls import path
# from .api import api
# urlpatterns = [path("api/", api.urls)]

# For demonstration, we can assign it directly
urlpatterns = api.urls

print("Variation 4: Modern & Explicit with Django Ninja loaded.")