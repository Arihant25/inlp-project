import json
import time
import uuid
from datetime import datetime, timezone
from enum import Enum
from functools import wraps
from http.server import BaseHTTPRequestHandler, HTTPServer
from collections import defaultdict

# --- Domain Schema ---

class UserRole(Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

# Mock Database
DB = {
    "users": {},
    "posts": {}
}

def initialize_mock_db():
    admin_id = uuid.uuid4()
    user_id = uuid.uuid4()
    DB["users"][str(admin_id)] = {
        "id": admin_id, "email": "admin@example.com", "password_hash": "hashed_admin_pass",
        "role": UserRole.ADMIN, "is_active": True, "created_at": datetime.now(timezone.utc)
    }
    DB["users"][str(user_id)] = {
        "id": user_id, "email": "user@example.com", "password_hash": "hashed_user_pass",
        "role": UserRole.USER, "is_active": True, "created_at": datetime.now(timezone.utc)
    }
    post_id = uuid.uuid4()
    DB["posts"][str(post_id)] = {
        "id": post_id, "user_id": user_id, "title": "First Post",
        "content": "This is the content of the first post.", "status": PostStatus.PUBLISHED
    }

# --- Middleware Implementation (Functional Decorators) ---

# 1. Error Handling Middleware (Outermost)
def handle_errors(handler_func):
    @wraps(handler_func)
    def wrapper(request_context):
        try:
            return handler_func(request_context)
        except Exception as e:
            print(f"ERROR: An unhandled exception occurred: {e}")
            return {
                "status_code": 500,
                "headers": {"Content-Type": "application/json"},
                "body": json.dumps({"error": "Internal Server Error", "detail": str(e)})
            }
    return wrapper

# 2. Request/Response Transformation Middleware
def transform_io(handler_func):
    @wraps(handler_func)
    def wrapper(request_context):
        # Request transformation: Parse JSON body if present
        content_length = int(request_context["handler"].headers.get('Content-Length', 0))
        if content_length > 0:
            body_str = request_context["handler"].rfile.read(content_length).decode('utf-8')
            try:
                request_context["body"] = json.loads(body_str)
            except json.JSONDecodeError:
                return {
                    "status_code": 400,
                    "headers": {"Content-Type": "application/json"},
                    "body": json.dumps({"error": "Invalid JSON format"})
                }
        
        # Call next in chain
        response = handler_func(request_context)
        
        # Response transformation: Wrap successful responses in a standard structure
        if 200 <= response.get("status_code", 500) < 300:
            original_body = json.loads(response.get("body", "{}"))
            transformed_body = {
                "status": "success",
                "data": original_body,
                "timestamp": datetime.now(timezone.utc).isoformat()
            }
            response["body"] = json.dumps(transformed_body)
        
        return response
    return wrapper

# 3. Rate Limiting Middleware
RATE_LIMIT_STORE = defaultdict(lambda: {"count": 0, "first_request_time": 0})
RATE_LIMIT_MAX_REQUESTS = 10
RATE_LIMIT_WINDOW_SECONDS = 60

def rate_limit(handler_func):
    @wraps(handler_func)
    def wrapper(request_context):
        client_ip = request_context["handler"].client_address[0]
        current_time = time.time()

        if current_time - RATE_LIMIT_STORE[client_ip]["first_request_time"] > RATE_LIMIT_WINDOW_SECONDS:
            RATE_LIMIT_STORE[client_ip]["count"] = 1
            RATE_LIMIT_STORE[client_ip]["first_request_time"] = current_time
        else:
            RATE_LIMIT_STORE[client_ip]["count"] += 1

        if RATE_LIMIT_STORE[client_ip]["count"] > RATE_LIMIT_MAX_REQUESTS:
            return {
                "status_code": 429,
                "headers": {"Content-Type": "application/json"},
                "body": json.dumps({"error": "Too Many Requests"})
            }
        
        return handler_func(request_context)
    return wrapper

# 4. CORS Handling Middleware
def handle_cors(handler_func):
    @wraps(handler_func)
    def wrapper(request_context):
        common_headers = {
            "Access-Control-Allow-Origin": "*",
            "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
            "Access-Control-Allow-Headers": "Content-Type, Authorization",
        }
        
        if request_context["handler"].command == 'OPTIONS':
            return {
                "status_code": 204,
                "headers": common_headers,
                "body": ""
            }
        
        response = handler_func(request_context)
        response["headers"].update(common_headers)
        return response
    return wrapper

# 5. Logging Middleware (Innermost)
def log_request(handler_func):
    @wraps(handler_func)
    def wrapper(request_context):
        start_time = time.perf_counter()
        print(f"-> IN:  {request_context['handler'].command} {request_context['handler'].path} from {request_context['handler'].client_address[0]}")
        
        response = handler_func(request_context)
        
        end_time = time.perf_counter()
        duration = (end_time - start_time) * 1000
        print(f"<- OUT: {response['status_code']} ({duration:.2f}ms)")
        return response
    return wrapper

# --- Handlers ---

@handle_errors
@transform_io
@rate_limit
@handle_cors
@log_request
def get_user_by_id(request_context):
    user_id = request_context["path_params"]["user_id"]
    user = DB["users"].get(user_id)
    if user:
        # Custom JSON encoder for enums and datetimes
        def json_serializer(obj):
            if isinstance(obj, (datetime)):
                return obj.isoformat()
            if isinstance(obj, Enum):
                return obj.value
            if isinstance(obj, uuid.UUID):
                return str(obj)
            raise TypeError(f"Type {type(obj)} not serializable")

        return {
            "status_code": 200,
            "headers": {"Content-Type": "application/json"},
            "body": json.dumps(user, default=json_serializer)
        }
    return {
        "status_code": 404,
        "headers": {"Content-Type": "application/json"},
        "body": json.dumps({"error": "User not found"})
    }

@handle_errors
@transform_io
@rate_limit
@handle_cors
@log_request
def create_post(request_context):
    body = request_context.get("body", {})
    user_id = body.get("user_id")
    title = body.get("title")

    if not all([user_id, title]):
        return {
            "status_code": 400,
            "headers": {"Content-Type": "application/json"},
            "body": json.dumps({"error": "user_id and title are required"})
        }

    if str(user_id) not in DB["users"]:
        return {
            "status_code": 404,
            "headers": {"Content-Type": "application/json"},
            "body": json.dumps({"error": "User not found"})
        }

    new_post = {
        "id": uuid.uuid4(),
        "user_id": uuid.UUID(user_id),
        "title": title,
        "content": body.get("content", ""),
        "status": PostStatus.DRAFT
    }
    DB["posts"][str(new_post["id"])] = new_post
    
    return {
        "status_code": 201,
        "headers": {"Content-Type": "application/json"},
        "body": json.dumps({"message": "Post created", "post_id": str(new_post["id"])})
    }

# --- HTTP Server and Router ---

class MyRequestHandler(BaseHTTPRequestHandler):
    def handle_request(self):
        # Simple router
        path_parts = self.path.strip("/").split("/")
        handler_func = None
        path_params = {}

        if len(path_parts) == 2 and path_parts[0] == "users":
            handler_func = get_user_by_id
            path_params["user_id"] = path_parts[1]
        elif self.command == 'POST' and self.path == "/posts":
            handler_func = create_post
        
        if handler_func:
            request_context = {
                "handler": self,
                "path_params": path_params
            }
            response = handler_func(request_context)
            self.send_response(response["status_code"])
            for key, value in response["headers"].items():
                self.send_header(key, value)
            self.end_headers()
            self.wfile.write(response["body"].encode('utf-8'))
        else:
            self.send_error(404, "Not Found")

    def do_GET(self):
        self.handle_request()

    def do_POST(self):
        self.handle_request()

    def do_OPTIONS(self):
        # CORS pre-flight is handled by the middleware
        self.handle_request()

def run_server(server_class=HTTPServer, handler_class=MyRequestHandler, port=8000):
    initialize_mock_db()
    server_address = ('', port)
    httpd = server_class(server_address, handler_class)
    print(f"Starting server on port {port}...")
    print("Try: curl http://localhost:8000/users/<some_uuid>")
    print("Example UUID from mock DB:", next(iter(DB["users"])))
    httpd.serve_forever()

if __name__ == '__main__':
    # To run this, you would call run_server().
    # This is blocked to allow the script to be imported without running.
    # run_server()
    pass