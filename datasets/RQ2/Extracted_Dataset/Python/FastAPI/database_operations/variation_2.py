# VARIATION 2: The Repository Pattern Developer
# This approach uses an object-oriented Repository pattern to abstract data access.
# - main.py: FastAPI app, routers, and dependency injection setup.
# - database.py: Database connection and session management.
# - models.py: SQLAlchemy ORM models.
# - schemas.py: Pydantic data transfer objects.
# - repositories/: Directory containing repository classes for each model.
#   - base_repo.py: An abstract base repository.
#   - user_repo.py: Concrete repository for User operations.
#   - post_repo.py: Concrete repository for Post operations.
#   - role_repo.py: Concrete repository for Role operations.

# --- File: requirements.txt ---
# fastapi
# uvicorn[standard]
# sqlalchemy[asyncio]
# aiosqlite
# alembic
# pydantic

import asyncio
import enum
import uuid
from datetime import datetime
from typing import List, Optional, AsyncGenerator, Type, TypeVar, Generic

from fastapi import FastAPI, Depends, HTTPException, Query
from pydantic import BaseModel, EmailStr
from sqlalchemy import (create_async_engine, String, Text, Boolean,
                        ForeignKey, Enum, Table, Column, select, delete)
from sqlalchemy.ext.asyncio import async_sessionmaker, AsyncSession
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship
from sqlalchemy.dialects.postgresql import UUID as PG_UUID

# --- File: database.py ---
DB_URL = "sqlite+aiosqlite:///./test_variation2.db"

db_engine = create_async_engine(DB_URL, connect_args={"check_same_thread": False})
AsyncSessionMaker = async_sessionmaker(db_engine, expire_on_commit=False)

async def get_async_session() -> AsyncGenerator[AsyncSession, None]:
    async with AsyncSessionMaker() as session:
        yield session

# --- File: models.py ---
class Base(DeclarativeBase):
    pass

class PostStatus(enum.Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

user_roles_table = Table(
    'user_roles', Base.metadata,
    Column('user_id', PG_UUID(as_uuid=True), ForeignKey('users.id'), primary_key=True),
    Column('role_id', PG_UUID(as_uuid=True), ForeignKey('roles.id'), primary_key=True)
)

class User(Base):
    __tablename__ = "users"
    id: Mapped[uuid.UUID] = mapped_column(PG_UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    email: Mapped[str] = mapped_column(String(255), unique=True, index=True, nullable=False)
    password_hash: Mapped[str] = mapped_column(String(255), nullable=False)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(default=datetime.utcnow)
    
    posts: Mapped[List["Post"]] = relationship("Post", back_populates="author", cascade="all, delete-orphan")
    roles: Mapped[List["Role"]] = relationship("Role", secondary=user_roles_table, back_populates="users", lazy="selectin")

class Post(Base):
    __tablename__ = "posts"
    id: Mapped[uuid.UUID] = mapped_column(PG_UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("users.id"), nullable=False)
    title: Mapped[str] = mapped_column(String(200), nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    status: Mapped[PostStatus] = mapped_column(Enum(PostStatus), default=PostStatus.DRAFT)

    author: Mapped["User"] = relationship("User", back_populates="posts")

class Role(Base):
    __tablename__ = "roles"
    id: Mapped[uuid.UUID] = mapped_column(PG_UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    name: Mapped[str] = mapped_column(String(50), unique=True, nullable=False)
    
    users: Mapped[List["User"]] = relationship("User", secondary=user_roles_table, back_populates="roles")

# --- File: schemas.py ---
class RoleDTO(BaseModel):
    id: uuid.UUID
    name: str
    class Config: from_attributes = True

class PostDTO(BaseModel):
    id: uuid.UUID
    title: str
    status: PostStatus
    class Config: from_attributes = True

class UserDTO(BaseModel):
    id: uuid.UUID
    email: EmailStr
    is_active: bool
    created_at: datetime
    posts: List[PostDTO] = []
    roles: List[RoleDTO] = []
    class Config: from_attributes = True

class UserCreateSchema(BaseModel):
    email: EmailStr
    password: str

class PostCreateSchema(BaseModel):
    title: str
    content: str

class RoleCreateSchema(BaseModel):
    name: str

# --- File: repositories/base_repo.py ---
ModelType = TypeVar("ModelType", bound=Base)

class BaseRepository(Generic[ModelType]):
    def __init__(self, session: AsyncSession, model: Type[ModelType]):
        self.session = session
        self.model = model

    async def get_by_id(self, obj_id: uuid.UUID) -> Optional[ModelType]:
        result = await self.session.execute(select(self.model).where(self.model.id == obj_id))
        return result.scalars().first()

    async def add(self, obj: ModelType) -> ModelType:
        self.session.add(obj)
        await self.session.commit()
        await self.session.refresh(obj)
        return obj

    async def delete(self, obj_id: uuid.UUID) -> bool:
        result = await self.session.execute(delete(self.model).where(self.model.id == obj_id))
        await self.session.commit()
        return result.rowcount > 0

# --- File: repositories/user_repo.py ---
class UserRepository(BaseRepository[User]):
    def __init__(self, session: AsyncSession):
        super().__init__(session, User)

    async def get_by_email(self, email: str) -> Optional[User]:
        result = await self.session.execute(select(self.model).where(self.model.email == email))
        return result.scalars().first()

    async def search(self, is_active: Optional[bool], email_contains: Optional[str]) -> List[User]:
        query = select(self.model)
        if is_active is not None:
            query = query.where(self.model.is_active == is_active)
        if email_contains:
            query = query.where(self.model.email.contains(email_contains))
        result = await self.session.execute(query)
        return result.scalars().all()

    async def assign_role(self, user: User, role: Role) -> User:
        if role not in user.roles:
            user.roles.append(role)
            return await self.add(user)
        return user
    
    async def create_with_first_post(self, user_data: UserCreateSchema, post_data: PostCreateSchema) -> User:
        async with self.session.begin(): # Transaction management
            hashed_password = user_data.password + "_hashed"
            new_user = User(email=user_data.email, password_hash=hashed_password)
            self.session.add(new_user)
            await self.session.flush() # Get ID for the post

            if post_data.title == "FAIL":
                raise ValueError("Transaction rollback simulation")

            new_post = Post(**post_data.model_dump(), user_id=new_user.id)
            self.session.add(new_post)
        
        await self.session.refresh(new_user)
        return new_user

# --- File: repositories/post_repo.py ---
class PostRepository(BaseRepository[Post]):
    def __init__(self, session: AsyncSession):
        super().__init__(session, Post)

# --- File: repositories/role_repo.py ---
class RoleRepository(BaseRepository[Role]):
    def __init__(self, session: AsyncSession):
        super().__init__(session, Role)

    async def get_by_name(self, name: str) -> Optional[Role]:
        result = await self.session.execute(select(self.model).where(self.model.name == name))
        return result.scalars().first()

# --- File: main.py ---
app = FastAPI(title="Variation 2: Repository Pattern")

# Dependency Providers
def get_user_repo(session: AsyncSession = Depends(get_async_session)) -> UserRepository:
    return UserRepository(session)

def get_post_repo(session: AsyncSession = Depends(get_async_session)) -> PostRepository:
    return PostRepository(session)

def get_role_repo(session: AsyncSession = Depends(get_async_session)) -> RoleRepository:
    return RoleRepository(session)

@app.on_event("startup")
async def on_startup():
    async with db_engine.begin() as conn:
        # Alembic should be used for production migrations
        await conn.run_sync(Base.metadata.create_all)
    
    async with AsyncSessionMaker() as session:
        role_repo = RoleRepository(session)
        if not await role_repo.get_by_name("ADMIN"):
            await role_repo.add(Role(name="ADMIN"))
        if not await role_repo.get_by_name("USER"):
            await role_repo.add(Role(name="USER"))

# --- API Routes ---
@app.post("/users", response_model=UserDTO, status_code=201)
async def create_user(
    user_data: UserCreateSchema,
    user_repo: UserRepository = Depends(get_user_repo)
):
    if await user_repo.get_by_email(user_data.email):
        raise HTTPException(status_code=400, detail="Email already exists")
    hashed_password = user_data.password + "_hashed"
    new_user = User(email=user_data.email, password_hash=hashed_password)
    return await user_repo.add(new_user)

@app.get("/users/{user_id}", response_model=UserDTO)
async def get_user(
    user_id: uuid.UUID,
    user_repo: UserRepository = Depends(get_user_repo)
):
    user = await user_repo.get_by_id(user_id)
    if not user:
        raise HTTPException(status_code=404, detail="User not found")
    return user

@app.get("/users", response_model=List[UserDTO])
async def find_users(
    is_active: Optional[bool] = Query(None),
    email_contains: Optional[str] = Query(None),
    user_repo: UserRepository = Depends(get_user_repo)
):
    return await user_repo.search(is_active, email_contains)

@app.delete("/users/{user_id}", status_code=204)
async def remove_user(
    user_id: uuid.UUID,
    user_repo: UserRepository = Depends(get_user_repo)
):
    if not await user_repo.delete(user_id):
        raise HTTPException(status_code=404, detail="User not found")

@app.post("/posts", response_model=PostDTO, status_code=201)
async def create_post(
    user_id: uuid.UUID,
    post_data: PostCreateSchema,
    post_repo: PostRepository = Depends(get_post_repo),
    user_repo: UserRepository = Depends(get_user_repo)
):
    if not await user_repo.get_by_id(user_id):
        raise HTTPException(status_code=404, detail="Author user not found")
    new_post = Post(**post_data.model_dump(), user_id=user_id)
    return await post_repo.add(new_post)

@app.post("/roles", response_model=RoleDTO, status_code=201)
async def create_role(
    role_data: RoleCreateSchema,
    role_repo: RoleRepository = Depends(get_role_repo)
):
    if await role_repo.get_by_name(role_data.name):
        raise HTTPException(status_code=400, detail="Role name already exists")
    new_role = Role(name=role_data.name)
    return await role_repo.add(new_role)

@app.post("/users/{user_id}/roles/{role_name}", response_model=UserDTO)
async def add_role_to_user(
    user_id: uuid.UUID,
    role_name: str,
    user_repo: UserRepository = Depends(get_user_repo),
    role_repo: RoleRepository = Depends(get_role_repo)
):
    user = await user_repo.get_by_id(user_id)
    if not user:
        raise HTTPException(status_code=404, detail="User not found")
    role = await role_repo.get_by_name(role_name)
    if not role:
        raise HTTPException(status_code=404, detail="Role not found")
    return await user_repo.assign_role(user, role)

@app.post("/transactional/user-with-post", response_model=UserDTO, status_code=201)
async def create_user_with_post_transaction(
    user_data: UserCreateSchema,
    post_data: PostCreateSchema,
    user_repo: UserRepository = Depends(get_user_repo)
):
    try:
        return await user_repo.create_with_first_post(user_data, post_data)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception:
        raise HTTPException(status_code=500, detail="Internal server error")

# --- Alembic Migration Setup ---
# The setup for Alembic would be identical to Variation 1,
# pointing to the models in `models.py` and the database URL in `database.py`.
# Run `alembic init alembic`, then configure `alembic/env.py` to use
# the async engine and target metadata from `models.Base.metadata`.
# Generate migrations with `alembic revision --autogenerate -m "Initial schema"`.