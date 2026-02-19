import json
import time
import uuid
from datetime import datetime, timezone
from enum import Enum
from http.server import BaseHTTPRequestHandler, HTTPServer
from collections import defaultdict

# --- Domain Schema ---

class UserRole(Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

# Mock Data Store
class DataStore:
    _users = {}
    _posts = {}

    @classmethod
    def initialize(cls):
        admin_id = uuid.uuid4()
        user_id = uuid.uuid4()
        cls._users[str(admin_id)] = {
            "id": admin_id, "email": "admin@example.com", "password_hash": "hashed_admin_pass",
            "role": UserRole.ADMIN, "is_active": True, "created_at": datetime.now(timezone.utc)
        }
        cls._users[str(user_id)] = {
            "id": user_id, "email": "user@example.com", "password_hash": "hashed_user_pass",
            "role": UserRole.USER, "is_active": True, "created_at": datetime.now(timezone.utc)
        }
        post_id = uuid.uuid4()
        cls._posts[str(post_id)] = {
            "id": post_id, "user_id": user_id, "title": "First Post",
            "content": "This is the content of the first post.", "status": PostStatus.PUBLISHED
        }

    @classmethod
    def get_user(cls, user_id):
        return cls._users.get(user_id)

    @classmethod
    def add_post(cls, post_data):
        post_id = str(post_data["id"])
        cls._posts[post_id] = post_data
        return post_id

# --- Middleware Implementation (Class-based Interceptors) ---

class Request:
    def __init__(self, handler):
        self.handler = handler
        self.method = handler.command
        self.path = handler.path
        self.headers = handler.headers
        self.client_address = handler.client_address
        self.body = None
        self.path_params = {}

class Response:
    def __init__(self, body, status_code=200, headers=None):
        self.body = body
        self.status_code = status_code
        self.headers = headers or {"Content-Type": "application/json"}

# Base Interceptor class
class Interceptor:
    def __init__(self, next_handler):
        self._next_handler = next_handler

    def __call__(self, request: Request) -> Response:
        return self._next_handler(request)

class ErrorHandlingInterceptor(Interceptor):
    def __call__(self, request: Request) -> Response:
        try:
            return self._next_handler(request)
        except Exception as e:
            print(f"SERVER ERROR: {e}")
            return Response(json.dumps({"error": "Internal Server Error"}), 500)

class IOTransformationInterceptor(Interceptor):
    def __call__(self, request: Request) -> Response:
        content_len = int(request.headers.get('Content-Length', 0))
        if content_len > 0:
            try:
                body_str = request.handler.rfile.read(content_len).decode('utf-8')
                request.body = json.loads(body_str)
            except json.JSONDecodeError:
                return Response(json.dumps({"error": "Bad Request: Malformed JSON"}), 400)
        
        response = self._next_handler(request)
        
        if 200 <= response.status_code < 300:
            data = json.loads(response.body)
            response.body = json.dumps({"data": data})
        return response

class RateLimitingInterceptor(Interceptor):
    _requests = defaultdict(list)
    LIMIT = 15
    PERIOD = 60  # seconds

    def __call__(self, request: Request) -> Response:
        client_ip = request.client_address[0]
        now = time.time()
        
        # Filter out old requests
        self._requests[client_ip] = [t for t in self._requests[client_ip] if now - t < self.PERIOD]
        
        if len(self._requests[client_ip]) >= self.LIMIT:
            return Response(json.dumps({"error": "Rate limit exceeded"}), 429)
        
        self._requests[client_ip].append(now)
        return self._next_handler(request)

class CORSInterceptor(Interceptor):
    def __call__(self, request: Request) -> Response:
        cors_headers = {
            "Access-Control-Allow-Origin": "*",
            "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
            "Access-Control-Allow-Headers": "Content-Type",
        }
        if request.method == 'OPTIONS':
            return Response("", 204, cors_headers)
        
        response = self._next_handler(request)
        response.headers.update(cors_headers)
        return response

class LoggingInterceptor(Interceptor):
    def __call__(self, request: Request) -> Response:
        start = time.perf_counter()
        print(f"Request received: {request.method} {request.path}")
        response = self._next_handler(request)
        duration = (time.perf_counter() - start) * 1000
        print(f"Request finished: {response.status_code} in {duration:.2f}ms")
        return response

# --- API Handlers ---

def get_user_handler(request: Request) -> Response:
    user_id = request.path_params.get("user_id")
    user = DataStore.get_user(user_id)
    if user:
        # Custom JSON encoder for complex types
        def json_default(o):
            if isinstance(o, (datetime, uuid.UUID)): return str(o)
            if isinstance(o, Enum): return o.value
            raise TypeError(f"Object of type {o.__class__.__name__} is not JSON serializable")
        return Response(json.dumps(user, default=json_default), 200)
    return Response(json.dumps({"error": "User not found"}), 404)

def create_post_handler(request: Request) -> Response:
    if not request.body or "user_id" not in request.body or "title" not in request.body:
        return Response(json.dumps({"error": "Missing required fields"}), 400)
    
    user = DataStore.get_user(request.body["user_id"])
    if not user:
        return Response(json.dumps({"error": "Associated user not found"}), 404)

    new_post = {
        "id": uuid.uuid4(),
        "user_id": uuid.UUID(request.body["user_id"]),
        "title": request.body["title"],
        "content": request.body.get("content", ""),
        "status": PostStatus.DRAFT
    }
    post_id = DataStore.add_post(new_post)
    return Response(json.dumps({"id": post_id, "status": "created"}), 201)

# --- Server and Router ---

class AppServer(BaseHTTPRequestHandler):
    
    def get_handler(self):
        path_parts = self.path.strip("/").split("/")
        
        # Simple routing logic
        if self.command == 'GET' and len(path_parts) == 2 and path_parts[0] == 'users':
            return get_user_handler, {"user_id": path_parts[1]}
        if self.command == 'POST' and self.path == '/posts':
            return create_post_handler, {}
            
        return None, {}

    def dispatch(self):
        base_handler, path_params = self.get_handler()
        
        if not base_handler:
            self.send_error(404, "Endpoint not found")
            return

        # Compose the middleware chain
        pipeline = ErrorHandlingInterceptor(
            IOTransformationInterceptor(
                RateLimitingInterceptor(
                    CORSInterceptor(
                        LoggingInterceptor(
                            base_handler
                        )
                    )
                )
            )
        )
        
        request = Request(self)
        request.path_params = path_params
        
        response = pipeline(request)
        
        self.send_response(response.status_code)
        for key, value in response.headers.items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(response.body.encode('utf-8'))

    def do_GET(self):
        self.dispatch()

    def do_POST(self):
        self.dispatch()

    def do_OPTIONS(self):
        self.dispatch()

def main():
    DataStore.initialize()
    server_address = ('', 8001)
    httpd = HTTPServer(server_address, AppServer)
    print("Starting OOP-style server on port 8001...")
    print("Example UUID from mock DB:", next(iter(DataStore._users)))
    httpd.serve_forever()

if __name__ == '__main__':
    # main()
    pass