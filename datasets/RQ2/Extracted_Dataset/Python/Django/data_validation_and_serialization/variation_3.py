import os
import sys
import uuid
from datetime import datetime
from xml.dom.minidom import parseString
from dicttoxml import dicttoxml

# --- Minimal Django & DRF Setup ---
def setup_django():
    os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'settings')
    from django.conf import settings
    if not settings.configured:
        settings.configure(
            SECRET_KEY='a-secret-key-for-testing',
            INSTALLED_APPS=['django.contrib.auth', 'django.contrib.contenttypes', 'rest_framework'],
            DATABASES={'default': {'ENGINE': 'django.db.backends.sqlite3', 'NAME': ':memory:'}}
        )
    import django
    django.setup()

setup_django()

# --- Imports ---
from django.db import models
from django.http import HttpResponse
from rest_framework import serializers, status
from rest_framework.decorators import api_view
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

class Post(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(User, on_delete=models.CASCADE)
    title = models.CharField(max_length=255)
    content = models.TextField()
    status = models.CharField(max_length=10, choices=PostStatus.choices, default=PostStatus.DRAFT)

# --- Validation Utilities ---
def validate_post_data(data, user):
    """
    A standalone validation function to separate logic from the view.
    """
    errors = {}
    title = data.get('title', '')
    content = data.get('content', '')

    if not title or len(title) < 5:
        errors['title'] = 'Title must be at least 5 characters long.'
    
    if not content:
        errors['content'] = 'Content cannot be empty.'

    if data.get('status') == PostStatus.PUBLISHED and "placeholder" in content.lower():
        errors['content'] = 'Cannot publish a post with placeholder content.'

    if not user.is_active:
        errors['user'] = 'User must be active to create posts.'

    return errors

# --- Serializers / DTOs ---
class PostModelSerializer(serializers.ModelSerializer):
    # Type coercion: DRF handles converting UUID string from request to UUID object
    user_id = serializers.UUIDField(write_only=True)

    class Meta:
        model = Post
        fields = ['id', 'user_id', 'title', 'content', 'status']
    
    def create(self, validated_data):
        # The user object is expected to be in the validated_data
        # We'll inject it in the view
        return Post.objects.create(**validated_data)

# --- API Views (Functional Style) ---
@api_view(['POST'])
def create_post_endpoint(request):
    """
    Creates a new post using a functional view.
    """
    try:
        # Type conversion from string UUID to User instance
        user = User.objects.get(id=request.data.get('user_id'))
    except (User.DoesNotExist, ValueError):
        return Response({"user_id": "Valid user_id is required."}, status=status.HTTP_400_BAD_REQUEST)

    # Use the external validation utility
    validation_errors = validate_post_data(request.data, user)
    if validation_errors:
        return Response(validation_errors, status=status.HTTP_400_BAD_REQUEST)

    # Use serializer for deserialization and object creation
    serializer = PostModelSerializer(data=request.data)
    if serializer.is_valid():
        # Inject the user instance into validated_data before saving
        serializer.save(user=user)
        
        # XML Generation on demand
        if 'application/xml' in request.META.get('HTTP_ACCEPT', ''):
            xml_output = dicttoxml(serializer.data, custom_root='post', attr_type=False)
            pretty_xml = parseString(xml_output).toprettyxml()
            return HttpResponse(pretty_xml, content_type='application/xml', status=status.HTTP_201_CREATED)

        return Response(serializer.data, status=status.HTTP_201_CREATED)
    
    # Fallback for serializer's own validation
    return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)

# --- Demonstration ---
if __name__ == '__main__':
    factory = APIRequestFactory()
    
    print("--- Variation 3: The Functional Fanatic ---")

    # Setup a user for testing
    active_user = User.objects.create(email='active@user.com', password_hash='...', is_active=True)
    inactive_user = User.objects.create(email='inactive@user.com', password_hash='...', is_active=False)

    # 1. Test Post Creation (Failure due to custom validation function)
    print("\n1. Testing post creation failure (inactive user)...")
    post_data = {
        "user_id": str(inactive_user.id),
        "title": "A Valid Title",
        "content": "Some valid content."
    }
    request = factory.post('/posts/', post_data, format='json')
    response = create_post_endpoint(request)
    print(f"Status: {response.status_code}")
    print(f"Error Response: {response.data}")
    assert response.status_code == 400
    assert 'user' in response.data

    # 2. Test Post Creation (Success, JSON response)
    print("\n2. Testing successful post creation (JSON response)...")
    post_data = {
        "user_id": str(active_user.id),
        "title": "My Awesome Post",
        "content": "This is some great content.",
        "status": "DRAFT"
    }
    request = factory.post('/posts/', post_data, format='json', HTTP_ACCEPT='application/json')
    response = create_post_endpoint(request)
    print(f"Status: {response.status_code}")
    print(f"Response JSON: {response.data}")
    assert response.status_code == 201

    # 3. Test Post Creation (Success, XML response)
    print("\n3. Testing successful post creation (XML response)...")
    post_data_for_xml = {
        "user_id": str(active_user.id),
        "title": "My XML Post",
        "content": "This content will be in XML.",
        "status": "PUBLISHED"
    }
    request = factory.post('/posts/', post_data_for_xml, format='json', HTTP_ACCEPT='application/xml')
    response = create_post_endpoint(request)
    print(f"Status: {response.status_code}")
    print(f"Response XML:\n{response.content.decode('utf-8')}")
    assert response.status_code == 201
    assert 'application/xml' in response['Content-Type']