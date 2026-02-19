import json
import uuid
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs
from datetime import datetime, timezone
from enum import Enum

# --- Domain Model & Enums ---

class UserRole(Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

# --- In-Memory Database ---

DB = {
    "users": {},
    "posts": {}
}

def initialize_mock_data():
    """Populates the in-memory DB with some sample data."""
    user_id_1 = str(uuid.uuid4())
    user_id_2 = str(uuid.uuid4())
    
    DB["users"][user_id_1] = {
        "id": user_id_1,
        "email": "admin@example.com",
        "password_hash": "hashed_password_1",
        "role": UserRole.ADMIN.value,
        "is_active": True,
        "created_at": datetime.now(timezone.utc).isoformat()
    }
    DB["users"][user_id_2] = {
        "id": user_id_2,
        "email": "user@example.com",
        "password_hash": "hashed_password_2",
        "role": UserRole.USER.value,
        "is_active": True,
        "created_at": datetime.now(timezone.utc).isoformat()
    }
    
    post_id_1 = str(uuid.uuid4())
    DB["posts"][post_id_1] = {
        "id": post_id_1,
        "user_id": user_id_1,
        "title": "First Post",
        "content": "This is the content of the first post.",
        "status": PostStatus.PUBLISHED.value
    }

# --- Request Handler ---

class SimpleCrudApiHandler(BaseHTTPRequestHandler):
    """
    A simple, monolithic handler for the REST API.
    Routing is done via if/elif/else blocks within each do_* method.
    """

    def _send_response(self, status_code, body, content_type="application/json"):
        self.send_response(status_code)
        self.send_header("Content-Type", content_type)
        self.end_headers()
        self.wfile.write(json.dumps(body).encode("utf-8"))

    def _parse_body(self):
        content_length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_length)
        return json.loads(body) if body else {}

    def do_GET(self):
        parsed_path = urlparse(self.path)
        path_parts = parsed_path.path.strip("/").split("/")
        query_params = parse_qs(parsed_path.query)

        if path_parts[0] == "users":
            if len(path_parts) == 1:
                # List users with pagination and filtering
                users = list(DB["users"].values())
                
                # Filtering
                if 'role' in query_params:
                    role_filter = query_params['role'][0].upper()
                    users = [u for u in users if u['role'] == role_filter]
                if 'is_active' in query_params:
                    active_filter = query_params['is_active'][0].lower() in ['true', '1']
                    users = [u for u in users if u['is_active'] == active_filter]

                # Pagination
                page = int(query_params.get("page", [1])[0])
                limit = int(query_params.get("limit", [10])[0])
                start_index = (page - 1) * limit
                end_index = start_index + limit
                
                paginated_users = users[start_index:end_index]
                
                response_data = {
                    "page": page,
                    "limit": limit,
                    "total": len(users),
                    "data": paginated_users
                }
                self._send_response(200, response_data)
            elif len(path_parts) == 2:
                # Get user by ID
                user_id = path_parts[1]
                user = DB["users"].get(user_id)
                if user:
                    self._send_response(200, user)
                else:
                    self._send_response(404, {"error": "User not found"})
            else:
                self._send_response(404, {"error": "Not Found"})
        else:
            self._send_response(404, {"error": "Not Found"})

    def do_POST(self):
        parsed_path = urlparse(self.path)
        if parsed_path.path == "/users":
            body = self._parse_body()
            if not body.get("email") or not body.get("password_hash"):
                self._send_response(400, {"error": "Email and password_hash are required"})
                return

            new_user_id = str(uuid.uuid4())
            new_user = {
                "id": new_user_id,
                "email": body["email"],
                "password_hash": body["password_hash"],
                "role": body.get("role", UserRole.USER.value),
                "is_active": body.get("is_active", True),
                "created_at": datetime.now(timezone.utc).isoformat()
            }
            DB["users"][new_user_id] = new_user
            self._send_response(201, new_user)
        else:
            self._send_response(404, {"error": "Not Found"})

    def do_PUT(self):
        parsed_path = urlparse(self.path)
        path_parts = parsed_path.path.strip("/").split("/")

        if len(path_parts) == 2 and path_parts[0] == "users":
            user_id = path_parts[1]
            if user_id not in DB["users"]:
                self._send_response(404, {"error": "User not found"})
                return

            body = self._parse_body()
            user = DB["users"][user_id]
            
            # PUT replaces the entire resource
            user["email"] = body.get("email", user["email"])
            user["password_hash"] = body.get("password_hash", user["password_hash"])
            user["role"] = body.get("role", user["role"])
            user["is_active"] = body.get("is_active", user["is_active"])
            
            DB["users"][user_id] = user
            self._send_response(200, user)
        else:
            self._send_response(404, {"error": "Not Found"})

    def do_DELETE(self):
        parsed_path = urlparse(self.path)
        path_parts = parsed_path.path.strip("/").split("/")

        if len(path_parts) == 2 and path_parts[0] == "users":
            user_id = path_parts[1]
            if user_id in DB["users"]:
                del DB["users"][user_id]
                self._send_response(204, {})
            else:
                self._send_response(404, {"error": "User not found"})
        else:
            self._send_response(404, {"error": "Not Found"})
            
    # PATCH is often similar to PUT but for partial updates.
    # For this variation, we'll make it an alias for PUT.
    do_PATCH = do_PUT

# --- Server Execution ---

def run_server(server_class=HTTPServer, handler_class=SimpleCrudApiHandler, port=8000):
    server_address = ("", port)
    httpd = server_class(server_address, handler_class)
    print(f"Starting httpd server on port {port}...")
    initialize_mock_data()
    print("Mock data initialized.")
    print("Example requests:")
    print("  GET http://localhost:8000/users")
    print(f"  GET http://localhost:8000/users/{list(DB['users'].keys())[0]}")
    print("  POST http://localhost:8000/users (with JSON body)")
    httpd.serve_forever()

if __name__ == "__main__":
    run_server()