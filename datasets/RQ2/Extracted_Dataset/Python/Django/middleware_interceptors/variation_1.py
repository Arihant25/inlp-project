# In a real Django project, this would be split into multiple files.
# For this self-contained example, we define everything in one file.
#
# File: myproject/settings.py (mocked)
# File: middleware/request_logging.py
# File: middleware/cors.py
# File: middleware/rate_limit.py
# File: middleware/transformation.py
# File: middleware/error_handling.py
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
from django.core.exceptions import MiddlewareNotUsed, SuspiciousOperation

# --- Mock Django Environment Setup ---

# 1. Mock Models (for context, not directly used by middleware)
class User:
    def __init__(self, id, email, role, is_active):
        self.id = id
        self.email = email
        self.role = role
        self.is_active = is_active
        self.is_authenticated = True

# 2. Mock Cache
MOCK_CACHE_STORE = {}
class MockCache:
    def get(self, key, default=None):
        return MOCK_CACHE_STORE.get(key, default)
    def set(self, key, value, timeout=None):
        MOCK_CACHE_STORE[key] = value
    def incr(self, key):
        MOCK_CACHE_STORE[key] = MOCK_CACHE_STORE.get(key, 0) + 1
        return MOCK_CACHE_STORE[key]

caches.caches = {'default': MockCache()}
cache = caches['default']

# 3. Mock Settings
settings.configure(
    DEBUG=False,
    MIDDLEWARE=[
        'middleware.error_handling.ErrorHandlerMiddleware',
        'middleware.cors.CorsMiddleware',
        'middleware.rate_limit.RateLimitMiddleware',
        'middleware.request_logging.RequestLoggingMiddleware',
        'middleware.transformation.ResponseTransformationMiddleware',
    ],
    # Custom settings for our middleware
    CORS_ALLOWED_ORIGINS=['https://example.com'],
    RATE_LIMIT_REQUESTS=100,
    RATE_LIMIT_WINDOW_SECONDS=60,
)

# 4. Mock Logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')

# --- Middleware Implementations (Classic Class-Based) ---

# File: middleware/request_logging.py
class RequestLoggingMiddleware:
    def __init__(self, get_response):
        self.get_response = get_response
        self.logger = logging.getLogger(__name__)

    def __call__(self, request):
        start_time = time.time()
        self.logger.info(f"Request started: {request.method} {request.path}")
        
        response = self.get_response(request)
        
        duration = time.time() - start_time
        self.logger.info(
            f"Request finished: {request.method} {request.path} "
            f"Status: {response.status_code} Duration: {duration:.4f}s"
        )
        return response

# File: middleware/cors.py
class CorsMiddleware:
    def __init__(self, get_response):
        self.get_response = get_response

    def __call__(self, request):
        # Handle pre-flight requests
        if request.method == 'OPTIONS':
            response = HttpResponse()
            response['Access-Control-Allow-Methods'] = 'GET, POST, PUT, DELETE, OPTIONS'
            response['Access-Control-Allow-Headers'] = 'Content-Type, Authorization'
        else:
            response = self.get_response(request)

        # Add CORS headers to all responses
        origin = request.headers.get('Origin')
        if origin in settings.CORS_ALLOWED_ORIGINS:
            response['Access-Control-Allow-Origin'] = origin
        
        return response

# File: middleware/rate_limit.py
class RateLimitMiddleware:
    def __init__(self, get_response):
        self.get_response = get_response

    def __call__(self, request):
        # Use IP address for anonymous users, user ID for authenticated users
        if request.user and request.user.is_authenticated:
            key = f"ratelimit:{request.user.id}"
        else:
            ip_address = request.META.get('REMOTE_ADDR', '127.0.0.1')
            key = f"ratelimit:{ip_address}"

        request_count = cache.get(key, 0) + 1
        
        if request_count > settings.RATE_LIMIT_REQUESTS:
            return JsonResponse({'error': 'Rate limit exceeded'}, status=429)

        cache.set(key, request_count, timeout=settings.RATE_LIMIT_WINDOW_SECONDS)
        
        response = self.get_response(request)
        return response

# File: middleware/transformation.py
class ResponseTransformationMiddleware:
    def __init__(self, get_response):
        self.get_response = get_response

    def __call__(self, request):
        response = self.get_response(request)
        
        # Only wrap JSON responses
        if isinstance(response, JsonResponse):
            original_data = json.loads(response.content)
            
            # Don't re-wrap if already in the standard format
            if 'data' in original_data or 'error' in original_data:
                return response

            transformed_data = {
                'status': 'success' if 200 <= response.status_code < 300 else 'error',
                'data': original_data,
                'request_id': str(uuid.uuid4())
            }
            response.content = json.dumps(transformed_data)
            response['Content-Length'] = len(response.content)
        
        return response

# File: middleware/error_handling.py
class ErrorHandlerMiddleware:
    def __init__(self, get_response):
        self.get_response = get_response
        self.logger = logging.getLogger(__name__)

    def __call__(self, request):
        try:
            response = self.get_response(request)
        except SuspiciousOperation as e:
            self.logger.warning(f"Suspicious operation: {e}")
            return JsonResponse({'error': 'Bad Request', 'details': str(e)}, status=400)
        except Exception as e:
            self.logger.exception(f"Unhandled exception for request {request.path}: {e}")
            if settings.DEBUG:
                error_details = str(e)
            else:
                error_details = 'An internal server error occurred.'
            return JsonResponse({'error': 'Server Error', 'details': error_details}, status=500)
        
        return response

# --- Simulation Runner ---
if __name__ == '__main__':
    # Mock a view that can succeed or fail
    def sample_view(request):
        if request.GET.get('fail'):
            raise ValueError("Simulated view error")
        if request.GET.get('suspicious'):
            raise SuspiciousOperation("Invalid characters in input")
        return JsonResponse({'message': 'Hello, world!'})

    # Dynamically build the middleware chain from settings
    def build_middleware_chain(view_func):
        handler = view_func
        for mw_path in reversed(settings.MIDDLEWARE):
            try:
                mw_class = import_string(mw_path)
                handler = mw_class(handler)
            except MiddlewareNotUsed:
                pass
        return handler

    # Create the final handler
    handler = build_middleware_chain(sample_view)

    print("--- 1. Successful Request ---")
    request = HttpRequest()
    request.method = 'GET'
    request.path = '/api/posts'
    request.META = {'REMOTE_ADDR': '192.168.1.1'}
    request.headers = {'Origin': 'https://example.com'}
    request.user = User(id=uuid.uuid4(), email='test@user.com', role='USER', is_active=True)
    
    response = handler(request)
    print(f"Status: {response.status_code}")
    print(f"Headers: {dict(response.headers)}")
    print(f"Body: {json.loads(response.content.decode())}\n")

    print("--- 2. Server Error Request ---")
    request_fail = HttpRequest()
    request_fail.method = 'GET'
    request_fail.path = '/api/error'
    request_fail.GET['fail'] = 'true'
    request_fail.META = {'REMOTE_ADDR': '192.168.1.2'}
    request_fail.headers = {'Origin': 'https://example.com'}
    request_fail.user = None

    response_fail = handler(request_fail)
    print(f"Status: {response_fail.status_code}")
    print(f"Headers: {dict(response_fail.headers)}")
    print(f"Body: {json.loads(response_fail.content.decode())}\n")

    print("--- 3. Rate Limit Test ---")
    for i in range(101):
        # Reset cache for the last request to see the limit hit
        if i == 100:
            MOCK_CACHE_STORE[f"ratelimit:192.168.1.3"] = 100
        
        req_rate = HttpRequest()
        req_rate.method = 'GET'
        req_rate.path = '/api/posts'
        req_rate.META = {'REMOTE_ADDR': '192.168.1.3'}
        req_rate.headers = {'Origin': 'https://example.com'}
        req_rate.user = None
        
        res_rate = handler(req_rate)
        if res_rate.status_code == 429:
            print(f"Rate limit hit on request #{i+1}")
            print(f"Status: {res_rate.status_code}")
            print(f"Body: {json.loads(res_rate.content.decode())}\n")
            break