# This single file simulates a two-file project structure (user_api.py and app.py)
# for a self-contained, runnable example.

import uuid
from datetime import datetime, timezone
from enum import Enum
from flask import Flask, Blueprint, jsonify, request, abort
from werkzeug.security import generate_password_hash

# --- (Simulated) user_api.py ---

# --- Enums ---
class UserRole(Enum):
    ADMIN = "admin"
    USER = "user"

# --- Blueprint Setup ---
user_bp = Blueprint('user_api', __name__, url_prefix='/api/v1/users')

# --- Mock Data Store (could be in a separate module) ---
MOCK_USER_DB = {}

def init_db_with_data(initial_data):
    """Helper to populate the mock DB."""
    global MOCK_USER_DB
    MOCK_USER_DB.clear()
    for user_id, user_data in initial_data.items():
        MOCK_USER_DB[user_id] = user_data

# --- Helper ---
def _serialize_user(user_data):
    """Prepare user data for API response, removing sensitive info."""
    return {
        "id": user_data["id"],
        "email": user_data["email"],
        "role": user_data["role"],
        "is_active": user_data["is_active"],
        "created_at": user_data["created_at"]
    }

# --- Routes ---
@user_bp.route('/', methods=['POST'])
def handle_create_user():
    payload = request.get_json()
    if not payload or 'email' not in payload or 'password' not in payload:
        return jsonify({"error": "Missing required fields: email, password"}), 400

    if any(u['email'] == payload['email'] for u in MOCK_USER_DB.values()):
        return jsonify({"error": "User with this email already exists"}), 409

    user_id = str(uuid.uuid4())
    new_user = {
        "id": user_id,
        "email": payload['email'],
        "password_hash": generate_password_hash(payload['password']),
        "role": UserRole(payload.get('role', 'user')).value,
        "is_active": payload.get('is_active', True),
        "created_at": datetime.now(timezone.utc).isoformat()
    }
    MOCK_USER_DB[user_id] = new_user
    return jsonify(_serialize_user(new_user)), 201

@user_bp.route('/<string:user_id>', methods=['GET'])
def handle_get_user(user_id):
    user = MOCK_USER_DB.get(user_id)
    if not user:
        abort(404, "User not found")
    return jsonify(_serialize_user(user))

@user_bp.route('/', methods=['GET'])
def handle_list_users():
    query_params = request.args
    page = query_params.get('page', 1, type=int)
    per_page = query_params.get('per_page', 10, type=int)
    role_filter = query_params.get('role')
    active_filter = query_params.get('is_active')

    user_list = list(MOCK_USER_DB.values())

    if role_filter:
        user_list = [u for u in user_list if u['role'] == role_filter]
    if active_filter is not None:
        is_active = active_filter.lower() in ('true', '1')
        user_list = [u for u in user_list if u['is_active'] == is_active]

    start_index = (page - 1) * per_page
    end_index = start_index + per_page
    paginated_list = user_list[start_index:end_index]

    response = {
        "metadata": {
            "page": page,
            "per_page": per_page,
            "total_items": len(user_list),
            "total_pages": (len(user_list) + per_page - 1) // per_page
        },
        "data": [_serialize_user(u) for u in paginated_list]
    }
    return jsonify(response)

@user_bp.route('/<string:user_id>', methods=['PUT', 'PATCH'])
def handle_update_user(user_id):
    user = MOCK_USER_DB.get(user_id)
    if not user:
        abort(404, "User not found")
    
    updates = request.get_json()
    if not updates:
        return jsonify({"error": "Request body cannot be empty"}), 400

    if 'email' in updates:
        user['email'] = updates['email']
    if 'password' in updates:
        user['password_hash'] = generate_password_hash(updates['password'])
    if 'role' in updates:
        user['role'] = UserRole(updates['role']).value
    if 'is_active' in updates:
        user['is_active'] = updates['is_active']
    
    MOCK_USER_DB[user_id] = user
    return jsonify(_serialize_user(user))

@user_bp.route('/<string:user_id>', methods=['DELETE'])
def handle_delete_user(user_id):
    if user_id not in MOCK_USER_DB:
        abort(404, "User not found")
    del MOCK_USER_DB[user_id]
    return jsonify({}), 204

# --- (Simulated) app.py ---

def create_app():
    """Application factory."""
    app = Flask(__name__)
    app.config["JSON_SORT_KEYS"] = False
    
    # Register the blueprint
    app.register_blueprint(user_bp)

    @app.route("/")
    def index():
        return "User API is running. Access it at /api/v1/users"

    return app

# --- Main Execution ---
if __name__ == '__main__':
    # Setup mock data for the blueprint's DB
    initial_data = {}
    for i in range(25):
        user_id = str(uuid.uuid4())
        initial_data[user_id] = {
            "id": user_id,
            "email": f"user{i}@example.com",
            "password_hash": "hashed_password",
            "role": "user" if i % 5 != 0 else "admin",
            "is_active": i % 10 != 0,
            "created_at": datetime.now(timezone.utc).isoformat()
        }
    init_db_with_data(initial_data)
    
    app = create_app()
    # To run this application:
    # 1. Save the code as a Python file (e.g., app_blueprint.py)
    # 2. Run from the terminal: flask --app app_blueprint run
    print("Flask app with Blueprint ready.")
    print(f"Total users in mock DB: {len(MOCK_USER_DB)}")