import uuid
import time
import logging
from datetime import datetime
from enum import Enum
from flask import Flask, request, jsonify, make_response

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
        "title": "Functional Python",
        "content": "Exploring functional patterns in Python.",
        "status": PostStatus.PUBLISHED
    }
]

# --- Middleware Module: middleware/logging.py ---
def log_request(app):
    @app.before_request
    def _log_before():
        app.logger.info(f"-> {request.method} {request.path} from {request.remote_addr}")

    @app.after_request
    def _log_after(resp):
        app.logger.info(f"<- {request.method} {request.path} - {resp.status_code}")
        return resp

# --- Middleware Module: middleware/security.py ---
rate_limit_cache = {}
RATE_LIMIT_THRESHOLD = 100
RATE_LIMIT_WINDOW = 60  # seconds

def setup_security_hooks(app):
    @app.before_request
    def _rate_limit():
        ip = request.remote_addr
        now = time.time()
        
        requests = rate_limit_cache.get(ip, [])
        valid_requests = [t for t in requests if t > now - RATE_LIMIT_WINDOW]
        
        if len(valid_requests) >= RATE_LIMIT_THRESHOLD:
            return jsonify(message="Rate limit exceeded"), 429
            
        valid_requests.append(now)
        rate_limit_cache[ip] = valid_requests

    @app.after_request
    def _add_cors_headers(resp):
        resp.headers['Access-Control-Allow-Origin'] = '*'
        resp.headers['Access-Control-Allow-Headers'] = 'Content-Type'
        return resp

# --- Middleware Module: middleware/transformation.py ---
def setup_transformation_hooks(app):
    @app.after_request
    def _wrap_json_response(resp):
        if resp.mimetype == 'application/json' and 200 <= resp.status_code < 300:
            data = resp.get_json()
            new_body = {
                "request_id": str(uuid.uuid4()),
                "response": data
            }
            new_resp = make_response(jsonify(new_body), resp.status_code)
            new_resp.headers.extend(resp.headers) # Preserve original headers
            return new_resp
        return resp

# --- Middleware Module: middleware/errors.py ---
def register_error_handlers(app):
    @app.errorhandler(404)
    def _handle_not_found(err):
        return jsonify(error="Not Found", details=str(err)), 404

    @app.errorhandler(500)
    def _handle_server_error(err):
        app.logger.error(f"Server Error: {err}", exc_info=True)
        return jsonify(error="Internal Server Error"), 500

    @app.errorhandler(Exception)
    def _handle_all_other_exceptions(err):
        app.logger.error(f"Caught unhandled exception: {type(err).__name__} - {err}", exc_info=True)
        return jsonify(error="An unexpected error occurred"), 500

# --- Central Middleware Registration ---
def register_all_middleware(app):
    """A single function to register all middleware components."""
    log_request(app)
    setup_security_hooks(app)
    setup_transformation_hooks(app)
    register_error_handlers(app)

# --- Application Setup ---
app = Flask(__name__)
logging.basicConfig(level=logging.INFO)
register_all_middleware(app)

# --- API Endpoints ---
@app.route('/posts', methods=['GET'])
def get_posts():
    """Returns a list of posts."""
    serializable_posts = [
        {**p, 'id': str(p['id']), 'user_id': str(p['user_id']), 'status': p['status'].value}
        for p in MOCK_POSTS
    ]
    return jsonify(serializable_posts)

@app.route('/error-test', methods=['GET'])
def test_error_handling():
    """This route intentionally causes an error."""
    raise TypeError("Simulating a type error for the handler.")

if __name__ == '__main__':
    app.run(port=5004, debug=True)