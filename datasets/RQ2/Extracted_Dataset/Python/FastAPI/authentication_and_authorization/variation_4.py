# Variation 4: Modular APIRouter Approach
# Organizes the application into feature-based modules using APIRouter.

# Required packages:
# pip install "fastapi[all]" "python-jose[cryptography]" "passlib[bcrypt]"

import uuid
from datetime import datetime, timedelta, timezone
from enum import Enum
from typing import Dict, Optional

from fastapi import APIRouter, Depends, FastAPI, HTTPException, status
from fastapi.security import OAuth2PasswordBearer, OAuth2PasswordRequestForm
from jose import JWTError, jwt
from passlib.context import CryptContext
from pydantic import BaseModel, Field, EmailStr

# --- Core Configuration (core/config.py) ---
class Settings:
    SECRET_KEY: str = "a_very_secret_key_for_jwt_v4"
    ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 30

settings = Settings()

# --- Domain Models (domain/models.py) ---
class UserRole(str, Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(str, Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

class User(BaseModel):
    id: uuid.UUID = Field(default_factory=uuid.uuid4)
    email: EmailStr
    password_hash: str
    role: UserRole = UserRole.USER
    is_active: bool = True
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))

class Post(BaseModel):
    id: uuid.UUID = Field(default_factory=uuid.uuid4)
    user_id: uuid.UUID
    title: str
    content: str
    status: PostStatus = PostStatus.DRAFT

# --- Schemas (domain/schemas.py) ---
class UserPublic(BaseModel):
    id: uuid.UUID
    email: EmailStr
    role: UserRole

class Token(BaseModel):
    access_token: str
    token_type: str

class TokenData(BaseModel):
    email: Optional[str] = None

# --- Mock Database (db/mock.py) ---
db_users: Dict[str, User] = {}
db_posts: Dict[uuid.UUID, Post] = {}

def init_db():
    pwd_context = CryptContext(schemes=["bcrypt"])
    admin = User(email="admin@example.com", password_hash=pwd_context.hash("admin_secret_v4"), role=UserRole.ADMIN)
    user = User(email="user@example.com", password_hash=pwd_context.hash("user_secret_v4"))
    db_users[admin.email] = admin
    db_users[user.email] = user

# --- Security Module (common/security.py) ---
pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")
oauth2_scheme = OAuth2PasswordBearer(tokenUrl="auth/login")

def verify_password(plain_password, hashed_password):
    return pwd_context.verify(plain_password, hashed_password)

def create_access_token(data: dict):
    to_encode = data.copy()
    expire = datetime.now(timezone.utc) + timedelta(minutes=settings.ACCESS_TOKEN_EXPIRE_MINUTES)
    to_encode.update({"exp": expire})
    return jwt.encode(to_encode, settings.SECRET_KEY, algorithm=settings.ALGORITHM)

def get_current_user(token: str = Depends(oauth2_scheme)) -> User:
    exc = HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Could not validate credentials",
        headers={"WWW-Authenticate": "Bearer"},
    )
    try:
        payload = jwt.decode(token, settings.SECRET_KEY, algorithms=[settings.ALGORITHM])
        email: str = payload.get("sub")
        if email is None:
            raise exc
    except JWTError:
        raise exc
    
    user = db_users.get(email)
    if user is None or not user.is_active:
        raise exc
    return user

def require_admin(current_user: User = Depends(get_current_user)) -> User:
    if current_user.role != UserRole.ADMIN:
        raise HTTPException(status.HTTP_403_FORBIDDEN, detail="Admin access required")
    return current_user

# --- Authentication Router (routers/auth.py) ---
auth_router = APIRouter(prefix="/auth", tags=["Authentication"])

@auth_router.post("/login", response_model=Token)
async def login(form_data: OAuth2PasswordRequestForm = Depends()):
    user = db_users.get(form_data.username)
    if not user or not verify_password(form_data.password, user.password_hash):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, detail="Incorrect email or password")
    
    access_token = create_access_token(data={"sub": user.email})
    return {"access_token": access_token, "token_type": "bearer"}

# --- Users Router (routers/users.py) ---
users_router = APIRouter(prefix="/users", tags=["Users"])

@users_router.get("/me", response_model=UserPublic)
async def get_self(current_user: User = Depends(get_current_user)):
    return current_user

# --- Posts Router (routers/posts.py) ---
posts_router = APIRouter(prefix="/posts", tags=["Posts"])

@posts_router.post("/", response_model=Post, status_code=201)
async def create_new_post(title: str, content: str, current_user: User = Depends(get_current_user)):
    post = Post(user_id=current_user.id, title=title, content=content)
    db_posts[post.id] = post
    return post

# --- Admin Router (routers/admin.py) ---
admin_router = APIRouter(prefix="/admin", tags=["Admin"], dependencies=[Depends(require_admin)])

@admin_router.get("/health")
async def admin_health_check(admin: User = Depends(require_admin)):
    return {
        "status": "ok",
        "admin_user": admin.email,
        "db_counts": {"users": len(db_users), "posts": len(db_posts)}
    }

# --- Main Application (main.py) ---
app = FastAPI(title="Variation 4: Modular APIRouter")

@app.on_event("startup")
async def on_startup():
    init_db()

app.include_router(auth_router)
app.include_router(users_router)
app.include_router(posts_router)
app.include_router(admin_router)

@app.get("/")
def root():
    return {"message": "API is running"}

# To run this app:
# 1. Save the code as a file (e.g., main.py).
# 2. Run `uvicorn main:app --reload`.