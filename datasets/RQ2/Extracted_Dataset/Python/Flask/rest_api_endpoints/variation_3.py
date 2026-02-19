import uuid
from datetime import datetime, timezone
from enum import Enum
from flask import Flask, jsonify, request, abort
from flask.views import MethodView
from werkzeug.security import generate_password_hash

# --- Domain Model ---
class UserRole(Enum):
    ADMIN = "admin"
    USER = "user"

# --- Data Access Layer (Repository Pattern) ---
class UserRepository:
    """A mock repository for managing user data in memory."""
    def __init__(self):
        self.users = {}

    def find_all(self):
        return list(self.users.values())

    def find_by_id(self, user_id):
        return self.users.get(user_id)

    def find_by_email(self, email):
        for user in self.users.values():
            if user['email'] == email:
                return user
        return None

    def save(self, user_data):
        if 'id' not in user_data:
            user_data['id'] = str(uuid.uuid4())
        self.users[user_data['id']] = user_data
        return user_data

    def delete(self, user_id):
        if user_id in self.users:
            del self.users[user_id]
            return True
        return False

    def populate_mock_data(self):
        self.users.clear()
        user1 = {
            "email": "admin.oop@example.com",
            "password_hash": generate_password_hash("securepass1"),
            "role": UserRole.ADMIN.value,
            "is_active": True,
            "created_at": datetime.now(timezone.utc)
        }
        user2 = {
            "email": "user.oop@example.com",
            "password_hash": generate_password_hash("securepass2"),
            "role": UserRole.USER.value,
            "is_active": True,
            "created_at": datetime.now(timezone.utc)
        }
        self.save(user1)
        self.save(user2)

# Instantiate repository (singleton-like for the app's lifetime)
user_repo = UserRepository()

# --- DTO / Serializer ---
def serialize_user(user):
    """Converts user model to a dictionary for JSON response."""
    if not user:
        return None
    return {
        "id": user["id"],
        "email": user["email"],
        "role": user["role"],
        "is_active": user["is_active"],
        "created_at": user["created_at"].isoformat()
    }

# --- API Views (Class-Based) ---
class UserAPI(MethodView):
    """Handles operations on a single user resource: /users/<user_id>"""

    def get(self, user_id):
        user = user_repo.find_by_id(user_id)
        if user is None:
            abort(404)
        return jsonify(serialize_user(user))

    def put(self, user_id): # Can also be PATCH
        user = user_repo.find_by_id(user_id)
        if user is None:
            abort(404)
        
        data = request.get_json()
        user['email'] = data.get('email', user['email'])
        user['role'] = data.get('role', user['role'])
        user['is_active'] = data.get('is_active', user['is_active'])
        if 'password' in data:
            user['password_hash'] = generate_password_hash(data['password'])
        
        updated_user = user_repo.save(user)
        return jsonify(serialize_user(updated_user))

    def delete(self, user_id):
        if not user_repo.delete(user_id):
            abort(404)
        return jsonify({}), 204

class UserListAPI(MethodView):
    """Handles operations on the user collection: /users"""

    def get(self):
        role = request.args.get('role')
        is_active_str = request.args.get('is_active')
        
        all_users = user_repo.find_all()
        
        if role:
            all_users = [u for u in all_users if u['role'] == role]
        if is_active_str is not None:
            is_active = is_active_str.lower() == 'true'
            all_users = [u for u in all_users if u['is_active'] == is_active]

        page = request.args.get('page', 1, type=int)
        per_page = request.args.get('per_page', 5, type=int)
        start = (page - 1) * per_page
        end = start + per_page
        
        paginated_users = all_users[start:end]

        return jsonify({
            "page": page,
            "per_page": per_page,
            "total": len(all_users),
            "data": [serialize_user(u) for u in paginated_users]
        })

    def post(self):
        data = request.get_json()
        if not data or not data.get('email') or not data.get('password'):
            abort(400, "Email and password are required.")
        
        if user_repo.find_by_email(data['email']):
            return jsonify({"error": "Email already in use"}), 409

        new_user = {
            "email": data['email'],
            "password_hash": generate_password_hash(data['password']),
            "role": data.get('role', UserRole.USER.value),
            "is_active": data.get('is_active', True),
            "created_at": datetime.now(timezone.utc)
        }
        created_user = user_repo.save(new_user)
        return jsonify(serialize_user(created_user)), 201

# --- Application Factory and URL Rule Registration ---
def create_flask_app():
    app = Flask(__name__)
    
    user_repo.populate_mock_data()

    user_view = UserAPI.as_view('user_api')
    user_list_view = UserListAPI.as_view('user_list_api')

    app.add_url_rule('/users/', view_func=user_list_view, methods=['GET', 'POST'])
    app.add_url_rule('/users/<string:user_id>', view_func=user_view, methods=['GET', 'PUT', 'DELETE'])
    
    return app

# --- Main Execution ---
if __name__ == '__main__':
    app = create_flask_app()
    # To run this application:
    # 1. Save the code as a Python file (e.g., app_oop.py)
    # 2. Run from the terminal: flask --app app_oop run
    print("Flask app with Class-Based Views ready.")
    print(f"Initial users in repo: {[u['email'] for u in user_repo.find_all()]}")