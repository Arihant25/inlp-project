import json
import base64
import hmac
import hashlib
import time
import os
import uuid
from datetime import datetime, timezone
from enum import Enum
import functools

# --- Domain Model & Enums ---
class UserRole(Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

# Using simple classes for data representation
class UserDTO:
    def __init__(self, id, email, password_hash, role, is_active=True, created_at=None):
        self.id = id
        self.email = email
        self.password_hash = password_hash
        self.role = role
        self.is_active = is_active
        self.created_at = created_at or datetime.now(timezone.utc)

class PostDTO:
    def __init__(self, id, user_id, title, content, status=PostStatus.DRAFT):
        self.id = id
        self.user_id = user_id
        self.title = title
        self.content = content
        self.status = status

# --- Singleton for Data Stores ---
class Singleton(type):
    _instances = {}
    def __call__(cls, *args, **kwargs):
        if cls not in cls._instances:
            cls._instances[cls] = super(Singleton, cls).__call__(*args, **kwargs)
        return cls._instances[cls]

class DataStore(metaclass=Singleton):
    def __init__(self):
        self.users = {}
        self.posts = {}
        self.revokedTokens = set()

# --- Static Manager Classes for Logic ---
class AuthManager:
    SECRET_KEY = os.urandom(32)
    TOKEN_EXPIRATION_SECS = 3600
    
    @staticmethod
    def _base64urlEncode(data):
        return base64.urlsafe_b64encode(data).rstrip(b'=')

    @staticmethod
    def _base64urlDecode(b64data):
        padding = b'=' * (-len(b64data) % 4)
        return base64.urlsafe_b64decode(b64data + padding)

    @staticmethod
    def hashPassword(password):
        salt = os.urandom(16)
        key = hashlib.pbkdf2_hmac('sha256', password.encode('utf-8'), salt, 100000)
        return salt.hex() + ':' + key.hex()

    @staticmethod
    def verifyPassword(storedPassword, providedPassword):
        try:
            saltHex, keyHex = storedPassword.split(':')
            salt = bytes.fromhex(saltHex)
            key = bytes.fromhex(keyHex)
            newKey = hashlib.pbkdf2_hmac('sha256', providedPassword.encode('utf-8'), salt, 100000)
            return hmac.compare_digest(key, newKey)
        except:
            return False

    @staticmethod
    def createToken(userDto):
        header = {'alg': 'HS256', 'typ': 'JWT'}
        payload = {
            'sub': str(userDto.id),
            'role': userDto.role.value,
            'iat': int(time.time()),
            'exp': int(time.time()) + AuthManager.TOKEN_EXPIRATION_SECS
        }
        header_b64 = AuthManager._base64urlEncode(json.dumps(header).encode())
        payload_b64 = AuthManager._base64urlEncode(json.dumps(payload).encode())
        signing_input = header_b64 + b'.' + payload_b64
        signature = hmac.new(AuthManager.SECRET_KEY, signing_input, hashlib.sha256).digest()
        signature_b64 = AuthManager._base64urlEncode(signature)
        return (signing_input + b'.' + signature_b64).decode()

    @staticmethod
    def validateToken(token):
        if token in DataStore().revokedTokens:
            raise Exception("Token is revoked")
        try:
            head_b64, payload_b64, sig_b64 = token.encode().split(b'.')
            expected_sig = hmac.new(AuthManager.SECRET_KEY, head_b64 + b'.' + payload_b64, hashlib.sha256).digest()
            if not hmac.compare_digest(AuthManager._base64urlDecode(sig_b64), expected_sig):
                raise Exception("Invalid signature")
            
            payload = json.loads(AuthManager._base64urlDecode(payload_b64))
            if payload['exp'] < time.time():
                raise Exception("Token has expired")
            
            return payload
        except Exception as e:
            raise Exception(f"Token validation failed: {e}")

# --- RBAC Decorator ---
def accessControl(requiredRole):
    def decorator(func):
        @functools.wraps(func)
        def wrapper(token, *args, **kwargs):
            try:
                payload = AuthManager.validateToken(token)
                userRole = UserRole(payload.get('role'))
                
                # Admin can do anything
                if userRole == UserRole.ADMIN or userRole == requiredRole:
                    context = {'userId': uuid.UUID(payload['sub']), 'userRole': userRole}
                    return func(context=context, *args, **kwargs)
                else:
                    raise PermissionError("Insufficient privileges")
            except Exception as e:
                print(f"[AUTH ERROR] {e}")
                return None
        return wrapper
    return decorator

# --- Application API ---
class AppAPI:
    def __init__(self):
        self.db = DataStore()

    def login(self, email, password):
        user = next((u for u in self.db.users.values() if u.email == email and u.is_active), None)
        if user and AuthManager.verifyPassword(user.password_hash, password):
            return AuthManager.createToken(user)
        return None

    def logout(self, token):
        self.db.revokedTokens.add(token)

    def processOauthCallback(self, provider_code):
        # Simulate getting user info from OAuth provider
        provider_user = {"email": "oauth.user@example.com"}
        user = next((u for u in self.db.users.values() if u.email == provider_user["email"]), None)
        if not user:
            userId = uuid.uuid4()
            user = UserDTO(userId, provider_user["email"], "OAUTH_NO_PASS", UserRole.USER)
            self.db.users[userId] = user
            print(f"Provisioned new OAuth user: {user.email}")
        return AuthManager.createToken(user)

    @accessControl(UserRole.USER)
    def createPost(self, token, title, content, context=None):
        postId = uuid.uuid4()
        post = PostDTO(postId, context['userId'], title, content)
        self.db.posts[postId] = post
        print(f"User {context['userId']} created post '{title}'")
        return post

    @accessControl(UserRole.ADMIN)
    def setPostStatus(self, token, postId, newStatus, context=None):
        post = self.db.posts.get(postId)
        if post:
            post.status = newStatus
            print(f"Admin {context['userId']} set post {postId} to {newStatus.value}")
            return post
        return None

# --- Main Execution ---
if __name__ == '__main__':
    api = AppAPI()
    db = DataStore()

    # Setup
    adminId = uuid.uuid4()
    userId = uuid.uuid4()
    db.users[adminId] = UserDTO(adminId, "admin@example.com", AuthManager.hashPassword("securepass1"), UserRole.ADMIN)
    db.users[userId] = UserDTO(userId, "user@example.com", AuthManager.hashPassword("securepass2"), UserRole.USER)
    print("--- System Ready ---")

    # 1. User logs in, creates a post
    print("\n--- User Actions ---")
    userToken = api.login("user@example.com", "securepass2")
    print("User login successful.")
    myPost = api.createPost(token=userToken, title="My Thoughts", content="Blah blah.")
    assert myPost is not None

    # 2. User tries to publish their own post (fails due to RBAC)
    print("\n--- User RBAC Failure Test ---")
    api.setPostStatus(token=userToken, postId=myPost.id, newStatus=PostStatus.PUBLISHED)

    # 3. Admin logs in, publishes the post
    print("\n--- Admin Actions ---")
    adminToken = api.login("admin@example.com", "securepass1")
    print("Admin login successful.")
    api.setPostStatus(token=adminToken, postId=myPost.id, newStatus=PostStatus.PUBLISHED)
    assert db.posts[myPost.id].status == PostStatus.PUBLISHED

    # 4. Logout and token revocation
    print("\n--- Logout Test ---")
    api.logout(userToken)
    api.createPost(token=userToken, title="After Logout", content="This should fail.")

    # 5. OAuth simulation
    print("\n--- OAuth Simulation ---")
    oauthToken = api.processOauthCallback("fake_oauth_code")
    print(f"OAuth login successful. Token: {oauthToken[:30]}...")
    oauthPost = api.createPost(token=oauthToken, title="Post via OAuth", content="Works!")
    assert oauthPost is not None