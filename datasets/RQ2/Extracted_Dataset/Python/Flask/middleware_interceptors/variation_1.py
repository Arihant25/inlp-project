import uuid
import time
import logging
from datetime import datetime
from enum import Enum
from functools import wraps
from flask import Flask, request, jsonify, make_response, g

# --- Configuration & Setup ---
class Config:
    RATE_LIMIT_MAX_REQUESTS = 100
    RATE_LIMIT_WINDOW_SECONDS = 60

app = Flask(__name__)
app.config.from_object(Config)

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')

# --- Domain Schema & Mock Data ---
class UserRole(Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

# Mock database
MOCK_USERS = {
    "1a1a1a1a-1a1a-1a1a-1a1a-1a1a1a1a1a1a": {
        "id": uuid.UUID("1a1a1a1a-1a1a-1a1a-1a1a-1a1a1a1a1a1a"),
        "email": "admin@example.com",
        "role": UserRole.ADMIN,
        "is_active": True,
        "created_at": datetime.utcnow()
    }
}

MOCK_POSTS = [
    {
        "id": uuid.uuid4(),
        "user_id": uuid.UUID("1a1a1a1a-1a1a-1a1a-1a1a-1a1a1a1a1a1a"),
        "title": "First Post by Admin",
        "content": "This is the content of the first post.",
        "status": PostStatus.PUBLISHED
    }
]

# In-memory store for rate limiting
rate_limit_records = {}

# --- Middleware Implementations (Functional Decorator Style) ---

@app.before_request
def log_request_info():
    """Request Logging Middleware"""
    g.start_time = time.time()
    logging.info(f"Request Start: {request.method} {request.path} from {request.remote_addr}")
    # Example of request transformation: adding a unique ID to each request
    g.request_id = str(uuid.uuid4())

@app.before_request
def handle_cors_preflight():
    """Handle CORS preflight OPTIONS requests."""
    if request.method.upper() == 'OPTIONS':
        response = make_response()
        response.headers.add("Access-Control-Allow-Origin", "*")
        response.headers.add("Access-Control-Allow-Headers", "Content-Type,Authorization")
        response.headers.add("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS")
        return response

@app.before_request
def check_rate_limit():
    """Rate Limiting Middleware"""
    client_ip = request.remote_addr
    current_time = time.time()
    
    if client_ip not in rate_limit_records:
        rate_limit_records[client_ip] = []
    
    # Remove timestamps outside the window
    rate_limit_records[client_ip] = [
        t for t in rate_limit_records[client_ip] 
        if t > current_time - app.config['RATE_LIMIT_WINDOW_SECONDS']
    ]
    
    # Add current request timestamp
    rate_limit_records[client_ip].append(current_time)
    
    if len(rate_limit_records[client_ip]) > app.config['RATE_LIMIT_MAX_REQUESTS']:
        error_response = jsonify({"error": "Too Many Requests", "message": "Rate limit exceeded."})
        return make_response(error_response, 429)

@app.after_request
def transform_and_log_response(response):
    """Response Transformation, CORS, and Logging Middleware"""
    # 1. Response Transformation: Wrap successful JSON responses in a standard envelope
    if response.status_code in [200, 201] and response.mimetype == 'application/json':
        data = response.get_json()
        transformed_data = {
            "status": "success",
            "data": data
        }
        response = jsonify(transformed_data)

    # 2. CORS Handling: Add headers to actual responses
    response.headers.add("Access-Control-Allow-Origin", "*")
    
    # 3. Final Logging
    duration = (time.time() - g.start_time) * 1000
    logging.info(
        f"Request End: {request.method} {request.path} -> {response.status} "
        f"(took {duration:.2f}ms, request_id: {g.get('request_id', 'N/A')})"
    )
    
    # Add custom header as another transformation example
    response.headers['X-Request-ID'] = g.get('request_id', 'N/A')
    
    return response

# --- Error Handling Middleware ---

@app.errorhandler(404)
def handle_not_found_error(error):
    """Handle 404 Not Found errors."""
    return jsonify({"status": "error", "message": "Resource not found."}), 404

@app.errorhandler(ValueError)
def handle_value_error(error):
    """Handle specific application errors like ValueError."""
    logging.warning(f"Caught a ValueError: {error}")
    return jsonify({"status": "error", "message": str(error)}), 400

@app.errorhandler(Exception)
def handle_generic_error(error):
    """Handle all other unhandled exceptions."""
    logging.error(f"Unhandled exception: {error}", exc_info=True)
    return jsonify({"status": "error", "message": "An internal server error occurred."}), 500


# --- API Routes ---

@app.route('/posts', methods=['GET'])
def get_posts():
    # Convert enums and UUIDs to strings for JSON serialization
    serializable_posts = [
        {**post, 'id': str(post['id']), 'user_id': str(post['user_id']), 'status': post['status'].value}
        for post in MOCK_POSTS
    ]
    return jsonify(serializable_posts)

@app.route('/error', methods=['GET'])
def trigger_error():
    """A route to test the error handler."""
    raise ValueError("This is a test error to demonstrate the error handler.")

if __name__ == '__main__':
    # Note: In production, use a proper WSGI server like Gunicorn or uWSGI.
    app.run(debug=True, port=5001)