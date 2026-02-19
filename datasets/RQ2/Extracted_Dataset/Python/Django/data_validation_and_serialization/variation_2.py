import os
import sys
import uuid
from datetime import datetime
import xml.etree.ElementTree as ET

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

class Post(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(User, on_delete=models.CASCADE)
    title = models.CharField(max_length=255)
    content = models.TextField()
    status = models.CharField(max_length=10, choices=PostStatus.choices, default=PostStatus.DRAFT)

# --- Serializers / DTOs (Explicit Style) ---
class UserDataTransferObject(serializers.Serializer):
    """
    A more explicit serializer, defining each field manually.
    This gives fine-grained control over validation and representation.
    """
    id = serializers.UUIDField(read_only=True)
    email = serializers.EmailField()
    password = serializers.CharField(write_only=True, style={'input_type': 'password'})
    role = serializers.ChoiceField(choices=UserRole.choices, default=UserRole.USER)
    is_active = serializers.BooleanField(read_only=True)
    created_at = serializers.DateTimeField(read_only=True)

    def validate_email(self, value):
        """Field-level validation for email."""
        if "spam" in value.lower():
            raise serializers.ValidationError("Email contains a forbidden word.")
        if User.objects.filter(email=value).exists():
            raise serializers.ValidationError("An account with this email already exists.")
        return value

    def create(self, validated_data):
        # Rename 'password' to 'password_hash' for the model
        validated_data['password_hash'] = f"hashed_{validated_data.pop('password')}"
        return User.objects.create(**validated_data)

    def update(self, instance, validated_data):
        # Update logic would go here
        instance.email = validated_data.get('email', instance.email)
        instance.role = validated_data.get('role', instance.role)
        instance.save()
        return instance

# --- XML Generation Helper ---
def generate_user_xml(user_data):
    """Converts a user data dictionary to an XML string."""
    root = ET.Element("user")
    for key, value in user_data.items():
        if key == 'id':
            root.set('id', str(value))
            continue
        child = ET.SubElement(root, key)
        child.text = str(value)
    return ET.tostring(root, encoding='unicode')

# --- API Views ---
class UserProfileView(APIView):
    """
    Handles User data, demonstrating explicit serialization and manual XML generation.
    """
    def get(self, request, user_id):
        try:
            user = User.objects.get(id=user_id)
        except User.DoesNotExist:
            return Response({"error": "User not found"}, status=status.HTTP_404_NOT_FOUND)

        # Serialize the user instance
        serializer = UserDataTransferObject(instance=user)
        
        # Content Negotiation
        if request.accepted_renderer.format == 'xml':
            xml_data = generate_user_xml(serializer.data)
            return HttpResponse(xml_data, content_type='application/xml')
        
        return Response(serializer.data)

    def post(self, request):
        user_validator = UserDataTransferObject(data=request.data)
        if not user_validator.is_valid():
            return Response(user_validator.errors, status=status.HTTP_400_BAD_REQUEST)
        
        new_user = user_validator.save()
        response_serializer = UserDataTransferObject(instance=new_user)
        return Response(response_serializer.data, status=status.HTTP_201_CREATED)

# --- Demonstration ---
if __name__ == '__main__':
    factory = APIRequestFactory()
    
    print("--- Variation 2: Explicit is Better than Implicit Developer ---")

    # 1. Test User Creation (Validation Failure)
    print("\n1. Testing user creation with invalid email...")
    invalid_data = {"email": "test@spam.com", "password": "password123"}
    request = factory.post('/users/', invalid_data, format='json')
    view = UserProfileView.as_view()
    response = view(request)
    print(f"Status: {response.status_code}")
    print(f"Error Response: {response.data}")
    assert response.status_code == 400
    assert 'email' in response.data

    # 2. Test User Creation (Success)
    print("\n2. Testing successful user creation...")
    valid_data = {"email": "jane.doe@example.com", "password": "a-secure-password"}
    request = factory.post('/users/', valid_data, format='json')
    response = view(request)
    print(f"Status: {response.status_code}")
    print(f"Response JSON: {response.data}")
    assert response.status_code == 201
    created_user_id = response.data['id']

    # 3. Test GET request for JSON response
    print("\n3. Testing GET request for JSON...")
    request = factory.get(f'/users/{created_user_id}/', HTTP_ACCEPT='application/json')
    # Mock the renderer for content negotiation
    request.accepted_renderer = type('JSONRenderer', (), {'format': 'json'})()
    response = view(request, user_id=created_user_id)
    print(f"Status: {response.status_code}")
    print(f"Response JSON: {response.data}")
    assert response.status_code == 200

    # 4. Test GET request for XML response
    print("\n4. Testing GET request for manual XML generation...")
    request = factory.get(f'/users/{created_user_id}/', HTTP_ACCEPT='application/xml')
    # Mock the renderer for content negotiation
    request.accepted_renderer = type('XMLRenderer', (), {'format': 'xml'})()
    response = view(request, user_id=created_user_id)
    print(f"Status: {response.status_code}")
    print(f"Response XML:\n{response.content.decode('utf-8')}")
    assert response.status_code == 200
    assert response['Content-Type'] == 'application/xml'