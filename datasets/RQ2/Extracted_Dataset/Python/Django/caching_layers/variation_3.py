import uuid
import datetime
import enum
import time
import functools
import hashlib
import json

# --- Django Framework Mock Setup ---
from django.conf import settings
from django.core.cache import caches

if not settings.configured:
    settings.configure(
        CACHES={
            'default': {
                'BACKEND': 'django.core.cache.backends.locmem.LocMemCache',
                'LOCATION': 'decorator-cache-space',
                'OPTIONS': {
                    'MAX_ENTRIES': 2000,
                    'CULL_FREQUENCY': 4,
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

# --- Mock Database and Models ---
MOCK_DB = {'posts': {}}

class PostStatus(enum.Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

class Post:
    def __init__(self, id, user_id, title, content, status):
        self.id = id
        self.user_id = user_id
        self.title = title
        self.content = content
        self.status = status

# --- Caching Utilities: cache_utils.py ---
# This developer prefers reusable decorators and utility functions.

def generate_cache_key(func, *args, **kwargs):
    """Creates a deterministic cache key from a function and its arguments."""
    # Use a stable representation of args and kwargs
    arg_representation = str(args) + str(sorted(kwargs.items()))
    # Hash the representation to keep key length manageable
    arg_hash = hashlib.sha256(arg_representation.encode()).hexdigest()
    return f"func_cache:{func.__name__}:{arg_hash}"

def cache_result(timeout):
    """
    A decorator for caching the result of a function.
    Implements the cache-aside pattern.
    """
    def decorator(func):
        @functools.wraps(func)
        def wrapper(*args, **kwargs):
            key = generate_cache_key(func, *args, **kwargs)
            
            # 1. Get from cache
            result = cache.get(key)
            if result is not None:
                print(f"CACHE HIT: func='{func.__name__}' key='{key[:30]}...'")
                return result
            
            print(f"CACHE MISS: func='{func.__name__}' key='{key[:30]}...'")
            # 2. On miss, execute function
            result = func(*args, **kwargs)
            
            # 3. Set result in cache with time-based expiration
            cache.set(key, result, timeout=timeout)
            print(f"CACHE SET: func='{func.__name__}' key='{key[:30]}...'")
            return result
        return wrapper
    return decorator

# --- Data Access Layer: data_access.py ---
# A layer of simple functions responsible for DB interaction, decorated for caching.

@cache_result(timeout=3600)  # Cache for 1 hour
def fetch_post(post_id: uuid.UUID) -> Post:
    """Fetches a single post from the database."""
    print(f"DATABASE: Fetching Post with id={post_id}")
    time.sleep(0.1)
    if post_id in MOCK_DB['posts']:
        return MOCK_DB['posts'][post_id]
    return None

@cache_result(timeout=600)  # Cache for 10 minutes
def fetch_active_posts_for_user(user_id: uuid.UUID) -> list[Post]:
    """Fetches all published posts for a given user."""
    print(f"DATABASE: Fetching posts for user_id={user_id}")
    time.sleep(0.2)
    return [
        p for p in MOCK_DB['posts'].values() 
        if p.user_id == user_id and p.status == PostStatus.PUBLISHED
    ]

# --- Explicit Cache Invalidation ---
def invalidate_cached_post(post_id: uuid.UUID):
    """Invalidates the cache for a single post fetch."""
    key = generate_cache_key(fetch_post, post_id=post_id)
    print(f"INVALIDATION: Deleting key for fetch_post: {key[:30]}...")
    cache.delete(key)

def invalidate_cached_user_posts(user_id: uuid.UUID):
    """Invalidates the cache for the user's post list."""
    key = generate_cache_key(fetch_active_posts_for_user, user_id=user_id)
    print(f"INVALIDATION: Deleting key for fetch_active_posts_for_user: {key[:30]}...")
    cache.delete(key)

# --- Business Logic/Views: views.py ---
# Views call the data access functions and explicitly invalidate caches on writes.

def get_post_detail(post_id: uuid.UUID):
    return fetch_post(post_id=post_id)

def get_user_post_list(user_id: uuid.UUID):
    return fetch_active_posts_for_user(user_id=user_id)

def update_post_title(post_id: uuid.UUID, user_id: uuid.UUID, new_title: str):
    # In a real view, you'd fetch the post first
    if post_id in MOCK_DB['posts']:
        print(f"\nDATABASE: Updating Post {post_id}")
        MOCK_DB['posts'][post_id].title = new_title
        
        # Explicitly invalidate relevant caches
        invalidate_cached_post(post_id)
        invalidate_cached_user_posts(user_id)
        return MOCK_DB['posts'][post_id]
    return None

if __name__ == '__main__':
    user_id_1 = uuid.uuid4()
    post_id_1 = uuid.uuid4()
    post_1 = Post(id=post_id_1, user_id=user_id_1, title="Decorators Rock", content="...", status=PostStatus.PUBLISHED)
    MOCK_DB['posts'][post_id_1] = post_1

    print("--- First request for post detail ---")
    get_post_detail(post_id_1)
    
    print("\n--- Second request for post detail (should be cached) ---")
    get_post_detail(post_id_1)

    print("\n--- First request for user's post list ---")
    get_user_post_list(user_id_1)

    print("\n--- Second request for user's post list (should be cached) ---")
    get_user_post_list(user_id_1)

    # Simulate a user updating a post
    update_post_title(post_id_1, user_id_1, "Decorators Are Awesome")

    print("\n--- Requesting post detail after update (cache miss) ---")
    updated_post = get_post_detail(post_id_1)
    print(f"Retrieved title: '{updated_post.title}'")

    print("\n--- Requesting user's post list after update (cache miss) ---")
    get_user_post_list(user_id_1)