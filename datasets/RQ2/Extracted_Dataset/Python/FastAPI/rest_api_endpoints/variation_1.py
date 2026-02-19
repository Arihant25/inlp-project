import uuid
from datetime import datetime, timezone
from enum import Enum
from typing import List, Dict, Optional

from fastapi import FastAPI, HTTPException, status, Path, Query
from pydantic import BaseModel, Field, EmailStr

# --- Domain Schema Definition ---

class UserRole(str, Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(str, Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

# --- Pydantic Models for User ---

class UserBase(BaseModel):
    email: EmailStr = Field(..., example="user@example.com")
    is_active: bool = Field(True, example=True)
    role: UserRole = Field(UserRole.USER, example="USER")

class UserCreate(UserBase):
    password: str = Field(..., min_length=8, example="a_strong_password")

class UserUpdate(BaseModel):
    email: Optional[EmailStr] = Field(None, example="new_user@example.com")
    is_active: Optional[bool] = Field(None, example=False)
    role: Optional[UserRole] = Field(None, example="ADMIN")

class UserInDB(UserBase):
    id: uuid.UUID = Field(default_factory=uuid.uuid4)
    password_hash: str = Field(...)
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))

class UserPublic(UserBase):
    id: uuid.UUID
    created_at: datetime

    class Config:
        from_attributes = True

# --- Pydantic Models for Post (as per domain requirement) ---

class Post(BaseModel):
    id: uuid.UUID
    user_id: uuid.UUID
    title: str
    content: str
    status: PostStatus

# --- In-Memory Database ---

# A simple dictionary to act as a database
DB_USERS: Dict[uuid.UUID, UserInDB] = {}

def populate_mock_data():
    """Adds some initial data to the in-memory database."""
    user1_id = uuid.uuid4()
    user2_id = uuid.uuid4()
    DB_USERS[user1_id] = UserInDB(
        id=user1_id,
        email="admin@example.com",
        password_hash="$2b$12$dummyhashforadmin",
        role=UserRole.ADMIN,
        is_active=True,
        created_at=datetime.now(timezone.utc)
    )
    DB_USERS[user2_id] = UserInDB(
        id=user2_id,
        email="user@example.com",
        password_hash="$2b$12$dummyhashforuser",
        role=UserRole.USER,
        is_active=False,
        created_at=datetime.now(timezone.utc)
    )

populate_mock_data()

# --- FastAPI Application ---

app = FastAPI(
    title="Simple User API",
    description="A functional, by-the-book implementation of a User REST API.",
    version="1.0.0"
)

# --- API Endpoints ---

@app.post("/users", response_model=UserPublic, status_code=status.HTTP_201_CREATED, tags=["Users"])
def create_user(user_in: UserCreate):
    """
    Create a new user.
    """
    for user in DB_USERS.values():
        if user.email == user_in.email:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="User with this email already exists."
            )
    
    # In a real app, you would hash the password here.
    password_hash = f"$2b$12$dummyhashfor{user_in.password}"
    
    new_user = UserInDB(
        **user_in.model_dump(exclude={"password"}),
        password_hash=password_hash
    )
    DB_USERS[new_user.id] = new_user
    return new_user

@app.get("/users", response_model=List[UserPublic], tags=["Users"])
def list_users(
    role: Optional[UserRole] = Query(None, description="Filter by user role"),
    is_active: Optional[bool] = Query(None, description="Filter by active status"),
    skip: int = Query(0, ge=0, description="Number of records to skip for pagination"),
    limit: int = Query(10, ge=1, le=100, description="Maximum number of records to return")
):
    """
    List users with pagination and filtering.
    """
    users = list(DB_USERS.values())
    
    if role is not None:
        users = [user for user in users if user.role == role]
    if is_active is not None:
        users = [user for user in users if user.is_active == is_active]
        
    return users[skip : skip + limit]

@app.get("/users/{user_id}", response_model=UserPublic, tags=["Users"])
def get_user_by_id(
    user_id: uuid.UUID = Path(..., description="The ID of the user to retrieve.")
):
    """
    Get a single user by their ID.
    """
    user = DB_USERS.get(user_id)
    if not user:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    return user

@app.put("/users/{user_id}", response_model=UserPublic, tags=["Users"])
def update_user(
    user_update: UserUpdate,
    user_id: uuid.UUID = Path(..., description="The ID of the user to update.")
):
    """
    Update a user's details with a full replacement (PUT).
    """
    user = DB_USERS.get(user_id)
    if not user:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")

    # Check for email conflict if email is being changed
    if user_update.email and user_update.email != user.email:
        for u in DB_USERS.values():
            if u.email == user_update.email:
                raise HTTPException(
                    status_code=status.HTTP_409_CONFLICT,
                    detail="User with this email already exists."
                )

    update_data = user_update.model_dump(exclude_unset=True)
    updated_user = user.model_copy(update=update_data)
    DB_USERS[user_id] = updated_user
    return updated_user

@app.patch("/users/{user_id}", response_model=UserPublic, tags=["Users"])
def partial_update_user(
    user_update: UserUpdate,
    user_id: uuid.UUID = Path(..., description="The ID of the user to partially update.")
):
    """
    Partially update a user's details (PATCH).
    """
    # This implementation is identical to PUT for simplicity,
    # as Pydantic's exclude_unset handles the partial nature.
    return update_user(user_update=user_update, user_id=user_id)

@app.delete("/users/{user_id}", status_code=status.HTTP_204_NO_CONTENT, tags=["Users"])
def delete_user(
    user_id: uuid.UUID = Path(..., description="The ID of the user to delete.")
):
    """
    Delete a user by their ID.
    """
    if user_id not in DB_USERS:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    del DB_USERS[user_id]
    return None