import json
import uuid
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs
from datetime import datetime, timezone
from enum import Enum
import copy

# --- Domain Models ---

class UserRole(Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

class User:
    def __init__(self, id, email, password_hash, role, is_active, created_at):
        self.id = id
        self.email = email
        self.password_hash = password_hash
        self.role = role
        self.is_active = is_active
        self.created_at = created_at

    def to_dict(self):
        return {
            "id": self.id,
            "email": self.email,
            "password_hash": self.password_hash,
            "role": self.role.value,
            "is_active": self.is_active,
            "created_at": self.created_at.isoformat()
        }

    @staticmethod
    def from_dict(data):
        return User(
            id=data.get('id'),
            email=data.get('email'),
            password_hash=data.get('password_hash'),
            role=UserRole(data.get('role')),
            is_active=data.get('is_active'),
            created_at=datetime.fromisoformat(data.get('created_at'))
        )

# --- Custom Exceptions ---

class NotFoundError(Exception):
    pass

class BadRequestError(Exception):
    pass

# --- Data Layer (Repository) ---

class UserRepository:
    _users_store = {}

    def __init__(self):
        if not self._users_store:
            self._initialize_data()

    def _initialize_data(self):
        user_id_1 = str(uuid.uuid4())
        user_1 = User(
            id=user_id_1, email="service.admin@example.com", password_hash="hash_abc",
            role=UserRole.ADMIN, is_active=True, created_at=datetime.now(timezone.utc)
        )
        self._users_store[user_id_1] = user_1

        user_id_2 = str(uuid.uuid4())
        user_2 = User(
            id=user_id_2, email="service.user@example.com", password_hash="hash_def",
            role=UserRole.USER, is_active=True, created_at=datetime.now(timezone.utc)
        )
        self._users_store[user_id_2] = user_2

    def find_by_id(self, user_id):
        user = self._users_store.get(user_id)
        return copy.deepcopy(user) if user else None

    def find_all(self):
        return [copy.deepcopy(u) for u in self._users_store.values()]

    def save(self, user):
        if not user.id:
            user.id = str(uuid.uuid4())
        self._users_store[user.id] = copy.deepcopy(user)
        return copy.deepcopy(user)

    def delete(self, user_id):
        if user_id in self._users_store:
            del self._users_store[user_id]
            return True
        return False

# --- Service Layer (Business Logic) ---

class UserService:
    def __init__(self, user_repository):
        self.user_repository = user_repository

    def get_user_by_id(self, user_id):
        user = self.user_repository.find_by_id(user_id)
        if not user:
            raise NotFoundError("User with given ID not found")
        return user

    def get_all_users(self, query_params):
        all_users = self.user_repository.find_all()
        
        # Filtering logic
        if 'role' in query_params:
            try:
                role_filter = UserRole(query_params['role'][0].upper())
                all_users = [u for u in all_users if u.role == role_filter]
            except ValueError:
                raise BadRequestError("Invalid role value for filtering")
        
        if 'is_active' in query_params:
            active_filter = query_params['is_active'][0].lower() in ['true', '1']
            all_users = [u for u in all_users if u.is_active == active_filter]

        # Pagination logic
        page = int(query_params.get("page", [1])[0])
        limit = int(query_params.get("limit", [10])[0])
        start_index = (page - 1) * limit
        end_index = start_index + limit
        
        paginated_users = all_users[start_index:end_index]
        return paginated_users, len(all_users), page, limit

    def create_new_user(self, data):
        if not data.get("email") or not data.get("password_hash"):
            raise BadRequestError("Email and password_hash are mandatory fields")
        
        new_user = User(
            id=None,
            email=data["email"],
            password_hash=data["password_hash"],
            role=UserRole(data.get("role", "USER")),
            is_active=data.get("is_active", True),
            created_at=datetime.now(timezone.utc)
        )
        return self.user_repository.save(new_user)

    def update_existing_user(self, user_id, data, is_partial):
        user = self.get_user_by_id(user_id)
        
        for key, value in data.items():
            if hasattr(user, key):
                if key == 'role':
                    setattr(user, key, UserRole(value))
                else:
                    setattr(user, key, value)
        
        return self.user_repository.save(user)

    def delete_user_by_id(self, user_id):
        if not self.user_repository.delete(user_id):
            raise NotFoundError("User with given ID not found")

# --- Controller Layer (HTTP Handling) ---

class LayeredApiHandler(BaseHTTPRequestHandler):
    
    # Instantiate layers. In a real app, this would use dependency injection.
    user_service = UserService(UserRepository())

    def _process_request(self, method):
        parsed_url = urlparse(self.path)
        path_segments = parsed_url.path.strip("/").split("/")
        query_params = parse_qs(parsed_url.query)

        try:
            if path_segments[0] == "users":
                if len(path_segments) == 1:
                    if method == 'GET':
                        self.handle_list_users_request(query_params)
                    elif method == 'POST':
                        self.handle_create_user_request()
                elif len(path_segments) == 2:
                    user_id = path_segments[1]
                    if method == 'GET':
                        self.handle_get_user_by_id_request(user_id)
                    elif method in ('PUT', 'PATCH'):
                        self.handle_update_user_request(user_id, is_partial=(method == 'PATCH'))
                    elif method == 'DELETE':
                        self.handle_delete_user_request(user_id)
                else:
                    raise NotFoundError("Invalid API path")
            else:
                raise NotFoundError("Endpoint not found")
        except (NotFoundError, BadRequestError) as e:
            status_code = 404 if isinstance(e, NotFoundError) else 400
            self._send_json_response(status_code, {"error": str(e)})
        except Exception as e:
            self._send_json_response(500, {"error": "An internal server error occurred"})

    def _send_json_response(self, status_code, data):
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        if data is not None:
            self.wfile.write(json.dumps(data).encode('utf-8'))

    def _get_request_body(self):
        content_length = int(self.headers.get('Content-Length', 0))
        return json.loads(self.rfile.read(content_length))

    def handle_list_users_request(self, query_params):
        users, total, page, limit = self.user_service.get_all_users(query_params)
        response_data = {
            "page": page, "limit": limit, "total": total,
            "data": [u.to_dict() for u in users]
        }
        self._send_json_response(200, response_data)

    def handle_get_user_by_id_request(self, user_id):
        user = self.user_service.get_user_by_id(user_id)
        self._send_json_response(200, user.to_dict())

    def handle_create_user_request(self):
        body = self._get_request_body()
        new_user = self.user_service.create_new_user(body)
        self._send_json_response(201, new_user.to_dict())

    def handle_update_user_request(self, user_id, is_partial):
        body = self._get_request_body()
        updated_user = self.user_service.update_existing_user(user_id, body, is_partial)
        self._send_json_response(200, updated_user.to_dict())

    def handle_delete_user_request(self, user_id):
        self.user_service.delete_user_by_id(user_id)
        self._send_json_response(204, None)

    def do_GET(self): self._process_request('GET')
    def do_POST(self): self._process_request('POST')
    def do_PUT(self): self._process_request('PUT')
    def do_PATCH(self): self._process_request('PATCH')
    def do_DELETE(self): self._process_request('DELETE')

# --- Server Execution ---

if __name__ == "__main__":
    server_address = ("", 8002)
    httpd = HTTPServer(server_address, LayeredApiHandler)
    print("Starting layered architecture server on port 8002...")
    httpd.serve_forever()