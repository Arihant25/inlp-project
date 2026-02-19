import uuid
from datetime import datetime, timezone
from enum import Enum
from typing import List, Dict, Annotated, Optional

from fastapi import FastAPI, HTTPException, status, Path, Query, Depends, Body
from pydantic import BaseModel, Field, EmailStr, ConfigDict

# --- Domain Schema Definition ---

class UserRole(str, Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(str, Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

# --- Pydantic Models (using modern features) ---

class UserBase(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    email: EmailStr
    is_active: bool = True

class UserCreate(UserBase):
    password: str = Field(..., min_length=8, description="User's password")
    role: UserRole = Field(default=UserRole.USER)

class UserPartialUpdate(BaseModel):
    email: Optional[EmailStr] = None
    is_active: Optional[bool] = None
    role: Optional[UserRole] = None

class UserDB(UserBase):
    id: uuid.UUID = Field(default_factory=uuid.uuid4)
    password_hash: str
    role: UserRole
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))

class UserPublic(UserBase):
    id: uuid.UUID
    role: UserRole
    created_at: datetime

class Post(BaseModel):
    id: uuid.UUID
    user_id: uuid.UUID
    title: str
    content: str
    status: PostStatus

# --- In-Memory Database & Dependency ---

DB: Dict[uuid.UUID, UserDB] = {
    uuid.UUID("a1b2c3d4-e5f6-7890-1234-567890abcdef"): UserDB(
        id=uuid.UUID("a1b2c3d4-e5f6-7890-1234-567890abcdef"),
        email="modern.admin@example.com",
        password_hash="<hashed_admin_pass>",
        role=UserRole.ADMIN,
        is_active=True,
    ),
    uuid.UUID("b2c3d4e5-f6a7-8901-2345-67890abcdef0"): UserDB(
        id=uuid.UUID("b2c3d4e5-f6a7-8901-2345-67890abcdef0"),
        email="modern.user@example.com",
        password_hash="<hashed_user_pass>",
        role=UserRole.USER,
        is_active=False,
    ),
}

def get_db() -> Dict[uuid.UUID, UserDB]:
    return DB

DB_DEP = Annotated[Dict[uuid.UUID, UserDB], Depends(get_db)]

# --- FastAPI Application ---

app = FastAPI(
    title="Modern & Concise User API",
    description="An implementation using modern Python and FastAPI features like Annotated.",
    version="1.0.0"
)

# --- API Endpoints ---

@app.post("/users", response_model=UserPublic, status_code=status.HTTP_201_CREATED, tags=["Users (Modern)"])
def create_user(
    db: DB_DEP,
    user_data: Annotated[UserCreate, Body(embed=True, description="New user data")]
) -> UserDB:
    """Creates a new user in the system."""
    if any(u.email == user_data.email for u in db.values()):
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail={"message": "An account with this email already exists."}
        )
    
    # In a real app, use a proper hashing library like passlib
    hashed_password = f"hashed::{user_data.password}"
    
    new_user = UserDB(
        **user_data.model_dump(exclude={"password"}),
        password_hash=hashed_password
    )
    db[new_user.id] = new_user
    return new_user

@app.get("/users", response_model=List[UserPublic], tags=["Users (Modern)"])
def search_users(
    db: DB_DEP,
    role: Annotated[UserRole | None, Query(description="Filter by user role")] = None,
    is_active: Annotated[bool | None, Query(description="Filter by active status")] = None,
    offset: Annotated[int, Query(ge=0)] = 0,
    limit: Annotated[int, Query(ge=1, le=100)] = 10,
) -> List[UserDB]:
    """Searches and paginates users based on query filters."""
    
    def user_filter(user: UserDB) -> bool:
        if role is not None and user.role != role:
            return False
        if is_active is not None and user.is_active != is_active:
            return False
        return True

    results = [user for user in db.values() if user_filter(user)]
    return results[offset : offset + limit]

@app.get("/users/{user_id}", response_model=UserPublic, tags=["Users (Modern)"])
def get_user(
    db: DB_DEP,
    user_id: Annotated[uuid.UUID, Path(description="The UUID of the user to fetch")]
) -> UserDB:
    """Retrieves a user by their unique ID."""
    user = db.get(user_id)
    if not user:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    return user

@app.put("/users/{user_id}", response_model=UserPublic, tags=["Users (Modern)"])
def update_user_full(
    db: DB_DEP,
    user_id: Annotated[uuid.UUID, Path(description="The UUID of the user to update")],
    update_data: UserPartialUpdate
) -> UserDB:
    """Updates a user's information (full update)."""
    user = get_user(db, user_id) # Reuse get_user for 404 check
    
    update_dict = update_data.model_dump(exclude_unset=False) # PUT requires all fields
    updated_user = user.model_copy(update=update_dict)
    db[user_id] = updated_user
    return updated_user

@app.patch("/users/{user_id}", response_model=UserPublic, tags=["Users (Modern)"])
def update_user_partial(
    db: DB_DEP,
    user_id: Annotated[uuid.UUID, Path(description="The UUID of the user to update")],
    update_data: UserPartialUpdate
) -> UserDB:
    """Updates a user's information (partial update)."""
    user = get_user(db, user_id) # Reuse get_user for 404 check
    
    update_dict = update_data.model_dump(exclude_unset=True)
    if not update_dict:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, detail="No fields to update")
        
    updated_user = user.model_copy(update=update_dict)
    db[user_id] = updated_user
    return updated_user

@app.delete("/users/{user_id}", status_code=status.HTTP_204_NO_CONTENT, tags=["Users (Modern)"])
def delete_user(
    db: DB_DEP,
    user_id: Annotated[uuid.UUID, Path(description="The UUID of the user to delete")]
) -> None:
    """Deletes a user from the system."""
    if user_id not in db:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    del db[user_id]