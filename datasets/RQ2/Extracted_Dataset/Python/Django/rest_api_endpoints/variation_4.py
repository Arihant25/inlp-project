# Variation 4: The "Verbose" Approach (APIView)
# Style: This developer wants maximum, low-level control and avoids DRF's generic
# view "magic". They build their views from the base APIView, handling every
# detail like object retrieval and serialization logic manually within each method.

import os
import sys
import uuid
from django.conf import settings
from django.core.management import execute_from_command_line
from django.db import models
from django.contrib.auth.models import AbstractUser, BaseUserManager
from django.urls import path
from django.http import Http404
from rest_framework.views import APIView
from rest_framework.response import Response
from rest_framework import status, serializers
from rest_framework.pagination import PageNumberPagination

# --- Minimal Django Setup ---
def setup_django():
    settings.configure(
        SECRET_KEY='a-secret-key-for-testing-only-v4',
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
class ServiceUserManager(BaseUserManager):
    def create_user(self, email, password=None, **extra_fields):
        if not email: raise ValueError('Email is required')
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
    objects = ServiceUserManager()

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
class UserResourceSerializer(serializers.ModelSerializer):
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

# --- Views (APIView) ---
class UserList(APIView):
    """
    List all users or create a new one.
    """
    def get(self, request, format=None):
        queryset = User.objects.all().order_by('email')

        # Manual Filtering
        if 'role' in request.query_params:
            queryset = queryset.filter(role=request.query_params['role'].upper())
        if 'is_active' in request.query_params:
            is_active_val = request.query_params['is_active'].lower() in ('true', '1')
            queryset = queryset.filter(is_active=is_active_val)
        if 'email_contains' in request.query_params:
            queryset = queryset.filter(email__icontains=request.query_params['email_contains'])

        # Manual Pagination
        paginator = PageNumberPagination()
        paginator.page_size = 10
        paginated_queryset = paginator.paginate_queryset(queryset, request, view=self)
        serializer = UserResourceSerializer(paginated_queryset, many=True)
        return paginator.get_paginated_response(serializer.data)

    def post(self, request, format=None):
        serializer = UserResourceSerializer(data=request.data)
        if serializer.is_valid():
            serializer.save()
            return Response(serializer.data, status=status.HTTP_201_CREATED)
        return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)

class UserDetail(APIView):
    """
    Retrieve, update or delete a user instance.
    """
    def get_object(self, pk):
        try:
            return User.objects.get(pk=pk)
        except User.DoesNotExist:
            raise Http404

    def get(self, request, pk, format=None):
        user = self.get_object(pk)
        serializer = UserResourceSerializer(user)
        return Response(serializer.data)

    def put(self, request, pk, format=None):
        user = self.get_object(pk)
        serializer = UserResourceSerializer(user, data=request.data)
        if serializer.is_valid():
            serializer.save()
            return Response(serializer.data)
        return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)
    
    def patch(self, request, pk, format=None):
        user = self.get_object(pk)
        serializer = UserResourceSerializer(user, data=request.data, partial=True)
        if serializer.is_valid():
            serializer.save()
            return Response(serializer.data)
        return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)

    def delete(self, request, pk, format=None):
        user = self.get_object(pk)
        user.delete()
        return Response(status=status.HTTP_204_NO_CONTENT)

# --- URL Routing ---
urlpatterns = [
    path('users/', UserList.as_view()),
    path('users/<uuid:pk>/', UserDetail.as_view()),
]

# --- Main execution block ---
def main():
    setup_django()
    execute_from_command_line(['manage.py', 'makemigrations', 'main'])
    execute_from_command_line(['manage.py', 'migrate'])

    User.objects.create_user(email='dave@mail.io', password='password')
    User.objects.create_user(email='eve@mail.io', password='password', is_active=False)
    User.objects.create_superuser(email='frank@mail.io', password='superpassword')
    print("Mock data created. Starting development server at http://127.0.0.1:8003/")
    print("API endpoints available at http://127.0.0.1:8003/users/")
    
    execute_from_command_line(['manage.py', 'runserver', '8003'])

if __name__ == "__main__":
    os.environ.setdefault('DJANGO_SETTINGS_MODULE', '__main__')
    main()