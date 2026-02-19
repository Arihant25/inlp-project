# Variation 2: The "Explicit" Approach (Generic Class-Based Views)
# Style: This developer prefers more explicit control than ViewSets but still wants the
# convenience of DRF's generic views. They separate the list/create logic from the
# detail/update/delete logic into two distinct classes.

import os
import sys
import uuid
from django.conf import settings
from django.core.management import execute_from_command_line
from django.db import models
from django.contrib.auth.models import AbstractUser, BaseUserManager
from django.http import Http404
from django.urls import path
from rest_framework import generics, pagination, filters
from rest_framework import serializers
from django_filters.rest_framework import DjangoFilterBackend

# --- Minimal Django Setup ---
def setup_django():
    settings.configure(
        SECRET_KEY='a-secret-key-for-testing-only-v2',
        DEBUG=True,
        DATABASES={'default': {'ENGINE': 'django.db.backends.sqlite3', 'NAME': ':memory:'}},
        INSTALLED_APPS=[
            'django.contrib.auth',
            'django.contrib.contenttypes',
            'rest_framework',
            'django_filters',
            '__main__',
        ],
        REST_FRAMEWORK={
            'DEFAULT_PAGINATION_CLASS': '__main__.CustomPagination',
            'DEFAULT_FILTER_BACKENDS': ['django_filters.rest_framework.DjangoFilterBackend'],
        },
        ROOT_URLCONF=__name__,
    )
    import django
    django.setup()

# --- Custom Pagination ---
class CustomPagination(pagination.PageNumberPagination):
    page_size = 5
    page_size_query_param = 'page_size'
    max_page_size = 100

# --- Models ---
class AppUserManager(BaseUserManager):
    def create_user(self, email, password=None, **extra_fields):
        if not email: raise ValueError('Users must have an email address')
        user = self.model(email=self.normalize_email(email), **extra_fields)
        user.set_password(password)
        user.save(using=self._db)
        return user

    def create_superuser(self, email, password, **extra_fields):
        extra_fields.setdefault('is_staff', True)
        extra_fields.setdefault('is_superuser', True)
        extra_fields.setdefault('role', User.Role.ADMIN)
        return self.create_user(email, password, **extra_fields)

class User(AbstractUser):
    class Role(models.TextChoices):
        ADMIN = 'ADMIN'
        USER = 'USER'
    
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    email = models.EmailField(unique=True)
    role = models.CharField(max_length=10, choices=Role.choices, default=Role.USER)
    username = None
    USERNAME_FIELD = 'email'
    REQUIRED_FIELDS = []
    objects = AppUserManager()

class Post(models.Model):
    class Status(models.TextChoices):
        DRAFT = 'DRAFT'
        PUBLISHED = 'PUBLISHED'
    
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(User, on_delete=models.CASCADE)
    title = models.CharField(max_length=255)
    content = models.TextField()
    status = models.CharField(max_length=10, choices=Status.choices, default=Status.DRAFT)

# --- Serializers ---
class UserAccountSerializer(serializers.ModelSerializer):
    created_at = serializers.DateTimeField(source='date_joined', read_only=True)

    class Meta:
        model = User
        fields = ('id', 'email', 'password', 'role', 'is_active', 'created_at')
        read_only_fields = ('id', 'created_at')
        extra_kwargs = {'password': {'write_only': True}}

    def create(self, validated_data):
        return User.objects.create_user(**validated_data)

    def update(self, instance, validated_data):
        password = validated_data.pop('password', None)
        for attr, value in validated_data.items():
            setattr(instance, attr, value)
        if password:
            instance.set_password(password)
        instance.save()
        return instance

# --- Views (Generic Class-Based) ---
class UserListCreateView(generics.ListCreateAPIView):
    """
    Handles GET (list) and POST (create) for User resources.
    """
    queryset = User.objects.all().order_by('email')
    serializer_class = UserAccountSerializer
    pagination_class = CustomPagination
    filter_backends = [DjangoFilterBackend, filters.SearchFilter, filters.OrderingFilter]
    filterset_fields = ['role', 'is_active']
    search_fields = ['email']
    ordering_fields = ['email', 'date_joined']

class UserDetailView(generics.RetrieveUpdateDestroyAPIView):
    """
    Handles GET (retrieve), PUT/PATCH (update), and DELETE for a single User resource.
    """
    queryset = User.objects.all()
    serializer_class = UserAccountSerializer
    lookup_field = 'id'

# --- URL Routing ---
urlpatterns = [
    path('api/users/', UserListCreateView.as_view(), name='user-list-create'),
    path('api/users/<uuid:id>/', UserDetailView.as_view(), name='user-detail'),
]

# --- Main execution block ---
def main():
    setup_django()
    execute_from_command_line(['manage.py', 'makemigrations', 'main'])
    execute_from_command_line(['manage.py', 'migrate'])

    # Create mock data
    User.objects.create_user(email='test.user.1@domain.com', password='password', role=User.Role.USER)
    User.objects.create_user(email='test.user.2@domain.com', password='password', role=User.Role.USER, is_active=False)
    User.objects.create_superuser(email='super.admin@domain.com', password='superpassword')
    print("Mock data created. Starting development server at http://127.0.0.1:8001/")
    print("API endpoints available at http://127.0.0.1:8001/api/users/")
    
    execute_from_command_line(['manage.py', 'runserver', '8001'])

if __name__ == "__main__":
    os.environ.setdefault('DJANGO_SETTINGS_MODULE', '__main__')
    main()