import os
import sys
import uuid
import re
from datetime import datetime

# --- Minimal Django & DRF Setup ---
# This setup allows running Django components in a standalone script.
def setup_django():
    os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'settings')
    from django.conf import settings
    if not settings.configured:
        settings.configure(
            SECRET_KEY='a-secret-key-for-testing',
            INSTALLED_APPS=[
                'django.contrib.auth',
                'django.contrib.contenttypes',
                'rest_framework',
            ],
            DATABASES={
                'default': {
                    'ENGINE': 'django.db.backends.sqlite3',
                    'NAME': ':memory:',
                }
            },
            REST_FRAMEWORK={
                'DEFAULT_RENDERER_CLASSES': [
                    'rest_framework.renderers.JSONRenderer',
                    # For production, you would add 'rest_framework_xml.renderers.XMLRenderer',
                ],
            }
        )
    import django
    django.setup()

setup_django()

# --- Imports ---
from django.db import models
from django.core.exceptions import ValidationError as DjangoValidationError
from rest_framework import serializers, status
from rest_framework.views import APIView
from rest_framework.response import Response
from rest_framework.test import APIRequestFactory

# --- Domain Models ---
class UserRole(models.TextChoices):
    ADMIN = 'ADMIN', 'Admin'
    USER = 'USER', 'User'

class PostStatus(models.TextChoices):
    DRAFT = 'DRAFT', 'Draft'
    PUBLISHED = 'PUBLISHED', 'Published'

class User(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    email = models.EmailField(unique=True)
    password_hash = models.CharField(max_length=128)
    role = models.CharField(max_length=10, choices=UserRole.choices, default=UserRole.USER)
    is_active = models.BooleanField(default=True)
    created_at = models.DateTimeField(auto_now_add=True)

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

# --- Custom Validators ---
def validate_phone_number(value):
    """A simple validator for a US phone number format."""
    if not re.match(r'^\+1\d{10}$', value):
        raise serializers.ValidationError("Phone number must be in the format +1XXXXXXXXXX.")

# --- Serializers / DTOs ---
class UserCreateSerializer(serializers.ModelSerializer):
    # Non-model field for input validation
    phone_number = serializers.CharField(validators=[validate_phone_number], write_only=True)
    # Override email to add custom error message
    email = serializers.EmailField(error_messages={'invalid': 'Please provide a valid email address.'})

    class Meta:
        model = User
        fields = ['id', 'email', 'password_hash', 'role', 'phone_number', 'created_at']
        read_only_fields = ['id', 'created_at']
        extra_kwargs = {
            'password_hash': {'write_only': True, 'required': True}
        }

    def create(self, validated_data):
        # Pop non-model fields before creating the model instance
        validated_data.pop('phone_number', None)
        # In a real app, you would hash the password here
        # validated_data['password_hash'] = make_password(validated_data['password_hash'])
        return User.objects.create(**validated_data)

class PostSerializer(serializers.ModelSerializer):
    user_id = serializers.UUIDField(source='user.id')

    class Meta:
        model = Post
        fields = ['id', 'user_id', 'title', 'content', 'status']

    def validate(self, data):
        """
        Object-level validation: Check if a user can publish a post.
        This runs after field-level validation.
        """
        # On creation, user is passed in context. On update, it's on the instance.
        user = self.context.get('user') or getattr(self.instance, 'user', None)
        if user and not user.is_active and data.get('status') == PostStatus.PUBLISHED:
            raise serializers.ValidationError({
                "status": "Inactive users cannot publish posts."
            })
        return data

# --- API Views ---
class UserRegistrationView(APIView):
    """
    Handles User creation with JSON data.
    Demonstrates input validation, JSON deserialization, and custom error formatting.
    """
    def post(self, request):
        serializer = UserCreateSerializer(data=request.data)
        if serializer.is_valid():
            user = serializer.save()
            # For demonstration, we serialize the created user back (excluding sensitive fields)
            response_data = {
                'id': user.id,
                'email': user.email,
                'role': user.role,
                'created_at': user.created_at
            }
            return Response(response_data, status=status.HTTP_201_CREATED)
        
        # DRF automatically formats errors
        return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)

# --- Demonstration ---
if __name__ == '__main__':
    factory = APIRequestFactory()

    print("--- Variation 1: By-the-Book DRF Developer ---")

    # 1. Test User Creation (Success)
    print("\n1. Testing successful user registration...")
    valid_user_data = {
        "email": "test@example.com",
        "password_hash": "strongpassword123",
        "role": "USER",
        "phone_number": "+15551234567"
    }
    request = factory.post('/users/', valid_user_data, format='json')
    view = UserRegistrationView.as_view()
    response = view(request)
    print(f"Status: {response.status_code}")
    print(f"Response JSON: {response.data}")
    assert response.status_code == 201

    # 2. Test User Creation (Validation Failure)
    print("\n2. Testing failed user registration (bad email and phone)...")
    invalid_user_data = {
        "email": "not-an-email",
        "password_hash": "short",
        "phone_number": "555-1234"
    }
    request = factory.post('/users/', invalid_user_data, format='json')
    response = view(request)
    print(f"Status: {response.status_code}")
    print(f"Formatted Error Response: {response.data}")
    assert response.status_code == 400
    assert 'email' in response.data and 'phone_number' in response.data

    # 3. Test Post Serialization (JSON Generation)
    print("\n3. Testing Post serialization to JSON...")
    # Create a mock user for the post
    user_instance = User.objects.create(email='author@example.com', password_hash='...')
    post_instance = Post.objects.create(user=user_instance, title="My First Post", content="Hello world.")
    serializer = PostSerializer(instance=post_instance)
    print(f"Serialized Post JSON: {serializer.data}")
    assert 'user_id' in serializer.data

    # 4. Test Post Deserialization with context validation
    print("\n4. Testing Post validation with context...")
    user_instance.is_active = False
    user_instance.save()
    post_data_to_validate = {
        "user_id": user_instance.id,
        "title": "New Title",
        "content": "Some content",
        "status": "PUBLISHED"
    }
    # Pass user in context to the serializer for validation
    serializer = PostSerializer(data=post_data_to_validate, context={'user': user_instance})
    is_valid = serializer.is_valid()
    print(f"Is post data valid for inactive user? {is_valid}")
    print(f"Validation Errors: {serializer.errors}")
    assert not is_valid
    assert 'status' in serializer.errors