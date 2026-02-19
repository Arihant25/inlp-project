# VARIATION 3: The All-in-One Router Developer (The Pragmatist)
# This approach co-locates related logic for simplicity and speed of development.
# - main.py: Main FastAPI app, includes routers.
# - database.py: Database connection and session management.
# - models_and_schemas.py: A single file for both SQLAlchemy models and Pydantic schemas.
# - routers/users.py: A router file containing all endpoints, business logic,
#   and database calls related to users.
# - routers/posts.py: A router file for post-related endpoints.

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
from datetime importdatetime
from typing import List, Optional, AsyncGenerator

from fastapi import FastAPI, Depends, HTTPException, APIRouter, Query
from pydantic import BaseModel, EmailStr
from sqlalchemy import (create_async_engine, String, Text, Boolean,
                        ForeignKey, Enum, Table, Column, select)
from sqlalchemy.ext.asyncio import async_sessionmaker, AsyncSession
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship
from sqlalchemy.dialects.postgresql import UUID as PG_UUID

# --- File: database.py ---
DATABASE_URL = "sqlite+aiosqlite:///./test_variation3.db"

engine = create_async_engine(DATABASE_URL, connect_args={"check_same_thread": False})
SessionLocal = async_sessionmaker(autocommit=False, autoflush=False, bind=engine)

async def get_db() -> AsyncGenerator[AsyncSession, None]:
    async with SessionLocal() as session:
        yield session

# --- File: models_and_schemas.py ---
class Base(DeclarativeBase):
    pass

# Enums
class PostStatus(str, enum.Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

# Association Table
user_roles_association_table = Table(
    'user_roles', Base.metadata,
    Column('user_id', PG_UUID(as_uuid=True), ForeignKey('users.id'), primary_key=True),
    Column('role_id', PG_UUID(as_uuid=True), ForeignKey('roles.id'), primary_key=True)
)

# SQLAlchemy Models
class User(Base):
    __tablename__ = "users"
    id: Mapped[uuid.UUID] = mapped_column(PG_UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    email: Mapped[str] = mapped_column(String(255), unique=True, index=True)
    password_hash: Mapped[str] = mapped_column(String(255))
    is_active: Mapped[bool] = mapped_column(default=True)
    created_at: Mapped[datetime] = mapped_column(default=datetime.utcnow)
    
    posts: Mapped[List["Post"]] = relationship(back_populates="user", cascade="all, delete-orphan")
    roles: Mapped[List["Role"]] = relationship(secondary=user_roles_association_table, back_populates="users", lazy="selectin")

class Post(Base):
    __tablename__ = "posts"
    id: Mapped[uuid.UUID] = mapped_column(PG_UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("users.id"))
    title: Mapped[str] = mapped_column(String(200))
    content: Mapped[str] = mapped_column(Text)
    status: Mapped[PostStatus] = mapped_column(Enum(PostStatus), default=PostStatus.DRAFT)
    user: Mapped["User"] = relationship(back_populates="posts")

class Role(Base):
    __tablename__ = "roles"
    id: Mapped[uuid.UUID] = mapped_column(PG_UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    name: Mapped[str] = mapped_column(String(50), unique=True)
    users: Mapped[List["User"]] = relationship(secondary=user_roles_association_table, back_populates="roles")

# Pydantic Schemas
class OrmModel(BaseModel):
    class Config:
        from_attributes = True

class RoleSchema(OrmModel):
    id: uuid.UUID
    name: str

class PostSchema(OrmModel):
    id: uuid.UUID
    title: str
    status: PostStatus

class UserSchema(OrmModel):
    id: uuid.UUID
    email: EmailStr
    is_active: bool
    created_at: datetime
    posts: List[PostSchema] = []
    roles: List[RoleSchema] = []

class UserCreate(BaseModel):
    email: EmailStr
    password: str

class PostCreate(BaseModel):
    title: str
    content: str
    status: PostStatus = PostStatus.DRAFT

class RoleCreate(BaseModel):
    name: str

# --- File: routers/users.py ---
users_router = APIRouter(prefix="/users", tags=["Users"])

@users_router.post("/", response_model=UserSchema, status_code=201)
async def create_user(user_in: UserCreate, db: AsyncSession = Depends(get_db)):
    existing_user = await db.execute(select(User).where(User.email == user_in.email))
    if existing_user.scalars().first():
        raise HTTPException(status_code=400, detail="Email already registered")
    
    hashed_password = user_in.password + "_hashed" # Dummy hashing
    new_user = User(email=user_in.email, password_hash=hashed_password)
    db.add(new_user)
    await db.commit()
    await db.refresh(new_user)
    return new_user

@users_router.get("/", response_model=List[UserSchema])
async def get_all_users(
    is_active: Optional[bool] = Query(None, description="Filter by active status"),
    email_contains: Optional[str] = Query(None, description="Filter by email substring"),
    db: AsyncSession = Depends(get_db)
):
    query = select(User)
    if is_active is not None:
        query = query.where(User.is_active == is_active)
    if email_contains:
        query = query.where(User.email.contains(email_contains))
    
    result = await db.execute(query)
    return result.scalars().all()

@users_router.get("/{user_id}", response_model=UserSchema)
async def get_user_by_id(user_id: uuid.UUID, db: AsyncSession = Depends(get_db)):
    user = await db.get(User, user_id)
    if not user:
        raise HTTPException(status_code=404, detail="User not found")
    return user

@users_router.delete("/{user_id}", status_code=204)
async def delete_user(user_id: uuid.UUID, db: AsyncSession = Depends(get_db)):
    user = await db.get(User, user_id)
    if not user:
        raise HTTPException(status_code=404, detail="User not found")
    await db.delete(user)
    await db.commit()

@users_router.post("/{user_id}/roles/{role_name}", response_model=UserSchema)
async def assign_role(user_id: uuid.UUID, role_name: str, db: AsyncSession = Depends(get_db)):
    user = await db.get(User, user_id)
    if not user:
        raise HTTPException(status_code=404, detail="User not found")
    
    role_res = await db.execute(select(Role).where(Role.name == role_name))
    role = role_res.scalars().first()
    if not role:
        raise HTTPException(status_code=404, detail="Role not found")
    
    if role not in user.roles:
        user.roles.append(role)
        await db.commit()
        await db.refresh(user)
    
    return user

# --- File: routers/posts.py ---
posts_router = APIRouter(prefix="/posts", tags=["Posts"])

@posts_router.post("/", response_model=PostSchema, status_code=201)
async def create_post(user_id: uuid.UUID, post_in: PostCreate, db: AsyncSession = Depends(get_db)):
    user = await db.get(User, user_id)
    if not user:
        raise HTTPException(status_code=404, detail="User not found to associate post with")
    
    new_post = Post(**post_in.model_dump(), user_id=user_id)
    db.add(new_post)
    await db.commit()
    await db.refresh(new_post)
    return new_post

# --- File: main.py ---
app = FastAPI(title="Variation 3: All-in-One Router")
app.include_router(users_router)
app.include_router(posts_router)

@app.on_event("startup")
async def startup_event():
    async with engine.begin() as conn:
        # Use Alembic for production migrations
        await conn.run_sync(Base.metadata.create_all)
    
    async with SessionLocal() as session:
        role_res = await session.execute(select(Role).where(Role.name == "ADMIN"))
        if not role_res.scalars().first():
            session.add(Role(name="ADMIN"))
            session.add(Role(name="USER"))
            await session.commit()

@app.post("/transactional/create-user-and-post", response_model=UserSchema, tags=["Transactions"])
async def create_user_and_post_atomic(user_in: UserCreate, post_in: PostCreate, db: AsyncSession = Depends(get_db)):
    """Demonstrates a transaction with potential rollback."""
    try:
        async with db.begin(): # Manages commit/rollback automatically
            # Create User
            hashed_password = user_in.password + "_hashed"
            new_user = User(email=user_in.email, password_hash=hashed_password)
            db.add(new_user)
            await db.flush() # Ensures new_user.id is available

            # Simulate failure condition
            if post_in.title == "FAIL":
                raise ValueError("Simulated failure to trigger rollback")

            # Create Post
            new_post = Post(**post_in.model_dump(), user_id=new_user.id)
            db.add(new_post)
        
        # The user object is expired after the transaction, so we need to refresh it
        # to load the relationships correctly.
        await db.refresh(new_user)
        return new_user
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        # Catch other potential DB errors
        raise HTTPException(status_code=500, detail=f"An internal error occurred: {e}")

# --- Alembic Migration Setup ---
# The setup for Alembic would be identical to Variation 1,
# pointing to the models in `models_and_schemas.py` and the database URL in `database.py`.
# Run `alembic init alembic`, then configure `alembic/env.py` to use
# the async engine and target metadata from `models_and_schemas.Base.metadata`.
# Generate migrations with `alembic revision --autogenerate -m "Initial schema"`.