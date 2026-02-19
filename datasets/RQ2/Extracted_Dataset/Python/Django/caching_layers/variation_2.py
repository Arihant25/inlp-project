import uuid
import datetime
import enum
import time
from unittest.mock import MagicMock

# --- Django Framework Mock Setup ---
from django.conf import settings
from django.core.cache import caches
from django.db import models

if not settings.configured:
    settings.configure(
        CACHES={
            'default': {
                'BACKEND': 'django.core.cache.backends.locmem.LocMemCache',
                'LOCATION': 'lru-cache-for-models',
                'TIMEOUT': 60 * 15, # 15 minute default
                'OPTIONS': {
                    'MAX_ENTRIES': 500,
                    'CULL_FREQUENCY': 2, # Cull 1/2 of entries when full
                }
            }
        },
        DATABASES={
            'default': {
                'ENGINE': 'django.db.backends.sqlite3',
                'NAME': ':memory:',
            }
        }
    )

cache = caches['default']

# --- Mock Database ---
MOCK_DB = {
    'users': {},
    'posts': {}
}

# --- Domain Schema: models.py ---
# This developer prefers logic within the Model or its Manager (Fat Model/Manager pattern).

class UserRole(enum.Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(enum.Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

class PostQuerySetMock:
    def __init__(self, items):
        self._items = items
    def get(self, id):
        print(f"DATABASE: Querying for Post {id}")
        time.sleep(0.1)
        if id in self._items:
            return self._items[id]
        raise self.model.DoesNotExist
    def filter(self, user_id):
        print(f"DATABASE: Querying for Posts by User {user_id}")
        time.sleep(0.2)
        return [p for p in self._items.values() if p.user_id == user_id]

class PostManager(models.Manager):
    def get_queryset(self):
        # In a real app, this returns a QuerySet. Here we mock it.
        return PostQuerySetMock(MOCK_DB['posts'])

    def get_cached(self, post_id: uuid.UUID):
        """Cache-aside logic implemented in the custom manager."""
        key = Post.get_cache_key(post_id)
        post = cache.get(key)
        if post:
            print(f"CACHE HIT for Post ID: {post_id}")
            return post
        
        print(f"CACHE MISS for Post ID: {post_id}")
        post = self.get_queryset().get(id=post_id)
        cache.set(key, post, timeout=3600) # 1 hour expiration
        print(f"CACHE SET for Post ID: {post_id}")
        return post

    def get_user_posts_cached(self, user_id: uuid.UUID):
        key = f"user:{user_id}:posts"
        posts = cache.get(key)
        if posts is not None:
            print(f"CACHE HIT for User Posts: {user_id}")
            return posts
        
        print(f"CACHE MISS for User Posts: {user_id}")
        posts = self.get_queryset().filter(user_id=user_id)
        cache.set(key, posts, timeout=600) # 10 minute expiration
        print(f"CACHE SET for User Posts: {user_id}")
        return posts

class Post(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4)
    user_id = models.UUIDField()
    title = models.CharField(max_length=255)
    content = models.TextField()
    status = models.CharField(max_length=10)

    # Attach the custom manager
    objects = PostManager()

    class DoesNotExist(Exception): pass
    
    # In a real Django model, you wouldn't pass `self` to save/delete
    def save(self, *args, **kwargs):
        """Overrides save to handle cache invalidation."""
        print(f"DATABASE: Saving Post {self.id}")
        MOCK_DB['posts'][self.id] = self
        self.clear_cache()
        # In real Django: super().save(*args, **kwargs)

    def delete(self, *args, **kwargs):
        """Overrides delete to handle cache invalidation."""
        print(f"DATABASE: Deleting Post {self.id}")
        if self.id in MOCK_DB['posts']:
            del MOCK_DB['posts'][self.id]
        self.clear_cache()
        # In real Django: super().delete(*args, **kwargs)

    @staticmethod
    def get_cache_key(post_id: uuid.UUID) -> str:
        return f"post_obj:{post_id}"

    def clear_cache(self):
        """Centralized method for cache invalidation."""
        post_key = self.get_cache_key(self.id)
        user_posts_key = f"user:{self.user_id}:posts"
        print(f"INVALIDATION: Deleting keys: {post_key}, {user_posts_key}")
        cache.delete(post_key)
        cache.delete(user_posts_key)

    # Mocking model instantiation
    def __init__(self, id, user_id, title, content, status):
        self.id = id
        self.user_id = user_id
        self.title = title
        self.content = content
        self.status = status

# --- Example Usage: views.py ---

if __name__ == '__main__':
    # Setup mock data
    user_id_1 = uuid.uuid4()
    post_id_1 = uuid.uuid4()
    post_1 = Post(id=post_id_1, user_id=user_id_1, title="Post One", content="Content...", status=PostStatus.PUBLISHED)
    MOCK_DB['posts'][post_id_1] = post_1

    print("--- First call to get a post ---")
    Post.objects.get_cached(post_id_1)

    print("\n--- Second call to get the same post (should hit cache) ---")
    Post.objects.get_cached(post_id_1)

    print("\n--- First call to get user's posts ---")
    Post.objects.get_user_posts_cached(user_id_1)

    print("\n--- Second call to get user's posts (should hit cache) ---")
    Post.objects.get_user_posts_cached(user_id_1)

    print("\n--- Updating the post via save() method ---")
    retrieved_post = Post.objects.get_cached(post_id_1)
    retrieved_post.title = "Updated Title"
    retrieved_post.save()

    print("\n--- Fetching the post again (should be a miss) ---")
    updated_post = Post.objects.get_cached(post_id_1)
    print(f"Retrieved title: '{updated_post.title}'")

    print("\n--- Fetching the user's posts again (should be a miss) ---")
    Post.objects.get_user_posts_cached(user_id_1)