import json
import base64
import hmac
import hashlib
import time
import os
import uuid
from datetime import datetime, timezone, timedelta
from enum import Enum
from abc import ABC, abstractmethod
from typing import Dict, Any, Optional, Set, Type, List
from functools import wraps

# --- Type Hinting & Domain Model ---
Payload = Dict[str, Any]
UserID = uuid.UUID
PostID = uuid.UUID

class Role(Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

class User:
    id: UserID
    email: str
    password_hash: str
    role: Role
    is_active: bool
    created_at: datetime

    def __init__(self, **kwargs):
        for k, v in kwargs.items():
            setattr(self, k, v)

class Post:
    id: PostID
    user_id: UserID
    title: str
    content: str
    status: PostStatus

    def __init__(self, **kwargs):
        for k, v in kwargs.items():
            setattr(self, k, v)

# --- Abstract Interfaces (Protocols) ---
class IPasswordHasher(ABC):
    @abstractmethod
    def hash(self, plain_text_password: str) -> str: ...
    
    @abstractmethod
    def verify(self, plain_text_password: str, password_hash: str) -> bool: ...

class ITokenHandler(ABC):
    @abstractmethod
    def encode(self, payload: Payload) -> str: ...
    
    @abstractmethod
    def decode(self, token: str) -> Payload: ...

class IAuthenticator(ABC):
    @abstractmethod
    def authenticate(self, **credentials: Any) -> Optional[User]: ...

class IAuthorizer(ABC):
    @abstractmethod
    def check_permission(self, user_context: Payload, required_roles: List[Role]) -> bool: ...

# --- Concrete Implementations ---
class PBKDF2PasswordHasher(IPasswordHasher):
    def hash(self, plain_text_password: str) -> str:
        salt = os.urandom(16)
        key = hashlib.pbkdf2_hmac('sha256', plain_text_password.encode(), salt, 120000)
        return f"pbkdf2_sha256${salt.hex()}${key.hex()}"

    def verify(self, plain_text_password: str, password_hash: str) -> bool:
        try:
            name, salt_hex, key_hex = password_hash.split('$')
            if name != 'pbkdf2_sha256': return False
            salt = bytes.fromhex(salt_hex)
            key = bytes.fromhex(key_hex)
            new_key = hashlib.pbkdf2_hmac('sha256', plain_text_password.encode(), salt, 120000)
            return hmac.compare_digest(key, new_key)
        except:
            return False

class JWTHandler(ITokenHandler):
    def __init__(self, secret: bytes, algorithm: str = 'HS256', lifetime_seconds: int = 3600):
        self._secret = secret
        self._algorithm = algorithm
        self._lifetime = timedelta(seconds=lifetime_seconds)
        self._hash_algo = hashlib.sha256

    def _b64u_encode(self, b: bytes) -> bytes: return base64.urlsafe_b64encode(b).replace(b'=', b'')
    def _b64u_decode(self, b: bytes) -> bytes: return base64.urlsafe_b64decode(b + b'=' * (-len(b) % 4))

    def encode(self, payload: Payload) -> str:
        header = {"alg": self._algorithm, "typ": "JWT"}
        payload['iat'] = datetime.now(timezone.utc)
        payload['exp'] = payload['iat'] + self._lifetime
        
        # Convert datetime objects to unix timestamps
        json_payload = json.dumps({k: v.timestamp() if isinstance(v, datetime) else v for k, v in payload.items()}, separators=(",", ":")).encode()

        b64_header = self._b64u_encode(json.dumps(header, separators=(",", ":")).encode())
        b64_payload = self._b64u_encode(json_payload)
        to_sign = b64_header + b'.' + b64_payload
        signature = hmac.new(self._secret, to_sign, self._hash_algo).digest()
        return (to_sign + b'.' + self._b64u_encode(signature)).decode()

    def decode(self, token: str) -> Payload:
        try:
            head_b64, pay_b64, sig_b64 = token.encode().split(b'.')
            expected_sig = hmac.new(self._secret, head_b64 + b'.' + pay_b64, self._hash_algo).digest()
            if not hmac.compare_digest(self._b64u_decode(sig_b64), expected_sig):
                raise ValueError("Signature verification failed")
            
            payload = json.loads(self._b64u_decode(pay_b64))
            if payload['exp'] < datetime.now(timezone.utc).timestamp():
                raise ValueError("Token has expired")
            return payload
        except Exception as e:
            raise ValueError(f"Token is invalid: {e}")

class RoleBasedAuthorizer(IAuthorizer):
    def check_permission(self, user_context: Payload, required_roles: List[Role]) -> bool:
        user_role = Role(user_context.get('role'))
        if not user_role: return False
        # Admins are superusers
        if user_role == Role.ADMIN: return True
        return user_role in required_roles

# --- Application Core ---
class SecurityService:
    def __init__(self, token_handler: ITokenHandler, authorizer: IAuthorizer):
        self._token_handler = token_handler
        self._authorizer = authorizer
        self._revoked_tokens: Set[str] = set()

    def generate_token_for_user(self, user: User) -> str:
        payload = {"sub": str(user.id), "role": user.role.value}
        return self._token_handler.encode(payload)

    def revoke_token(self, token: str):
        self._revoked_tokens.add(token)

    def protected_endpoint(self, roles: List[Role]):
        def decorator(func):
            @wraps(func)
            def wrapper(token: str, *args, **kwargs):
                if token in self._revoked_tokens:
                    print("Access Denied: Token has been revoked.")
                    return None
                try:
                    payload = self._token_handler.decode(token)
                    if not self._authorizer.check_permission(payload, roles):
                        print(f"Access Denied: Requires one of roles {roles}, but user has role {payload.get('role')}.")
                        return None
                    return func(user_context=payload, *args, **kwargs)
                except ValueError as e:
                    print(f"Access Denied: {e}")
                    return None
            return wrapper
        return decorator

# --- Main Application Runner ---
if __name__ == '__main__':
    # 1. Dependency Injection Setup
    SECRET = os.urandom(32)
    password_hasher = PBKDF2PasswordHasher()
    token_handler = JWTHandler(secret=SECRET)
    authorizer = RoleBasedAuthorizer()
    security_service = SecurityService(token_handler, authorizer)

    # 2. In-memory data stores
    users_db: Dict[UserID, User] = {}
    posts_db: Dict[PostID, Post] = {}

    # 3. Populate with mock data
    admin_id = uuid.uuid4()
    users_db[admin_id] = User(id=admin_id, email="admin@example.com", password_hash=password_hasher.hash("adminpass"), role=Role.ADMIN, is_active=True)
    user_id = uuid.uuid4()
    users_db[user_id] = User(id=user_id, email="user@example.com", password_hash=password_hasher.hash("userpass"), role=Role.USER, is_active=True)
    print("--- System configured with DI and mock data ---")

    # 4. Define service functions with decorators
    def login(email, password):
        user = next((u for u in users_db.values() if u.email == email), None)
        if user and user.is_active and password_hasher.verify(password, user.password_hash):
            return security_service.generate_token_for_user(user)
        return None
    
    def simulate_oauth_login(provider_id: str):
        # In a real app, exchange code for user info
        oauth_email = f"{provider_id}@oauth.provider.com"
        user = next((u for u in users_db.values() if u.email == oauth_email), None)
        if not user:
            new_id = uuid.uuid4()
            user = User(id=new_id, email=oauth_email, password_hash="N/A", role=Role.USER, is_active=True)
            users_db[new_id] = user
        return security_service.generate_token_for_user(user)

    @security_service.protected_endpoint(roles=[Role.USER, Role.ADMIN])
    def create_post(token: str, title: str, content: str, user_context: Payload):
        post_id = uuid.uuid4()
        post = Post(id=post_id, user_id=uuid.UUID(user_context['sub']), title=title, content=content, status=PostStatus.DRAFT)
        posts_db[post_id] = post
        print(f"User {user_context['sub']} created post '{title}'")
        return post

    @security_service.protected_endpoint(roles=[Role.ADMIN])
    def publish_post(token: str, post_id: PostID, user_context: Payload):
        if post_id in posts_db:
            posts_db[post_id].status = PostStatus.PUBLISHED
            print(f"Admin {user_context['sub']} published post {post_id}")
            return posts_db[post_id]
        return None

    # 5. Execute scenario
    print("\n--- User Scenario ---")
    user_token = login("user@example.com", "userpass")
    print("User logged in.")
    my_post = create_post(token=user_token, title="My First Post", content="...")
    assert my_post is not None
    print("User trying to publish (should fail)...")
    publish_post(token=user_token, post_id=my_post.id)

    print("\n--- Admin Scenario ---")
    admin_token = login("admin@example.com", "adminpass")
    print("Admin logged in.")
    publish_post(token=admin_token, post_id=my_post.id)
    assert posts_db[my_post.id].status == PostStatus.PUBLISHED

    print("\n--- Session Management Scenario ---")
    security_service.revoke_token(user_token)
    print("User token revoked.")
    create_post(token=user_token, title="Post After Logout", content="This should fail.")
    
    print("\n--- OAuth Scenario ---")
    oauth_token = simulate_oauth_login("github_user_123")
    print("OAuth user logged in.")
    create_post(token=oauth_token, title="My OAuth Post", content="...")