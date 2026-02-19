import os
import uuid
import enum
from datetime import datetime
from functools import wraps

from flask import Flask, jsonify, request, redirect, url_for, Blueprint, current_app
from flask_login import LoginManager, UserMixin, login_user, logout_user, current_user, login_required
from flask_bcrypt import Bcrypt
from flask_jwt_extended import create_access_token, jwt_required, get_jwt_identity, JWTManager
from authlib.integrations.flask_client import OAuth

# --- In a real app, this would be in models.py ---
class Role(enum.Enum):
    ADMIN = 'ADMIN'
    USER = 'USER'

class PostStatus(enum.Enum):
    DRAFT = 'DRAFT'
    PUBLISHED = 'PUBLISHED'

class UserModel(UserMixin):
    def __init__(self, id, email, password_hash, role=Role.USER, is_active=True):
        self.id = id
        self.email = email
        self.password_hash = password_hash
        self.role = role
        self.is_active = is_active
        self.created_at = datetime.utcnow()

# --- In a real app, this would be in services.py or a database module ---
class UserService:
    def __init__(self, users_storage):
        self._users = users_storage

    def find_by_id(self, user_id):
        return self._users.get(user_id)

    def find_by_email(self, email):
        for user in self._users.values():
            if user.email == email:
                return user
        return None
    
    def create_oauth_user(self, email):
        new_id = str(uuid.uuid4())
        user = UserModel(id=new_id, email=email, password_hash=None, role=Role.USER)
        self._users[new_id] = user
        return user

# --- Mock Database ---
MOCK_USERS_DB = {}
MOCK_POSTS_DB = {}

# --- In a real app, this would be in extensions.py ---
bcrypt = Bcrypt()
jwt = JWTManager()
login_manager = LoginManager()
oauth = OAuth()

# --- RBAC Decorator ---
def role_required(role_name):
    def decorator(fn):
        @wraps(fn)
        def wrapper(*args, **kwargs):
            user_id = get_jwt_identity()
            user_service = UserService(MOCK_USERS_DB)
            user = user_service.find_by_id(user_id)
            if not user or user.role.value != role_name:
                return jsonify(message="Permission denied: Insufficient role"), 403
            return fn(*args, **kwargs)
        return wrapper
    return decorator

# --- In a real app, this would be in auth/routes.py ---
auth_bp = Blueprint('auth', __name__)

@auth_bp.route('/login', methods=['POST'])
def session_login():
    email = request.form['email']
    password = request.form['password']
    user_service = UserService(MOCK_USERS_DB)
    user = user_service.find_by_email(email)
    if user and bcrypt.check_password_hash(user.password_hash, password):
        login_user(user)
        return redirect(url_for('main.profile'))
    return 'Invalid credentials', 401

@auth_bp.route('/logout')
@login_required
def session_logout():
    logout_user()
    return redirect(url_for('main.index'))

@auth_bp.route('/oauth/login/<provider>')
def oauth_login(provider):
    client = oauth.create_client(provider)
    if not client:
        return "Invalid provider", 404
    redirect_uri = url_for('auth.oauth_callback', provider=provider, _external=True)
    return client.authorize_redirect(redirect_uri)

@auth_bp.route('/oauth/callback/<provider>')
def oauth_callback(provider):
    client = oauth.create_client(provider)
    token = client.authorize_access_token()
    user_info = client.get('userinfo', token=token).json()
    email = user_info['email']
    
    user_service = UserService(MOCK_USERS_DB)
    user = user_service.find_by_email(email)
    if not user:
        user = user_service.create_oauth_user(email)
    
    login_user(user)
    return redirect(url_for('main.profile'))

# --- In a real app, this would be in api/routes.py ---
api_bp = Blueprint('api', __name__, url_prefix='/api')

@api_bp.route('/token', methods=['POST'])
def get_token():
    email = request.json.get('email')
    password = request.json.get('password')
    user_service = UserService(MOCK_USERS_DB)
    user = user_service.find_by_email(email)
    if not user or not bcrypt.check_password_hash(user.password_hash, password):
        return jsonify({"error": "Invalid credentials"}), 401
    
    access_token = create_access_token(identity=user.id)
    return jsonify(access_token=access_token)

@api_bp.route('/posts')
@jwt_required()
def list_posts():
    current_user_id = get_jwt_identity()
    posts = [p for p in MOCK_POSTS_DB.values() if p['user_id'] == current_user_id]
    return jsonify(posts)

@api_bp.route('/admin/stats')
@jwt_required()
@role_required('ADMIN')
def admin_stats():
    return jsonify({
        "users_count": len(MOCK_USERS_DB),
        "posts_count": len(MOCK_POSTS_DB)
    })

# --- In a real app, this would be in a main/routes.py or similar ---
main_bp = Blueprint('main', __name__)

@main_bp.route('/')
def index():
    return 'Welcome to the Blueprint-based App!'

@main_bp.route('/profile')
@login_required
def profile():
    return f'Logged in as {current_user.email} (Role: {current_user.role.value})'

# --- Application Factory ---
def create_app():
    app = Flask(__name__)
    app.config.update(
        SECRET_KEY='blueprint_secret_key',
        JWT_SECRET_KEY='blueprint_jwt_secret',
        GOOGLE_CLIENT_ID='your-google-client-id',
        GOOGLE_CLIENT_SECRET='your-google-client-secret',
    )

    # Initialize extensions
    bcrypt.init_app(app)
    jwt.init_app(app)
    login_manager.init_app(app)
    oauth.init_app(app)

    oauth.register(
        name='google',
        client_id=app.config["GOOGLE_CLIENT_ID"],
        client_secret=app.config["GOOGLE_CLIENT_SECRET"],
        server_metadata_url='https://accounts.google.com/.well-known/openid-configuration',
        client_kwargs={'scope': 'openid email profile'}
    )

    @login_manager.user_loader
    def load_user(user_id):
        user_service = UserService(MOCK_USERS_DB)
        return user_service.find_by_id(user_id)

    # Register blueprints
    app.register_blueprint(auth_bp)
    app.register_blueprint(api_bp)
    app.register_blueprint(main_bp)

    # Initialize mock data within app context
    with app.app_context():
        admin_id = str(uuid.uuid4())
        user_id = str(uuid.uuid4())
        MOCK_USERS_DB[admin_id] = UserModel(
            id=admin_id, email='admin@example.com',
            password_hash=bcrypt.generate_password_hash('adminpass').decode('utf-8'),
            role=Role.ADMIN
        )
        MOCK_USERS_DB[user_id] = UserModel(
            id=user_id, email='user@example.com',
            password_hash=bcrypt.generate_password_hash('userpass').decode('utf-8'),
            role=Role.USER
        )
        post_id = str(uuid.uuid4())
        MOCK_POSTS_DB[post_id] = {
            'id': post_id, 'user_id': user_id, 'title': 'My Post', 'content': '...'}

    return app

if __name__ == '__main__':
    app = create_app()
    app.run(debug=True, port=5002)