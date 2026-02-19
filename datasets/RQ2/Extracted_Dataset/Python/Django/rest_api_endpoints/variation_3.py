# Variation 3: The "Functional" Approach (Function-Based Views)
# Style: This developer prefers a functional style, using DRF's @api_view decorator.
# This approach is more explicit and can be simpler for straightforward endpoints.

import os
import sys
import uuid
from django.conf import settings
from django.core.management import execute_from_command_line
from django.db import models
from django.contrib.auth.models import AbstractUser, BaseUserManager
from django.urls import path
from rest_framework import status
from rest_framework.decorators import api_view
from rest_framework.response import Response
from rest_framework.pagination import PageNumberPagination
from django.shortcuts import get_object_or_404

# --- Minimal Django Setup ---
def setup_django():
    settings.configure(
        SECRET_KEY='a-secret-key-for-testing-only-v3',
        DEBUG=True,
        DATABASES={'default': {'ENGINE': 'django.db.backends.sqlite3', 'NAME': ':memory:'}},
        INSTALLED_APPS=[
            'django.contrib.auth',
            'django.contrib.contenttypes',
            'rest_framework',
            '__main__',
        ],
        ROOT_URLCONF=__name__,
    )
    import django
    django.setup()

# --- Models ---
class CustomUserManager(BaseUserManager):
    def create_user(self, email, password=None, **extra_fields):
        email = self.normalize_email(email)
        user = self.model(email=email, **extra_fields)
        user.set_password(password)
        user.save()
        return user

    def create_superuser(self, email, password, **extra_fields):
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
    username = None
    USERNAME_FIELD = 'email'
    REQUIRED_FIELDS = []
    objects = CustomUserManager()

class Post(models.Model):
    class Status(models.TextChoices):
        DRAFT = 'DRAFT', 'Draft'
        PUBLISHED = 'PUBLISHED', 'Published'
    
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(User, on_delete=models.CASCADE)
    title = models.CharField(max_length=255)
    content = models.TextField()
    status = models.CharField(max_length=10, choices=Status.choices, default=Status.DRAFT)

# --- Serializers (must be imported before views) ---
from rest_framework import serializers

class UserDataSerializer(serializers.ModelSerializer):
    created_at = serializers.DateTimeField(source='date_joined', read_only=True)

    class Meta:
        model = User
        fields = ['id', 'email', 'password', 'role', 'is_active', 'created_at']
        extra_kwargs = {'password': {'write_only': True}}

    def create(self, validated_data):
        return User.objects.create_user(**validated_data)

    def update(self, instance, validated_data):
        password = validated_data.pop('password', None)
        instance = super().update(instance, validated_data)
        if password:
            instance.set_password(password)
            instance.save()
        return instance

# --- Views (Function-Based) ---
@api_view(['GET', 'POST'])
def user_collection_view(request):
    """
    List all users with filtering and pagination, or create a new user.
    """
    if request.method == 'GET':
        users = User.objects.all().order_by('-date_joined')
        
        # Manual filtering
        role = request.query_params.get('role')
        is_active = request.query_params.get('is_active')
        search_email = request.query_params.get('search')

        if role:
            users = users.filter(role__iexact=role)
        if is_active is not None:
            is_active_bool = is_active.lower() in ['true', '1']
            users = users.filter(is_active=is_active_bool)
        if search_email:
            users = users.filter(email__icontains=search_email)

        paginator = PageNumberPagination()
        paginator.page_size = 10
        paginated_users = paginator.paginate_queryset(users, request)
        serializer = UserDataSerializer(paginated_users, many=True)
        return paginator.get_paginated_response(serializer.data)

    elif request.method == 'POST':
        serializer = UserDataSerializer(data=request.data)
        if serializer.is_valid():
            serializer.save()
            return Response(serializer.data, status=status.HTTP_201_CREATED)
        return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)

@api_view(['GET', 'PUT', 'PATCH', 'DELETE'])
def user_element_view(request, pk):
    """
    Retrieve, update or delete a user instance.
    """
    user = get_object_or_404(User, pk=pk)

    if request.method == 'GET':
        serializer = UserDataSerializer(user)
        return Response(serializer.data)

    elif request.method in ['PUT', 'PATCH']:
        partial = request.method == 'PATCH'
        serializer = UserDataSerializer(user, data=request.data, partial=partial)
        if serializer.is_valid():
            serializer.save()
            return Response(serializer.data)
        return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)

    elif request.method == 'DELETE':
        user.delete()
        return Response(status=status.HTTP_204_NO_CONTENT)

# --- URL Routing ---
urlpatterns = [
    path('api/v1/users/', user_collection_view, name='user-collection'),
    path('api/v1/users/<uuid:pk>/', user_element_view, name='user-element'),
]

# --- Main execution block ---
def main():
    setup_django()
    execute_from_command_line(['manage.py', 'makemigrations', 'main'])
    execute_from_command_line(['manage.py', 'migrate'])

    User.objects.create_user(email='alice@web.com', password='password')
    User.objects.create_user(email='bob@web.com', password='password', is_active=False)
    User.objects.create_superuser(email='charlie@web.com', password='superpassword')
    print("Mock data created. Starting development server at http://127.0.0.1:8002/")
    print("API endpoints available at http://127.0.0.1:8002/api/v1/users/")
    
    execute_from_command_line(['manage.py', 'runserver', '8002'])

if __name__ == "__main__":
    os.environ.setdefault('DJANGO_SETTINGS_MODULE', '__main__')
    main()