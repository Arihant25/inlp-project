# This variation simulates a modular, multi-file project structure.
# File boundaries are indicated by comments.

# --- file: schemas/enums.py ---
from enum import Enum

class UserRole(str, Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(str, Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

# --- file: schemas/user.py ---
import uuid
from datetime import datetime
from typing import Optional

from pydantic import BaseModel, Field, EmailStr
# from schemas.enums import UserRole # In a real project

class UserBase(BaseModel):
    email: EmailStr
    
class UserCreate(UserBase):
    password: str = Field(..., min_length=8)
    role: UserRole = UserRole.USER
    is_active: bool = True

class UserUpdate(BaseModel):
    email: Optional[EmailStr] = None
    role: Optional[UserRole] = None
    is_active: Optional[bool] = None

class UserPublic(UserBase):
    id: uuid.UUID
    role: UserRole
    is_active: bool
    created_at: datetime

    class Config:
        from_attributes = True

class UserInDB(UserPublic):
    password_hash: str

# --- file: schemas/post.py ---
# import uuid
# from pydantic import BaseModel
# from schemas.enums import PostStatus # In a real project

class Post(BaseModel):
    id: uuid.UUID
    user_id: uuid.UUID
    title: str
    content: str
    status: PostStatus

# --- file: db/in_memory.py ---
# import uuid
# from datetime import datetime, timezone
# from typing import Dict
# from schemas.user import UserInDB # In a real project
# from schemas.enums import UserRole # In a real project

# This dictionary serves as our in-memory database.
# In a real application, this would be a database connection and session.
USERS_DB: Dict[uuid.UUID, UserInDB] = {}

def _initialize_db():
    user_id_1 = uuid.uuid4()
    user_id_2 = uuid.uuid4()
    USERS_DB[user_id_1] = UserInDB(
        id=user_id_1, email="modular.admin@example.com", password_hash="hash_admin",
        role=UserRole.ADMIN, is_active=True, created_at=datetime.now(timezone.utc)
    )
    USERS_DB[user_id_2] = UserInDB(
        id=user_id_2, email="modular.user@example.com", password_hash="hash_user",
        role=UserRole.USER, is_active=True, created_at=datetime.now(timezone.utc)
    )
_initialize_db()

# --- file: services/user_service.py ---
# import uuid
# from datetime import datetime, timezone
# from typing import List, Optional
# from db.in_memory import USERS_DB # In a real project
# from schemas.user import UserCreate, UserUpdate, UserInDB # In a real project
# from schemas.enums import UserRole # In a real project
from fastapi import HTTPException, status

class UserService:
    def __init__(self, db: Dict[uuid.UUID, UserInDB]):
        self.db = db

    def get_user(self, user_id: uuid.UUID) -> UserInDB:
        user = self.db.get(user_id)
        if not user:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "User not found")
        return user

    def list_users(self, role: Optional[UserRole], is_active: Optional[bool], skip: int, limit: int) -> List[UserInDB]:
        filtered_users = list(self.db.values())
        if role:
            filtered_users = [u for u in filtered_users if u.role == role]
        if is_active is not None:
            filtered_users = [u for u in filtered_users if u.is_active == is_active]
        return filtered_users[skip : skip + limit]

    def create_user(self, user_data: UserCreate) -> UserInDB:
        if any(u.email == user_data.email for u in self.db.values()):
            raise HTTPException(status.HTTP_409_CONFLICT, "Email already in use")
        
        # Dummy password hashing
        hashed_password = user_data.password + "_hashed"
        
        new_user = UserInDB(
            id=uuid.uuid4(),
            created_at=datetime.now(timezone.utc),
            password_hash=hashed_password,
            **user_data.model_dump(exclude={"password"})
        )
        self.db[new_user.id] = new_user
        return new_user

    def update_user(self, user_id: uuid.UUID, update_data: UserUpdate) -> UserInDB:
        user = self.get_user(user_id)
        
        update_dict = update_data.model_dump(exclude_unset=True)
        if "email" in update_dict and update_dict["email"] != user.email:
            if any(u.email == update_dict["email"] for u in self.db.values()):
                raise HTTPException(status.HTTP_409_CONFLICT, "Email already in use")

        updated_user = user.model_copy(update=update_dict)
        self.db[user_id] = updated_user
        return updated_user

    def delete_user(self, user_id: uuid.UUID):
        self.get_user(user_id) # Raises 404 if not found
        del self.db[user_id]

# --- file: api/dependencies.py ---
# from services.user_service import UserService # In a real project
# from db.in_memory import USERS_DB # In a real project

def get_user_service() -> UserService:
    return UserService(db=USERS_DB)

# --- file: api/v1/endpoints/users.py ---
# import uuid
# from typing import List, Optional
from fastapi import APIRouter, Depends, status
# from schemas.user import UserCreate, UserUpdate, UserPublic # In a real project
# from schemas.enums import UserRole # In a real project
# from services.user_service import UserService # In a real project
# from api.dependencies import get_user_service # In a real project

router = APIRouter()

@router.post("", response_model=UserPublic, status_code=status.HTTP_201_CREATED)
def handle_create_user(user_in: UserCreate, service: UserService = Depends(get_user_service)):
    return service.create_user(user_data=user_in)

@router.get("", response_model=List[UserPublic])
def handle_list_users(
    role: Optional[UserRole] = None,
    is_active: Optional[bool] = None,
    skip: int = 0,
    limit: int = 50,
    service: UserService = Depends(get_user_service)
):
    return service.list_users(role=role, is_active=is_active, skip=skip, limit=limit)

@router.get("/{user_id}", response_model=UserPublic)
def handle_get_user(user_id: uuid.UUID, service: UserService = Depends(get_user_service)):
    return service.get_user(user_id=user_id)

@router.put("/{user_id}", response_model=UserPublic)
def handle_update_user(user_id: uuid.UUID, user_in: UserUpdate, service: UserService = Depends(get_user_service)):
    return service.update_user(user_id=user_id, update_data=user_in)

@router.patch("/{user_id}", response_model=UserPublic)
def handle_partial_update_user(user_id: uuid.UUID, user_in: UserUpdate, service: UserService = Depends(get_user_service)):
    return service.update_user(user_id=user_id, update_data=user_in)

@router.delete("/{user_id}", status_code=status.HTTP_204_NO_CONTENT)
def handle_delete_user(user_id: uuid.UUID, service: UserService = Depends(get_user_service)):
    service.delete_user(user_id=user_id)
    return None

# --- file: api/v1/api.py ---
from fastapi import APIRouter
# from api.v1.endpoints import users # In a real project

api_router = APIRouter()
api_router.include_router(router, prefix="/users", tags=["Users (Modular)"])

# --- file: main.py ---
from fastapi import FastAPI
# from api.v1.api import api_router # In a real project

app = FastAPI(
    title="Modular User API",
    description="A modular, multi-file style implementation.",
    version="1.0.0"
)

app.include_router(api_router, prefix="/api/v1")