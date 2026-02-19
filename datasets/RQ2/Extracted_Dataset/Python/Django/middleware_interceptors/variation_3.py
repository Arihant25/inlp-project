# In a real Django project, this would be split into multiple files.
# For this self-contained example, we define everything in one file.
#
# File: myproject/settings.py (mocked)
# File: middleware/pipeline.py
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
from django.core.exceptions import PermissionDenied

# --- Mock Django Environment Setup ---

# 1. Mock Models
class User:
    def __init__(self, id, role='USER', is_authenticated=True):
        self.id = id
        self.role = role
        self.is_authenticated = is_authenticated

# 2. Mock Cache
MOCK_CACHE_STORE = {}
class MockCache:
    def get(self, key, default=None):
        return MOCK_CACHE_STORE.get(key, default)
    def set(self, key, value, timeout=None):
        MOCK_CACHE_STORE[key] = value
    def incr(self, key):
        if key not in MOCK_CACHE_STORE:
            MOCK_CACHE_STORE[key] = 0
        MOCK_CACHE_STORE[key] += 1
        return MOCK_CACHE_STORE[key]

caches.caches = {'default': MockCache()}
cache = caches['default']

# 3. Mock Settings
settings.configure(
    DEBUG=False,
    MIDDLEWARE=[
        'middleware.pipeline.ProcessingPipelineMiddleware',
    ],
    # Custom settings for the pipeline
    PROCESSING_PIPELINE_CONFIG={
        'CORS': {
            'ALLOWED_ORIGINS': ['https://app.example.com'],
            'ALLOWED_METHODS': ['GET', 'POST'],
        },
        'RATE_LIMIT': {
            'USER_REQUESTS_PER_HOUR': 1000,
            'ANON_REQUESTS_PER_HOUR': 100,
        },
        'RESPONSE_TRANSFORM': {
            'WRAP_JSON': True,
        }
    }
)

# 4. Mock Logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s | %(name)s | %(message)s')

# --- Middleware Implementation (Configurable Facade/Pipeline) ---

# File: middleware/pipeline.py
class ProcessingPipelineMiddleware:
    """
    An orchestrator middleware that delegates tasks to specialized handlers.
    Configuration is driven entirely by Django settings.
    """
    def __init__(self, get_response):
        self.get_response = get_response
        self.config = settings.PROCESSING_PIPELINE_CONFIG
        self.logger = logging.getLogger(self.__class__.__name__)

    def __call__(self, request):
        request.request_id = uuid.uuid4()
        self.logger.info(f"[{request.request_id}] Processing started: {request.method} {request.path}")
        
        try:
            # --- Pre-processing hooks ---
            
            # CORS pre-flight check
            if request.method == 'OPTIONS':
                return self._handle_cors_preflight(request)

            # Rate Limiting
            self._apply_rate_limit(request)

            # --- Main view execution ---
            response = self.get_response(request)

        except PermissionDenied as e:
            self.logger.warning(f"[{request.request_id}] Permission denied: {e}")
            response = JsonResponse({'error': 'Forbidden', 'details': str(e)}, status=403)
        except Exception as e:
            self.logger.exception(f"[{request.request_id}] Unhandled exception")
            response = JsonResponse({'error': 'Internal Server Error'}, status=500)

        # --- Post-processing hooks ---
        response = self._add_cors_headers(request, response)
        response = self._transform_response(request, response)
        
        self.logger.info(f"[{request.request_id}] Processing finished with status {response.status_code}")
        return response

    def _handle_cors_preflight(self, request):
        response = HttpResponse(status=204)
        config = self.config.get('CORS', {})
        response['Access-Control-Allow-Methods'] = ', '.join(config.get('ALLOWED_METHODS', []))
        response['Access-Control-Allow-Headers'] = 'Content-Type, Authorization'
        return response

    def _add_cors_headers(self, request, response):
        config = self.config.get('CORS', {})
        origin = request.headers.get('Origin')
        if origin in config.get('ALLOWED_ORIGINS', []):
            response['Access-Control-Allow-Origin'] = origin
        return response

    def _apply_rate_limit(self, request):
        config = self.config.get('RATE_LIMIT', {})
        if request.user.is_authenticated:
            key = f"rate_limit:user:{request.user.id}"
            limit = config.get('USER_REQUESTS_PER_HOUR', 1000)
        else:
            ip = request.META.get('REMOTE_ADDR', '127.0.0.1')
            key = f"rate_limit:anon:{ip}"
            limit = config.get('ANON_REQUESTS_PER_HOUR', 100)
        
        count = cache.incr(key)
        if count == 1:
            cache.set(key, 1, timeout=3600) # Set expiry on first request

        if count > limit:
            raise PermissionDenied("Rate limit exceeded.")

    def _transform_response(self, request, response):
        config = self.config.get('RESPONSE_TRANSFORM', {})
        if config.get('WRAP_JSON') and isinstance(response, JsonResponse) and 200 <= response.status_code < 300:
            try:
                data = json.loads(response.content)
                if 'payload' in data and 'meta' in data: # Avoid re-wrapping
                    return response
                
                wrapped_data = {
                    'payload': data,
                    'meta': {
                        'status_code': response.status_code,
                        'request_id': str(request.request_id)
                    }
                }
                response.content = json.dumps(wrapped_data)
                response['Content-Length'] = len(response.content)
            except json.JSONDecodeError:
                pass
        return response


# --- Simulation Runner ---
if __name__ == '__main__':
    # Mock a view
    def get_user_posts_view(request, user_id):
        if 'error' in request.GET:
            raise Exception("Database connection failed")
        return JsonResponse({'user_id': user_id, 'posts': [{'id': 1, 'title': 'My First Post'}]})

    # Build the middleware chain
    handler = lambda req: get_user_posts_view(req, user_id='a_user_id')
    mw_class = import_string(settings.MIDDLEWARE[0])
    handler = mw_class(handler)

    print("--- 1. Authenticated User - Successful Request ---")
    req_auth = HttpRequest()
    req_auth.method = 'GET'
    req_auth.path = '/api/users/a_user_id/posts'
    req_auth.META = {'REMOTE_ADDR': '203.0.113.1'}
    req_auth.headers = {'Origin': 'https://app.example.com'}
    req_auth.user = User(id='a_user_id', role='ADMIN')
    
    res_auth = handler(req_auth)
    print(f"Status: {res_auth.status_code}")
    print(f"Headers: {dict(res_auth.headers)}")
    print(f"Body: {json.loads(res_auth.content.decode())}\n")

    print("--- 2. Anonymous User - Rate Limit Exceeded ---")
    req_anon = HttpRequest()
    req_anon.method = 'POST'
    req_anon.path = '/api/login'
    req_anon.META = {'REMOTE_ADDR': '198.51.100.5'}
    req_anon.headers = {'Origin': 'https://app.example.com'}
    req_anon.user = User(id=None, is_authenticated=False)
    
    # Set cache to the limit
    cache.set('rate_limit:anon:198.51.100.5', 100, timeout=3600)

    res_anon = handler(req_anon)
    print(f"Status: {res_anon.status_code}")
    print(f"Headers: {dict(res_anon.headers)}")
    print(f"Body: {json.loads(res_anon.content.decode())}\n")

    print("--- 3. Internal Server Error ---")
    req_err = HttpRequest()
    req_err.method = 'GET'
    req_err.path = '/api/users/a_user_id/posts'
    req_err.GET['error'] = '1'
    req_err.META = {'REMOTE_ADDR': '203.0.113.1'}
    req_err.headers = {'Origin': 'https://app.example.com'}
    req_err.user = User(id='a_user_id', role='ADMIN')

    res_err = handler(req_err)
    print(f"Status: {res_err.status_code}")
    print(f"Headers: {dict(res_err.headers)}")
    print(f"Body: {json.loads(res_err.content.decode())}\n")