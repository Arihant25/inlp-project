# Variation 3: Class-Based Dependencies Approach
# Centralizes authorization logic into a reusable class dependency for cleaner endpoints.

# Required packages:
# pip install "fastapi[all]" "python-jose[cryptography]" "passlib[bcrypt]"

import uuid
from datetime import datetime, timedelta, timezone
from enum import Enum
from typing import Dict, Optional

from fastapi import Depends, FastAPI, HTTPException, status
from fastapi.security import OAuth2PasswordBearer
from jose import JWTError, jwt
from passlib.context import CryptContext
from pydantic import BaseModel, Field, EmailStr

# --- Configuration ---
APP_SECRET_KEY = "a_very_secret_key_for_jwt_v3"
JWT_ALGO = "HS256"
TOKEN_LIFETIME_MINUTES = 30

# --- Enums ---
class Role(str, Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class Status(str, Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

# --- Schemas ---
class User(BaseModel):
    id: uuid.UUID
    email: EmailStr
    password_hash: str
    role: Role
    is_active: bool
    created_at: datetime

class Post(BaseModel):
    id: uuid.UUID
    user_id: uuid.UUID
    title: str
    content: str
    status: Status

class UserPublic(BaseModel):
    id: uuid.UUID
    email: EmailStr
    role: Role

# --- Mock Database ---
class MockDB:
    _users: Dict[str, User] = {}
    _posts: Dict[uuid.UUID, Post] = {}

    @classmethod
    def get_user(cls, email: str) -> Optional[User]:
        return cls._users.get(email)

    @classmethod
    def add_user(cls, user: User):
        cls._users[user.email] = user
    
    @classmethod
    def add_post(cls, post: Post):
        cls._posts[post.id] = post

def setup_mock_db():
    pwd_context = CryptContext(schemes=["bcrypt"])
    MockDB.add_user(User(
        id=uuid.uuid4(), email="admin@example.com",
        password_hash=pwd_context.hash("admin_secret_v3"),
        role=Role.ADMIN, is_active=True, created_at=datetime.now(timezone.utc)
    ))
    MockDB.add_user(User(
        id=uuid.uuid4(), email="user@example.com",
        password_hash=pwd_context.hash("user_secret_v3"),
        role=Role.USER, is_active=True, created_at=datetime.now(timezone.utc)
    ))

# --- Security & Auth Logic ---
pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")
oauth2_scheme = OAuth2PasswordBearer(tokenUrl="/login")

class AuthHandler:
    def create_access_token(self, email: str) -> str:
        expires = datetime.now(timezone.utc) + timedelta(minutes=TOKEN_LIFETIME_MINUTES)
        payload = {"sub": email, "exp": expires}
        return jwt.encode(payload, APP_SECRET_KEY, algorithm=JWT_ALGO)

    def decode_token(self, token: str) -> Optional[str]:
        try:
            payload = jwt.decode(token, APP_SECRET_KEY, algorithms=[JWT_ALGO])
            return payload.get("sub")
        except JWTError:
            return None

# --- Centralized Authorizer Dependency ---
class Authorizer:
    def __init__(self, token: str = Depends(oauth2_scheme)):
        self.token = token
        self.credentials_exception = HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid authentication credentials",
            headers={"WWW-Authenticate": "Bearer"},
        )

    def get_current_user(self) -> User:
        auth_handler = AuthHandler()
        email = auth_handler.decode_token(self.token)
        if not email:
            raise self.credentials_exception
        
        user = MockDB.get_user(email)
        if not user or not user.is_active:
            raise self.credentials_exception
        
        return user

    def require_role(self, required_role: Role) -> User:
        user = self.get_current_user()
        if user.role != required_role:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail=f"Requires {required_role.value} role"
            )
        return user

    def require_user(self) -> User:
        return self.get_current_user()

    def require_admin(self) -> User:
        return self.require_role(Role.ADMIN)

# --- FastAPI App ---
app = FastAPI(title="Variation 3: Class-Based Dependencies")

@app.on_event("startup")
async def startup():
    setup_mock_db()

@app.post("/login")
def login(email: EmailStr, password: str):
    user = MockDB.get_user(email)
    if not user or not pwd_context.verify(password, user.password_hash):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, detail="Invalid credentials")
    
    auth_handler = AuthHandler()
    access_token = auth_handler.create_access_token(email=user.email)
    return {"access_token": access_token, "token_type": "bearer"}

@app.get("/me", response_model=UserPublic)
def get_me(auth: Authorizer = Depends()):
    current_user = auth.require_user()
    return current_user

@app.post("/posts", status_code=201)
def create_post(title: str, content: str, auth: Authorizer = Depends()):
    current_user = auth.require_user()
    new_post = Post(
        id=uuid.uuid4(),
        user_id=current_user.id,
        title=title,
        content=content,
        status=Status.DRAFT
    )
    MockDB.add_post(new_post)
    return {"message": "Post created successfully", "post_id": new_post.id}

@app.get("/admin/stats")
def get_admin_stats(auth: Authorizer = Depends()):
    admin_user = auth.require_admin()
    return {
        "message": f"Hello Admin {admin_user.email}",
        "stats": {
            "total_users": len(MockDB._users),
            "total_posts": len(MockDB._posts)
        }
    }

# To run this app:
# 1. Save the code as a file (e.g., main.py).
# 2. Run `uvicorn main:app --reload`.