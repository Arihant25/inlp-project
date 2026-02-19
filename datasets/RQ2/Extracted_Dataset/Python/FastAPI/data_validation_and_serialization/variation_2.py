# Variation 2: The "Functional & Concise" Developer
# Style: Prefers a single-file, functional approach with modern type hints.
# Features: Annotated for validation, custom exception handler, custom XML response class.

# Required packages: fastapi uvicorn pydantic pydantic-extra-types lxml
import uvicorn
from fastapi import FastAPI, Request, status, Depends
from fastapi.responses import Response
from fastapi.exceptions import RequestValidationError
from fastapi.encoders import jsonable_encoder
from pydantic import BaseModel, EmailStr, Field, field_validator, UUID4
from typing import Annotated, List
from uuid import uuid4
from datetime import datetime
from enum import Enum
from lxml import etree
import re

# --- Enums & Constants ---
class UserRole(str, Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(str, Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

PHONE_REGEX = re.compile(r"^\+[1-9]\d{1,14}$")

# --- Custom XML Response ---
class XMLResponse(Response):
    media_type = "application/xml"

# --- App and Error Handling ---
app = FastAPI(title="Concise & Functional API")

@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request: Request, exc: RequestValidationError):
    """Custom formatter for validation errors."""
    error_details = []
    for error in exc.errors():
        error_details.append({
            "field": ".".join(map(str, error["loc"][1:])), # remove 'body'
            "message": error["msg"]
        })
    return XMLResponse(
        status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
        content=jsonable_encoder({"status": "error", "details": error_details})
    )

# --- Schemas with Annotated ---
class UserCreateSchema(BaseModel):
    email: EmailStr
    phone: Annotated[str, Field(pattern=PHONE_REGEX)]
    password: str

    @field_validator('password')
    @classmethod
    def password_complexity(cls, v: str) -> str:
        if len(v) < 8:
            raise ValueError("Password must be at least 8 characters long")
        return v

class UserResponseSchema(BaseModel):
    id: UUID4
    email: EmailStr
    role: UserRole
    is_active: bool
    created_at: datetime

class PostCreateSchema(BaseModel):
    user_id: UUID4
    title: Annotated[str, Field(min_length=3, max_length=50)]
    content: str

class PostResponseSchema(BaseModel):
    id: UUID4
    title: str
    status: PostStatus

# --- Mock DB & Dependencies ---
MOCK_DB = {"users": {}, "posts": {}}

def get_db():
    return MOCK_DB

# --- API Routes ---
@app.post("/users", response_model=UserResponseSchema, status_code=201, tags=["Users"])
def register_user(user_data: UserCreateSchema, db: dict = Depends(get_db)):
    user_id = uuid4()
    new_user = {
        "id": user_id,
        "email": user_data.email,
        "password_hash": f"hashed_{user_data.password}",
        "role": UserRole.USER,
        "is_active": True,
        "created_at": datetime.utcnow()
    }
    db["users"][user_id] = new_user
    return new_user

@app.post("/posts", response_model=PostResponseSchema, status_code=201, tags=["Posts"])
def submit_post(post_data: PostCreateSchema, db: dict = Depends(get_db)):
    if post_data.user_id not in db["users"]:
        # This will be caught by the default HTTPException handler, not our custom one
        raise status.HTTP_404_NOT_FOUND(detail="Author not found")
    post_id = uuid4()
    new_post = {
        "id": post_id,
        "user_id": post_data.user_id,
        "title": post_data.title,
        "content": post_data.content,
        "status": PostStatus.DRAFT
    }
    db["posts"][post_id] = new_post
    return new_post

async def parse_xml_body(request: Request) -> dict:
    """Dependency to parse XML from request body."""
    content_type = request.headers.get('content-type')
    if not content_type or 'application/xml' not in content_type:
        raise HTTPException(status.HTTP_415_UNSUPPORTED_MEDIA_TYPE, "Requires application/xml")
    try:
        body = await request.body()
        root = etree.fromstring(body)
        return {child.tag: child.text for child in root}
    except etree.XMLSyntaxError:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Invalid XML payload")

@app.post("/posts/from-xml", response_model=PostResponseSchema, tags=["Posts"])
def submit_post_from_xml(
    parsed_xml: dict = Depends(parse_xml_body),
    db: dict = Depends(get_db)
):
    # Pydantic model validates the dictionary parsed from XML
    post_data = PostCreateSchema(**parsed_xml)
    return submit_post(post_data, db)

@app.get("/users/{user_id}/xml", response_class=XMLResponse, tags=["Users"])
def get_user_xml(user_id: UUID4, db: dict = Depends(get_db)):
    user = db["users"].get(user_id)
    if not user:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "User not found")
    
    root = etree.Element("user", id=str(user["id"]))
    etree.SubElement(root, "email").text = user["email"]
    etree.SubElement(root, "role").text = user["role"].value
    etree.SubElement(root, "is_active").text = str(user["is_active"]).lower()
    etree.SubElement(root, "created_at").text = user["created_at"].isoformat()
    
    return XMLResponse(content=etree.tostring(root, pretty_print=True))

if __name__ == "__main__":
    # Mock data
    test_user_id = uuid4()
    MOCK_DB["users"][test_user_id] = {
        "id": test_user_id, "email": "test@example.com", "password_hash": "...",
        "role": UserRole.USER, "is_active": True, "created_at": datetime.utcnow()
    }
    uvicorn.run(app, host="0.0.0.0", port=8001)