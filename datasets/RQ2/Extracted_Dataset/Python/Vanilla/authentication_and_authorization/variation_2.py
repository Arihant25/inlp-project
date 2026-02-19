import json
import base64
import hmac
import hashlib
import time
import os
import uuid
from datetime import datetime, timedelta, timezone
from enum import Enum
from functools import wraps

# --- Domain Model ---
class Role(Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

class User:
    def __init__(self, id, email, password_hash, role, is_active=True):
        self.id = id
        self.email = email
        self.password_hash = password_hash
        self.role = role
        self.is_active = is_active
        self.created_at = datetime.now(timezone.utc)

class Post:
    def __init__(self, id, user_id, title, content):
        self.id = id
        self.user_id = user_id
        self.title = title
        self.content = content
        self.status = PostStatus.DRAFT

# --- Core Services ---
class PasswordManager:
    SALT_BYTES = 16
    ITERATIONS = 100000

    def hash(self, password):
        salt = os.urandom(self.SALT_BYTES)
        pwd_hash = hashlib.pbkdf2_hmac('sha256', password.encode('utf-8'), salt, self.ITERATIONS)
        return f"{salt.hex()}${pwd_hash.hex()}"

    def verify(self, stored_hash, provided_password):
        try:
            salt_hex, hash_hex = stored_hash.split('$')
            salt = bytes.fromhex(salt_hex)
        except (ValueError, TypeError):
            return False
        
        provided_hash = hashlib.pbkdf2_hmac('sha256', provided_password.encode('utf-8'), salt, self.ITERATIONS)
        return hmac.compare_digest(bytes.fromhex(hash_hex), provided_hash)

class JWTManager:
    def __init__(self, secret_key, lifespan_seconds=3600):
        self.secret_key = secret_key
        self.lifespan = lifespan_seconds

    def _base64url_encode(self, data):
        return base64.urlsafe_b64encode(data).rstrip(b'=')

    def _base64url_decode(self, b64data):
        padding = b'=' * (-len(b64data) % 4)
        return base64.urlsafe_b64decode(b64data + padding)

    def create_token(self, user):
        header = {"alg": "HS256", "typ": "JWT"}
        payload = {
            "sub": str(user.id),
            "role": user.role.value,
            "iat": int(time.time()),
            "exp": int(time.time()) + self.lifespan
        }
        
        encoded_header = self._base64url_encode(json.dumps(header, separators=(",", ":")).encode())
        encoded_payload = self._base64url_encode(json.dumps(payload, separators=(",", ":")).encode())
        
        message = encoded_header + b'.' + encoded_payload
        signature = hmac.new(self.secret_key, message, hashlib.sha256).digest()
        
        return f"{(message + b'.' + self._base64url_encode(signature)).decode()}"

    def validate_token(self, token):
        try:
            encoded_header, encoded_payload, encoded_signature = token.encode().split(b'.')
            message = encoded_header + b'.' + encoded_payload
            
            expected_signature = hmac.new(self.secret_key, message, hashlib.sha256).digest()
            if not hmac.compare_digest(self._base64url_decode(encoded_signature), expected_signature):
                raise ValueError("Invalid signature")
                
            payload = json.loads(self._base64url_decode(encoded_payload))
            if payload['exp'] < int(time.time()):
                raise ValueError("Token expired")
                
            return payload
        except Exception as e:
            raise ValueError(f"Invalid token: {e}")

# --- Application Layer ---
class Application:
    def __init__(self):
        self.db_users = {}
        self.db_posts = {}
        self.session_blacklist = set()
        self.secret_key = os.urandom(32)
        
        self.password_manager = PasswordManager()
        self.jwt_manager = JWTManager(self.secret_key)

    def requires_auth(self, required_role):
        def decorator(f):
            @wraps(f)
            def decorated_function(token, *args, **kwargs):
                if token in self.session_blacklist:
                    print("Authorization Error: Token has been revoked.")
                    return None
                try:
                    payload = self.jwt_manager.validate_token(token)
                    user_role = Role(payload.get('role'))
                    
                    # Admins have access to everything
                    if user_role != Role.ADMIN and user_role != required_role:
                        raise PermissionError(f"Insufficient permissions. Requires {required_role.value}.")
                    
                    user_context = {'id': uuid.UUID(payload['sub']), 'role': user_role}
                    return f(user_context, *args, **kwargs)
                except (ValueError, PermissionError) as e:
                    print(f"Authorization Error: {e}")
                    return None
            return decorated_function
        return decorator

    def register_user(self, email, password, role):
        user_id = uuid.uuid4()
        password_hash = self.password_manager.hash(password)
        new_user = User(user_id, email, password_hash, role)
        self.db_users[user_id] = new_user
        return new_user

    def login(self, email, password):
        user = next((u for u in self.db_users.values() if u.email == email and u.is_active), None)
        if user and self.password_manager.verify(user.password_hash, password):
            return self.jwt_manager.create_token(user)
        return None

    def logout(self, token):
        self.session_blacklist.add(token)
        print("Session token blacklisted.")

    def handle_oauth_login(self, auth_code):
        # Mocking OAuth provider interaction
        print(f"Simulating OAuth flow for code: {auth_code}")
        provider_data = {"email": "oauth.user@example.com", "name": "OAuth User"}
        
        user = next((u for u in self.db_users.values() if u.email == provider_data['email']), None)
        if not user:
            user = self.register_user(provider_data['email'], "N/A", Role.USER)
            user.password_hash = "OAUTH_LOGIN" # Mark as non-password user
            print(f"New OAuth user created: {user.email}")
        
        return self.jwt_manager.create_token(user)

# --- Main Execution ---
if __name__ == "__main__":
    app = Application()
    
    # Setup users
    admin = app.register_user("admin@example.com", "admin_pass", Role.ADMIN)
    user = app.register_user("user@example.com", "user_pass", Role.USER)
    print("--- System Initialized ---")

    # User login and actions
    print("\n--- User Workflow ---")
    user_token = app.login("user@example.com", "user_pass")
    print("User login successful.")
    
    @app.requires_auth(required_role=Role.USER)
    def create_post(user_context, title, content):
        post_id = uuid.uuid4()
        new_post = Post(post_id, user_context['id'], title, content)
        app.db_posts[post_id] = new_post
        print(f"User {user_context['id']} created post '{title}'.")
        return new_post

    my_post = create_post(token=user_token, title="A User's Post", content="Content here.")
    assert my_post is not None

    # Admin login and actions
    print("\n--- Admin Workflow ---")
    admin_token = app.login("admin@example.com", "admin_pass")
    print("Admin login successful.")

    @app.requires_auth(required_role=Role.ADMIN)
    def publish_post(user_context, post_id):
        post = app.db_posts.get(post_id)
        if post:
            post.status = PostStatus.PUBLISHED
            print(f"Admin {user_context['id']} published post ID {post_id}.")
            return post
        return None

    # User tries to publish (should fail)
    print("User attempting to publish (expect failure)...")
    publish_post(token=user_token, post_id=my_post.id)

    # Admin publishes (should succeed)
    print("Admin attempting to publish (expect success)...")
    published_post = publish_post(token=admin_token, post_id=my_post.id)
    assert published_post.status == PostStatus.PUBLISHED

    # Logout and token invalidation
    print("\n--- Logout and Session Test ---")
    app.logout(user_token)
    create_post(token=user_token, title="Another Post", content="This should fail.")

    # OAuth Simulation
    print("\n--- OAuth2 Simulation ---")
    oauth_token = app.handle_oauth_login("fake_oauth_code_123")
    assert oauth_token is not None
    print(f"OAuth login successful. Token: {oauth_token[:30]}...")
    
    # Verify OAuth user can perform user actions
    oauth_post = create_post(token=oauth_token, title="OAuth Post", content="From OAuth user.")
    assert oauth_post is not None