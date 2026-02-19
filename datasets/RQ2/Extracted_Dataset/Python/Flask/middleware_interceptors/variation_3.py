import uuid
import time
import logging
from datetime importdatetime
from enum import Enum
from flask import Flask, request, jsonify, g, make_response
from flask_limiter import Limiter
from flask_limiter.util import get_remote_address
from werkzeug.exceptions import HTTPException

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
        "title": "Building Scalable Apps",
        "content": "An article on modular design.",
        "status": PostStatus.PUBLISHED
    }
]

# --- Extension-like Middleware Manager ---

class MiddlewareManager:
    def __init__(self, app=None):
        self.app = app
        if app is not in (None,):
            self.init_app(app)

    def init_app(self, app):
        """Register all middleware with the Flask app instance."""
        # Rate limiting using a Flask extension
        self.limiter = Limiter(
            get_remote_address,
            app=app,
            default_limits=["100 per minute", "20 per second"],
            storage_uri="memory://",
        )

        # Register hooks
        app.before_request(self.log_and_transform_request)
        app.after_request(self.add_cors_and_transform_response)
        app.teardown_request(self.log_teardown)
        
        # Register error handlers
        app.register_error_handler(HTTPException, self.handle_http_error)
        app.register_error_handler(Exception, self.handle_generic_error)

        app.logger.setLevel(logging.INFO)

    def log_and_transform_request(self):
        """Middleware for logging and transforming incoming requests."""
        g.request_start_time = time.perf_counter()
        g.correlation_id = str(uuid.uuid4())
        self.app.logger.info(
            f"Request received: {request.method} {request.url} "
            f"[correlation_id={g.correlation_id}]"
        )

    def add_cors_and_transform_response(self, response):
        """Middleware for CORS, response transformation, and final logging."""
        # Add CORS headers
        response.headers['Access-Control-Allow-Origin'] = '*'
        response.headers['Access-Control-Allow-Headers'] = 'Content-Type,Authorization'
        response.headers['Access-Control-Allow-Methods'] = 'GET,PUT,POST,DELETE,OPTIONS'
        
        # Add custom correlation ID header
        response.headers['X-Correlation-ID'] = g.correlation_id

        # Response transformation for successful JSON responses
        if 200 <= response.status_code < 300 and response.is_json:
            json_data = response.get_json()
            response_envelope = {
                "meta": {"correlation_id": g.correlation_id},
                "payload": json_data
            }
            response = make_response(jsonify(response_envelope), response.status_code)

        return response

    def log_teardown(self, exception=None):
        """Log at the end of the request, regardless of success or failure."""
        elapsed_time = (time.perf_counter() - g.request_start_time) * 1000
        log_message = (
            f"Request finished in {elapsed_time:.2f}ms "
            f"[correlation_id={g.correlation_id}]"
        )
        if exception:
            self.app.logger.error(f"{log_message} with exception: {exception}")
        else:
            self.app.logger.info(log_message)

    def handle_http_error(self, e):
        """Centralized handler for all werkzeug.exceptions.HTTPException."""
        response = e.get_response()
        response.data = jsonify({
            "error_code": e.code,
            "error_name": e.name,
            "error_message": e.description,
        }).data
        response.content_type = "application/json"
        return response

    def handle_generic_error(self, e):
        """Catch-all for non-HTTP exceptions."""
        self.app.logger.error(f"Unhandled exception caught: {e}", exc_info=True)
        return jsonify({
            "error_code": 500,
            "error_name": "InternalServerError",
            "error_message": "An unexpected server error occurred.",
        }), 500

# --- Application Factory ---
def create_app():
    app = Flask(__name__)
    
    # Initialize middleware
    middleware_manager = MiddlewareManager()
    middleware_manager.init_app(app)

    # --- API Routes ---
    @app.route('/posts', methods=['GET'])
    def get_all_posts():
        serializable_posts = [
            {**p, 'id': str(p['id']), 'user_id': str(p['user_id']), 'status': p['status'].value}
            for p in MOCK_POSTS
        ]
        return jsonify(serializable_posts)

    @app.route('/error', methods=['GET'])
    def create_server_error():
        """Route to test the generic error handler."""
        x = 1 / 0
        return "This will not be reached."

    return app

# --- Main Execution ---
if __name__ == '__main__':
    flask_app = create_app()
    flask_app.run(port=5003, debug=False)