# Variation 1: The Classic Sinatra App (Functional Style)
# This approach uses a single file with helpers and `before` filters,
# which is common for small to medium-sized Sinatra applications.

# --- Gem Dependencies ---
# gem 'sinatra'
# gem 'bcrypt'
# gem 'jwt'
# gem 'json'

require 'sinatra'
require 'bcrypt'
require 'jwt'
require 'securerandom'
require 'json'
require 'time'

# --- Configuration ---
configure do
  enable :sessions
  set :session_secret, SecureRandom.hex(64)
  set :jwt_secret, 'super_secret_key_for_jwt'
end

# --- Mock Database ---
# In a real app, this would be a database connection (e.g., Sequel, ActiveRecord).
USERS = {}
POSTS = {}

# Helper to create a user with a hashed password
def create_user(email, password, role, is_active: true)
  user_id = SecureRandom.uuid
  USERS[user_id] = {
    id: user_id,
    email: email,
    password_hash: BCrypt::Password.create(password),
    role: role, # :ADMIN or :USER
    is_active: is_active,
    created_at: Time.now.utc
  }
  USERS[user_id]
end

# Seed mock data
ADMIN_USER = create_user('admin@example.com', 'adminpass', :ADMIN)
REGULAR_USER = create_user('user@example.com', 'userpass', :USER)

POSTS[SecureRandom.uuid] = { id: SecureRandom.uuid, user_id: ADMIN_USER[:id], title: "Admin's First Post", content: "...", status: :PUBLISHED }
POSTS[SecureRandom.uuid] = { id: SecureRandom.uuid, user_id: REGULAR_USER[:id], title: "User's Draft", content: "...", status: :DRAFT }

# --- Authentication & Authorization Helpers ---
helpers do
  def find_user_by_email(email)
    USERS.values.find { |u| u[:email] == email }
  end

  def find_user_by_id(id)
    USERS[id]
  end

  # Session-based authentication
  def current_user
    return @current_user if @current_user
    @current_user = find_user_by_id(session[:user_id]) if session[:user_id]
  end

  def logged_in?
    !current_user.nil?
  end

  def require_login
    halt 401, { error: 'Unauthorized' }.to_json unless logged_in?
  end

  # Role-based access control
  def require_role(role)
    require_login
    halt 403, { error: 'Forbidden' }.to_json unless current_user[:role] == role
  end

  # JWT-based authentication
  def protected_by_jwt!
    auth_header = request.env['HTTP_AUTHORIZATION']
    halt 401, { error: 'Authorization header missing' }.to_json unless auth_header

    token = auth_header.split(' ').last
    begin
      decoded_token = JWT.decode(token, settings.jwt_secret, true, { algorithm: 'HS256' })
      user_id = decoded_token.first['user_id']
      @jwt_user = find_user_by_id(user_id)
      halt 401, { error: 'Invalid token or user not found' }.to_json unless @jwt_user
    rescue JWT::DecodeError => e
      halt 401, { error: "Invalid token: #{e.message}" }.to_json
    end
  end

  def jwt_user
    @jwt_user
  end

  def authorize_jwt_role!(role)
    protected_by_jwt!
    halt 403, { error: 'Forbidden' }.to_json unless jwt_user[:role] == role
  end
end

# --- Routes ---

# Homepage
get '/' do
  "Welcome! Public Page."
end

# --- Session-Based Auth Routes ---
post '/login' do
  content_type :json
  params = JSON.parse(request.body.read)
  user = find_user_by_email(params['email'])

  if user && user[:is_active] && BCrypt::Password.new(user[:password_hash]) == params['password']
    session[:user_id] = user[:id]
    { message: 'Login successful' }.to_json
  else
    halt 401, { error: 'Invalid credentials' }.to_json
  end
end

get '/logout' do
  session.clear
  redirect '/'
end

get '/profile' do
  require_login
  content_type :json
  current_user.slice(:id, :email, :role).to_json
end

get '/admin/dashboard' do
  require_role(:ADMIN)
  "Welcome to the Admin Dashboard, #{current_user[:email]}!"
end

# --- JWT-Based API Routes ---
before '/api/*' do
  content_type :json
end

post '/api/login' do
  params = JSON.parse(request.body.read)
  user = find_user_by_email(params['email'])

  if user && user[:is_active] && BCrypt::Password.new(user[:password_hash]) == params['password']
    payload = { user_id: user[:id], exp: Time.now.to_i + 3600 } # Expires in 1 hour
    token = JWT.encode(payload, settings.jwt_secret, 'HS256')
    { token: token }.to_json
  else
    halt 401, { error: 'Invalid credentials' }.to_json
  end
end

get '/api/posts' do
  protected_by_jwt!
  # Any logged-in user can see published posts
  published_posts = POSTS.values.select { |p| p[:status] == :PUBLISHED }
  published_posts.to_json
end

delete '/api/posts/:id' do
  authorize_jwt_role!(:ADMIN)
  post_id = params['id']
  if POSTS.delete(post_id)
    status 204 # No Content
  else
    halt 404, { error: 'Post not found' }.to_json
  end
end

# --- Mock OAuth2 Client Flow ---
get '/auth/provider' do
  # 1. Redirect user to the OAuth provider
  # In a real app, this would be a real URL with client_id, scope, etc.
  redirect "http://localhost:4567/auth/provider/callback?code=mock_auth_code_12345"
end

get '/auth/provider/callback' do
  # 2. Provider redirects back with a code
  auth_code = params['code']
  halt 400, "Missing auth code" unless auth_code

  # 3. Exchange code for an access token (mocked)
  # 4. Use access token to get user info (mocked)
  # Here, we'll just assume the code is valid and corresponds to our regular user.
  user = REGULAR_USER
  session[:user_id] = user[:id]

  "OAuth login successful! Welcome, #{user[:email]}. Redirecting to profile..."
  # In a real app, you'd redirect to a profile page.
  # redirect '/profile'
end