import os
import uuid
import enum
from datetime import datetime
from functools import wraps

from flask import Flask, jsonify, request, redirect, url_for, g
from flask.views import MethodView
from flask_login import LoginManager, UserMixin, login_user, logout_user, current_user, login_required
from flask_bcrypt import Bcrypt
from flask_jwt_extended import create_access_token, jwt_required, get_jwt_identity, JWTManager
from authlib.integrations.flask_client import OAuth

# --- Data Models ---
class RoleEnum(enum.Enum):
    ADMIN = 'ADMIN'
    USER = 'USER'

class UserEntity(UserMixin):
    def __init__(self, **kwargs):
        self.id = kwargs.get('id')
        self.email = kwargs.get('email')
        self.password_hash = kwargs.get('password_hash')
        self.role = kwargs.get('role', RoleEnum.USER)
        self.is_active = kwargs.get('is_active', True)
        self.created_at = kwargs.get('created_at', datetime.utcnow())

# --- Mock Data Store ---
class DataStore:
    USERS = {}
    POSTS = {}

    @classmethod
    def find_user_by_id(cls, user_id):
        return cls.USERS.get(user_id)

    @classmethod
    def find_user_by_email(cls, email):
        return next((u for u in cls.USERS.values() if u.email == email), None)

# --- Extensions ---
bcrypt = Bcrypt()
jwt_manager = JWTManager()
login_manager = LoginManager()
oauth_client = OAuth()

# --- Auth Decorators for MethodView ---
def jwt_role_required(role):
    def decorator(func):
        @wraps(func)
        def wrapper(*args, **kwargs):
            user_id = get_jwt_identity()
            user = DataStore.find_user_by_id(user_id)
            if not user or user.role != role:
                return jsonify(error="Forbidden"), 403
            g.current_user = user
            return func(*args, **kwargs)
        return jwt_required()(wrapper)
    return decorator

# --- Class-Based Views (Resources) ---
class TokenResource(MethodView):
    def post(self):
        payload = request.get_json()
        user_object = DataStore.find_user_by_email(payload.get('email'))
        if user_object and bcrypt.check_password_hash(user_object.password_hash, payload.get('password')):
            token = create_access_token(identity=user_object.id)
            return jsonify(access_token=token), 200
        return jsonify(error="Invalid credentials"), 401

class PostCollectionResource(MethodView):
    decorators = [jwt_required()]

    def get(self):
        user_id = get_jwt_identity()
        user_posts = [p for p in DataStore.POSTS.values() if p['user_id'] == user_id]
        return jsonify(posts=user_posts), 200

class AdminDashboardResource(MethodView):
    decorators = [jwt_role_required(RoleEnum.ADMIN)]

    def get(self):
        return jsonify(
            message=f"Welcome Admin, {g.current_user.email}",
            stats={"user_count": len(DataStore.USERS)}
        ), 200

class SessionLoginView(MethodView):
    def post(self):
        user_obj = DataStore.find_user_by_email(request.form.get('email'))
        if user_obj and bcrypt.check_password_hash(user_obj.password_hash, request.form.get('password')):
            login_user(user_obj)
            return redirect(url_for('profile'))
        return "Login failed.", 401

class OAuthLoginView(MethodView):
    def get(self, provider_name):
        client = oauth_client.create_client(provider_name)
        redirect_uri = url_for('oauth_callback', provider_name=provider_name, _external=True)
        return client.authorize_redirect(redirect_uri)

class OAuthCallbackView(MethodView):
    def get(self, provider_name):
        client = oauth_client.create_client(provider_name)
        token = client.authorize_access_token()
        user_info = client.parse_id_token(token)
        
        user = DataStore.find_user_by_email(user_info['email'])
        if not user:
            user_id = str(uuid.uuid4())
            user = UserEntity(id=user_id, email=user_info['email'], password_hash=None)
            DataStore.USERS[user_id] = user
        
        login_user(user)
        return redirect(url_for('profile'))

# --- Application Factory ---
def create_flask_app():
    app = Flask(__name__)
    app.config.from_mapping(
        SECRET_KEY='class_based_view_secret',
        JWT_SECRET_KEY='class_based_jwt_secret',
        GOOGLE_CLIENT_ID='your-google-client-id',
        GOOGLE_CLIENT_SECRET='your-google-client-secret',
    )

    # Init extensions
    bcrypt.init_app(app)
    jwt_manager.init_app(app)
    login_manager.init_app(app)
    oauth_client.init_app(app)

    oauth_client.register(
        name='google',
        server_metadata_url='https://accounts.google.com/.well-known/openid-configuration',
        client_id=app.config['GOOGLE_CLIENT_ID'],
        client_secret=app.config['GOOGLE_CLIENT_SECRET'],
        client_kwargs={'scope': 'openid email profile'}
    )

    @login_manager.user_loader
    def user_loader_callback(user_id):
        return DataStore.find_user_by_id(user_id)

    # Register URL rules for MethodViews
    app.add_url_rule('/api/tokens', view_func=TokenResource.as_view('token_api'))
    app.add_url_rule('/api/posts', view_func=PostCollectionResource.as_view('posts_api'))
    app.add_url_rule('/api/admin/dashboard', view_func=AdminDashboardResource.as_view('admin_api'))
    app.add_url_rule('/login', view_func=SessionLoginView.as_view('login'))
    app.add_url_rule('/oauth/login/<provider_name>', view_func=OAuthLoginView.as_view('oauth_login'))
    app.add_url_rule('/oauth/callback/<provider_name>', view_func=OAuthCallbackView.as_view('oauth_callback'))

    # Simple session-based routes
    @app.route('/profile')
    @login_required
    def profile():
        return f"Hello, {current_user.email}. Role: {current_user.role.value}"

    @app.route('/logout')
    @login_required
    def logout():
        logout_user()
        return "Logged out."

    # Init mock data
    with app.app_context():
        admin_id = str(uuid.uuid4())
        user_id = str(uuid.uuid4())
        DataStore.USERS = {
            admin_id: UserEntity(
                id=admin_id, email='admin@example.com',
                password_hash=bcrypt.generate_password_hash('complex_admin_pass').decode(),
                role=RoleEnum.ADMIN
            ),
            user_id: UserEntity(
                id=user_id, email='user@example.com',
                password_hash=bcrypt.generate_password_hash('complex_user_pass').decode(),
                role=RoleEnum.USER
            )
        }
        post_id = str(uuid.uuid4())
        DataStore.POSTS = {
            post_id: {'id': post_id, 'user_id': user_id, 'title': 'OOP Post', 'content': 'Content'}
        }

    return app

if __name__ == '__main__':
    my_app = create_flask_app()
    my_app.run(debug=True, port=5003)