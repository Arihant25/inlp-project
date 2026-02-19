# VARIATION 1: The Classic Service Layer Developer
# This approach separates concerns into distinct layers:
# - main.py: API routing and application setup.
# - database.py: Database connection and session management.
# - models.py: SQLAlchemy ORM models.
# - schemas.py: Pydantic data transfer objects.
# - services.py: Business logic and database operations (CRUD).
# - alembic/: Directory for database migrations.

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
from pydantic import BaseModel, EmailStr, Field
from sqlalchemy import (create_async_engine, String, Text, Boolean,
                        ForeignKey, Enum, Table, Column, select)
from sqlalchemy.ext.asyncio import async_sessionmaker, AsyncSession
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship
from sqlalchemy.dialects.postgresql import UUID as PG_UUID

# --- File: database.py ---
DATABASE_URL = "sqlite+aiosqlite:///./test_variation1.db"

engine = create_async_engine(DATABASE_URL, connect_args={"check_same_thread": False})
AsyncSessionFactory = async_sessionmaker(engine, expire_on_commit=False)

async def get_db_session() -> AsyncGenerator[AsyncSession, None]:
    """Dependency to get a database session."""
    async with AsyncSessionFactory() as session:
        yield session

# --- File: models.py ---
class Base(DeclarativeBase):
    pass

class PostStatus(enum.Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

user_roles_association = Table(
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
    roles: Mapped[List["Role"]] = relationship("Role", secondary=user_roles_association, back_populates="users", lazy="selectin")

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
    
    users: Mapped[List["User"]] = relationship("User", secondary=user_roles_association, back_populates="roles")


# --- File: schemas.py ---
# Role Schemas
class RoleBase(BaseModel):
    name: str

class RoleCreate(RoleBase):
    pass

class RoleSchema(RoleBase):
    id: uuid.UUID
    class Config:
        from_attributes = True

# Post Schemas
class PostBase(BaseModel):
    title: str
    content: str
    status: PostStatus = PostStatus.DRAFT

class PostCreate(PostBase):
    pass

class PostSchema(PostBase):
    id: uuid.UUID
    user_id: uuid.UUID
    class Config:
        from_attributes = True

# User Schemas
class UserBase(BaseModel):
    email: EmailStr

class UserCreate(UserBase):
    password: str

class UserSchema(UserBase):
    id: uuid.UUID
    is_active: bool
    created_at: datetime
    posts: List[PostSchema] = []
    roles: List[RoleSchema] = []
    class Config:
        from_attributes = True

class UserUpdate(BaseModel):
    is_active: Optional[bool] = None

# Transaction Schema
class UserWithPostCreate(BaseModel):
    user: UserCreate
    post: PostCreate

# --- File: services.py ---
class UserService:
    async def get_user(self, db: AsyncSession, user_id: uuid.UUID) -> Optional[User]:
        result = await db.execute(select(User).where(User.id == user_id))
        return result.scalars().first()

    async def get_user_by_email(self, db: AsyncSession, email: str) -> Optional[User]:
        result = await db.execute(select(User).where(User.email == email))
        return result.scalars().first()

    async def get_users(self, db: AsyncSession, is_active: Optional[bool] = None, email_contains: Optional[str] = None, skip: int = 0, limit: int = 100) -> List[User]:
        query = select(User).offset(skip).limit(limit)
        if is_active is not None:
            query = query.where(User.is_active == is_active)
        if email_contains:
            query = query.where(User.email.contains(email_contains))
        result = await db.execute(query)
        return result.scalars().all()

    async def create_user(self, db: AsyncSession, user_data: UserCreate) -> User:
        # In a real app, hash the password here
        hashed_password = user_data.password + "_hashed"
        db_user = User(email=user_data.email, password_hash=hashed_password)
        db.add(db_user)
        await db.commit()
        await db.refresh(db_user)
        return db_user

    async def update_user(self, db: AsyncSession, user: User, update_data: UserUpdate) -> User:
        if update_data.is_active is not None:
            user.is_active = update_data.is_active
        await db.commit()
        await db.refresh(user)
        return user

    async def delete_user(self, db: AsyncSession, user_id: uuid.UUID) -> bool:
        user = await self.get_user(db, user_id)
        if not user:
            return False
        await db.delete(user)
        await db.commit()
        return True

class PostService:
    async def create_user_post(self, db: AsyncSession, post_data: PostCreate, user_id: uuid.UUID) -> Post:
        db_post = Post(**post_data.model_dump(), user_id=user_id)
        db.add(db_post)
        await db.commit()
        await db.refresh(db_post)
        return db_post

class RoleService:
    async def get_role_by_name(self, db: AsyncSession, name: str) -> Optional[Role]:
        result = await db.execute(select(Role).where(Role.name == name))
        return result.scalars().first()

    async def create_role(self, db: AsyncSession, role_data: RoleCreate) -> Role:
        db_role = Role(name=role_data.name)
        db.add(db_role)
        await db.commit()
        await db.refresh(db_role)
        return db_role

    async def assign_role_to_user(self, db: AsyncSession, user: User, role: Role) -> User:
        if role not in user.roles:
            user.roles.append(role)
            await db.commit()
            await db.refresh(user)
        return user

class TransactionService:
    async def create_user_and_post_transactional(self, db: AsyncSession, data: UserWithPostCreate) -> User:
        async with db.begin(): # begin() starts a transaction and handles commit/rollback
            # In a real app, hash the password
            hashed_password = data.user.password + "_hashed"
            db_user = User(email=data.user.email, password_hash=hashed_password)
            db.add(db_user)
            await db.flush() # flush to get the user ID before commit

            # This will cause a rollback if the title is "FAIL"
            if data.post.title == "FAIL":
                raise ValueError("Simulating a failed operation.")

            db_post = Post(**data.post.model_dump(), user_id=db_user.id)
            db.add(db_post)
        
        # Refresh the user outside the transaction to load the new post
        await db.refresh(db_user)
        return db_user

# Instantiate services
user_service = UserService()
post_service = PostService()
role_service = RoleService()
transaction_service = TransactionService()

# --- File: main.py ---
app = FastAPI(title="Variation 1: Service Layer")

@app.on_event("startup")
async def on_startup():
    async with engine.begin() as conn:
        # In a real app, you'd use Alembic migrations.
        # This is for demonstration purposes.
        await conn.run_sync(Base.metadata.create_all)
        
        # Seed initial data
        async with AsyncSessionFactory() as session:
            admin_role = await role_service.get_role_by_name(session, "ADMIN")
            if not admin_role:
                admin_role = await role_service.create_role(session, RoleCreate(name="ADMIN"))
            
            user_role = await role_service.get_role_by_name(session, "USER")
            if not user_role:
                user_role = await role_service.create_role(session, RoleCreate(name="USER"))


# --- User Routes ---
@app.post("/users/", response_model=UserSchema, status_code=201)
async def create_user_endpoint(user: UserCreate, db: AsyncSession = Depends(get_db_session)):
    db_user = await user_service.get_user_by_email(db, email=user.email)
    if db_user:
        raise HTTPException(status_code=400, detail="Email already registered")
    return await user_service.create_user(db, user_data=user)

@app.get("/users/", response_model=List[UserSchema])
async def read_users_endpoint(
    is_active: Optional[bool] = Query(None, description="Filter by active status"),
    email_contains: Optional[str] = Query(None, description="Filter by email substring"),
    skip: int = 0, 
    limit: int = 100, 
    db: AsyncSession = Depends(get_db_session)
):
    users = await user_service.get_users(db, is_active=is_active, email_contains=email_contains, skip=skip, limit=limit)
    return users

@app.get("/users/{user_id}", response_model=UserSchema)
async def read_user_endpoint(user_id: uuid.UUID, db: AsyncSession = Depends(get_db_session)):
    db_user = await user_service.get_user(db, user_id=user_id)
    if db_user is None:
        raise HTTPException(status_code=404, detail="User not found")
    return db_user

@app.patch("/users/{user_id}", response_model=UserSchema)
async def update_user_endpoint(user_id: uuid.UUID, user_update: UserUpdate, db: AsyncSession = Depends(get_db_session)):
    db_user = await user_service.get_user(db, user_id=user_id)
    if db_user is None:
        raise HTTPException(status_code=404, detail="User not found")
    return await user_service.update_user(db, user=db_user, update_data=user_update)

@app.delete("/users/{user_id}", status_code=204)
async def delete_user_endpoint(user_id: uuid.UUID, db: AsyncSession = Depends(get_db_session)):
    success = await user_service.delete_user(db, user_id=user_id)
    if not success:
        raise HTTPException(status_code=404, detail="User not found")
    return

# --- Post Routes ---
@app.post("/users/{user_id}/posts/", response_model=PostSchema, status_code=201)
async def create_post_for_user_endpoint(user_id: uuid.UUID, post: PostCreate, db: AsyncSession = Depends(get_db_session)):
    db_user = await user_service.get_user(db, user_id=user_id)
    if db_user is None:
        raise HTTPException(status_code=404, detail="User not found")
    return await post_service.create_user_post(db, post_data=post, user_id=user_id)

# --- Role & M2M Routes ---
@app.post("/roles/", response_model=RoleSchema, status_code=201)
async def create_role_endpoint(role: RoleCreate, db: AsyncSession = Depends(get_db_session)):
    return await role_service.create_role(db, role_data=role)

@app.post("/users/{user_id}/roles/{role_name}", response_model=UserSchema)
async def assign_role_to_user_endpoint(user_id: uuid.UUID, role_name: str, db: AsyncSession = Depends(get_db_session)):
    user = await user_service.get_user(db, user_id)
    if not user:
        raise HTTPException(status_code=404, detail="User not found")
    role = await role_service.get_role_by_name(db, role_name)
    if not role:
        raise HTTPException(status_code=404, detail="Role not found")
    return await role_service.assign_role_to_user(db, user, role)

# --- Transaction Route ---
@app.post("/users-with-post/", response_model=UserSchema, status_code=201)
async def create_user_and_post_transactional_endpoint(data: UserWithPostCreate, db: AsyncSession = Depends(get_db_session)):
    try:
        return await transaction_service.create_user_and_post_transactional(db, data)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception:
        raise HTTPException(status_code=500, detail="An unexpected error occurred.")

# --- Alembic Migration Setup ---
# To set up Alembic, run: `alembic init alembic`
# Then, modify alembic/env.py and create migration scripts.

# --- File: alembic/env.py (relevant parts) ---
"""
import asyncio
from logging.config import fileConfig

from sqlalchemy import pool
from sqlalchemy.engine import Connection
from sqlalchemy.ext.asyncio import async_engine_from_config

from alembic import context

# this is the Alembic Config object, which provides
# access to the values within the .ini file in use.
config = context.config

# Interpret the config file for Python logging.
# This line sets up loggers basically.
if config.config_file_name is not None:
    fileConfig(config.config_file_name)

# add your model's MetaData object here
# for 'autogenerate' support
# from myapp import mymodel
# target_metadata = mymodel.Base.metadata
# In our case:
from models import Base
target_metadata = Base.metadata

# other values from the config, defined by the needs of env.py,
# can be acquired:
# my_important_option = config.get_main_option("my_important_option")
# ... etc.
config.set_main_option("sqlalchemy.url", DATABASE_URL)


def run_migrations_offline() -> None:
    # ... (standard offline setup)
    ...

def do_run_migrations(connection: Connection) -> None:
    context.configure(connection=connection, target_metadata=target_metadata)

    with context.begin_transaction():
        context.run_migrations()


async def run_async_migrations() -> None:
    connectable = async_engine_from_config(
        config.get_section(config.config_ini_section, {}),
        prefix="sqlalchemy.",
        poolclass=pool.NullPool,
    )

    async with connectable.connect() as connection:
        await connection.run_sync(do_run_migrations)

    await connectable.dispose()


def run_migrations_online() -> None:
    asyncio.run(run_async_migrations())

if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
"""

# --- File: alembic/versions/xxxxxxxx_initial_migration.py ---
"""
# revision identifiers, used by Alembic.
revision = 'a1b2c3d4e5f6'
down_revision = None
branch_labels = None
depends_on = None

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

def upgrade() -> None:
    # ### commands auto generated by Alembic - please adjust! ###
    op.create_table('roles',
    sa.Column('id', postgresql.UUID(as_uuid=True), nullable=False),
    sa.Column('name', sa.String(length=50), nullable=False),
    sa.PrimaryKeyConstraint('id'),
    sa.UniqueConstraint('name')
    )
    op.create_table('users',
    sa.Column('id', postgresql.UUID(as_uuid=True), nullable=False),
    sa.Column('email', sa.String(length=255), nullable=False),
    sa.Column('password_hash', sa.String(length=255), nullable=False),
    sa.Column('is_active', sa.Boolean(), nullable=False),
    sa.Column('created_at', sa.DateTime(), nullable=False),
    sa.PrimaryKeyConstraint('id')
    )
    op.create_index(op.f('ix_users_email'), 'users', ['email'], unique=True)
    op.create_table('posts',
    sa.Column('id', postgresql.UUID(as_uuid=True), nullable=False),
    sa.Column('user_id', postgresql.UUID(as_uuid=True), nullable=False),
    sa.Column('title', sa.String(length=200), nullable=False),
    sa.Column('content', sa.Text(), nullable=False),
    sa.Column('status', sa.Enum('DRAFT', 'PUBLISHED', name='poststatus'), nullable=False),
    sa.ForeignKeyConstraint(['user_id'], ['users.id'], ),
    sa.PrimaryKeyConstraint('id')
    )
    op.create_table('user_roles',
    sa.Column('user_id', postgresql.UUID(as_uuid=True), nullable=False),
    sa.Column('role_id', postgresql.UUID(as_uuid=True), nullable=False),
    sa.ForeignKeyConstraint(['role_id'], ['roles.id'], ),
    sa.ForeignKeyConstraint(['user_id'], ['users.id'], ),
    sa.PrimaryKeyConstraint('user_id', 'role_id')
    )
    # ### end Alembic commands ###


def downgrade() -> None:
    # ### commands auto generated by Alembic - please adjust! ###
    op.drop_table('user_roles')
    op.drop_table('posts')
    op.drop_index(op.f('ix_users_email'), table_name='users')
    op.drop_table('users')
    op.drop_table('roles')
    # ### end Alembic commands ###
"""