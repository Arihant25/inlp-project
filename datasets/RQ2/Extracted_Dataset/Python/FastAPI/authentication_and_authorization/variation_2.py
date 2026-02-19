# Variation 2: Object-Oriented Service Layer Approach
# Encapsulates business logic into service classes for better organization and testability.

# Required packages:
# pip install "fastapi[all]" "python-jose[cryptography]" "passlib[bcrypt]"

import uuid
from datetime import datetime, timedelta, timezone
from enum import Enum
from typing import Dict, Optional

from fastapi import Depends, FastAPI, HTTPException, status
from fastapi.security import OAuth2PasswordBearer, OAuth2PasswordRequestForm
from jose import JWTError, jwt
from passlib.context import CryptContext
from pydantic import BaseModel, Field, EmailStr

# --- Constants & Config ---
JWT_SECRET_KEY = "a_very_secret_key_for_jwt_v2"
JWT_ALGORITHM = "HS256"
TOKEN_EXPIRE_MINUTES = 30
OAUTH2_SCHEME = OAuth2PasswordBearer(tokenUrl="auth/token")

# --- Enums ---
class UserRoleEnum(str, Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatusEnum(str, Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

# --- Pydantic Schemas ---
class UserSchema(BaseModel):
    id: uuid.UUID
    email: EmailStr
    role: UserRoleEnum
    is_active: bool
    created_at: datetime

    class Config:
        from_attributes = True

class PostSchema(BaseModel):
    id: uuid.UUID
    user_id: uuid.UUID
    title: str
    content: str
    status: PostStatusEnum

class TokenSchema(BaseModel):
    access_token: str
    token_type: str

# --- Domain Models (could be SQLAlchemy models) ---
class User:
    def __init__(self, id, email, password_hash, role, is_active, created_at):
        self.id = id
        self.email = email
        self.password_hash = password_hash
        self.role = role
        self.is_active = is_active
        self.created_at = created_at

# --- Mock Data Store ---
class DataStore:
    users: Dict[str, User] = {}
    posts: Dict[uuid.UUID, PostSchema] = {}

DB = DataStore()

def initialize_datastore():
    pwd_ctx = CryptContext(schemes=["bcrypt"], deprecated="auto")
    admin = User(
        id=uuid.uuid4(), email="admin@example.com",
        password_hash=pwd_ctx.hash("admin_secret_v2"),
        role=UserRoleEnum.ADMIN, is_active=True, created_at=datetime.now(timezone.utc)
    )
    user = User(
        id=uuid.uuid4(), email="user@example.com",
        password_hash=pwd_ctx.hash("user_secret_v2"),
        role=UserRoleEnum.USER, is_active=True, created_at=datetime.now(timezone.utc)
    )
    DB.users[admin.email] = admin
    DB.users[user.email] = user

# --- Service Layer ---
class AuthService:
    def __init__(self):
        self.pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")

    def verify_password(self, plain_password: str, hashed_password: str) -> bool:
        return self.pwd_context.verify(plain_password, hashed_password)

    def create_access_token(self, *, data: dict, expires_delta: timedelta = None) -> str:
        to_encode = data.copy()
        expire = datetime.now(timezone.utc) + (expires_delta or timedelta(minutes=TOKEN_EXPIRE_MINUTES))
        to_encode.update({"exp": expire})
        return jwt.encode(to_encode, JWT_SECRET_KEY, algorithm=JWT_ALGORITHM)

    def authenticate_user(self, email: str, password: str) -> Optional[User]:
        user = DB.users.get(email)
        if not user or not self.verify_password(password, user.password_hash):
            return None
        return user

class UserService:
    def get_user_by_email(self, email: str) -> Optional[User]:
        return DB.users.get(email)

class PostService:
    def create_post(self, user_id: uuid.UUID, title: str, content: str) -> PostSchema:
        post = PostSchema(user_id=user_id, title=title, content=content)
        DB.posts[post.id] = post
        return post

# --- Dependencies ---
auth_service = AuthService()
user_service = UserService()
post_service = PostService()

def get_current_active_user(token: str = Depends(OAUTH2_SCHEME)) -> User:
    credentials_exception = HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Could not validate credentials",
        headers={"WWW-Authenticate": "Bearer"},
    )
    try:
        payload = jwt.decode(token, JWT_SECRET_KEY, algorithms=[JWT_ALGORITHM])
        email: str = payload.get("sub")
        if email is None:
            raise credentials_exception
    except JWTError:
        raise credentials_exception
    
    user = user_service.get_user_by_email(email)
    if user is None or not user.is_active:
        raise credentials_exception
    return user

def require_admin_role(current_user: User = Depends(get_current_active_user)):
    if current_user.role != UserRoleEnum.ADMIN:
        raise HTTPException(status.HTTP_403_FORBIDDEN, detail="Admin privileges required")
    return current_user

# --- FastAPI Application ---
app = FastAPI(title="Variation 2: Service Layer")

@app.on_event("startup")
async def on_startup():
    initialize_datastore()

@app.post("/auth/token", response_model=TokenSchema)
def login(form_data: OAuth2PasswordRequestForm = Depends()):
    user = auth_service.authenticate_user(email=form_data.username, password=form_data.password)
    if not user:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Incorrect email or password",
        )
    access_token = auth_service.create_access_token(data={"sub": user.email})
    return {"access_token": access_token, "token_type": "bearer"}

@app.get("/profile/me", response_model=UserSchema)
def get_my_profile(current_user: User = Depends(get_current_active_user)):
    return current_user

@app.post("/content/posts", response_model=PostSchema, status_code=201)
def create_new_post(title: str, content: str, current_user: User = Depends(get_current_active_user)):
    return post_service.create_post(user_id=current_user.id, title=title, content=content)

@app.get("/admin/overview")
def get_admin_overview(admin_user: User = Depends(require_admin_role)):
    return {
        "message": f"Admin overview for {admin_user.email}",
        "data": {"users": len(DB.users), "posts": len(DB.posts)}
    }

# To run this app:
# 1. Save the code as a file (e.g., main.py).
# 2. Run `uvicorn main:app --reload`.