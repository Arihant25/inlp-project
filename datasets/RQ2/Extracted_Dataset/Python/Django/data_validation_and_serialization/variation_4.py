import os
import sys
import uuid
from datetime import datetime
import io

# --- Minimal Django & DRF Setup ---
def setup_django():
    os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'settings')
    from django.conf import settings
    if not settings.configured:
        settings.configure(
            SECRET_KEY='a-secret-key-for-testing',
            INSTALLED_APPS=['django.contrib.auth', 'django.contrib.contenttypes', 'rest_framework'],
            DATABASES={'default': {'ENGINE': 'django.db.backends.sqlite3', 'NAME': ':memory:'}},
            REST_FRAMEWORK={
                'DEFAULT_PARSER_CLASSES': [
                    'rest_framework.parsers.JSONParser',
                    'rest_framework_xml.parsers.XMLParser', # Assume this is installed for production
                ],
            }
        )
    import django
    django.setup()

setup_django()

# --- Imports ---
from django.db import models
from rest_framework import serializers, status
from rest_framework.views import APIView
from rest_framework.response import Response
from rest_framework.test import APIRequestFactory
from rest_framework.parsers import JSONParser, BaseParser
import xml.etree.ElementTree as ET

# --- Mock XML Parser for standalone script ---
class SimpleXMLParser(BaseParser):
    media_type = 'application/xml'
    def parse(self, stream, media_type=None, parser_context=None):
        data = stream.read().decode('utf-8')
        root = ET.fromstring(data)
        parsed_data = {child.tag: child.text for child in root}
        return parsed_data

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

# --- Reusable Class-Based Validator ---
class ProfanityValidator:
    """
    A reusable validator class to check for forbidden words.
    Can be easily configured and reused across different serializers.
    """
    FORBIDDEN_WORDS = {'crap', 'heck', 'darn'}

    def __init__(self, forbidden_words=None):
        self.forbidden_words = forbidden_words or self.FORBIDDEN_WORDS

    def __call__(self, value):
        found_words = self.forbidden_words.intersection(value.lower().split())
        if found_words:
            raise serializers.ValidationError(f"Content contains forbidden words: {', '.join(found_words)}")

# --- Serializers / DTOs (Pragmatic & Minimalist) ---
class PostMinimalSerializer(serializers.ModelSerializer):
    # Use extra_kwargs to configure field validation concisely
    class Meta:
        model = Post
        fields = ['id', 'user', 'title', 'content', 'status']
        extra_kwargs = {
            'user': {'required': True},
            'title': {'min_length': 10, 'error_messages': {'min_length': 'Title is too short.'}},
            'content': {'validators': [ProfanityValidator()]},
            'id': {'read_only': True}
        }

# --- API Views ---
class PostManagerView(APIView):
    """
    A lean view demonstrating XML parsing and class-based validators.
    """
    parser_classes = [JSONParser, SimpleXMLParser] # Use our mock parser

    def post(self, request):
        # The parser automatically handles JSON or XML based on Content-Type
        # and populates request.data
        serializer = PostMinimalSerializer(data=request.data)
        
        if serializer.is_valid():
            serializer.save()
            return Response(serializer.data, status=status.HTTP_201_CREATED)
        
        return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)

# --- Demonstration ---
if __name__ == '__main__':
    factory = APIRequestFactory()
    
    print("--- Variation 4: The Pragmatic Minimalist ---")

    # Setup a user for testing
    test_user = User.objects.create(email='test.user@example.com', password_hash='...')
    
    # 1. Test Post Creation with JSON (Validation Failure)
    print("\n1. Testing post creation with JSON (profanity validator)...")
    invalid_json_data = {
        "user": test_user.id,
        "title": "A Post About Nothing",
        "content": "This is a bunch of crap.",
        "status": "DRAFT"
    }
    request = factory.post('/posts/', invalid_json_data, format='json')
    view = PostManagerView.as_view()
    response = view(request)
    print(f"Status: {response.status_code}")
    print(f"Error Response: {response.data}")
    assert response.status_code == 400
    assert 'content' in response.data and 'crap' in response.data['content'][0]

    # 2. Test Post Creation with XML (Validation Failure)
    print("\n2. Testing post creation with XML (title too short)...")
    invalid_xml_data = f"""
    <post>
        <user>{test_user.id}</user>
        <title>Short</title>
        <content>This is some valid content.</content>
        <status>DRAFT</status>
    </post>
    """
    request = factory.post('/posts/', invalid_xml_data, content_type='application/xml')
    response = view(request)
    print(f"Status: {response.status_code}")
    print(f"Error Response: {response.data}")
    assert response.status_code == 400
    assert 'title' in response.data

    # 3. Test Post Creation with XML (Success)
    print("\n3. Testing successful post creation with XML input...")
    valid_xml_data = f"""
    <post>
        <user>{test_user.id}</user>
        <title>A Perfectly Valid Title</title>
        <content>This content is clean and proper.</content>
        <status>PUBLISHED</status>
    </post>
    """
    request = factory.post('/posts/', valid_xml_data, content_type='application/xml')
    response = view(request)
    print(f"Status: {response.status_code}")
    print(f"Response Data (from XML input): {response.data}")
    assert response.status_code == 201
    assert response.data['title'] == 'A Perfectly Valid Title'