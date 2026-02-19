# Variation 1: The "Standard & Explicit" Developer
# Style: Follows official tutorials closely, with clear separation of concerns.
# Features: Standard Pydantic validation, @validator, explicit XML handling.

# Required packages: fastapi uvicorn pydantic pydantic-extra-types lxml
import uvicorn
from fastapi import FastAPI, Request, Response, HTTPException, status
from pydantic import BaseModel, Field, EmailStr, validator, UUID4
from pydantic_extra_types.phone_numbers import PhoneNumber
from typing import List, Optional
from uuid import uuid4
from datetime import datetime
from enum import Enum
from lxml import etree

# --- Enums ---
class UserRole(str, Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(str, Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

# --- Mock Database ---
DB_USERS = {}
DB_POSTS = {}

# --- Schemas (DTOs) ---
# Base models
class UserBase(BaseModel):
    email: EmailStr
    phone_number: PhoneNumber = Field(..., description="International phone number format, e.g., +14155552671")
    is_active: bool = True

# Schema for creating a user (input)
class UserCreate(UserBase):
    password: str = Field(..., min_length=8)

    @validator('password')
    def password_must_contain_special_char(cls, v):
        if not any(char in '!@#$%^&*()' for char in v):
            raise ValueError('Password must contain at least one special character.')
        return v

# Schema for user representation (output)
class User(UserBase):
    id: UUID4
    role: UserRole = UserRole.USER
    created_at: datetime

    class Config:
        from_attributes = True

# Schema for creating a post (input)
class PostCreate(BaseModel):
    user_id: UUID4
    title: str = Field(..., min_length=5, max_length=100)
    content: str

# Schema for post representation (output)
class Post(BaseModel):
    id: UUID4
    user_id: UUID4
    title: str
    content: str
    status: PostStatus = PostStatus.DRAFT

    class Config:
        from_attributes = True

# --- FastAPI App Initialization ---
app = FastAPI(
    title="Data Validation & Serialization Demo",
    description="Standard and Explicit Implementation"
)

# --- API Endpoints ---
@app.post("/users/", response_model=User, status_code=status.HTTP_201_CREATED, tags=["Users"])
def create_user(user_in: UserCreate):
    """
    Create a new user.
    - Validates email and phone number format.
    - Custom validator for password complexity.
    - Serializes the created user object to JSON.
    """
    if user_in.email in [u['email'] for u in DB_USERS.values()]:
        raise HTTPException(status_code=400, detail="Email already registered")

    user_id = uuid4()
    # In a real app, hash the password. This is a mock.
    password_hash = f"hashed_{user_in.password}"
    
    new_user = {
        "id": user_id,
        "email": user_in.email,
        "phone_number": user_in.phone_number,
        "password_hash": password_hash,
        "is_active": user_in.is_active,
        "role": UserRole.USER,
        "created_at": datetime.utcnow()
    }
    DB_USERS[user_id] = new_user
    return new_user

@app.get("/users/{user_id}", response_model=User, tags=["Users"])
def get_user(user_id: UUID4):
    """
    Retrieve a user by ID.
    - Coerces path parameter to UUID.
    - Serializes user data to JSON.
    """
    user = DB_USERS.get(user_id)
    if not user:
        raise HTTPException(status_code=404, detail="User not found")
    return user

@app.post("/posts/", response_model=Post, status_code=status.HTTP_201_CREATED, tags=["Posts"])
def create_post(post_in: PostCreate):
    """
    Create a new post.
    - Validates required fields, title length.
    - Coerces user_id to UUID.
    """
    if post_in.user_id not in DB_USERS:
        raise HTTPException(status_code=404, detail=f"User with id {post_in.user_id} not found")
    
    post_id = uuid4()
    new_post = {
        "id": post_id,
        "user_id": post_in.user_id,
        "title": post_in.title,
        "content": post_in.content,
        "status": PostStatus.DRAFT
    }
    DB_POSTS[post_id] = new_post
    return new_post

@app.get("/posts/{post_id}/xml", tags=["Posts"])
def get_post_as_xml(post_id: UUID4):
    """
    Retrieve a post and return it as XML.
    - Manually generates an XML response.
    """
    post = DB_POSTS.get(post_id)
    if not post:
        raise HTTPException(status_code=404, detail="Post not found")

    # Generate XML
    root = etree.Element("post")
    etree.SubElement(root, "id").text = str(post["id"])
    etree.SubElement(root, "user_id").text = str(post["user_id"])
    etree.SubElement(root, "title").text = post["title"]
    etree.SubElement(root, "content").text = post["content"]
    etree.SubElement(root, "status").text = post["status"].value

    xml_data = etree.tostring(root, pretty_print=True, xml_declaration=True, encoding='UTF-8')
    
    return Response(content=xml_data, media_type="application/xml")

@app.post("/posts/from_xml", tags=["Posts"])
async def create_post_from_xml(request: Request):
    """
    Create a post from an XML payload.
    - Manually parses an XML request body.
    """
    content_type = request.headers.get('content-type')
    if 'application/xml' not in content_type:
        raise HTTPException(status_code=415, detail="Unsupported Media Type, must be application/xml")
    
    body = await request.body()
    try:
        root = etree.fromstring(body)
        post_data = {
            "user_id": root.findtext("user_id"),
            "title": root.findtext("title"),
            "content": root.findtext("content")
        }
        # Use Pydantic model for validation
        validated_post = PostCreate(**post_data)
        return create_post(validated_post)
    except etree.XMLSyntaxError:
        raise HTTPException(status_code=400, detail="Invalid XML format")
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))

if __name__ == "__main__":
    # Mock some data for testing
    admin_id = uuid4()
    DB_USERS[admin_id] = {
        "id": admin_id, "email": "admin@example.com", "phone_number": "+15555555555",
        "password_hash": "hashed_password", "is_active": True, "role": UserRole.ADMIN,
        "created_at": datetime.utcnow()
    }
    post_id = uuid4()
    DB_POSTS[post_id] = {
        "id": post_id, "user_id": admin_id, "title": "My First Post",
        "content": "This is the content.", "status": PostStatus.PUBLISHED
    }
    uvicorn.run(app, host="0.0.0.0", port=8000)