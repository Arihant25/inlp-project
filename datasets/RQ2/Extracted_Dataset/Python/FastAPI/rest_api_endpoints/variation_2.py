import uuid
from datetime import datetime, timezone
from enum import Enum
from typing import List, Dict, Optional, Generator

from fastapi import FastAPI, HTTPException, status, Depends, APIRouter
from pydantic import BaseModel, Field, EmailStr

# --- Domain Schema Definition ---

class UserRole(str, Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(str, Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

# --- Pydantic Models for User ---

class UserSchema(BaseModel):
    email: EmailStr
    is_active: bool = True
    role: UserRole = UserRole.USER

    class Config:
        from_attributes = True

class UserCreateSchema(UserSchema):
    password: str = Field(..., min_length=8)

class UserUpdateSchema(BaseModel):
    email: Optional[EmailStr] = None
    is_active: Optional[bool] = None
    role: Optional[UserRole] = None

class UserResponseSchema(UserSchema):
    id: uuid.UUID
    created_at: datetime

class _UserInDB(UserSchema):
    id: uuid.UUID
    password_hash: str
    created_at: datetime

# --- Pydantic Models for Post (as per domain requirement) ---

class PostSchema(BaseModel):
    id: uuid.UUID
    user_id: uuid.UUID
    title: str
    content: str
    status: PostStatus

# --- Service/Repository Layer ---

class UserRepository:
    """
    A class to handle data access and business logic for Users.
    This simulates a repository pattern.
    """
    def __init__(self):
        self._db: Dict[uuid.UUID, _UserInDB] = {}
        # Populate with some mock data
        user1_id = uuid.uuid4()
        user2_id = uuid.uuid4()
        self._db[user1_id] = _UserInDB(
            id=user1_id, email="admin.repo@example.com", password_hash="hash1",
            role=UserRole.ADMIN, is_active=True, created_at=datetime.now(timezone.utc)
        )
        self._db[user2_id] = _UserInDB(
            id=user2_id, email="user.repo@example.com", password_hash="hash2",
            role=UserRole.USER, is_active=False, created_at=datetime.now(timezone.utc)
        )

    def get_by_id(self, user_id: uuid.UUID) -> Optional[_UserInDB]:
        return self._db.get(user_id)

    def get_by_email(self, email: str) -> Optional[_UserInDB]:
        for user in self._db.values():
            if user.email == email:
                return user
        return None

    def get_all(self, skip: int, limit: int, role: Optional[UserRole], is_active: Optional[bool]) -> List[_UserInDB]:
        users = list(self._db.values())
        if role:
            users = [u for u in users if u.role == role]
        if is_active is not None:
            users = [u for u in users if u.is_active == is_active]
        return users[skip : skip + limit]

    def create(self, user_data: UserCreateSchema) -> _UserInDB:
        if self.get_by_email(user_data.email):
            raise HTTPException(status.HTTP_409_CONFLICT, "Email already registered")
        
        hashed_password = f"hashed_{user_data.password}"
        new_user = _UserInDB(
            id=uuid.uuid4(),
            password_hash=hashed_password,
            created_at=datetime.now(timezone.utc),
            **user_data.model_dump(exclude={"password"})
        )
        self._db[new_user.id] = new_user
        return new_user

    def update(self, user_id: uuid.UUID, update_data: UserUpdateSchema) -> Optional[_UserInDB]:
        user = self.get_by_id(user_id)
        if not user:
            return None
        
        if update_data.email and update_data.email != user.email:
            if self.get_by_email(update_data.email):
                raise HTTPException(status.HTTP_409_CONFLICT, "Email already registered")

        update_dict = update_data.model_dump(exclude_unset=True)
        updated_user = user.model_copy(update=update_dict)
        self._db[user_id] = updated_user
        return updated_user

    def delete(self, user_id: uuid.UUID) -> bool:
        if user_id in self._db:
            del self._db[user_id]
            return True
        return False

# --- Dependency Injection ---

# Create a single instance of the repository
user_repository = UserRepository()

def get_user_repository() -> Generator[UserRepository, None, None]:
    yield user_repository

# --- API Router ---

router = APIRouter(prefix="/users", tags=["Users (OOP)"])

@router.post("/", response_model=UserResponseSchema, status_code=status.HTTP_201_CREATED)
def register_user(
    user_in: UserCreateSchema,
    repo: UserRepository = Depends(get_user_repository)
):
    return repo.create(user_data=user_in)

@router.get("/", response_model=List[UserResponseSchema])
def get_user_list(
    role: Optional[UserRole] = None,
    is_active: Optional[bool] = None,
    skip: int = 0,
    limit: int = 100,
    repo: UserRepository = Depends(get_user_repository)
):
    return repo.get_all(skip=skip, limit=limit, role=role, is_active=is_active)

@router.get("/{user_id}", response_model=UserResponseSchema)
def get_user_details(
    user_id: uuid.UUID,
    repo: UserRepository = Depends(get_user_repository)
):
    user = repo.get_by_id(user_id)
    if not user:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    return user

@router.put("/{user_id}", response_model=UserResponseSchema)
def update_user_details(
    user_id: uuid.UUID,
    user_in: UserUpdateSchema,
    repo: UserRepository = Depends(get_user_repository)
):
    updated_user = repo.update(user_id, user_in)
    if not updated_user:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    return updated_user

@router.patch("/{user_id}", response_model=UserResponseSchema)
def partially_update_user_details(
    user_id: uuid.UUID,
    user_in: UserUpdateSchema,
    repo: UserRepository = Depends(get_user_repository)
):
    # The service's update method already handles partial updates correctly
    return update_user_details(user_id, user_in, repo)

@router.delete("/{user_id}", status_code=status.HTTP_204_NO_CONTENT)
def remove_user(
    user_id: uuid.UUID,
    repo: UserRepository = Depends(get_user_repository)
):
    if not repo.delete(user_id):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    return None

# --- Main Application ---

app = FastAPI(
    title="OOP User API",
    description="An OOP-style implementation using a Repository/Service layer.",
    version="1.0.0"
)
app.include_router(router)