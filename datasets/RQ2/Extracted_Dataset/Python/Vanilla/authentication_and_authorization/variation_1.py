import json
import base64
import hmac
import hashlib
import time
import os
import uuid
from datetime import datetime, timedelta, timezone
from enum import Enum
import functools

# --- Configuration ---
SECRET_KEY = os.urandom(32)
TOKEN_LIFESPAN_SECONDS = 3600  # 1 hour
PASSWORD_SALT_BYTES = 16
HASH_ITERATIONS = 100000

# --- Domain Model ---
class Role(Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

# Using dictionaries to represent our entities
# In a real app, these would be dataclasses or full classes
User = dict
Post = dict

# --- In-Memory Database & Session Store ---
DB_USERS = {}
DB_POSTS = {}
SESSION_BLACKLIST = set()

# --- Utility Functions ---
def base64url_encode(data):
    return base64.urlsafe_b64encode(data).rstrip(b'=')

def base64url_decode(b64data):
    padding = b'=' * (4 - (len(b64data) % 4))
    return base64.urlsafe_b64decode(b64data + padding)

# --- Password Hashing ---
def hash_password(password):
    salt = os.urandom(PASSWORD_SALT_BYTES)
    pwd_hash = hashlib.pbkdf2_hmac(
        'sha256', password.encode('utf-8'), salt, HASH_ITERATIONS
    )
    return f"{salt.hex()}${pwd_hash.hex()}"

def verify_password(stored_password_hash, provided_password):
    try:
        salt_hex, hash_hex = stored_password_hash.split('$')
        salt = bytes.fromhex(salt_hex)
        stored_hash = bytes.fromhex(hash_hex)
    except ValueError:
        return False
    
    provided_hash = hashlib.pbkdf2_hmac(
        'sha256', provided_password.encode('utf-8'), salt, HASH_ITERATIONS
    )
    return hmac.compare_digest(stored_hash, provided_hash)

# --- JWT Implementation ---
def generate_jwt(user_id, role):
    header = {"alg": "HS256", "typ": "JWT"}
    payload = {
        "sub": str(user_id),
        "role": role.value,
        "iat": int(time.time()),
        "exp": int(time.time()) + TOKEN_LIFESPAN_SECONDS
    }
    
    encoded_header = base64url_encode(json.dumps(header, separators=(",", ":")).encode('utf-8'))
    encoded_payload = base64url_encode(json.dumps(payload, separators=(",", ":")).encode('utf-8'))
    
    message = encoded_header + b'.' + encoded_payload
    signature = hmac.new(SECRET_KEY, message, hashlib.sha256).digest()
    encoded_signature = base64url_encode(signature)
    
    return (message + b'.' + encoded_signature).decode('utf-8')

def validate_jwt(token):
    if token in SESSION_BLACKLIST:
        raise ValueError("Token has been revoked (logged out).")

    try:
        encoded_header, encoded_payload, encoded_signature = token.encode('utf-8').split(b'.')
        
        message = encoded_header + b'.' + encoded_payload
        expected_signature = hmac.new(SECRET_KEY, message, hashlib.sha256).digest()
        
        decoded_signature = base64url_decode(encoded_signature)

        if not hmac.compare_digest(expected_signature, decoded_signature):
            raise ValueError("Invalid token signature.")
            
        payload = json.loads(base64url_decode(encoded_payload))
        
        if payload['exp'] < int(time.time()):
            raise ValueError("Token has expired.")
            
        return payload
    except (ValueError, IndexError, KeyError):
        raise ValueError("Invalid token format or content.")

# --- RBAC Decorator ---
def requires_role(required_role):
    def decorator(func):
        @functools.wraps(func)
        def wrapper(token, *args, **kwargs):
            try:
                payload = validate_jwt(token)
                user_role = Role(payload.get('role'))
                
                if user_role != required_role and user_role != Role.ADMIN:
                    raise PermissionError(f"Access denied. Requires role: {required_role.value}")
                
                # Pass user context to the decorated function
                kwargs['user_context'] = {
                    'id': uuid.UUID(payload['sub']),
                    'role': user_role
                }
                return func(*args, **kwargs)
            except (ValueError, PermissionError) as e:
                print(f"Authorization Error: {e}")
                return None
        return wrapper
    return decorator

# --- Service Functions (Simulated API Endpoints) ---
def login(email, password):
    user = next((u for u in DB_USERS.values() if u['email'] == email and u['is_active']), None)
    if user and verify_password(user['password_hash'], password):
        return generate_jwt(user['id'], user['role'])
    return None

def logout(token):
    SESSION_BLACKLIST.add(token)
    print("User logged out successfully.")

def handle_oauth_callback(auth_code):
    # Simulate exchanging code for user info from an OAuth provider
    print(f"Handling OAuth callback with code: {auth_code}")
    # Mock provider response
    oauth_user_info = {
        "email": "oauth.user@example.com",
        "provider_id": "provider12345"
    }
    
    user = next((u for u in DB_USERS.values() if u['email'] == oauth_user_info['email']), None)
    if not user:
        # Create a new user for the OAuth login
        new_user_id = uuid.uuid4()
        user = {
            "id": new_user_id,
            "email": oauth_user_info['email'],
            "password_hash": "OAUTH_USER_NO_PASSWORD",
            "role": Role.USER,
            "is_active": True,
            "created_at": datetime.now(timezone.utc)
        }
        DB_USERS[new_user_id] = user
        print(f"Created new user for OAuth login: {user['email']}")
    
    return generate_jwt(user['id'], user['role'])

@requires_role(Role.USER)
def create_post(token, title, content, user_context=None):
    post_id = uuid.uuid4()
    new_post = {
        "id": post_id,
        "user_id": user_context['id'],
        "title": title,
        "content": content,
        "status": PostStatus.DRAFT
    }
    DB_POSTS[post_id] = new_post
    print(f"User '{user_context['id']}' created post '{title}'.")
    return new_post

@requires_role(Role.ADMIN)
def publish_any_post(token, post_id, user_context=None):
    post = DB_POSTS.get(post_id)
    if post:
        post['status'] = PostStatus.PUBLISHED
        print(f"Admin '{user_context['id']}' published post '{post['title']}'.")
        return post
    print(f"Post with ID {post_id} not found.")
    return None

# --- Main Execution ---
if __name__ == "__main__":
    # 1. Setup: Create mock users
    admin_id = uuid.uuid4()
    user_id = uuid.uuid4()
    DB_USERS[admin_id] = {
        "id": admin_id, "email": "admin@example.com", "password_hash": hash_password("admin123"),
        "role": Role.ADMIN, "is_active": True, "created_at": datetime.now(timezone.utc)
    }
    DB_USERS[user_id] = {
        "id": user_id, "email": "user@example.com", "password_hash": hash_password("user123"),
        "role": Role.USER, "is_active": True, "created_at": datetime.now(timezone.utc)
    }
    print("--- Initial Setup Complete ---")
    
    # 2. User Login and Post Creation
    print("\n--- User Login and RBAC Test ---")
    user_token = login("user@example.com", "user123")
    assert user_token is not None
    print("User logged in successfully.")
    
    my_post = create_post(token=user_token, title="My First Post", content="Hello World!")
    assert my_post is not None
    
    # 3. User attempts Admin action (should fail)
    print("\n--- User attempts Admin action (expect failure) ---")
    publish_any_post(token=user_token, post_id=my_post['id'])

    # 4. Admin Login and Admin action
    print("\n--- Admin Login and RBAC Test ---")
    admin_token = login("admin@example.com", "admin123")
    assert admin_token is not None
    print("Admin logged in successfully.")
    
    publish_any_post(token=admin_token, post_id=my_post['id'])
    assert DB_POSTS[my_post['id']]['status'] == PostStatus.PUBLISHED
    
    # 5. Token Validation and Logout
    print("\n--- Token Validation and Logout ---")
    payload = validate_jwt(user_token)
    print(f"User token is valid. Payload: {payload}")
    logout(user_token)
    try:
        validate_jwt(user_token)
    except ValueError as e:
        print(f"Successfully caught expected error for revoked token: {e}")

    # 6. OAuth2 Simulation
    print("\n--- OAuth2 Client Simulation ---")
    oauth_token = handle_oauth_callback("some_fake_auth_code")
    assert oauth_token is not None
    print(f"OAuth user logged in. Token starts with: {oauth_token[:20]}...")
    oauth_payload = validate_jwt(oauth_token)
    assert oauth_payload['role'] == 'USER'
    print("OAuth token validated successfully.")