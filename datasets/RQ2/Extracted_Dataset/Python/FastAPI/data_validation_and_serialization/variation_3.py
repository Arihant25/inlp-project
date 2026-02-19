# Variation 3: The "Class-Based & Organized" Developer
# Style: Organizes endpoints into resource-like classes using APIRouter.
# Features: Class-based routing, root_validator for cross-field validation.

# Required packages: fastapi uvicorn pydantic lxml
import uvicorn
from fastapi import FastAPI, APIRouter, HTTPException, Body, status
from pydantic import BaseModel, Field, EmailStr, UUID4, root_validator
from typing import List, Dict
from uuid import UUID, uuid4
from datetime import datetime
from enum import Enum
from lxml import etree
from fastapi.responses import Response

# --- Domain Enums ---
class UserRoleEnum(str, Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatusEnum(str, Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

# --- Mock ORM Models (for simulation) ---
class UserORM:
    def __init__(self, **data):
        self.id = data.get("id", uuid4())
        self.email = data["email"]
        self.password_hash = f"hashed_{data['password']}"
        self.role = data.get("role", UserRoleEnum.USER)
        self.is_active = data.get("is_active", True)
        self.created_at = data.get("created_at", datetime.utcnow())

class PostORM:
    def __init__(self, **data):
        self.id = data.get("id", uuid4())
        self.user_id = data["user_id"]
        self.title = data["title"]
        self.content = data["content"]
        self.status = data.get("status", PostStatusEnum.DRAFT)

# --- Mock Database Layer ---
class Database:
    users: Dict[UUID, UserORM] = {}
    posts: Dict[UUID, PostORM] = {}

db = Database()

# --- Pydantic Schemas ---
class UserCreateIn(BaseModel):
    email: EmailStr
    password: str = Field(..., min_length=8)

class UserOut(BaseModel):
    id: UUID4
    email: EmailStr
    role: UserRoleEnum
    is_active: bool
    created_at: datetime

    class Config:
        from_attributes = True

class PostCreateIn(BaseModel):
    user_id: UUID4
    title: str = Field(..., max_length=120)
    content: str
    status: PostStatusEnum = PostStatusEnum.DRAFT

    @root_validator(skip_on_failure=True)
    def title_cannot_be_clickbait_if_published(cls, values):
        title, status = values.get('title', ''), values.get('status')
        if status == PostStatusEnum.PUBLISHED and "shocking" in title.lower():
            raise ValueError("Published posts cannot have 'shocking' in the title.")
        return values

class PostOut(BaseModel):
    id: UUID4
    user_id: UUID4
    title: str
    status: PostStatusEnum

    class Config:
        from_attributes = True

# --- Resource Class for Users ---
user_router = APIRouter(prefix="/users", tags=["Users (Class-Based)"])

@user_router.post("/", response_model=UserOut, status_code=status.HTTP_201_CREATED)
class UserCreator:
    def __call__(self, user_data: UserCreateIn) -> UserORM:
        if any(u.email == user_data.email for u in db.users.values()):
            raise HTTPException(status.HTTP_409_CONFLICT, "Email already exists")
        new_user = UserORM(**user_data.model_dump())
        db.users[new_user.id] = new_user
        return new_user

@user_router.get("/{user_id}", response_model=UserOut)
class UserFinder:
    def __call__(self, user_id: UUID) -> UserORM:
        user = db.users.get(user_id)
        if not user:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "User not found")
        return user

# --- Resource Class for Posts ---
post_router = APIRouter(prefix="/posts", tags=["Posts (Class-Based)"])

@post_router.post("/", response_model=PostOut, status_code=status.HTTP_201_CREATED)
class PostCreator:
    def __call__(self, post_data: PostCreateIn) -> PostORM:
        if post_data.user_id not in db.users:
            raise HTTPException(status.HTTP_400_BAD_REQUEST, "Author does not exist")
        new_post = PostORM(**post_data.model_dump())
        db.posts[new_post.id] = new_post
        return new_post

@post_router.get("/{post_id}/xml")
class PostXmlExporter:
    def _to_xml(self, post: PostORM) -> bytes:
        root = etree.Element("post")
        etree.SubElement(root, "id").text = str(post.id)
        etree.SubElement(root, "title").text = post.title
        etree.SubElement(root, "status").text = post.status.value
        return etree.tostring(root, encoding="utf-8", xml_declaration=True, pretty_print=True)

    def __call__(self, post_id: UUID) -> Response:
        post = db.posts.get(post_id)
        if not post:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "Post not found")
        xml_content = self._to_xml(post)
        return Response(content=xml_content, media_type="application/xml")

# --- Main App ---
app = FastAPI(title="Class-Based & Organized API")
app.include_router(user_router)
app.include_router(post_router)

if __name__ == "__main__":
    # Seed data
    test_user = UserORM(email="test@dev.com", password="password123")
    db.users[test_user.id] = test_user
    uvicorn.run(app, host="0.0.0.0", port=8002)