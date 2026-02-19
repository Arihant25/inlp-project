# Variation 1: The "Classic" App Factory Pattern with Blueprints
# This developer prefers a structured, scalable approach using application factories
# and blueprints. Logic is kept close to the routes, but organized by domain.
#
# To run this code:
# 1. pip install Flask Flask-SQLAlchemy Flask-Migrate
# 2. Save as a single file (e.g., run.py)
# 3. Run `python run.py`
# 4. In a separate terminal, you can initialize migrations (optional for in-memory):
#    export FLASK_APP=run.py
#    flask db init
#    flask db migrate -m "Initial migration."
#    flask db upgrade

import os
import uuid
import enum
from datetime import datetime
from flask import Flask, jsonify, request
from flask.views import MethodView
from flask_sqlalchemy import SQLAlchemy
from flask_migrate import Migrate
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship
from sqlalchemy import String, Text, ForeignKey, Enum, Boolean, DateTime, Table, Column
from sqlalchemy.dialects.postgresql import UUID

# --- Configuration ---
class Config:
    SQLALCHEMY_DATABASE_URI = os.environ.get('DATABASE_URL') or 'sqlite:///:memory:'
    SQLALCHEMY_TRACK_MODIFICATIONS = False

# --- Database Initialization ---
class Base(DeclarativeBase):
  pass

db = SQLAlchemy(model_class=Base)
migrate = Migrate()

# --- Models ---
class RoleEnum(enum.Enum):
    ADMIN = 'ADMIN'
    USER = 'USER'

class PostStatusEnum(enum.Enum):
    DRAFT = 'DRAFT'
    PUBLISHED = 'PUBLISHED'

users_roles_association = Table(
    'users_roles',
    db.metadata,
    Column('user_id', UUID(as_uuid=True), ForeignKey('users.id'), primary_key=True),
    Column('role_id', UUID(as_uuid=True), ForeignKey('roles.id'), primary_key=True)
)

class User(db.Model):
    __tablename__ = 'users'
    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    email: Mapped[str] = mapped_column(String(120), unique=True, nullable=False)
    password_hash: Mapped[str] = mapped_column(String(255), nullable=False)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)

    posts: Mapped[list["Post"]] = relationship("Post", back_populates="author", cascade="all, delete-orphan")
    roles: Mapped[list["Role"]] = relationship("Role", secondary=users_roles_association, back_populates="users")

    def to_dict(self):
        return {
            'id': str(self.id),
            'email': self.email,
            'is_active': self.is_active,
            'created_at': self.created_at.isoformat(),
            'roles': [role.name for role in self.roles],
            'post_count': len(self.posts)
        }

class Role(db.Model):
    __tablename__ = 'roles'
    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    name: Mapped[RoleEnum] = mapped_column(Enum(RoleEnum), unique=True, nullable=False)
    
    users: Mapped[list["User"]] = relationship("User", secondary=users_roles_association, back_populates="roles")

class Post(db.Model):
    __tablename__ = 'posts'
    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(ForeignKey('users.id'), nullable=False)
    title: Mapped[str] = mapped_column(String(200), nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    status: Mapped[PostStatusEnum] = mapped_column(Enum(PostStatusEnum), default=PostStatusEnum.DRAFT)

    author: Mapped["User"] = relationship("User", back_populates="posts")

    def to_dict(self):
        return {
            'id': str(self.id),
            'user_id': str(self.user_id),
            'title': self.title,
            'content': self.content,
            'status': self.status.value
        }

# --- API Blueprint ---
from flask import Blueprint

api_bp = Blueprint('api', __name__)

@api_bp.route('/users', methods=['POST'])
def create_user_with_roles():
    """Creates a user and assigns roles in a single transaction."""
    data = request.get_json()
    if not data or 'email' not in data or 'password' not in data:
        return jsonify({'error': 'Missing required fields'}), 400

    # Transaction Demo: Create user and assign roles atomically
    try:
        admin_role = db.session.execute(db.select(Role).filter_by(name=RoleEnum.ADMIN)).scalar_one_or_none()
        user_role = db.session.execute(db.select(Role).filter_by(name=RoleEnum.USER)).scalar_one_or_none()
        
        if not admin_role or not user_role:
             return jsonify({'error': 'Roles not initialized in DB'}), 500

        new_user = User(email=data['email'], password_hash=data['password']) # In prod, hash the password
        
        # Assign roles from request, default to USER
        requested_roles = data.get('roles', ['USER'])
        if 'ADMIN' in requested_roles:
            new_user.roles.append(admin_role)
        if 'USER' in requested_roles:
            new_user.roles.append(user_role)

        db.session.add(new_user)
        db.session.commit()
        return jsonify(new_user.to_dict()), 201
    except Exception as e:
        db.session.rollback() # Rollback on error
        return jsonify({'error': str(e)}), 500

@api_bp.route('/users', methods=['GET'])
def get_users():
    """Query Building Demo: Gets users with optional filters."""
    query = db.select(User)
    
    is_active_filter = request.args.get('is_active')
    if is_active_filter is not None:
        is_active = is_active_filter.lower() in ['true', '1', 't']
        query = query.where(User.is_active == is_active)
        
    users = db.session.execute(query).scalars().all()
    return jsonify([user.to_dict() for user in users])

@api_pbp.route('/users/<uuid:user_id>', methods=['GET'])
def get_user_with_posts(user_id):
    """One-to-Many Demo: Get a user and their posts."""
    user = db.session.get(User, user_id)
    if not user:
        return jsonify({'error': 'User not found'}), 404
    
    user_data = user.to_dict()
    user_data['posts'] = [post.to_dict() for post in user.posts]
    return jsonify(user_data)

@api_bp.route('/users/<uuid:user_id>', methods=['DELETE'])
def delete_user(user_id):
    """Demonstrates cascading delete."""
    user = db.session.get(User, user_id)
    if not user:
        return jsonify({'error': 'User not found'}), 404
    
    db.session.delete(user)
    db.session.commit()
    return '', 204

# --- Application Factory ---
def create_app(config_class=Config):
    app = Flask(__name__)
    app.config.from_object(config_class)

    db.init_app(app)
    migrate.init_app(app, db)

    app.register_blueprint(api_bp, url_prefix='/api')

    with app.app_context():
        db.create_all()
        # Seed initial data for roles (many-to-many)
        if not db.session.execute(db.select(Role)).first():
            print("Seeding roles...")
            admin_role = Role(name=RoleEnum.ADMIN)
            user_role = Role(name=RoleEnum.USER)
            db.session.add_all([admin_role, user_role])
            db.session.commit()

    return app

# --- Main Execution ---
if __name__ == '__main__':
    app = create_app()
    app.run(debug=True)