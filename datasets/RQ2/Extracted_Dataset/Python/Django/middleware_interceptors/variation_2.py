# In a real Django project, this would be split into multiple files.
# For this self-contained example, we define everything in one file.
#
# File: myproject/settings.py (mocked)
# File: myproject/middleware.py
# File: myapp/views.py (mocked)
# File: manage.py (mocked runner)

import logging
import time
import json
import uuid
from functools import reduce
from django.utils.module_loading import import_string
from django.conf import settings
from django.http import HttpRequest, JsonResponse, HttpResponse
from django.core.cache import caches

# --- Mock Django Environment Setup ---

# 1. Mock Models
class User:
    def __init__(self, id, is_authenticated=False):
        self.id = id
        self.is_authenticated = is_authenticated

# 2. Mock Cache
MOCK_CACHE_STORE = {}
class MockCache:
    def get(self, key, default=None):
        return MOCK_CACHE_STORE.get(key, default)
    def set(self, key, value, timeout=None):
        MOCK_CACHE_STORE[key] = value

caches.caches = {'default': MockCache()}
cache = caches['default']

# 3. Mock Settings
settings.configure(
    DEBUG=True,
    MIDDLEWARE=[
        'middleware.security_middleware',
        'middleware.logging_and_error_middleware',
        'middleware.api_envelope_middleware',
    ],
    CORS_ORIGIN_WHITELIST=('https://api.example.com',),
    RATE_LIMIT_PER_MINUTE=50,
)

# 4. Mock Logging
logging.basicConfig(level=logging.INFO, format='%(levelname)s [%(name)s] %(message)s')
log = logging.getLogger(__name__)

# --- Middleware Implementations (Functional Approach) ---

# File: myproject/middleware.py

def security_middleware(get_response):
    """Handles CORS and Rate Limiting."""
    
    def middleware(request):
        # Part 1: Rate Limiting
        client_id = (
            request.user.id if request.user.is_authenticated 
            else request.META.get('REMOTE_ADDR', 'unknown')
        )
        cache_key = f"rate-limit:{client_id}:{int(time.time() / 60)}"
        
        count = cache.get(cache_key, 0) + 1
        cache.set(cache_key, count, timeout=60)

        if count > settings.RATE_LIMIT_PER_MINUTE:
            return JsonResponse({'error': 'Too many requests'}, status=429)

        # Part 2: CORS Handling
        if request.method == 'OPTIONS':
            response = HttpResponse(status=204)
        else:
            response = get_response(request)

        origin = request.headers.get('Origin')
        if origin in settings.CORS_ORIGIN_WHITELIST:
            response['Access-Control-Allow-Origin'] = origin
            response['Access-Control-Allow-Headers'] = 'Authorization, Content-Type'
            response['Access-Control-Allow-Methods'] = 'GET, POST, OPTIONS'
        
        return response

    return middleware


def logging_and_error_middleware(get_response):
    """Handles request/response logging and catches exceptions."""

    def middleware(request):
        request.correlation_id = str(uuid.uuid4())
        start_ts = time.monotonic()
        
        log.info(f"REQ IN <{request.correlation_id}>: {request.method} {request.path}")

        try:
            response = get_response(request)
        except Exception as e:
            log.exception(f"ERR <{request.correlation_id}>: Unhandled exception: {e}")
            error_payload = {
                'error': 'Internal Server Error',
                'correlation_id': request.correlation_id
            }
            if settings.DEBUG:
                error_payload['details'] = str(e)
            return JsonResponse(error_payload, status=500)

        duration_ms = (time.monotonic() - start_ts) * 1000
        log.info(f"RES OUT <{request.correlation_id}>: {response.status_code} in {duration_ms:.2f}ms")
        
        return response

    return middleware


def api_envelope_middleware(get_response):
    """Wraps successful JSON responses in a standard envelope."""

    def middleware(request):
        response = get_response(request)

        if isinstance(response, JsonResponse) and 200 <= response.status_code < 300:
            try:
                content = json.loads(response.content)
                # Avoid double-wrapping
                if 'data' not in content and 'meta' not in content:
                    response.content = json.dumps({
                        'data': content,
                        'meta': {'request_id': getattr(request, 'correlation_id', None)}
                    })
                    response['Content-Length'] = len(response.content)
            except json.JSONDecodeError:
                # Not a valid JSON response, pass through
                pass
        
        return response

    return middleware


# --- Simulation Runner ---
if __name__ == '__main__':
    # Mock a view
    def api_posts_view(request):
        if request.method == 'POST':
            return JsonResponse({'id': str(uuid.uuid4()), 'title': 'New Post'}, status=201)
        if 'error' in request.GET:
            raise RuntimeError("A simulated error occurred in the view.")
        return JsonResponse([{'id': str(uuid.uuid4()), 'title': 'First Post'}])

    # Build the middleware chain
    handler = api_posts_view
    for mw_path in reversed(settings.MIDDLEWARE):
        # In this functional example, we import the function directly
        # We need to mock the module structure for import_string to work
        import sys, types
        module = types.ModuleType('middleware')
        setattr(module, mw_path.split('.')[-1], globals()[mw_path.split('.')[-1]])
        sys.modules['middleware'] = module
        
        mw_factory = import_string(mw_path)
        handler = mw_factory(handler)

    print("--- 1. Successful GET Request ---")
    req_get = HttpRequest()
    req_get.method = 'GET'
    req_get.path = '/api/posts'
    req_get.META = {'REMOTE_ADDR': '127.0.0.1'}
    req_get.headers = {'Origin': 'https://api.example.com'}
    req_get.user = User(id=None, is_authenticated=False)
    
    res_get = handler(req_get)
    print(f"Status: {res_get.status_code}")
    print(f"Headers: {dict(res_get.headers)}")
    print(f"Body: {json.loads(res_get.content.decode())}\n")

    print("--- 2. Request with Server Error ---")
    req_err = HttpRequest()
    req_err.method = 'GET'
    req_err.path = '/api/posts'
    req_err.GET['error'] = '1'
    req_err.META = {'REMOTE_ADDR': '127.0.0.1'}
    req_err.headers = {'Origin': 'https://api.example.com'}
    req_err.user = User(id=None, is_authenticated=False)

    res_err = handler(req_err)
    print(f"Status: {res_err.status_code}")
    print(f"Headers: {dict(res_err.headers)}")
    print(f"Body: {json.loads(res_err.content.decode())}\n")

    print("--- 3. Rate Limit Exceeded Request ---")
    MOCK_CACHE_STORE.clear()
    req_rate = HttpRequest()
    req_rate.method = 'GET'
    req_rate.path = '/api/posts'
    req_rate.META = {'REMOTE_ADDR': '10.0.0.5'}
    req_rate.headers = {'Origin': 'https://api.example.com'}
    req_rate.user = User(id=None, is_authenticated=False)
    
    # Set cache to be just at the limit
    cache_key = f"rate-limit:10.0.0.5:{int(time.time() / 60)}"
    cache.set(cache_key, settings.RATE_LIMIT_PER_MINUTE)

    res_rate = handler(req_rate)
    print(f"Status: {res_rate.status_code}")
    print(f"Headers: {dict(res_rate.headers)}")
    print(f"Body: {json.loads(res_rate.content.decode())}\n")