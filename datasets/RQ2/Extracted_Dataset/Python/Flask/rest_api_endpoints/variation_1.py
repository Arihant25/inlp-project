import uuid
from datetime import datetime, timezone
from enum import Enum
from flask import Flask, jsonify, request, abort
from werkzeug.security import generate_password_hash

# --- Application Setup ---
app = Flask(__name__)
app.config["JSON_SORT_KEYS"] = False

# --- Domain Model & Mock Data ---
class UserRole(Enum):
    ADMIN = "admin"
    USER = "user"

# In-memory dictionary to act as a simple database
DB_USERS = {}

def setup_mock_data():
    """Initializes the in-memory database with some sample users."""
    global DB_USERS
    DB_USERS = {}
    user_ids = [str(uuid.uuid4()) for _ in range(3)]

    DB_USERS[user_ids[0]] = {
        "id": user_ids[0],
        "email": "admin@example.com",
        "password_hash": generate_password_hash("admin_pass"),
        "role": UserRole.ADMIN.value,
        "is_active": True,
        "created_at": datetime.now(timezone.utc).isoformat()
    }
    DB_USERS[user_ids[1]] = {
        "id": user_ids[1],
        "email": "user1@example.com",
        "password_hash": generate_password_hash("user1_pass"),
        "role": UserRole.USER.value,
        "is_active": True,
        "created_at": datetime.now(timezone.utc).isoformat()
    }
    DB_USERS[user_ids[2]] = {
        "id": user_ids[2],
        "email": "inactive@example.com",
        "password_hash": generate_password_hash("inactive_pass"),
        "role": UserRole.USER.value,
        "is_active": False,
        "created_at": datetime.now(timezone.utc).isoformat()
    }

# --- Helper Function ---
def user_to_response(user):
    """Converts a user dictionary to a JSON-safe response, excluding password."""
    response_data = user.copy()
    del response_data["password_hash"]
    return response_data

# --- API Endpoints ---
@app.route("/users", methods=["POST"])
def create_user():
    """Creates a new user."""
    data = request.get_json()
    if not data or not "email" in data or not "password" in data:
        abort(400, description="Missing email or password")

    if any(u["email"] == data["email"] for u in DB_USERS.values()):
        abort(409, description="Email already exists")

    new_user_id = str(uuid.uuid4())
    new_user = {
        "id": new_user_id,
        "email": data["email"],
        "password_hash": generate_password_hash(data["password"]),
        "role": data.get("role", UserRole.USER.value),
        "is_active": data.get("is_active", True),
        "created_at": datetime.now(timezone.utc).isoformat()
    }
    DB_USERS[new_user_id] = new_user
    return jsonify(user_to_response(new_user)), 201

@app.route("/users/<user_id>", methods=["GET"])
def get_user(user_id):
    """Gets a single user by their ID."""
    user = DB_USERS.get(user_id)
    if not user:
        abort(404, description="User not found")
    return jsonify(user_to_response(user))

@app.route("/users", methods=["GET"])
def list_users():
    """Lists and filters users with pagination."""
    # Filtering
    role_filter = request.args.get("role")
    active_filter = request.args.get("is_active")

    filtered_users = list(DB_USERS.values())

    if role_filter:
        filtered_users = [u for u in filtered_users if u["role"] == role_filter]
    if active_filter is not None:
        is_active = active_filter.lower() in ['true', '1', 't']
        filtered_users = [u for u in filtered_users if u["is_active"] == is_active]

    # Pagination
    page = request.args.get("page", 1, type=int)
    per_page = request.args.get("per_page", 10, type=int)
    start = (page - 1) * per_page
    end = start + per_page

    paginated_users = filtered_users[start:end]

    return jsonify({
        "page": page,
        "per_page": per_page,
        "total": len(filtered_users),
        "users": [user_to_response(u) for u in paginated_users]
    })

@app.route("/users/<user_id>", methods=["PUT", "PATCH"])
def update_user(user_id):
    """Updates a user's details."""
    user = DB_USERS.get(user_id)
    if not user:
        abort(404, description="User not found")

    data = request.get_json()
    if not data:
        abort(400, description="No update data provided")

    if "email" in data:
        user["email"] = data["email"]
    if "password" in data:
        user["password_hash"] = generate_password_hash(data["password"])
    if "role" in data:
        user["role"] = data["role"]
    if "is_active" in data:
        user["is_active"] = data["is_active"]

    DB_USERS[user_id] = user
    return jsonify(user_to_response(user))

@app.route("/users/<user_id>", methods=["DELETE"])
def delete_user(user_id):
    """Deletes a user."""
    if user_id not in DB_USERS:
        abort(404, description="User not found")
    
    del DB_USERS[user_id]
    return '', 204

# --- Main Execution ---
if __name__ == "__main__":
    setup_mock_data()
    # To run this application:
    # 1. Save the code as a Python file (e.g., app_functional.py)
    # 2. Run from the terminal: flask --app app_functional run
    # Example usage:
    # curl http://127.0.0.1:5000/users
    # curl -X POST -H "Content-Type: application/json" -d '{"email":"new@test.com", "password":"new"}' http://127.0.0.1:5000/users
    print("Flask app ready. Use a WSGI server for production.")
    print(f"Initial users: {list(DB_USERS.keys())}")