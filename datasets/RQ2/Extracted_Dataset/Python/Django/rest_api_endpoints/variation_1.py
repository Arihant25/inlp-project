# Variation 1: The "Classic" DRF Approach (ModelViewSet & Routers)
# Style: This is the most common, concise, and idiomatic DRF approach.
# It leverages ViewSets and Routers for maximum code reuse and convention over configuration.

import os
import sys
import uuid
from django.conf import settings
from django.core.management import execute_from_command_line
from django.db import models
from django.contrib.auth.models import AbstractUser, BaseUserManager
from django.http import JsonResponse
from django.urls import path, include
from rest_framework import serializers, viewsets, pagination, filters
from rest_framework.routers import DefaultRouter
from django_filters.rest_framework import DjangoFilterBackend, FilterSet, CharFilter

# --- Minimal Django Setup (for self-contained execution) ---
def setup_django():
    settings.configure(
        SECRET_KEY='a-secret-key-for-testing-only',
        DEBUG=True,
        DATABASES={
            'default': {
                'ENGINE': 'django.db.backends.sqlite3',
                'NAME': ':memory:',
            }
        },
        INSTALLED_APPS=[
            'django.contrib.auth',
            'django.contrib.contenttypes',
            'rest_framework',
            'django_filters',
            '__main__',  # Refers to the current script
        ],
        REST_FRAMEWORK={
            'DEFAULT_PAGINATION_CLASS': 'rest_framework.pagination.PageNumberPagination',
            'PAGE_SIZE': 10,
            'DEFAULT_FILTER_BACKENDS': ['django_filters.rest_framework.DjangoFilterBackend'],
        },
        ROOT_URLCONF=__name__,
    )
    import django
    django.setup()

# --- Models ---
class UserManager(BaseUserManager):
    def create_user(self, email, password=None, **extra_fields):
        if not email:
            raise ValueError('The Email field must be set')
        email = self.normalize_email(email)
        user = self.model(email=email, **extra_fields)
        user.set_password(password)
        user.save(using=self._db)
        return user

    def create_superuser(self, email, password=None, **extra_fields):
        extra_fields.setdefault('is_staff', True)
        extra_fields.setdefault('is_superuser', True)
        extra_fields.setdefault('role', User.Role.ADMIN)
        return self.create_user(email, password, **extra_fields)

class User(AbstractUser):
    class Role(models.TextChoices):
        ADMIN = 'ADMIN', 'Admin'
        USER = 'USER', 'User'

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    email = models.EmailField(unique=True)
    role = models.CharField(max_length=10, choices=Role.choices, default=Role.USER)
    
    # password_hash is the 'password' field from AbstractUser
    # created_at is the 'date_joined' field from AbstractUser
    # is_active is also from AbstractUser

    username = None  # We use email as the unique identifier
    USERNAME_FIELD = 'email'
    REQUIRED_FIELDS = []
    
    objects = UserManager()

class Post(models.Model):
    class Status(models.TextChoices):
        DRAFT = 'DRAFT', 'Draft'
        PUBLISHED = 'PUBLISHED', 'Published'

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name='posts')
    title = models.CharField(max_length=255)
    content = models.TextField()
    status = models.CharField(max_length=10, choices=Status.choices, default=Status.DRAFT)

# --- Serializers ---
class UserSerializer(serializers.ModelSerializer):
    # Renaming date_joined to created_at for API consistency
    created_at = serializers.DateTimeField(source='date_joined', read_only=True)

    class Meta:
        model = User
        fields = ['id', 'email', 'password', 'role', 'is_active', 'created_at']
        extra_kwargs = {
            'password': {'write_only': True, 'style': {'input_type': 'password'}},
        }

    def create(self, validated_data):
        user = User.objects.create_user(
            email=validated_data['email'],
            password=validated_data['password'],
            role=validated_data.get('role', User.Role.USER),
            is_active=validated_data.get('is_active', True)
        )
        return user

    def update(self, instance, validated_data):
        password = validated_data.pop('password', None)
        user = super().update(instance, validated_data)
        if password:
            user.set_password(password)
            user.save()
        return user

# --- Filters ---
class UserFilter(FilterSet):
    email = CharFilter(lookup_expr='icontains')

    class Meta:
        model = User
        fields = ['email', 'role', 'is_active']

# --- Views (ViewSet) ---
class UserViewSet(viewsets.ModelViewSet):
    """
    API endpoint that allows users to be viewed or edited.
    Provides list, create, retrieve, update, and destroy actions.
    Supports filtering by email (contains), role (exact), and is_active (exact).
    """
    queryset = User.objects.all().order_by('-date_joined')
    serializer_class = UserSerializer
    filterset_class = UserFilter
    search_fields = ['email'] # Adds search box in browsable API

# --- URL Routing ---
router = DefaultRouter()
router.register(r'users', UserViewSet)

urlpatterns = [
    path('api/', include(router.urls)),
]

# --- Main execution block for demonstration ---
def main():
    setup_django()
    
    # In-memory migrations
    execute_from_command_line(['manage.py', 'makemigrations', 'main'])
    execute_from_command_line(['manage.py', 'migrate'])

    # Create mock data
    User.objects.create_user(email='user1@example.com', password='password123', role=User.Role.USER)
    User.objects.create_user(email='user2@example.com', password='password123', role=User.Role.USER, is_active=False)
    User.objects.create_superuser(email='admin@example.com', password='adminpassword')
    print("Mock data created. Starting development server at http://127.0.0.1:8000/")
    print("API endpoints available at http://127.0.0.1:8000/api/users/")
    
    # Run server
    execute_from_command_line(['manage.py', 'runserver'])

if __name__ == "__main__":
    # This setup allows the script to be run directly
    # In a real project, these files would be separate and managed by Django's manage.py
    # For this self-contained example, we simulate the environment.
    os.environ.setdefault('DJANGO_SETTINGS_MODULE', '__main__')
    from django.core.management.commands.runserver import Command as runserver
    runserver.default_port = "8000"
    main()