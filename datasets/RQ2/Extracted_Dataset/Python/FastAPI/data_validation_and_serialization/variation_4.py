# Variation 4: The "Modern & Asynchronous" Developer
# Style: Leverages modern Python/Pydantic V2 features, fully async.
# Features: Pydantic V2 @field_validator, custom error formatting, bulk operations.

# Required packages: fastapi uvicorn pydantic lxml
import uvicorn
from fastapi import FastAPI, Request, status
from fastapi.responses import JSONResponse
from pydantic import (
    BaseModel, EmailStr, Field, UUID4, field_validator, model_validator,
    ConfigDict, ValidationError
)
from typing import List
from uuid import uuid4
from datetime import datetime, timezone
from enum import Enum
from lxml import etree

# --- Enums ---
class UserRole(str, Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(str, Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

# --- Mock Async Database ---
class AsyncDB:
    _users = {}
    _posts = {}

    async def add_user(self, user_data: dict):
        user_id = user_data['id']
        self._users[user_id] = user_data
        return user_data

    async def get_user_by_email(self, email: str):
        for user in self._users.values():
            if user['email'] == email:
                return user
        return None
    
    async def get_user_by_id(self, user_id: UUID4):
        return self._users.get(user_id)

    async def add_post(self, post_data: dict):
        post_id = post_data['id']
        self._posts[post_id] = post_data
        return post_data

db = AsyncDB()

# --- Pydantic V2 Schemas ---
class UserCreate(BaseModel):
    email: EmailStr
    password: str

    @field_validator('password', mode='after')
    @classmethod
    def validate_password(cls, p: str) -> str:
        if len(p) < 10:
            raise ValueError('Password must be at least 10 characters')
        if not any(c.isdigit() for c in p):
            raise ValueError('Password must contain a number')
        return f"hashed_for_safety_{p}" # Simulate hashing

class UserPublic(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    
    id: UUID4
    email: EmailStr
    role: UserRole
    created_at: datetime

class PostBase(BaseModel):
    title: str = Field(..., min_length=1)
    content: str

class PostCreate(PostBase):
    user_id: UUID4

    @model_validator(mode='before')
    @classmethod
    def check_title_and_content_not_same(cls, data):
        if isinstance(data, dict) and data.get('title') == data.get('content'):
            raise ValueError('Title and content cannot be the same')
        return data

class PostPublic(PostBase):
    model_config = ConfigDict(from_attributes=True)
    
    id: UUID4
    status: PostStatus

# --- App and Custom Error Handler ---
app = FastAPI(title="Modern & Async API")

@app.exception_handler(ValidationError)
async def pydantic_validation_exception_handler(request: Request, exc: ValidationError):
    """Custom handler to format Pydantic's ValidationError."""
    errors = []
    for error in exc.errors():
        field = ".".join(str(loc) for loc in error['loc'])
        errors.append({"error_code": "VALIDATION_FAILED", "field": field, "message": error['msg']})
    return JSONResponse(
        status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
        content={"detail": errors},
    )

# --- API Endpoints ---
@app.post("/users/bulk", response_model=List[UserPublic], status_code=status.HTTP_201_CREATED, tags=["Users"])
async def create_users_bulk(users_in: List[UserCreate]):
    """
    Create multiple users in a single request.
    - Validates a list of Pydantic models.
    """
    created_users = []
    for user_in in users_in:
        if await db.get_user_by_email(user_in.email):
            # In a real bulk operation, you might collect errors and continue
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail=f"User with email {user_in.email} already exists."
            )
        
        new_user_data = {
            "id": uuid4(),
            "email": user_in.email,
            "password_hash": user_in.password, # Already "hashed" by validator
            "role": UserRole.USER,
            "is_active": True,
            "created_at": datetime.now(timezone.utc)
        }
        created = await db.add_user(new_user_data)
        created_users.append(created)
    return created_users

@app.post("/posts/from-xml-async", response_model=PostPublic, tags=["Posts"])
async def create_post_from_xml(request: Request):
    """
    Parses XML and validates with Pydantic V2 model.
    """
    if request.headers.get('content-type') != 'application/xml':
        raise HTTPException(status.HTTP_415_UNSUPPORTED_MEDIA_TYPE, "Expected application/xml")
    
    body = await request.body()
    try:
        root = etree.fromstring(body)
        data = {
            "user_id": root.findtext("user_id"),
            "title": root.findtext("title"),
            "content": root.findtext("content"),
        }
        # Pydantic V2 model handles validation and type coercion
        post_model = PostCreate.model_validate(data)
    except etree.XMLSyntaxError:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Malformed XML")
    except ValidationError as e:
        # Re-raise to be caught by our custom handler
        raise e

    if not await db.get_user_by_id(post_model.user_id):
        raise HTTPException(status.HTTP_404_NOT_FOUND, "User not found")

    new_post_data = {
        "id": uuid4(),
        **post_model.model_dump(),
        "status": PostStatus.DRAFT
    }
    created_post = await db.add_post(new_post_data)
    return created_post

@app.get("/users/{user_id}", response_model=UserPublic, tags=["Users"])
async def get_user(user_id: UUID4):
    """
    Retrieve a user by their ID.
    - Path parameter is automatically coerced to UUID.
    """
    user = await db.get_user_by_id(user_id)
    if not user:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "User not found")
    return user

if __name__ == "__main__":
    # Seed data
    import asyncio
    async def seed():
        user_id = uuid4()
        await db.add_user({
            "id": user_id, "email": "seed@example.com", "password_hash": "...",
            "role": UserRole.ADMIN, "is_active": True, "created_at": datetime.now(timezone.utc)
        })
    asyncio.run(seed())
    uvicorn.run(app, host="0.0.0.0", port=8003)