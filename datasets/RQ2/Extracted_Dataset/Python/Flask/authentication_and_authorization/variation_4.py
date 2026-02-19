import uuid
import enum
from datetime import datetime
from functools import wraps
from dataclasses import dataclass

from flask import Flask, jsonify, request, redirect, url_for, g, current_app
from flask_login import LoginManager, UserMixin, login_user, logout_user, current_user, login_required
from flask_bcrypt import Bcrypt
from flask_jwt_extended import create_access_token, jwt_required, get_jwt_identity, JWTManager
from authlib.integrations.flask_client import OAuth

# --- Domain Models (using dataclasses for a modern feel) ---
class Role(enum.Enum):
    ADMIN = 'ADMIN'
    USER = 'USER'

@dataclass
class User(UserMixin):
    id: str
    email: str
    password_hash: str
    role: Role
    is_active: bool = True
    created_at: datetime = datetime.utcnow()

# --- In-Memory DB and Service Layer (Functional Style) ---
_DB = {"users": {}, "posts": {}}

def user_svc_find_by_id(uid):
    return _DB["users"].get(uid)

def user_svc_find_by_email(email):
    return next((u for u in _DB["users"].values() if u.email == email), None)

def user_svc_save(usr):
    _DB["users"][usr.id] = usr
    return usr

# --- Auth Utilities ---
bcrypt = Bcrypt()
jwt = JWTManager()
login_manager = LoginManager()
oauth = OAuth()

def require_role(role: Role):
    def decorator(f):
        @wraps(f)
        @jwt_required()
        def decorated(*args, **kwargs):
            user_id = get_jwt_identity()
            usr = user_svc_find_by_id(user_id)
            if not usr or usr.role != role:
                return jsonify(msg="Access forbidden: insufficient permissions"), 403
            g.user = usr
            return f(*args, **kwargs)
        return decorated
    return decorator

# --- Application Factory and Core Logic ---
def create_app(config_object=None):
    app = Flask(__name__)
    
    # Configuration
    cfg = {
        "SECRET_KEY": "a_minimalist_secret",
        "JWT_SECRET_KEY": "a_minimalist_jwt_secret",
        "GOOGLE_CLIENT_ID": "your-google-client-id",
        "GOOGLE_CLIENT_SECRET": "your-google-client-secret",
    }
    if config_object:
        cfg.update(config_object)
    app.config.from_mapping(cfg)

    # Initialize extensions
    bcrypt.init_app(app)
    jwt.init_app(app)
    login_manager.init_app(app)
    oauth.init_app(app)

    oauth.register(
        'google',
        client_id=app.config['GOOGLE_CLIENT_ID'],
        client_secret=app.config['GOOGLE_CLIENT_SECRET'],
        server_metadata_url='https://accounts.google.com/.well-known/openid-configuration',
        client_kwargs={'scope': 'openid email profile'}
    )

    @login_manager.user_loader
    def load_user(user_id):
        return user_svc_find_by_id(user_id)

    # --- Routes ---
    @app.route('/session/login', methods=['POST'])
    def handle_session_login():
        email, pwd = request.form.get('email'), request.form.get('password')
        usr = user_svc_find_by_email(email)
        if usr and bcrypt.check_password_hash(usr.password_hash, pwd):
            login_user(usr)
            return redirect(url_for('get_profile'))
        return "Invalid credentials", 401

    @app.route('/session/logout')
    @login_required
    def handle_session_logout():
        logout_user()
        return "Logged out successfully."

    @app.route('/profile')
    @login_required
    def get_profile():
        return jsonify(id=current_user.id, email=current_user.email, role=current_user.role.value)

    @app.route('/api/auth/token', methods=['POST'])
    def create_token():
        data = request.get_json()
        usr = user_svc_find_by_email(data.get('email'))
        if not usr or not bcrypt.check_password_hash(usr.password_hash, data.get('password')):
            return jsonify(msg="Bad email or password"), 401
        return jsonify(access_token=create_access_token(identity=usr.id))

    @app.route('/oauth/google/login')
    def handle_oauth_login():
        redirect_uri = url_for('handle_oauth_callback', _external=True)
        return oauth.google.authorize_redirect(redirect_uri)

    @app.route('/oauth/google/callback')
    def handle_oauth_callback():
        token = oauth.google.authorize_access_token()
        user_info = oauth.google.parse_id_token(token)
        email = user_info['email']
        
        usr = user_svc_find_by_email(email)
        if not usr:
            usr = User(id=str(uuid.uuid4()), email=email, password_hash="", role=Role.USER)
            user_svc_save(usr)
        
        login_user(usr)
        return redirect(url_for('get_profile'))

    @app.route('/api/posts')
    @jwt_required()
    def get_user_posts():
        uid = get_jwt_identity()
        posts = [p for p in _DB['posts'].values() if p['user_id'] == uid]
        return jsonify(posts)

    @app.route('/api/admin/overview')
    @require_role(Role.ADMIN)
    def get_admin_overview():
        # g.user is available from the decorator
        return jsonify(msg=f"Welcome, admin {g.user.email}", user_count=len(_DB['users']))

    # --- Seed Data ---
    with app.app_context():
        admin_id, user_id = str(uuid.uuid4()), str(uuid.uuid4())
        user_svc_save(User(
            id=admin_id, email='admin@example.com',
            password_hash=bcrypt.generate_password_hash('adminpass').decode(), role=Role.ADMIN
        ))
        user_svc_save(User(
            id=user_id, email='user@example.com',
            password_hash=bcrypt.generate_password_hash('userpass').decode(), role=Role.USER
        ))
        post_id = str(uuid.uuid4())
        _DB['posts'][post_id] = {'id': post_id, 'user_id': user_id, 'title': 'A Post'}

    return app

if __name__ == '__main__':
    app = create_app()
    app.run(debug=True, port=5004)