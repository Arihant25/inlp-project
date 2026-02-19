# VARIATION 4: The Advanced/CQRS-inspired Developer
# This approach separates read (Query) and write (Command) operations into different modules.
# - main.py: FastAPI app, routers.
# - database.py: Database connection and session management.
# - models.py: SQLAlchemy ORM models.
# - schemas.py: Pydantic data transfer objects.
# - commands/: Directory for all data-modifying logic (CUD of CRUD).
#   - user_commands.py
# - queries/: Directory for all data-retrieval logic (R of CRUD).
#   - user_queries.py
# The API layer becomes a thin orchestrator, calling command and query handlers.

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
from typing import List, Optional, AsyncGenerator

from fastapi import FastAPI, Depends, HTTPException, Query
from pydantic import BaseModel, EmailStr
from sqlalchemy import (create_async_engine, String, Text, Boolean,
                        ForeignKey, Enum, Table, Column, select)
from sqlalchemy.ext.asyncio import async_sessionmaker, AsyncSession
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship
from sqlalchemy.dialects.postgresql import UUID as PG_UUID

# --- File: database.py ---
DATABASE_URL = "sqlite+aiosqlite:///./test_variation4.db"

engine = create_async_engine(DATABASE_URL, connect_args={"check_same_thread": False})
AsyncSessionMaker = async_sessionmaker(engine, expire_on_commit=False)

async def get_db_session() -> AsyncGenerator[AsyncSession, None]:
    async with AsyncSessionMaker() as session:
        yield session

# --- File: models.py ---
class Base(DeclarativeBase):
    pass

class PostStatus(enum.Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

user_roles_assoc = Table(
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
    
    posts: Mapped[List["Post"]] = relationship("Post", back_populates="user", cascade="all, delete-orphan")
    roles: Mapped[List["Role"]] = relationship("Role", secondary=user_roles_assoc, back_populates="users", lazy="selectin")

class Post(Base):
    __tablename__ = "posts"
    id: Mapped[uuid.UUID] = mapped_column(PG_UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("users.id"), nullable=False)
    title: Mapped[str] = mapped_column(String(200), nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    status: Mapped[PostStatus] = mapped_column(Enum(PostStatus), default=PostStatus.DRAFT)
    user: Mapped["User"] = relationship("User", back_populates="posts")

class Role(Base):
    __tablename__ = "roles"
    id: Mapped[uuid.UUID] = mapped_column(PG_UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    name: Mapped[str] = mapped_column(String(50), unique=True, nullable=False)
    users: Mapped[List["User"]] = relationship("User", secondary=user_roles_assoc, back_populates="roles")

# --- File: schemas.py ---
class RoleInfo(BaseModel):
    id: uuid.UUID
    name: str
    class Config: from_attributes = True

class PostInfo(BaseModel):
    id: uuid.UUID
    title: str
    status: PostStatus
    class Config: from_attributes = True

class UserInfo(BaseModel):
    id: uuid.UUID
    email: EmailStr
    is_active: bool
    created_at: datetime
    posts: List[PostInfo] = []
    roles: List[RoleInfo] = []
    class Config: from_attributes = True

class UserCreateData(BaseModel):
    email: EmailStr
    password: str

class PostCreateData(BaseModel):
    title: str
    content: str

class RoleCreateData(BaseModel):
    name: str

# --- File: queries/user_queries.py ---
class UserQueries:
    def __init__(self, db: AsyncSession):
        self.db = db

    async def find_by_id(self, user_id: uuid.UUID) -> Optional[User]:
        return await self.db.get(User, user_id)

    async def find_by_email(self, email: str) -> Optional[User]:
        stmt = select(User).where(User.email == email)
        result = await self.db.execute(stmt)
        return result.scalars().first()

    async def search_with_filters(self, is_active: Optional[bool], email_contains: Optional[str]) -> List[User]:
        stmt = select(User).order_by(User.created_at.desc())
        if is_active is not None:
            stmt = stmt.where(User.is_active == is_active)
        if email_contains:
            stmt = stmt.where(User.email.contains(email_contains))
        result = await self.db.execute(stmt)
        return result.scalars().all()

# --- File: queries/role_queries.py ---
class RoleQueries:
    def __init__(self, db: AsyncSession):
        self.db = db
    
    async def find_by_name(self, name: str) -> Optional[Role]:
        stmt = select(Role).where(Role.name == name)
        result = await self.db.execute(stmt)
        return result.scalars().first()

# --- File: commands/user_commands.py ---
class UserCommands:
    def __init__(self, db: AsyncSession):
        self.db = db

    async def handle_create_user(self, data: UserCreateData) -> User:
        hashed_password = data.password + "_hashed"
        new_user = User(email=data.email, password_hash=hashed_password)
        self.db.add(new_user)
        await self.db.commit()
        await self.db.refresh(new_user)
        return new_user

    async def handle_delete_user(self, user: User) -> None:
        await self.db.delete(user)
        await self.db.commit()

    async def handle_assign_role(self, user: User, role: Role) -> User:
        if role not in user.roles:
            user.roles.append(role)
            await self.db.commit()
            await self.db.refresh(user)
        return user

# --- File: commands/post_commands.py ---
class PostCommands:
    def __init__(self, db: AsyncSession):
        self.db = db

    async def handle_create_post(self, data: PostCreateData, user_id: uuid.UUID) -> Post:
        new_post = Post(**data.model_dump(), user_id=user_id)
        self.db.add(new_post)
        await self.db.commit()
        await self.db.refresh(new_post)
        return new_post

# --- File: commands/transaction_commands.py ---
class TransactionCommands:
    def __init__(self, db: AsyncSession):
        self.db = db

    async def handle_create_user_with_post(self, user_data: UserCreateData, post_data: PostCreateData) -> User:
        async with self.db.begin(): # Transaction context manager
            hashed_password = user_data.password + "_hashed"
            new_user = User(email=user_data.email, password_hash=hashed_password)
            self.db.add(new_user)
            await self.db.flush()

            if post_data.title == "FAIL":
                raise ValueError("Simulated failure for rollback")

            new_post = Post(**post_data.model_dump(), user_id=new_user.id)
            self.db.add(new_post)
        
        await self.db.refresh(new_user)
        return new_user

# --- File: main.py ---
app = FastAPI(title="Variation 4: CQRS-inspired")

# Dependency Injection for handlers
def get_user_queries(db: AsyncSession = Depends(get_db_session)) -> UserQueries:
    return UserQueries(db)

def get_user_commands(db: AsyncSession = Depends(get_db_session)) -> UserCommands:
    return UserCommands(db)

def get_post_commands(db: AsyncSession = Depends(get_db_session)) -> PostCommands:
    return PostCommands(db)

def get_role_queries(db: AsyncSession = Depends(get_db_session)) -> RoleQueries:
    return RoleQueries(db)

def get_transaction_commands(db: AsyncSession = Depends(get_db_session)) -> TransactionCommands:
    return TransactionCommands(db)

@app.on_event("startup")
async def on_startup():
    async with engine.begin() as conn:
        # Use Alembic for production migrations
        await conn.run_sync(Base.metadata.create_all)
    async with AsyncSessionMaker() as session:
        if not (await session.execute(select(Role).where(Role.name == "ADMIN"))).scalars().first():
            session.add_all([Role(name="ADMIN"), Role(name="USER")])
            await session.commit()

# --- API Routes ---
@app.post("/users", response_model=UserInfo, status_code=201)
async def create_user_endpoint(
    data: UserCreateData,
    queries: UserQueries = Depends(get_user_queries),
    commands: UserCommands = Depends(get_user_commands)
):
    if await queries.find_by_email(data.email):
        raise HTTPException(status_code=400, detail="Email already in use")
    return await commands.handle_create_user(data)

@app.get("/users/{user_id}", response_model=UserInfo)
async def get_user_endpoint(user_id: uuid.UUID, queries: UserQueries = Depends(get_user_queries)):
    user = await queries.find_by_id(user_id)
    if not user:
        raise HTTPException(status_code=404, detail="User not found")
    return user

@app.get("/users", response_model=List[UserInfo])
async def search_users_endpoint(
    is_active: Optional[bool] = Query(None),
    email_contains: Optional[str] = Query(None),
    queries: UserQueries = Depends(get_user_queries)
):
    return await queries.search_with_filters(is_active, email_contains)

@app.delete("/users/{user_id}", status_code=204)
async def delete_user_endpoint(
    user_id: uuid.UUID,
    queries: UserQueries = Depends(get_user_queries),
    commands: UserCommands = Depends(get_user_commands)
):
    user = await queries.find_by_id(user_id)
    if not user:
        raise HTTPException(status_code=404, detail="User not found")
    await commands.handle_delete_user(user)

@app.post("/users/{user_id}/posts", response_model=PostInfo, status_code=201)
async def create_post_endpoint(
    user_id: uuid.UUID,
    data: PostCreateData,
    user_queries: UserQueries = Depends(get_user_queries),
    post_commands: PostCommands = Depends(get_post_commands)
):
    if not await user_queries.find_by_id(user_id):
        raise HTTPException(status_code=404, detail="User not found")
    return await post_commands.handle_create_post(data, user_id)

@app.post("/users/{user_id}/roles/{role_name}", response_model=UserInfo)
async def assign_role_endpoint(
    user_id: uuid.UUID,
    role_name: str,
    user_queries: UserQueries = Depends(get_user_queries),
    role_queries: RoleQueries = Depends(get_role_queries),
    user_commands: UserCommands = Depends(get_user_commands)
):
    user = await user_queries.find_by_id(user_id)
    if not user:
        raise HTTPException(status_code=404, detail="User not found")
    role = await role_queries.find_by_name(role_name)
    if not role:
        raise HTTPException(status_code=404, detail="Role not found")
    return await user_commands.handle_assign_role(user, role)

@app.post("/transactions/user-with-post", response_model=UserInfo, status_code=201)
async def create_user_with_post_transactional_endpoint(
    user_data: UserCreateData,
    post_data: PostCreateData,
    commands: TransactionCommands = Depends(get_transaction_commands)
):
    try:
        return await commands.handle_create_user_with_post(user_data, post_data)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception:
        raise HTTPException(status_code=500, detail="A server error occurred during the transaction.")

# --- Alembic Migration Setup ---
# The setup for Alembic would be identical to Variation 1,
# pointing to the models in `models.py` and the database URL in `database.py`.
# Run `alembic init alembic`, then configure `alembic/env.py` to use
# the async engine and target metadata from `models.Base.metadata`.
# Generate migrations with `alembic revision --autogenerate -m "Initial schema"`.