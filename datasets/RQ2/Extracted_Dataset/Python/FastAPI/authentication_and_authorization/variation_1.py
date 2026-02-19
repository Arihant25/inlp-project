# Variation 1: Functional/Procedural Approach
# A common, straightforward style often seen in tutorials.
# Logic is organized into functions grouped by concern.

# Required packages:
# pip install "fastapi[all]" "python-jose[cryptography]" "passlib[bcrypt]"

import uuid
from datetime import datetime, timedelta, timezone
from enum import Enum
from typing import List, Optional

from fastapi import Depends, FastAPI, HTTPException, status
from fastapi.security import OAuth2PasswordBearer, OAuth2PasswordRequestForm
from jose import JWTError, jwt
from passlib.context import CryptContext
from pydantic import BaseModel, Field, EmailStr

# --- Configuration ---
SECRET_KEY = "a_very_secret_key_for_jwt"
ALGORITHM = "HS256"
ACCESS_TOKEN_EXPIRE_MINUTES = 30

# --- Security Utilities ---
pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")
oauth2_scheme = OAuth2PasswordBearer(tokenUrl="token")

# --- Enums ---
class UserRole(str, Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(str, Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

# --- Pydantic Models (Schemas) ---
class UserBase(BaseModel):
    email: EmailStr
    is_active: bool = True
    role: UserRole = UserRole.USER

class UserInDB(UserBase):
    id: uuid.UUID = Field(default_factory=uuid.uuid4)
    password_hash: str
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))

class UserPublic(UserBase):
    id: uuid.UUID
    created_at: datetime

class Post(BaseModel):
    id: uuid.UUID = Field(default_factory=uuid.uuid4)
    user_id: uuid.UUID
    title: str
    content: str
    status: PostStatus = PostStatus.DRAFT

class Token(BaseModel):
    access_token: str
    token_type: str

class TokenData(BaseModel):
    email: Optional[str] = None

# --- Mock Database ---
MOCK_DB_USERS = {}
MOCK_DB_POSTS = {}

def populate_mock_db():
    admin_user = UserInDB(
        id=uuid.uuid4(),
        email="admin@example.com",
        password_hash=pwd_context.hash("admin_secret"),
        role=UserRole.ADMIN,
        is_active=True
    )
    regular_user = UserInDB(
        id=uuid.uuid4(),
        email="user@example.com",
        password_hash=pwd_context.hash("user_secret"),
        role=UserRole.USER,
        is_active=True
    )
    MOCK_DB_USERS[admin_user.email] = admin_user
    MOCK_DB_USERS[regular_user.email] = regular_user

# --- Authentication Functions ---
def verify_password(plain_password: str, hashed_password: str) -> bool:
    return pwd_context.verify(plain_password, hashed_password)

def get_user_by_email(email: str) -> Optional[UserInDB]:
    return MOCK_DB_USERS.get(email)

def create_access_token(data: dict, expires_delta: Optional[timedelta] = None) -> str:
    to_encode = data.copy()
    if expires_delta:
        expire = datetime.now(timezone.utc) + expires_delta
    else:
        expire = datetime.now(timezone.utc) + timedelta(minutes=15)
    to_encode.update({"exp": expire})
    encoded_jwt = jwt.encode(to_encode, SECRET_KEY, algorithm=ALGORITHM)
    return encoded_jwt

# --- Dependency Functions ---
async def get_current_user(token: str = Depends(oauth2_scheme)) -> UserInDB:
    credentials_exception = HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Could not validate credentials",
        headers={"WWW-Authenticate": "Bearer"},
    )
    try:
        payload = jwt.decode(token, SECRET_KEY, algorithms=[ALGORITHM])
        email: str = payload.get("sub")
        if email is None:
            raise credentials_exception
        token_data = TokenData(email=email)
    except JWTError:
        raise credentials_exception
    
    user = get_user_by_email(email=token_data.email)
    if user is None or not user.is_active:
        raise credentials_exception
    return user

async def get_current_admin_user(current_user: UserInDB = Depends(get_current_user)) -> UserInDB:
    if current_user.role != UserRole.ADMIN:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="The user doesn't have enough privileges"
        )
    return current_user

# --- FastAPI App and Routes ---
app = FastAPI(title="Variation 1: Functional Approach")

@app.on_event("startup")
async def startup_event():
    populate_mock_db()

@app.post("/token", response_model=Token)
async def login_for_access_token(form_data: OAuth2PasswordRequestForm = Depends()):
    user = get_user_by_email(email=form_data.username)
    if not user or not verify_password(form_data.password, user.password_hash):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Incorrect email or password",
            headers={"WWW-Authenticate": "Bearer"},
        )
    access_token_expires = timedelta(minutes=ACCESS_TOKEN_EXPIRE_MINUTES)
    access_token = create_access_token(
        data={"sub": user.email}, expires_delta=access_token_expires
    )
    return {"access_token": access_token, "token_type": "bearer"}

@app.get("/users/me", response_model=UserPublic)
async def read_users_me(current_user: UserInDB = Depends(get_current_user)):
    return current_user

@app.post("/posts", response_model=Post, status_code=status.HTTP_201_CREATED)
async def create_post(title: str, content: str, current_user: UserInDB = Depends(get_current_user)):
    new_post = Post(user_id=current_user.id, title=title, content=content)
    MOCK_DB_POSTS[new_post.id] = new_post
    return new_post

@app.get("/admin/dashboard")
async def admin_dashboard(current_admin: UserInDB = Depends(get_current_admin_user)):
    return {
        "message": f"Welcome Admin {current_admin.email}!",
        "user_count": len(MOCK_DB_USERS),
        "post_count": len(MOCK_DB_POSTS)
    }

# To run this app:
# 1. Save the code as a file (e.g., main.py).
# 2. Run `uvicorn main:app --reload`.