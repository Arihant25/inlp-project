# Variation 2: The "Monolithic" Single-File App
# This developer prefers simplicity for smaller projects. Everything is in one file,
# using functional route decorators directly on the app object.
# It's fast to write but less maintainable as the project grows.
#
# To run this code:
# 1. pip install Flask Flask-SQLAlchemy Flask-Migrate
# 2. Save as app.py
# 3. Run `python app.py`
# 4. In a separate terminal, you can initialize migrations (optional for in-memory):
#    export FLASK_APP=app.py
#    flask db init
#    flask db migrate -m "Initial migration."
#    flask db upgrade

import uuid
import enum
from datetime import datetime
from flask import Flask, jsonify, request
from flask_sqlalchemy import SQLAlchemy
from flask_migrate import Migrate
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship
from sqlalchemy import String, Text, ForeignKey, Enum, Boolean, DateTime, Table, Column
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.exc import IntegrityError

# --- App & DB Setup ---
app = Flask(__name__)
app.config['SQLALCHEMY_DATABASE_URI'] = 'sqlite:///:memory:'
app.config['SQLALCHEMY_TRACK_MODIFICATIONS'] = False

class Base(DeclarativeBase):
  pass

db = SQLAlchemy(app, model_class=Base)
migrate = Migrate(app, db)

# --- Enums & Models ---
class RoleType(enum.Enum):
    ADMIN = 'ADMIN'
    USER = 'USER'

class PostStatus(enum.Enum):
    DRAFT = 'DRAFT'
    PUBLISHED = 'PUBLISHED'

# Many-to-Many association table
users_roles_tbl = Table(
    'users_roles',
    db.metadata,
    Column('user_id', UUID(as_uuid=True), ForeignKey('user_account.id'), primary_key=True),
    Column('role_item.id', UUID(as_uuid=True), ForeignKey('role_item.id'), primary_key=True)
)

class UserAccount(db.Model):
    __tablename__ = 'user_account'
    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    email: Mapped[str] = mapped_column(String(120), unique=True, nullable=False)
    password_hash: Mapped[str] = mapped_column(String(255), nullable=False)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)
    
    # One-to-Many relationship
    posts: Mapped[list["PostItem"]] = relationship("PostItem", back_populates="author", cascade="all, delete-orphan")
    # Many-to-Many relationship
    roles: Mapped[list["RoleItem"]] = relationship("RoleItem", secondary=users_roles_tbl, back_populates="users")

class RoleItem(db.Model):
    __tablename__ = 'role_item'
    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    name: Mapped[RoleType] = mapped_column(Enum(RoleType), unique=True, nullable=False)
    users: Mapped[list["UserAccount"]] = relationship("UserAccount", secondary=users_roles_tbl, back_populates="roles")

class PostItem(db.Model):
    __tablename__ = 'post_item'
    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(ForeignKey('user_account.id'), nullable=False)
    title: Mapped[str] = mapped_column(String(200), nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    status: Mapped[PostStatus] = mapped_column(Enum(PostStatus), default=PostStatus.DRAFT)
    author: Mapped["UserAccount"] = relationship("UserAccount", back_populates="posts")

# --- Routes ---
@app.route('/users', methods=['POST'])
def create_user():
    """CRUD: Create a user and assign roles."""
    data = request.json
    if not data or not data.get('email') or not data.get('password'):
        return jsonify(error="Email and password are required"), 400

    try:
        new_user = UserAccount(email=data['email'], password_hash=data['password'])
        
        role_names = data.get('roles', ['USER'])
        roles = db.session.execute(db.select(RoleItem).where(RoleItem.name.in_([RoleType(r) for r in role_names]))).scalars().all()
        new_user.roles.extend(roles)
        
        db.session.add(new_user)
        db.session.commit()
        
        return jsonify(id=str(new_user.id), email=new_user.email), 201
    except IntegrityError:
        db.session.rollback() # Transaction Rollback
        return jsonify(error="Email already exists"), 409
    except Exception as e:
        db.session.rollback() # Transaction Rollback
        return jsonify(error=f"An unexpected error occurred: {e}"), 500

@app.route('/users', methods=['GET'])
def list_users():
    """Query Building: Get all users with filtering."""
    q = db.select(UserAccount)
    if 'active' in request.args:
        is_active = request.args.get('active', 'true').lower() == 'true'
        q = q.where(UserAccount.is_active == is_active)
    
    users = db.session.execute(q).scalars().all()
    
    result = [
        {'id': str(u.id), 'email': u.email, 'is_active': u.is_active, 'roles': [r.name.value for r in u.roles]}
        for u in users
    ]
    return jsonify(result)

@app.route('/users/<uuid:user_id>/posts', methods=['POST'])
def create_post_for_user(user_id):
    """CRUD: Create a post for a specific user."""
    user = db.session.get(UserAccount, user_id)
    if not user:
        return jsonify(error="User not found"), 404
    
    data = request.json
    if not data or not data.get('title') or not data.get('content'):
        return jsonify(error="Title and content are required"), 400
        
    new_post = PostItem(
        title=data['title'],
        content=data['content'],
        status=PostStatus(data.get('status', 'DRAFT').upper()),
        author=user
    )
    db.session.add(new_post)
    db.session.commit()
    
    return jsonify(id=str(new_post.id), title=new_post.title, status=new_post.status.value), 201

@app.route('/posts/<uuid:post_id>', methods=['PUT'])
def update_post(post_id):
    """CRUD: Update a post."""
    post = db.session.get(PostItem, post_id)
    if not post:
        return jsonify(error="Post not found"), 404
        
    data = request.json
    post.title = data.get('title', post.title)
    post.content = data.get('content', post.content)
    if 'status' in data:
        post.status = PostStatus(data['status'].upper())
        
    db.session.commit()
    return jsonify(message="Post updated successfully")

# --- App Context & Seeding ---
with app.app_context():
    db.create_all()
    # Seed roles if they don't exist
    if not db.session.execute(db.select(RoleItem)).first():
        print("Seeding initial roles...")
        db.session.add_all([
            RoleItem(name=RoleType.ADMIN),
            RoleItem(name=RoleType.USER)
        ])
        db.session.commit()

# --- Main Execution ---
if __name__ == '__main__':
    app.run(debug=True, port=5001)