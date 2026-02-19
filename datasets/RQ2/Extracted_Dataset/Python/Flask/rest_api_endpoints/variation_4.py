# This single file simulates a multi-file project structure for demonstration.
# In a real project, these would be in separate files:
# models.py, repository.py, services.py, exceptions.py, views.py, app.py

import uuid
from datetime import datetime, timezone
from enum import Enum
from werkzeug.security import generate_password_hash
from flask import Flask, jsonify, request

# --- (Simulated) exceptions.py ---
class APIError(Exception):
    """Base class for application-specific errors."""
    status_code = 500
    message = "An unexpected error occurred."
    def __init__(self, message=None, status_code=None):
        super().__init__()
        if message is not None:
            self.message = message
        if status_code is not None:
            self.status_code = status_code
    def to_dict(self):
        return {'error': self.message}

class NotFoundError(APIError):
    status_code = 404
    message = "Resource not found."

class BadRequestError(APIError):
    status_code = 400

class ConflictError(APIError):
    status_code = 409
    message = "Resource already exists."

# --- (Simulated) models.py ---
class UserRole(Enum):
    ADMIN = "admin"
    USER = "user"

class User:
    def __init__(self, id, email, password_hash, role, is_active, created_at):
        self.id = id
        self.email = email
        self.password_hash = password_hash
        self.role = role
        self.is_active = is_active
        self.created_at = created_at

    def to_dict(self):
        """Serializes the object, excluding sensitive data."""
        return {
            "id": self.id,
            "email": self.email,
            "role": self.role.value,
            "is_active": self.is_active,
            "created_at": self.created_at.isoformat()
        }

# --- (Simulated) repository.py ---
class UserDataStore:
    """In-memory data store acting as a mock database."""
    _users = {}
    
    @classmethod
    def get(cls, user_id):
        return cls._users.get(user_id)
    
    @classmethod
    def get_all(cls):
        return list(cls._users.values())
        
    @classmethod
    def save(cls, user):
        cls._users[user.id] = user
        return user
        
    @classmethod
    def delete(cls, user_id):
        if user_id in cls._users:
            del cls._users[user_id]
            return True
        return False

    @classmethod
    def find_by_email(cls, email):
        for user in cls.get_all():
            if user.email == email:
                return user
        return None

    @classmethod
    def seed(cls):
        cls._users.clear()
        user1 = User(
            id=str(uuid.uuid4()), email="service.admin@example.com",
            password_hash=generate_password_hash("admin123"), role=UserRole.ADMIN,
            is_active=True, created_at=datetime.now(timezone.utc)
        )
        user2 = User(
            id=str(uuid.uuid4()), email="service.user@example.com",
            password_hash=generate_password_hash("user123"), role=UserRole.USER,
            is_active=False, created_at=datetime.now(timezone.utc)
        )
        cls.save(user1)
        cls.save(user2)

# --- (Simulated) services.py ---
class UserService:
    """Encapsulates all business logic related to users."""
    
    def get_user_by_id(self, user_id):
        user = UserDataStore.get(user_id)
        if not user:
            raise NotFoundError(f"User with ID {user_id} not found.")
        return user

    def get_all_users(self, filters, pagination):
        all_users = UserDataStore.get_all()
        
        if 'role' in filters:
            try:
                role_enum = UserRole(filters['role'])
                all_users = [u for u in all_users if u.role == role_enum]
            except ValueError:
                raise BadRequestError(f"Invalid role value: {filters['role']}")
        if 'is_active' in filters:
            is_active = str(filters['is_active']).lower() in ('true', '1')
            all_users = [u for u in all_users if u.is_active == is_active]
            
        page = pagination.get('page', 1)
        per_page = pagination.get('per_page', 10)
        start = (page - 1) * per_page
        end = start + per_page
        
        return all_users[start:end], len(all_users)

    def create_new_user(self, data):
        if not data or 'email' not in data or 'password' not in data:
            raise BadRequestError("Missing required fields: email, password.")
        
        if UserDataStore.find_by_email(data['email']):
            raise ConflictError("An account with this email already exists.")
            
        new_user = User(
            id=str(uuid.uuid4()),
            email=data['email'],
            password_hash=generate_password_hash(data['password']),
            role=UserRole(data.get('role', 'user')),
            is_active=data.get('is_active', True),
            created_at=datetime.now(timezone.utc)
        )
        return UserDataStore.save(new_user)

    def update_existing_user(self, user_id, data):
        user_to_update = self.get_user_by_id(user_id)
        
        if 'email' in data:
            user_to_update.email = data['email']
        if 'password' in data:
            user_to_update.password_hash = generate_password_hash(data['password'])
        if 'role' in data:
            user_to_update.role = UserRole(data['role'])
        if 'is_active' in data:
            user_to_update.is_active = data['is_active']
            
        return UserDataStore.save(user_to_update)

    def delete_user_by_id(self, user_id):
        self.get_user_by_id(user_id)
        if not UserDataStore.delete(user_id):
            raise APIError("Failed to delete user.")

# --- (Simulated) views.py ---
def register_user_routes(app, user_service):
    
    @app.route("/users", methods=["POST"])
    def create_user():
        new_user = user_service.create_new_user(request.get_json())
        return jsonify(new_user.to_dict()), 201

    @app.route("/users/<user_id>", methods=["GET"])
    def get_user(user_id):
        user = user_service.get_user_by_id(user_id)
        return jsonify(user.to_dict())

    @app.route("/users", methods=["GET"])
    def list_users():
        filters = {k: v for k, v in request.args.items() if k not in ['page', 'per_page']}
        pagination = {
            'page': request.args.get('page', 1, type=int),
            'per_page': request.args.get('per_page', 10, type=int)
        }
        users, total = user_service.get_all_users(filters, pagination)
        return jsonify({
            "page": pagination['page'],
            "per_page": pagination['per_page'],
            "total_count": total,
            "users": [u.to_dict() for u in users]
        })

    @app.route("/users/<user_id>", methods=["PUT", "PATCH"])
    def update_user(user_id):
        updated_user = user_service.update_existing_user(user_id, request.get_json())
        return jsonify(updated_user.to_dict())

    @app.route("/users/<user_id>", methods=["DELETE"])
    def delete_user(user_id):
        user_service.delete_user_by_id(user_id)
        return "", 204

# --- (Simulated) app.py ---
def create_application():
    app = Flask(__name__)
    user_service = UserService()
    register_user_routes(app, user_service)
    
    @app.errorhandler(APIError)
    def handle_api_error(error):
        response = jsonify(error.to_dict())
        response.status_code = error.status_code
        return response
        
    UserDataStore.seed()
    return app

# --- Main Execution ---
if __name__ == '__main__':
    app = create_application()
    # To run this application:
    # 1. Save the code as a Python file (e.g., app_service.py)
    # 2. Run from the terminal: flask --app app_service run
    print("Flask app with Service Layer architecture ready.")
    print(f"Initial users in data store: {[u.email for u in UserDataStore.get_all()]}")