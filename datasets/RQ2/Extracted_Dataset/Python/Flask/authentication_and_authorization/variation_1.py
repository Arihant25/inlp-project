import os
import uuid
import enum
from datetime import datetime, timedelta
from functools import wraps

from flask import Flask, jsonify, request, session, redirect, url_for, g
from flask_login import LoginManager, UserMixin, login_user, logout_user, current_user, login_required
from flask_bcrypt import Bcrypt
from flask_jwt_extended import create_access_token, jwt_required, get_jwt_identity, JWTManager
from authlib.integrations.flask_client import OAuth

# 1. Configuration & Setup
app = Flask(__name__)
app.config.update(
    SECRET_KEY='a_very_secret_key_for_sessions',
    JWT_SECRET_KEY='a_super_secret_key_for_jwt',
    GOOGLE_CLIENT_ID='your-google-client-id', # Replace with real credentials
    GOOGLE_CLIENT_SECRET='your-google-client-secret', # Replace with real credentials
)

bcrypt = Bcrypt(app)
jwt = JWTManager(app)
login_manager = LoginManager(app)
login_manager.login_view = 'login'
oauth = OAuth(app)

google = oauth.register(
    name='google',
    client_id=app.config["GOOGLE_CLIENT_ID"],
    client_secret=app.config["GOOGLE_CLIENT_SECRET"],
    access_token_url='https://accounts.google.com/o/oauth2/token',
    access_token_params=None,
    authorize_url='https://accounts.google.com/o/oauth2/auth',
    authorize_params=None,
    api_base_url='https://www.googleapis.com/oauth2/v1/',
    userinfo_endpoint='https://openidconnect.googleapis.com/v1/userinfo',
    client_kwargs={'scope': 'openid email profile'},
    jwks_uri="https://www.googleapis.com/oauth2/v3/certs",
)

# 2. Domain Schema & Mock Database
class Role(enum.Enum):
    ADMIN = 'ADMIN'
    USER = 'USER'

class PostStatus(enum.Enum):
    DRAFT = 'DRAFT'
    PUBLISHED = 'PUBLISHED'

class User(UserMixin):
    def __init__(self, id, email, password_hash, role=Role.USER, is_active=True):
        self.id = id
        self.email = email
        self.password_hash = password_hash
        self.role = role
        self.is_active = is_active
        self.created_at = datetime.utcnow()

MOCK_USERS_DB = {}
MOCK_POSTS_DB = {}

def init_db():
    admin_id = str(uuid.uuid4())
    user_id = str(uuid.uuid4())
    
    MOCK_USERS_DB[admin_id] = User(
        id=admin_id,
        email='admin@example.com',
        password_hash=bcrypt.generate_password_hash('admin123').decode('utf-8'),
        role=Role.ADMIN
    )
    MOCK_USERS_DB[user_id] = User(
        id=user_id,
        email='user@example.com',
        password_hash=bcrypt.generate_password_hash('user123').decode('utf-8'),
        role=Role.USER
    )
    
    post_id = str(uuid.uuid4())
    MOCK_POSTS_DB[post_id] = {
        'id': post_id,
        'user_id': user_id,
        'title': 'A User\'s First Post',
        'content': 'Hello world!',
        'status': PostStatus.PUBLISHED
    }

# 3. Auth & RBAC Logic
@login_manager.user_loader
def load_user(user_id):
    return MOCK_USERS_DB.get(user_id)

def find_user_by_email(email):
    for user in MOCK_USERS_DB.values():
        if user.email == email:
            return user
    return None

def requires_role(role_name):
    def decorator(f):
        @wraps(f)
        def decorated_function(*args, **kwargs):
            user_id = get_jwt_identity()
            user = MOCK_USERS_DB.get(user_id)
            if not user or user.role.value != role_name:
                return jsonify({"msg": "Admins only!"}), 403
            return f(*args, **kwargs)
        return decorated_function
    return decorator

# 4. Routes
@app.route('/')
def index():
    if current_user.is_authenticated:
        return f'Hello, {current_user.email}! <a href="/logout">Logout</a>'
    return 'Hello, Guest! <a href="/login">Login</a> or <a href="/oauth/login/google">Login with Google</a>'

# Session-based Authentication
@app.route('/login', methods=['GET', 'POST'])
def login():
    if request.method == 'POST':
        email = request.form['email']
        password = request.form['password']
        user = find_user_by_email(email)
        if user and bcrypt.check_password_hash(user.password_hash, password):
            login_user(user)
            return redirect(url_for('profile'))
        return 'Invalid credentials', 401
    return '''
        <form method="post">
            Email: <input type="text" name="email"><br>
            Password: <input type="password" name="password"><br>
            <input type="submit" value="Login">
        </form>
    '''

@app.route('/logout')
@login_required
def logout():
    logout_user()
    return redirect(url_for('index'))

@app.route('/profile')
@login_required
def profile():
    return f'<h1>Profile Page</h1><p>Welcome, {current_user.email}. Your role is {current_user.role.value}.</p>'

# JWT-based API Authentication
@app.route('/api/login', methods=['POST'])
def api_login():
    email = request.json.get('email', None)
    password = request.json.get('password', None)
    user = find_user_by_email(email)
    if not user or not bcrypt.check_password_hash(user.password_hash, password):
        return jsonify({"msg": "Bad email or password"}), 401
    
    access_token = create_access_token(identity=user.id)
    return jsonify(access_token=access_token)

# OAuth2 Client Implementation
@app.route('/oauth/login/google')
def oauth_login_google():
    redirect_uri = url_for('oauth_callback_google', _external=True)
    return google.authorize_redirect(redirect_uri)

@app.route('/oauth/callback/google')
def oauth_callback_google():
    token = google.authorize_access_token()
    user_info = google.get('userinfo').json()
    email = user_info['email']
    
    user = find_user_by_email(email)
    if not user:
        # Auto-register new user from OAuth
        new_user_id = str(uuid.uuid4())
        user = User(id=new_user_id, email=email, password_hash=None, role=Role.USER)
        MOCK_USERS_DB[new_user_id] = user

    login_user(user)
    return redirect(url_for('profile'))

# Protected API endpoints with RBAC
@app.route('/api/posts', methods=['GET'])
@jwt_required()
def get_posts():
    user_id = get_jwt_identity()
    user = MOCK_USERS_DB.get(user_id)
    if not user:
        return jsonify({"msg": "User not found"}), 404
    
    user_posts = [p for p in MOCK_POSTS_DB.values() if p['user_id'] == user_id]
    return jsonify(posts=user_posts)

@app.route('/api/admin/dashboard', methods=['GET'])
@jwt_required()
@requires_role('ADMIN')
def admin_dashboard():
    return jsonify({
        "message": "Welcome to the Admin Dashboard!",
        "total_users": len(MOCK_USERS_DB),
        "total_posts": len(MOCK_POSTS_DB)
    })

if __name__ == '__main__':
    init_db()
    app.run(debug=True, port=5001)