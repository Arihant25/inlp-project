import json
import time
import uuid
from datetime import datetime, timezone
from enum import Enum
from http.server import BaseHTTPRequestHandler, HTTPServer
from collections import defaultdict

# --- Domain Model ---
class UserRole(Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

# --- Mock Persistence Layer ---
class MockDB:
    _instance = None
    
    def __new__(cls):
        if cls._instance is None:
            cls._instance = super(MockDB, cls).__new__(cls)
            cls.users = {}
            cls.posts = {}
            cls._initialize_data()
        return cls._instance

    @classmethod
    def _initialize_data(cls):
        admin_id = uuid.uuid4()
        user_id = uuid.uuid4()
        cls.users[str(admin_id)] = {
            "id": admin_id, "email": "admin@example.com", "password_hash": "...",
            "role": UserRole.ADMIN, "is_active": True, "created_at": datetime.now(timezone.utc)
        }
        cls.users[str(user_id)] = {
            "id": user_id, "email": "user@example.com", "password_hash": "...",
            "role": UserRole.USER, "is_active": True, "created_at": datetime.now(timezone.utc)
        }
        post_id = uuid.uuid4()
        cls.posts[str(post_id)] = {
            "id": post_id, "user_id": user_id, "title": "A Post",
            "content": "Content here.", "status": PostStatus.PUBLISHED
        }

# --- Request/Response Context ---
class HTTPContext:
    def __init__(self, handler):
        self.request_handler = handler
        self.request_body = None
        self.path_vars = {}
        self.response_status = 200
        self.response_headers = {"Content-Type": "application/json"}
        self.response_body = ""
        self.is_halted = False

# --- Middleware Pipeline Implementation ---

class RequestPipeline:
    def __init__(self, middleware_layers):
        self.layers = middleware_layers

    def execute(self, context, final_handler):
        # Build the chain of calls from the inside out
        chained_handler = final_handler
        for layer in reversed(self.layers):
            chained_handler = layer(chained_handler)
        
        # Execute the full chain
        chained_handler(context)

# Middleware Layers (as higher-order functions)
def create_error_handler_layer(next_handler):
    def handle(context: HTTPContext):
        try:
            next_handler(context)
        except Exception as e:
            print(f"PIPELINE ERROR: {type(e).__name__} - {e}")
            context.response_status = 500
            context.response_body = json.dumps({"error": "Server processing failed"})
    return handle

def create_transformer_layer(next_handler):
    def handle(context: HTTPContext):
        content_len = int(context.request_handler.headers.get('Content-Length', 0))
        if content_len > 0:
            body_str = context.request_handler.rfile.read(content_len).decode('utf-8')
            context.request_body = json.loads(body_str)
        
        next_handler(context)
        
        if 200 <= context.response_status < 300 and context.response_body:
            data = json.loads(context.response_body)
            context.response_body = json.dumps({"payload": data})
    return handle

class RateLimiterLayer:
    _hits = defaultdict(lambda: {'timestamp': 0, 'count': 0})
    _limit = 20
    _window = 60

    def __call__(self, next_handler):
        def handle(context: HTTPContext):
            ip = context.request_handler.client_address[0]
            now = time.time()
            if now - self._hits[ip]['timestamp'] > self._window:
                self._hits[ip] = {'timestamp': now, 'count': 1}
            else:
                self._hits[ip]['count'] += 1

            if self._hits[ip]['count'] > self._limit:
                context.response_status = 429
                context.response_body = json.dumps({"error": "Too many requests"})
                context.is_halted = True
                return
            next_handler(context)
        return handle

def create_cors_layer(next_handler):
    def handle(context: HTTPContext):
        context.response_headers.update({
            "Access-Control-Allow-Origin": "*",
            "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
            "Access-Control-Allow-Headers": "Content-Type",
        })
        if context.request_handler.command == 'OPTIONS':
            context.response_status = 204
            context.is_halted = True
            return
        next_handler(context)
    return handle

def create_logging_layer(next_handler):
    def handle(context: HTTPContext):
        print(f"-> {context.request_handler.command} {context.request_handler.path}")
        next_handler(context)
        print(f"<- {context.response_status}")
    return handle

# --- Endpoint Handlers ---
def get_user_endpoint(context: HTTPContext):
    user_id = context.path_vars.get("id")
    user = MockDB().users.get(user_id)
    if user:
        def default_json_encoder(o):
            if isinstance(o, (datetime, uuid.UUID)): return o.isoformat()
            if isinstance(o, Enum): return o.value
            return str(o)
        context.response_body = json.dumps(user, default=default_json_encoder)
    else:
        context.response_status = 404
        context.response_body = json.dumps({"error": "Not Found"})

def create_post_endpoint(context: HTTPContext):
    body = context.request_body
    if not body or not body.get("user_id") or not body.get("title"):
        context.response_status = 400
        context.response_body = json.dumps({"error": "Bad Request"})
        return
    
    post_id = uuid.uuid4()
    MockDB().posts[str(post_id)] = {
        "id": post_id, "user_id": uuid.UUID(body["user_id"]), "title": body["title"],
        "content": body.get("content", ""), "status": PostStatus.DRAFT
    }
    context.response_status = 201
    context.response_body = json.dumps({"post_id": str(post_id)})

# --- Server ---
class PipelineHTTPHandler(BaseHTTPRequestHandler):
    
    def get_route(self):
        path = self.path.strip("/").split("/")
        if self.command == 'GET' and len(path) == 2 and path[0] == 'users':
            return get_user_endpoint, {"id": path[1]}
        if self.command == 'POST' and self.path == '/posts':
            return create_post_endpoint, {}
        return None, {}

    def handle_one_request(self):
        endpoint, path_vars = self.get_route()
        if not endpoint:
            self.send_error(404)
            return

        context = HTTPContext(self)
        context.path_vars = path_vars

        pipeline = RequestPipeline([
            create_error_handler_layer,
            create_transformer_layer,
            RateLimiterLayer(),
            create_cors_layer,
            create_logging_layer,
        ])
        
        pipeline.execute(context, endpoint)

        self.send_response(context.response_status)
        for k, v in context.response_headers.items():
            self.send_header(k, v)
        self.end_headers()
        if context.response_body:
            self.wfile.write(context.response_body.encode('utf-8'))

    def do_GET(self): self.handle_one_request()
    def do_POST(self): self.handle_one_request()
    def do_OPTIONS(self): self.handle_one_request()

def start_server():
    MockDB() # Initialize DB
    server_address = ('', 8002)
    httpd = HTTPServer(server_address, PipelineHTTPHandler)
    print("Starting Pipeline-style server on port 8002...")
    print("Example UUID from mock DB:", next(iter(MockDB.users)))
    httpd.serve_forever()

if __name__ == '__main__':
    # start_server()
    pass