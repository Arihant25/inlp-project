import uuid
import datetime
import enum
import re
from typing import Optional
from xml.etree import ElementTree as ET

from flask import Flask, request, jsonify, Response
from pydantic import BaseModel, Field, EmailStr, validator, ValidationError

# --- App Setup ---
app = Flask(__name__)
app.config["JSON_SORT_KEYS"] = False

# --- Mock In-Memory Store ---
DATA_STORE = {
    "users": {},
    "posts": {}
}

# --- Domain Enums ---
class UserRoleEnum(str, enum.Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatusEnum(str, enum.Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

# --- Pydantic Models for Validation & Serialization ---
class UserCreate(BaseModel):
    email: EmailStr
    password: str
    role: UserRoleEnum = UserRoleEnum.USER
    is_active: bool = True

    @validator('password')
    def password_complexity(cls, v):
        """Custom validator for password strength."""
        if len(v) < 8:
            raise ValueError('Password must be at least 8 characters long')
        if not re.search(r'[A-Z]', v) or not re.search(r'[a-z]', v) or not re.search(r'\d', v):
            raise ValueError('Password must contain an uppercase letter, a lowercase letter, and a digit')
        return v

class UserResponse(BaseModel):
    id: uuid.UUID
    email: EmailStr
    role: UserRoleEnum
    is_active: bool
    created_at: datetime.datetime

class PostCreate(BaseModel):
    user_id: uuid.UUID
    title: str = Field(..., min_length=5, max_length=100)
    content: str = Field(..., min_length=10)
    status: PostStatusEnum = PostStatusEnum.DRAFT

class PostResponse(BaseModel):
    id: uuid.UUID
    user_id: uuid.UUID
    title: str
    status: PostStatusEnum

# --- Custom Error Handler for Pydantic ---
@app.errorhandler(ValidationError)
def handle_pydantic_validation_error(e):
    """Formats Pydantic's ValidationError into a user-friendly JSON response."""
    error_details = []
    for error in e.errors():
        error_details.append({
            "field": ".".join(map(str, error['loc'])),
            "message": error['msg']
        })
    return jsonify({"errors": error_details}), 400

# --- API Endpoints ---
@app.route("/users", methods=["POST"])
def add_user():
    """Creates a user via JSON, validated by Pydantic."""
    # Pydantic model handles validation upon instantiation
    validated_data = UserCreate.model_validate(request.get_json())

    # Create a full user object for storage
    new_user = {
        "id": uuid.uuid4(),
        "password_hash": f"hashed_{validated_data.password}", # Hashing would happen here
        "created_at": datetime.datetime.utcnow(),
        **validated_data.model_dump()
    }
    del new_user["password"] # Don't store raw password
    
    DATA_STORE["users"][new_user["id"]] = new_user

    # Serialize for response using the response model
    response_model = UserResponse.model_validate(new_user)
    return Response(response_model.model_dump_json(indent=2), status=201, mimetype='application/json')

@app.route("/users/<uuid:user_id>", methods=["GET"])
def find_user(user_id):
    """Retrieves a user and serializes with Pydantic."""
    user = DATA_STORE["users"].get(user_id)
    if not user:
        return jsonify({"error": "User not found"}), 404
    
    response_model = UserResponse.model_validate(user)
    return Response(response_model.model_dump_json(indent=2), mimetype='application/json')

@app.route("/posts/xml", methods=["POST"])
def add_post_via_xml():
    """Creates a post from XML, validates with Pydantic, returns XML."""
    if 'application/xml' not in request.content_type:
        return Response("<error>Content-Type must be application/xml</error>", 415, mimetype='application/xml')

    try:
        root = ET.fromstring(request.data)
        # Type conversion/coercion from XML text to Python types
        post_data = {
            "user_id": uuid.UUID(root.find('user_id').text),
            "title": root.find('title').text,
            "content": root.find('content').text,
            "status": root.find('status').text.upper() if root.find('status') is not None else 'DRAFT'
        }
        validated_post = PostCreate.model_validate(post_data)
    except (ET.ParseError, AttributeError, ValueError, ValidationError) as e:
        error_msg = "Malformed XML or validation error"
        if isinstance(e, ValidationError):
            error_msg = e.errors()[0]['msg']
        
        error_root = ET.Element("error")
        ET.SubElement(error_root, "message").text = error_msg
        return Response(ET.tostring(error_root), status=400, mimetype='application/xml')

    new_post_id = uuid.uuid4()
    new_post = {
        "id": new_post_id,
        **validated_post.model_dump()
    }
    DATA_STORE["posts"][new_post_id] = new_post

    # Generate XML response
    response_root = ET.Element("post")
    ET.SubElement(response_root, "id").text = str(new_post_id)
    ET.SubElement(response_root, "title").text = validated_post.title
    ET.SubElement(response_root, "status").text = "CREATED"
    return Response(ET.tostring(response_root), status=201, mimetype='application/xml')

if __name__ == "__main__":
    # Pre-populate with a user for testing post creation
    mock_user_id = uuid.uuid4()
    DATA_STORE["users"][mock_user_id] = {
        "id": mock_user_id,
        "email": "test@example.com",
        "password_hash": "hashed_password",
        "role": UserRoleEnum.USER,
        "is_active": True,
        "created_at": datetime.datetime.utcnow()
    }
    print(f"Mock user created with ID: {mock_user_id}")
    app.run(debug=True, port=5002)