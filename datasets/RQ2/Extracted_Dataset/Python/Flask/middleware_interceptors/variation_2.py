import uuid
import time
import logging
from datetime import datetime
from enum import Enum
from flask import Flask, request, jsonify, make_response
from werkzeug.wrappers import Request, Response
from werkzeug.exceptions import NotFound, InternalServerError, TooManyRequests

# --- Domain Schema ---
class UserRole(Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

# --- Mock Data ---
MOCK_POSTS = [
    {
        "id": uuid.uuid4(),
        "user_id": uuid.uuid4(),
        "title": "A Post Title",
        "content": "Content of the post.",
        "status": PostStatus.PUBLISHED
    }
]

# --- Class-based WSGI Middleware ---

class LoggingMiddleware:
    def __init__(self, wsgi_app):
        self.wsgi_app = wsgi_app
        logging.basicConfig(level=logging.INFO, format='%(asctime)s [%(levelname)s] %(message)s')

    def __call__(self, environ, start_response):
        req = Request(environ)
        start_time = time.time()
        logging.info(f"Incoming Request: {req.method} {req.path}")
        
        def custom_start_response(status, headers, exc_info=None):
            duration_ms = (time.time() - start_time) * 1000
            logging.info(f"Outgoing Response: {req.method} {req.path} - Status: {status} - Duration: {duration_ms:.2f}ms")
            return start_response(status, headers, exc_info)

        return self.wsgi_app(environ, custom_start_response)

class RateLimitingMiddleware:
    def __init__(self, wsgi_app, max_requests=100, window_seconds=60):
        self.wsgi_app = wsgi_app
        self.max_requests = max_requests
        self.window_seconds = window_seconds
        self.requests = {}

    def __call__(self, environ, start_response):
        client_ip = environ.get('REMOTE_ADDR')
        current_time = time.time()

        if client_ip not in self.requests:
            self.requests[client_ip] = []

        # Filter out old timestamps
        self.requests[client_ip] = [t for t in self.requests[client_ip] if t > current_time - self.window_seconds]

        if len(self.requests[client_ip]) >= self.max_requests:
            res = Response('{"error": "Rate limit exceeded"}', mimetype='application/json', status=429)
            return res(environ, start_response)

        self.requests[client_ip].append(current_time)
        return self.wsgi_app(environ, start_response)

class CorsAndTransformMiddleware:
    def __init__(self, wsgi_app):
        self.wsgi_app = wsgi_app

    def __call__(self, environ, start_response):
        req = Request(environ)
        
        # Handle preflight OPTIONS request
        if req.method == 'OPTIONS':
            headers = [
                ('Access-Control-Allow-Origin', '*'),
                ('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS'),
                ('Access-Control-Allow-Headers', 'Content-Type, Authorization'),
                ('Access-Control-Max-Age', '86400')
            ]
            res = Response(status=204)
            res.headers.extend(headers)
            return res(environ, start_response)

        # Process the actual request and add headers/transform later
        def custom_start_response(status, headers, exc_info=None):
            headers.append(('Access-Control-Allow-Origin', '*'))
            headers.append(('X-Content-Type-Options', 'nosniff'))
            return start_response(status, headers, exc_info)

        # This is a simplified transformation example for WSGI.
        # A more robust solution would involve inspecting the response body,
        # which is more complex at the WSGI level. Here we just add headers.
        return self.wsgi_app(environ, custom_start_response)

# --- Flask App Setup ---
app = Flask(__name__)

# --- Error Handling (Idiomatic Flask way is still best) ---
@app.errorhandler(NotFound)
def handle_404(e):
    return jsonify(error=str(e)), 404

@app.errorhandler(InternalServerError)
def handle_500(e):
    return jsonify(error="An unexpected error occurred."), 500

@app.errorhandler(Exception)
def handle_generic_exception(e):
    logging.error(f"Unhandled exception: {e}", exc_info=True)
    # In a real app, you might not want to expose the original error message.
    original = getattr(e, "original_exception", e)
    return jsonify(error=f"Server error: {type(original).__name__}"), 500

# --- API Routes ---
@app.route('/posts')
def list_posts():
    serializable_posts = [
        {**p, 'id': str(p['id']), 'user_id': str(p['user_id']), 'status': p['status'].value}
        for p in MOCK_POSTS
    ]
    return jsonify(posts=serializable_posts)

@app.route('/error')
def make_error():
    1 / 0  # This will raise a ZeroDivisionError

# --- Apply WSGI Middleware ---
# The order of wrapping is important: outer layers run first.
app.wsgi_app = LoggingMiddleware(app.wsgi_app)
app.wsgi_app = RateLimitingMiddleware(app.wsgi_app)
app.wsgi_app = CorsAndTransformMiddleware(app.wsgi_app)

if __name__ == '__main__':
    from werkzeug.serving import run_simple
    # Use Werkzeug's runner to serve the wrapped app
    run_simple('localhost', 5002, app)