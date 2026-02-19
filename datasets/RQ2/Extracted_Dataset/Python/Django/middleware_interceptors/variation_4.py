# In a real Django project, this would be split into multiple files.
# For this self-contained example, we define everything in one file.
#
# File: myproject/settings.py (mocked)
# File: middleware/async_middleware.py
# File: myapp/views.py (mocked)
# File: manage.py (mocked runner)

import logging
import time
import json
import uuid
import asyncio
from functools import reduce
from django.utils.module_loading import import_string
from django.conf import settings
from django.http import HttpRequest, JsonResponse, HttpResponse

# --- Mock Django Environment Setup ---

# 1. Mock Models
class User:
    def __init__(self, id, is_authenticated=True):
        self.id = id
        self.is_authenticated = is_authenticated

# 2. Mock Async Cache
MOCK_CACHE_STORE = {}
class MockAsyncCache:
    async def get(self, key, default=None):
        await asyncio.sleep(0.001) # Simulate network latency
        return MOCK_CACHE_STORE.get(key, default)
    async def set(self, key, value, timeout=None):
        await asyncio.sleep(0.001)
        MOCK_CACHE_STORE[key] = value
    async def incr(self, key):
        await asyncio.sleep(0.001)
        val = MOCK_CACHE_STORE.get(key, 0) + 1
        MOCK_CACHE_STORE[key] = val
        return val

# This is a simplified mock. Django's cache setup is more complex.
# We'll instantiate our mock directly in the middleware.
mock_async_cache = MockAsyncCache()

# 3. Mock Settings
settings.configure(
    DEBUG=False,
    MIDDLEWARE=[
        'middleware.async_middleware.AsyncErrorHandlingMiddleware',
        'middleware.async_middleware.AsyncCorsMiddleware',
        'middleware.async_middleware.AsyncRateLimitMiddleware',
        'middleware.async_middleware.AsyncLoggingAndTransformMiddleware',
    ],
    CORS_ALLOWED_ORIGINS=['https://async-app.io'],
    RATE_LIMIT_MAX_HITS=200,
    RATE_LIMIT_TIMEFRAME_SECONDS=60,
)

# 4. Mock Logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s [ASYNC] %(message)s')

# --- Middleware Implementations (Modern Async) ---

# File: middleware/async_middleware.py

class AsyncErrorHandlingMiddleware:
    def __init__(self, get_response):
        self.get_response = get_response
        # Check if get_response is async, and adapt if not
        self.is_async = asyncio.iscoroutinefunction(self.get_response)

    async def __call__(self, request):
        try:
            if self.is_async:
                response = await self.get_response(request)
            else:
                response = self.get_response(request)
        except Exception as e:
            logging.error(f"Async middleware caught exception: {e}", exc_info=True)
            return JsonResponse({"error": "An unexpected error occurred"}, status=500)
        return response

class AsyncCorsMiddleware:
    def __init__(self, get_response):
        self.get_response = get_response

    async def __call__(self, request):
        if request.method == 'OPTIONS':
            response = HttpResponse(status=204)
        else:
            response = await self.get_response(request)
        
        origin = request.headers.get('Origin')
        if origin in settings.CORS_ALLOWED_ORIGINS:
            response['Access-Control-Allow-Origin'] = origin
            response['Access-Control-Allow-Headers'] = 'X-Request-ID, Content-Type'
            response['Access-Control-Allow-Methods'] = 'GET, POST, OPTIONS'
        return response

class AsyncRateLimitMiddleware:
    def __init__(self, get_response):
        self.get_response = get_response
        self.cache = mock_async_cache # Use our async mock

    async def __call__(self, request):
        ip = request.META.get('REMOTE_ADDR', '127.0.0.1')
        key = f"async_ratelimit:{ip}"
        
        count = await self.cache.incr(key)
        if count == 1:
            # In a real scenario, you'd use `set` with `nx=True` and `expire`
            await self.cache.set(key, 1, timeout=settings.RATE_LIMIT_TIMEFRAME_SECONDS)

        if count > settings.RATE_LIMIT_MAX_HITS:
            return JsonResponse({'error': 'Rate limit exceeded'}, status=429)
        
        return await self.get_response(request)

class AsyncLoggingAndTransformMiddleware:
    def __init__(self, get_response):
        self.get_response = get_response
        self.logger = logging.getLogger(self.__class__.__name__)

    async def __call__(self, request):
        start_time = time.perf_counter()
        request.id = str(uuid.uuid4().hex)
        
        self.logger.info(f"RID={request.id} -> {request.method} {request.path}")
        
        response = await self.get_response(request)
        
        duration = (time.perf_counter() - start_time) * 1000
        self.logger.info(f"RID={request.id} <- {response.status_code} ({duration:.2f}ms)")
        
        # Response Transformation
        if isinstance(response, JsonResponse) and 200 <= response.status_code < 300:
            try:
                data = json.loads(response.content)
                if 'data' not in data: # Avoid double wrapping
                    response.content = json.dumps({
                        'data': data,
                        'request_id': request.id
                    })
                    response['Content-Length'] = len(response.content)
            except json.JSONDecodeError:
                pass # Not valid JSON, ignore
        
        response['X-Request-ID'] = request.id
        return response

# --- Simulation Runner ---
async def main():
    # Mock an async view
    async def fetch_posts_view(request):
        if 'crash' in request.GET:
            raise ValueError("Simulated async view crash")
        await asyncio.sleep(0.05) # Simulate async I/O (e.g., database call)
        return JsonResponse([{'id': str(uuid.uuid4()), 'title': 'Async Post'}])

    # Build the middleware chain
    handler = fetch_posts_view
    for mw_path in reversed(settings.MIDDLEWARE):
        # We need to mock the module structure for import_string to work
        import sys, types
        module = types.ModuleType('middleware.async_middleware')
        setattr(module, mw_path.split('.')[-1], globals()[mw_path.split('.')[-1]])
        sys.modules['middleware.async_middleware'] = module

        mw_class = import_string(mw_path)
        handler = mw_class(handler)

    print("--- 1. Successful Async Request ---")
    req_ok = HttpRequest()
    req_ok.method = 'GET'
    req_ok.path = '/api/v2/posts'
    req_ok.META = {'REMOTE_ADDR': '10.10.10.1'}
    req_ok.headers = {'Origin': 'https://async-app.io'}
    req_ok.user = User(id=uuid.uuid4())
    
    res_ok = await handler(req_ok)
    print(f"Status: {res_ok.status_code}")
    print(f"Headers: {dict(res_ok.headers)}")
    print(f"Body: {json.loads(res_ok.content.decode())}\n")

    print("--- 2. Async Request with View Error ---")
    req_err = HttpRequest()
    req_err.method = 'GET'
    req_err.path = '/api/v2/posts'
    req_err.GET['crash'] = '1'
    req_err.META = {'REMOTE_ADDR': '10.10.10.2'}
    req_err.headers = {'Origin': 'https://async-app.io'}
    req_err.user = User(id=uuid.uuid4())

    res_err = await handler(req_err)
    print(f"Status: {res_err.status_code}")
    print(f"Headers: {dict(res_err.headers)}")
    print(f"Body: {json.loads(res_err.content.decode())}\n")

    print("--- 3. Async Rate Limit Exceeded ---")
    req_rate = HttpRequest()
    req_rate.method = 'POST'
    req_rate.path = '/api/v2/posts'
    req_rate.META = {'REMOTE_ADDR': '10.10.10.3'}
    req_rate.headers = {'Origin': 'https://async-app.io'}
    req_rate.user = User(id=uuid.uuid4())
    
    # Set cache to the limit
    await mock_async_cache.set('async_ratelimit:10.10.10.3', settings.RATE_LIMIT_MAX_HITS)

    res_rate = await handler(req_rate)
    print(f"Status: {res_rate.status_code}")
    print(f"Headers: {dict(res_rate.headers)}")
    print(f"Body: {json.loads(res_rate.content.decode())}\n")

if __name__ == '__main__':
    asyncio.run(main())