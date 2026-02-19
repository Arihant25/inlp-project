import json
import re
import uuid
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs
from datetime import datetime, timezone
from enum import Enum

# --- Domain Model ---

class UserRole(Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

# --- Data Access Layer ---

class UserRepository:
    """
    Manages user data in an in-memory store.
    Simulates a database repository.
    """
    _users = {}
    _posts = {}

    def __init__(self):
        self.initializeData()

    def initializeData(self):
        if UserRepository._users: # Already initialized
            return
            
        userId1 = str(uuid.uuid4())
        userId2 = str(uuid.uuid4())
        
        UserRepository._users = {
            userId1: {
                "id": userId1, "email": "admin.dev@example.com", "password_hash": "hash1",
                "role": UserRole.ADMIN.value, "is_active": True, "created_at": datetime.now(timezone.utc).isoformat()
            },
            userId2: {
                "id": userId2, "email": "user.dev@example.com", "password_hash": "hash2",
                "role": UserRole.USER.value, "is_active": False, "created_at": datetime.now(timezone.utc).isoformat()
            }
        }
        
        postId1 = str(uuid.uuid4())
        UserRepository._posts = {
            postId1: {
                "id": postId1, "user_id": userId1, "title": "OOP Post",
                "content": "Content from the OOP variation.", "status": PostStatus.PUBLISHED.value
            }
        }

    def findById(self, userId):
        return self._users.get(userId)

    def findAll(self, filters=None, page=1, limit=10):
        allUsers = list(self._users.values())
        
        if filters:
            if 'role' in filters:
                allUsers = [u for u in allUsers if u['role'] == filters['role'][0].upper()]
            if 'is_active' in filters:
                isActive = filters['is_active'][0].lower() in ['true', '1']
                allUsers = [u for u in allUsers if u['is_active'] == isActive]

        total = len(allUsers)
        startIndex = (page - 1) * limit
        endIndex = startIndex + limit
        
        return allUsers[startIndex:endIndex], total

    def save(self, user_data):
        userId = user_data.get("id", str(uuid.uuid4()))
        user_data["id"] = userId
        if "created_at" not in user_data:
            user_data["created_at"] = datetime.now(timezone.utc).isoformat()
        self._users[userId] = user_data
        return user_data

    def deleteById(self, userId):
        if userId in self._users:
            del self._users[userId]
            return True
        return False

# --- Controller & Router ---

class UserController:
    def __init__(self, userRepository):
        self.userRepository = userRepository

    def listUsers(self, requestHandler, path_match, query_params):
        page = int(query_params.get("page", [1])[0])
        limit = int(query_params.get("limit", [10])[0])
        
        users, total = self.userRepository.findAll(filters=query_params, page=page, limit=limit)
        
        response = {"page": page, "limit": limit, "total": total, "data": users}
        requestHandler.sendJsonResponse(200, response)

    def getUser(self, requestHandler, path_match, query_params):
        userId = path_match.group(1)
        user = self.userRepository.findById(userId)
        if user:
            requestHandler.sendJsonResponse(200, user)
        else:
            requestHandler.sendJsonResponse(404, {"message": "User not found"})

    def createUser(self, requestHandler, path_match, query_params):
        body = requestHandler.getJsonBody()
        if not body.get("email") or not body.get("password_hash"):
            requestHandler.sendJsonResponse(400, {"message": "Missing required fields"})
            return
        
        newUser = {
            "email": body["email"],
            "password_hash": body["password_hash"],
            "role": body.get("role", UserRole.USER.value),
            "is_active": body.get("is_active", True)
        }
        createdUser = self.userRepository.save(newUser)
        requestHandler.sendJsonResponse(201, createdUser)

    def updateUser(self, requestHandler, path_match, query_params):
        userId = path_match.group(1)
        user = self.userRepository.findById(userId)
        if not user:
            requestHandler.sendJsonResponse(404, {"message": "User not found"})
            return
        
        body = requestHandler.getJsonBody()
        user.update(body) # Simple update, allows partial (PATCH) or full (PUT)
        updatedUser = self.userRepository.save(user)
        requestHandler.sendJsonResponse(200, updatedUser)

    def deleteUser(self, requestHandler, path_match, query_params):
        userId = path_match.group(1)
        if self.userRepository.deleteById(userId):
            requestHandler.sendJsonResponse(204, None)
        else:
            requestHandler.sendJsonResponse(404, {"message": "User not found"})

class Router:
    def __init__(self):
        userRepo = UserRepository()
        userController = UserController(userRepo)
        self.routes = [
            ("GET", re.compile(r"^/users/?$"), userController.listUsers),
            ("GET", re.compile(r"^/users/([a-f0-9-]+)/?$"), userController.getUser),
            ("POST", re.compile(r"^/users/?$"), userController.createUser),
            ("PUT", re.compile(r"^/users/([a-f0-9-]+)/?$"), userController.updateUser),
            ("PATCH", re.compile(r"^/users/([a-f0-9-]+)/?$"), userController.updateUser),
            ("DELETE", re.compile(r"^/users/([a-f0-9-]+)/?$"), userController.deleteUser),
        ]

    def route(self, requestHandler):
        parsedUrl = urlparse(requestHandler.path)
        queryParams = parse_qs(parsedUrl.query)
        
        for method, pattern, handlerFunc in self.routes:
            if method == requestHandler.command:
                match = pattern.match(parsedUrl.path)
                if match:
                    handlerFunc(requestHandler, match, queryParams)
                    return
        
        requestHandler.sendJsonResponse(404, {"message": "Endpoint not found"})

# --- HTTP Server Handler ---

class OopApiHandler(BaseHTTPRequestHandler):
    
    router = Router()

    def sendJsonResponse(self, statusCode, payload):
        self.send_response(statusCode)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        if payload is not None:
            self.wfile.write(json.dumps(payload).encode('utf-8'))

    def getJsonBody(self):
        contentLength = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(contentLength)
        return json.loads(body) if body else {}

    def do_GET(self):
        self.router.route(self)

    def do_POST(self):
        self.router.route(self)

    def do_PUT(self):
        self.router.route(self)

    def do_PATCH(self):
        self.router.route(self)

    def do_DELETE(self):
        self.router.route(self)

# --- Server Execution ---

if __name__ == "__main__":
    serverAddress = ("", 8001)
    httpd = HTTPServer(serverAddress, OopApiHandler)
    print("Starting OOP-style server on port 8001...")
    httpd.serve_forever()