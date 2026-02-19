# Variation 3: The "Service Layer" Pattern with Marshmallow
# This developer emphasizes separation of concerns by introducing a service layer
# for business logic and using Marshmallow for robust serialization/deserialization.
# Routes (views) are thin and delegate all work to services.
#
# To run this code:
# 1. pip install Flask Flask-SQLAlchemy Flask-Migrate Flask-Marshmallow marshmallow-sqlalchemy
# 2. Save as a single file (e.g., run_service_app.py)
# 3. Run `python run_service_app.py`
# 4. In a separate terminal, you can initialize migrations (optional for in-memory):
#    export FLASK_APP=run_service_app.py
#    flask db init
#    flask db migrate -m "Initial migration."
#    flask db upgrade

import uuid
import enum
from datetime import datetime
from flask import Flask, jsonify, request
from flask_sqlalchemy import SQLAlchemy
from flask_migrate import Migrate
from flask_marshmallow import Marshmallow
from marshmallow_sqlalchemy import SQLAlchemyAutoSchema, auto_field
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship
from sqlalchemy import String, Text, ForeignKey, Enum, Boolean, DateTime, Table, Column
from sqlalchemy.dialects.postgresql import UUID

# --- Core App Objects ---
class Base(DeclarativeBase): pass
db = SQLAlchemy(model_class=Base)
migrate = Migrate()
ma = Marshmallow()

# --- Domain Models ---
class RoleEnum(enum.Enum):
    ADMIN = 'ADMIN'
    USER = 'USER'

class PostStatusEnum(enum.Enum):
    DRAFT = 'DRAFT'
    PUBLISHED = 'PUBLISHED'

users_roles = db.Table(
    'users_roles',
    db.Column('user_id', UUID(as_uuid=True), db.ForeignKey('user.id'), primary_key=True),
    db.Column('role_id', UUID(as_uuid=True), db.ForeignKey('role.id'), primary_key=True)
)

class User(db.Model):
    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    email: Mapped[str] = mapped_column(String(120), unique=True, nullable=False)
    password_hash: Mapped[str] = mapped_column(String(255), nullable=False)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)
    posts: Mapped[list["Post"]] = relationship(back_populates="author", cascade="all, delete-orphan")
    roles: Mapped[list["Role"]] = relationship(secondary=users_roles, back_populates="users")

class Role(db.Model):
    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    name: Mapped[RoleEnum] = mapped_column(Enum(RoleEnum), unique=True, nullable=False)
    users: Mapped[list["User"]] = relationship(secondary=users_roles, back_populates="roles")

class Post(db.Model):
    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(ForeignKey('user.id'), nullable=False)
    title: Mapped[str] = mapped_column(String(200), nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    status: Mapped[PostStatusEnum] = mapped_column(Enum(PostStatusEnum), default=PostStatusEnum.DRAFT)
    author: Mapped["User"] = relationship(back_populates="posts")

# --- Schemas (Serialization/Deserialization) ---
class RoleSchema(SQLAlchemyAutoSchema):
    class Meta:
        model = Role
        load_instance = True
        include_fk = True
    name = auto_field(column_name='name', dump_only=True)

class PostSchema(SQLAlchemyAutoSchema):
    class Meta:
        model = Post
        load_instance = True
        include_fk = True
    status = auto_field(column_name='status', dump_only=True)

class UserSchema(SQLAlchemyAutoSchema):
    class Meta:
        model = User
        load_instance = True
        include_relationships = True
    posts = ma.Nested(PostSchema, many=True, exclude=('author',))
    roles = ma.Nested(RoleSchema, many=True, exclude=('users',))

user_schema = UserSchema()
users_schema = UserSchema(many=True)
post_schema = PostSchema()

# --- Service Layer (Business Logic) ---
class UserService:
    def find_all(self, filter_params):
        query = db.select(User)
        if 'is_active' in filter_params:
            is_active = filter_params['is_active'].lower() in ['true', '1']
            query = query.where(User.is_active == is_active)
        return db.session.execute(query).scalars().all()

    def find_by_id(self, user_id):
        return db.session.get(User, user_id)

    def create_with_roles(self, user_data):
        """Transaction and Many-to-Many Demo"""
        try:
            new_user = User(email=user_data['email'], password_hash=user_data['password'])
            role_names = user_data.get('roles', ['USER'])
            roles = db.session.execute(db.select(Role).where(Role.name.in_([RoleEnum(r) for r in role_names]))).scalars().all()
            if not roles:
                raise ValueError("Invalid or no roles provided.")
            new_user.roles.extend(roles)
            db.session.add(new_user)
            db.session.commit()
            return new_user
        except Exception:
            db.session.rollback()
            raise

    def delete(self, user_id):
        user = self.find_by_id(user_id)
        if user:
            db.session.delete(user)
            db.session.commit()
        return user

# --- Views/Controllers (Routes) ---
from flask import Blueprint

api_v3 = Blueprint('api_v3', __name__)
user_service = UserService()

@api_v3.route('/users', methods=['GET'])
def list_users():
    """Query Building via Service"""
    users = user_service.find_all(request.args)
    return jsonify(users_schema.dump(users))

@api_v3.route('/users', methods=['POST'])
def create_user():
    try:
        user = user_service.create_with_roles(request.json)
        return jsonify(user_schema.dump(user)), 201
    except Exception as e:
        return jsonify({'error': str(e)}), 400

@api_v3.route('/users/<uuid:user_id>', methods=['GET'])
def get_user(user_id):
    """One-to-Many Demo via Service"""
    user = user_service.find_by_id(user_id)
    if not user:
        return jsonify({'error': 'User not found'}), 404
    return jsonify(user_schema.dump(user))

@api_v3.route('/users/<uuid:user_id>', methods=['DELETE'])
def delete_user(user_id):
    user = user_service.delete(user_id)
    if not user:
        return jsonify({'error': 'User not found'}), 404
    return '', 204

# --- App Factory ---
def create_flask_app():
    app = Flask(__name__)
    app.config['SQLALCHEMY_DATABASE_URI'] = 'sqlite:///:memory:'
    app.config['SQLALCHEMY_TRACK_MODIFICATIONS'] = False

    db.init_app(app)
    migrate.init_app(app, db)
    ma.init_app(app)

    app.register_blueprint(api_v3, url_prefix='/api')

    with app.app_context():
        db.create_all()
        if not Role.query.first():
            print("Seeding roles for service app...")
            db.session.add_all([Role(name=RoleEnum.ADMIN), Role(name=RoleEnum.USER)])
            db.session.commit()
    return app

# --- Main Execution ---
if __name__ == '__main__':
    app = create_flask_app()
    app.run(debug=True, port=5002)