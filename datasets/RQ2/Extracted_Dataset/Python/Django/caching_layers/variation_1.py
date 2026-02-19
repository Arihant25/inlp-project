import uuid
import datetime
import enum
import time
from unittest.mock import MagicMock

# --- Django Framework Mock Setup ---
# This setup simulates a Django environment for standalone execution.
from django.conf import settings
from django.core.cache import caches
from django.db import models
from django.db.models.signals import post_save, post_delete
from django.dispatch import receiver

if not settings.configured:
    settings.configure(
        # In-memory cache with LRU (Least Recently Used) strategy
        CACHES={
            'default': {
                'BACKEND': 'django.core.cache.backends.locmem.LocMemCache',
                'LOCATION': 'unique-snowflake',
                'TIMEOUT': 300,  # Default timeout for keys
                'OPTIONS': {
                    'MAX_ENTRIES': 1000,  # Max items before culling
                    'CULL_FREQUENCY': 3,  # 1/3 of items culled when MAX_ENTRIES is reached
                }
            }
        },
        # Mock database engine
        DATABASES={
            'default': {
                'ENGINE': 'django.db.backends.sqlite3',
                'NAME': ':memory:',
            }
        }
    )

cache = caches['default']

# --- Domain Schema: models.py ---

class UserRole(enum.Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(enum.Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

# Mocking Django's ORM for standalone execution
# In a real project, these would be standard Django models.
MOCK_DB = {
    'users': {},
    'posts': {}
}

class User:
    def __init__(self, id, email, password_hash, role, is_active, created_at):
        self.id = id
        self.email = email
        self.password_hash = password_hash
        self.role = role
        self.is_active = is_active
        self.created_at = created_at

class Post:
    def __init__(self, id, user_id, title, content, status):
        self.id = id
        self.user_id = user_id
        self.title = title
        self.content = content
        self.status = status
    
    @classmethod
    def save(cls, instance):
        print(f"DATABASE: Saving Post {instance.id}")
        MOCK_DB['posts'][instance.id] = instance
        # Simulate Django's signal dispatch
        post_save.send(sender=cls, instance=instance)

    @classmethod
    def delete(cls, instance):
        print(f"DATABASE: Deleting Post {instance.id}")
        if instance.id in MOCK_DB['posts']:
            del MOCK_DB['posts'][instance.id]
        # Simulate Django's signal dispatch
        post_delete.send(sender=cls, instance=instance)

# Mock ORM Manager
class PostManagerMock:
    def get(self, id):
        print(f"DATABASE: Querying for Post {id}")
        time.sleep(0.1) # Simulate DB latency
        if id in MOCK_DB['posts']:
            return MOCK_DB['posts'][id]
        raise FileNotFoundError("Post not found")

    def filter(self, user_id):
        print(f"DATABASE: Querying for Posts by User {user_id}")
        time.sleep(0.2) # Simulate DB latency
        return [p for p in MOCK_DB['posts'].values() if p.user_id == user_id]

Post.objects = PostManagerMock()


# --- Service Layer: services.py ---
# This developer prefers a clear separation of concerns using a service layer.

class PostService:
    @staticmethod
    def _get_post_cache_key(post_id: uuid.UUID) -> str:
        return f"post:{post_id}"

    @staticmethod
    def _get_user_posts_cache_key(user_id: uuid.UUID) -> str:
        return f"user_posts:{user_id}"

    def get_post_by_id(self, post_id: uuid.UUID) -> Post:
        """Implements Cache-Aside pattern for fetching a single post."""
        cache_key = self._get_post_cache_key(post_id)
        
        # 1. Attempt to get from cache
        cached_post = cache.get(cache_key)
        if cached_post:
            print(f"CACHE HIT for key: {cache_key}")
            return cached_post
        
        print(f"CACHE MISS for key: {cache_key}")
        
        # 2. On miss, get from data source (DB)
        post = Post.objects.get(id=post_id)
        
        # 3. Set the result in the cache for future requests
        # Time-based expiration: 1 hour
        cache.set(cache_key, post, timeout=3600)
        print(f"CACHE SET for key: {cache_key}")
        
        return post

    def get_posts_for_user(self, user_id: uuid.UUID) -> list[Post]:
        """Implements Cache-Aside for a list of posts."""
        cache_key = self._get_user_posts_cache_key(user_id)

        cached_posts = cache.get(cache_key)
        if cached_posts is not None: # Check for None to handle empty list caching
            print(f"CACHE HIT for key: {cache_key}")
            return cached_posts

        print(f"CACHE MISS for key: {cache_key}")
        posts = Post.objects.filter(user_id=user_id)
        
        # Time-based expiration: 10 minutes
        cache.set(cache_key, posts, timeout=600)
        print(f"CACHE SET for key: {cache_key}")

        return posts

    def update_post_content(self, post_id: uuid.UUID, new_content: str) -> Post:
        """Updates a post and triggers cache invalidation via signals."""
        post = self.get_post_by_id(post_id)
        post.content = new_content
        Post.save(post) # This will trigger the post_save signal
        return post

# --- Signal-based Cache Invalidation: signals.py ---
# This developer uses signals for automatic, decoupled cache invalidation.

@receiver(post_save, sender=Post)
def invalidate_post_cache_on_save(sender, instance, **kwargs):
    """Invalidates cache for a specific post and its user's post list on save."""
    post_key = PostService._get_post_cache_key(instance.id)
    user_posts_key = PostService._get_user_posts_cache_key(instance.user_id)
    
    print(f"SIGNAL (post_save): Deleting cache keys: {post_key}, {user_posts_key}")
    cache.delete(post_key)
    cache.delete(user_posts_key)

@receiver(post_delete, sender=Post)
def invalidate_post_cache_on_delete(sender, instance, **kwargs):
    """Invalidates cache for a specific post and its user's post list on delete."""
    post_key = PostService._get_post_cache_key(instance.id)
    user_posts_key = PostService._get_user_posts_cache_key(instance.user_id)
    
    print(f"SIGNAL (post_delete): Deleting cache keys: {post_key}, {user_posts_key}")
    cache.delete(post_key)
    cache.delete(user_posts_key)


# --- Example Usage: views.py ---

if __name__ == '__main__':
    # Setup mock data
    user_id_1 = uuid.uuid4()
    post_id_1 = uuid.uuid4()
    post_1 = Post(id=post_id_1, user_id=user_id_1, title="First Post", content="Content 1", status=PostStatus.PUBLISHED)
    MOCK_DB['posts'][post_id_1] = post_1

    service = PostService()

    print("--- First Request for a single post ---")
    service.get_post_by_id(post_id_1)
    
    print("\n--- Second Request for the same post (should be cached) ---")
    service.get_post_by_id(post_id_1)

    print("\n--- First Request for a user's posts ---")
    service.get_posts_for_user(user_id_1)

    print("\n--- Second Request for the same user's posts (should be cached) ---")
    service.get_posts_for_user(user_id_1)

    print("\n--- Updating a post (triggers invalidation) ---")
    service.update_post_content(post_id_1, "Updated Content!")

    print("\n--- Requesting the updated post (should be a cache miss) ---")
    updated_post = service.get_post_by_id(post_id_1)
    print(f"Retrieved content: '{updated_post.content}'")

    print("\n--- Requesting the user's posts again (should be a cache miss) ---")
    service.get_posts_for_user(user_id_1)