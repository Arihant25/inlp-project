import uuid
import datetime
import enum
import time
from unittest.mock import MagicMock

# --- Django Framework Mock Setup ---
from django.conf import settings
from django.core.cache import caches
from django.http import JsonResponse

if not settings.configured:
    settings.configure(
        CACHES={
            'default': {
                'BACKEND': 'django.core.cache.backends.locmem.LocMemCache',
                'LOCATION': 'view-level-cache',
                'TIMEOUT': 60 * 5, # 5 minute default
                'OPTIONS': {
                    'MAX_ENTRIES': 300,
                    'CULL_FREQUENCY': 3,
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
    
    def to_dict(self):
        return {'id': str(self.id), 'title': self.title, 'content': self.content}

class PostManagerMock:
    def get(self, id):
        print(f"DATABASE: SELECT Post WHERE id={id}")
        time.sleep(0.1)
        if id in MOCK_DB['posts']:
            return MOCK_DB['posts'][id]
        raise Exception("Post not found")

    def filter(self, user_id):
        print(f"DATABASE: SELECT Posts WHERE user_id={user_id}")
        time.sleep(0.2)
        return [p for p in MOCK_DB['posts'].values() if p.user_id == user_id]

Post.objects = PostManagerMock()

# --- View Layer: views.py ---
# This developer implements caching logic directly within the views for simplicity and explicitness.

def post_detail_view(request, post_id: uuid.UUID):
    """A view that fetches and displays a single post, with caching."""
    # Define a simple, predictable cache key
    cache_key = f"view:post_detail:{post_id}"
    
    # Cache-Aside: Step 1 - Check cache
    cached_data = cache.get(cache_key)
    if cached_data:
        print(f"CACHE HIT for {cache_key}")
        return JsonResponse(cached_data)
    
    print(f"CACHE MISS for {cache_key}")
    # Cache-Aside: Step 2 - On miss, fetch from DB
    try:
        post = Post.objects.get(id=post_id)
        post_data = post.to_dict()
        
        # Cache-Aside: Step 3 - Store in cache with time-based expiration
        cache.set(cache_key, post_data, timeout=3600) # Cache for 1 hour
        print(f"CACHE SET for {cache_key}")
        
        return JsonResponse(post_data)
    except Exception as e:
        return JsonResponse({'error': str(e)}, status=404)

def user_posts_view(request, user_id: uuid.UUID):
    """A view that lists a user's posts, with caching."""
    cache_key = f"view:user_posts:{user_id}"
    
    cached_list = cache.get(cache_key)
    if cached_list is not None:
        print(f"CACHE HIT for {cache_key}")
        return JsonResponse({'posts': cached_list})
        
    print(f"CACHE MISS for {cache_key}")
    posts = Post.objects.filter(user_id=user_id)
    posts_data = [p.to_dict() for p in posts]
    
    cache.set(cache_key, posts_data, timeout=600) # Cache for 10 minutes
    print(f"CACHE SET for {cache_key}")
    
    return JsonResponse({'posts': posts_data})

def update_post_view(request, post_id: uuid.UUID):
    """A view that updates a post and performs explicit cache invalidation."""
    try:
        # In a real app, this would come from request.POST or request.body
        new_content = "This is the new, updated content."
        
        # Update the data in the database
        post_to_update = MOCK_DB['posts'][post_id]
        post_to_update.content = new_content
        print(f"DATABASE: UPDATE Post SET content='...' WHERE id={post_id}")
        
        # Invalidation Strategy: Delete keys that are now stale
        post_detail_key = f"view:post_detail:{post_id}"
        user_posts_key = f"view:user_posts:{post_to_update.user_id}"
        
        print(f"INVALIDATION: Deleting keys: {post_detail_key}, {user_posts_key}")
        cache.delete(post_detail_key)
        cache.delete(user_posts_key)
        
        return JsonResponse({'status': 'success', 'post': post_to_update.to_dict()})
    except Exception as e:
        return JsonResponse({'error': str(e)}, status=404)

if __name__ == '__main__':
    # Mock request object
    mock_request = MagicMock()
    
    # Setup mock data
    user_id_1 = uuid.uuid4()
    post_id_1 = uuid.uuid4()
    post_1 = Post(id=post_id_1, user_id=user_id_1, title="View Caching", content="Old content", status=PostStatus.PUBLISHED)
    MOCK_DB['posts'][post_id_1] = post_1

    print("--- First request to post_detail_view ---")
    response = post_detail_view(mock_request, post_id_1)
    print(f"Response: {response.content.decode()}")

    print("\n--- Second request to post_detail_view (should be cached) ---")
    response = post_detail_view(mock_request, post_id_1)
    print(f"Response: {response.content.decode()}")

    print("\n--- First request to user_posts_view ---")
    response = user_posts_view(mock_request, user_id_1)
    print(f"Response: {response.content.decode()}")

    print("\n--- Simulating an update via update_post_view ---")
    update_response = update_post_view(mock_request, post_id_1)
    print(f"Update Response: {update_response.content.decode()}")

    print("\n--- Requesting post detail after update (should be a miss) ---")
    response = post_detail_view(mock_request, post_id_1)
    print(f"Response: {response.content.decode()}")

    print("\n--- Requesting user posts after update (should be a miss) ---")
    response = user_posts_view(mock_request, user_id_1)
    print(f"Response: {response.content.decode()}")