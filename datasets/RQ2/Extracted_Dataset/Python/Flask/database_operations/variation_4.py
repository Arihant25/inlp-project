# Variation 4: The "Class-Based Views" / Flask-RESTful Style
# This developer prefers an object-oriented approach to API design, using a library
# like Flask-RESTful to structure endpoints as classes (Resources). This groups
# HTTP methods for a given resource together.
#
# To run this code:
# 1. pip install Flask Flask-SQLAlchemy Flask-Migrate flask-restful
# 2. Save as a single file (e.g., run_restful_app.py)
# 3. Run `python run_restful_app.py`
# 4. In a separate terminal, you can initialize migrations (optional for in-memory):
#    export FLASK_APP=run_restful_app.py
#    flask db init
#    flask db migrate -m "Initial migration."
#    flask db upgrade

import uuid
import enum
from datetime import datetime
from flask import Flask, request
from flask_sqlalchemy import SQLAlchemy
from flask_migrate import Migrate
from flask_restful import Api, Resource, reqparse
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship
from sqlalchemy import String, Text, ForeignKey, Enum, Boolean, DateTime, Table, Column
from sqlalchemy.dialects.postgresql import UUID

# --- Basic App Setup ---
app = Flask(__name__)
app.config['SQLALCHEMY_DATABASE_URI'] = 'sqlite:///:memory:'
app.config['SQLALCHEMY_TRACK_MODIFICATIONS'] = False
api = Api(app)

# --- DB and ORM Setup ---
class Base(DeclarativeBase): pass
db = SQLAlchemy(app, model_class=Base)
migrate = Migrate(app, db)

# --- Model Definitions ---
class RoleName(enum.Enum):
    ADMIN = 'ADMIN'
    USER = 'USER'

class PostState(enum.Enum):
    DRAFT = 'DRAFT'
    PUBLISHED = 'PUBLISHED'

users_roles_table = Table(
    'users_roles_link', db.metadata,
    Column('user_id', UUID(as_uuid=True), ForeignKey('users.id'), primary_key=True),
    Column('role_id', UUID(as_uuid=True), ForeignKey('roles.id'), primary_key=True)
)

class UserModel(db.Model):
    __tablename__ = 'users'
    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    email: Mapped[str] = mapped_column(String(120), unique=True, nullable=False)
    password_hash: Mapped[str] = mapped_column(String(255), nullable=False)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)
    posts: Mapped[list["PostModel"]] = relationship(back_populates="author", cascade="all, delete-orphan")
    roles: Mapped[list["RoleModel"]] = relationship(secondary=users_roles_table, back_populates="users")

class RoleModel(db.Model):
    __tablename__ = 'roles'
    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    name: Mapped[RoleName] = mapped_column(Enum(RoleName), unique=True, nullable=False)
    users: Mapped[list["UserModel"]] = relationship(secondary=users_roles_table, back_populates="roles")

class PostModel(db.Model):
    __tablename__ = 'posts'
    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(ForeignKey('users.id'), nullable=False)
    title: Mapped[str] = mapped_column(String(200), nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    status: Mapped[PostState] = mapped_column(Enum(PostState), default=PostState.DRAFT)
    author: Mapped["UserModel"] = relationship(back_populates="posts")

# --- Helper for Serialization ---
def serialize_user(user):
    return {
        'id': str(user.id),
        'email': user.email,
        'is_active': user.is_active,
        'created_at': user.created_at.isoformat(),
        'roles': [role.name.value for role in user.roles],
        'posts': [serialize_post(p) for p in user.posts]
    }

def serialize_post(post):
    return {
        'id': str(post.id),
        'title': post.title,
        'status': post.status.value
    }

# --- Resource Classes (Class-Based Views) ---
class UserListResource(Resource):
    def get(self):
        """Query Building with Filters"""
        parser = reqparse.RequestParser()
        parser.add_argument('is_active', type=bool, location='args')
        args = parser.parse_args()

        query = db.select(UserModel)
        if args['is_active'] is not None:
            query = query.where(UserModel.is_active == args['is_active'])
        
        users = db.session.execute(query).scalars().all()
        return [serialize_user(u) for u in users], 200

    def post(self):
        """Transaction and Many-to-Many Demo"""
        parser = reqparse.RequestParser()
        parser.add_argument('email', type=str, required=True, help='Email cannot be blank')
        parser.add_argument('password', type=str, required=True, help='Password cannot be blank')
        parser.add_argument('roles', type=str, action='append', default=['USER'])
        args = parser.parse_args()

        if db.session.execute(db.select(UserModel).where(UserModel.email == args['email'])).scalar_one_or_none():
            return {'message': 'User with that email already exists'}, 400

        try:
            user = UserModel(email=args['email'], password_hash=args['password'])
            
            role_enums = [RoleName(r) for r in args['roles']]
            roles = db.session.execute(db.select(RoleModel).where(RoleModel.name.in_(role_enums))).scalars().all()
            user.roles.extend(roles)

            db.session.add(user)
            db.session.commit()
            return serialize_user(user), 201
        except Exception as e:
            db.session.rollback() # Transaction Rollback
            return {'message': f'An error occurred: {e}'}, 500

class UserResource(Resource):
    def get(self, user_id):
        """One-to-Many Demo"""
        user = db.session.get(UserModel, uuid.UUID(user_id))
        if not user:
            return {'message': 'User not found'}, 404
        return serialize_user(user), 200

    def delete(self, user_id):
        user = db.session.get(UserModel, uuid.UUID(user_id))
        if not user:
            return {'message': 'User not found'}, 404
        db.session.delete(user)
        db.session.commit()
        return '', 204

class PostListResource(Resource):
    def post(self, user_id):
        user = db.session.get(UserModel, uuid.UUID(user_id))
        if not user:
            return {'message': 'User not found'}, 404

        parser = reqparse.RequestParser()
        parser.add_argument('title', type=str, required=True)
        parser.add_argument('content', type=str, required=True)
        args = parser.parse_args()

        post = PostModel(title=args['title'], content=args['content'], author=user)
        db.session.add(post)
        db.session.commit()
        return serialize_post(post), 201

# --- API Route Definitions ---
api.add_resource(UserListResource, '/users')
api.add_resource(UserResource, '/users/<string:user_id>')
api.add_resource(PostListResource, '/users/<string:user_id>/posts')

# --- App Context and Seeding ---
with app.app_context():
    db.create_all()
    if not db.session.execute(db.select(RoleModel)).first():
        print("Seeding roles for RESTful app...")
        db.session.add_all([
            RoleModel(name=RoleName.ADMIN),
            RoleModel(name=RoleName.USER)
        ])
        db.session.commit()

# --- Main Execution ---
if __name__ == '__main__':
    app.run(debug=True, port=5003)