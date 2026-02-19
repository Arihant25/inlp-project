# Variation 3: The Service-Oriented Approach
# This pattern extracts business logic into dedicated service classes,
# keeping the Sinatra routes thin and focused on HTTP concerns.

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
  set :jwt_signing_secret, 'yet_another_secret_for_signing'
end

# --- Mock Data Layer ---
# A simple in-memory repository pattern.
module Repository
  USERS = {}
  POSTS = {}

  def self.seed
    admin_id = SecureRandom.uuid
    user_id = SecureRandom.uuid

    USERS[admin_id] = { id: admin_id, email: 'admin@example.com', password_hash: BCrypt::Password.create('adminpass'), role: 'ADMIN', is_active: true, created_at: Time.now.utc }
    USERS[user_id] = { id: user_id, email: 'user@example.com', password_hash: BCrypt::Password.create('userpass'), role: 'USER', is_active: true, created_at: Time.now.utc }

    post_id = SecureRandom.uuid
    POSTS[post_id] = { id: post_id, user_id: user_id, title: "A User's Post", content: "Content here", status: 'PUBLISHED' }
  end

  def self.find_user_by_email(email)
    USERS.values.find { |u| u[:email] == email }
  end

  def self.find_user_by_id(id)
    USERS[id]
  end
end
Repository.seed

# --- Service Layer ---

# Handles JWT creation and verification
class JwtService
  SECRET = Sinatra::Application.settings.jwt_signing_secret
  ALGORITHM = 'HS256'.freeze

  def self.issue_token(payload)
    defaults = { exp: Time.now.to_i + 3600 * 4 } # 4-hour expiration
    JWT.encode(defaults.merge(payload), SECRET, ALGORITHM)
  end

  def self.decode_token(token)
    decoded = JWT.decode(token, SECRET, true, { algorithm: ALGORITHM })
    decoded.first # Return the payload
  rescue JWT::DecodeError
    nil
  end
end

# Handles user authentication logic
class AuthenticationService
  def self.authenticate_by_password(email, password)
    user_data = Repository.find_user_by_email(email)
    return nil unless user_data && user_data[:is_active]

    bcrypt_hash = BCrypt::Password.new(user_data[:password_hash])
    bcrypt_hash == password ? user_data : nil
  end

  def self.authenticate_by_oauth(provider, code)
    # Mock implementation: In a real app, this would involve HTTP calls to the provider.
    # We'll just return a user if the mock code is correct.
    return Repository.find_user_by_email('user@example.com') if provider == 'mock_provider' && code == 'valid_oauth_code'
    nil
  end
end

# Handles authorization logic (RBAC)
class AuthorizationService
  def initialize(user)
    @user = user
  end

  def can?(action, resource)
    return false unless @user

    case action
    when :read
      resource == :public_profile || resource == :posts
    when :delete
      resource == :posts && @user[:role] == 'ADMIN'
    when :access
      resource == :admin_panel && @user[:role] == 'ADMIN'
    else
      false
    end
  end
end

# --- Sinatra Application ---
enable :sessions
set :session_secret, SecureRandom.hex(32)

helpers do
  def json_body
    request.body.rewind
    JSON.parse(request.body.read, symbolize_names: true)
  end

  def current_user
    return @current_user if defined?(@current_user)
    
    # Prefer JWT auth for API calls
    if request.env['HTTP_AUTHORIZATION']
      token = request.env['HTTP_AUTHORIZATION'].split(' ').last
      payload = JwtService.decode_token(token)
      @current_user = payload ? Repository.find_user_by_id(payload['user_id']) : nil
    # Fallback to session auth for web calls
    elsif session[:user_id]
      @current_user = Repository.find_user_by_id(session[:user_id])
    else
      @current_user = nil
    end
  end

  def authorize!(action, resource)
    auth_service = AuthorizationService.new(current_user)
    halt 403, { error: 'Forbidden' }.to_json unless auth_service.can?(action, resource)
  end
end

before do
  content_type :json
end

# --- Routes ---

post '/login' do
  credentials = json_body
  user = AuthenticationService.authenticate_by_password(credentials[:email], credentials[:password])
  
  if user
    session[:user_id] = user[:id]
    { status: 'ok', message: 'Session created' }.to_json
  else
    halt 401, { error: 'Invalid credentials' }.to_json
  end
end

post '/api/token' do
  credentials = json_body
  user = AuthenticationService.authenticate_by_password(credentials[:email], credentials[:password])

  if user
    token = JwtService.issue_token({ user_id: user[:id], role: user[:role] })
    { token: token }.to_json
  else
    halt 401, { error: 'Invalid credentials' }.to_json
  end
end

get '/auth/:provider/callback' do
  user = AuthenticationService.authenticate_by_oauth(params[:provider], params[:code])
  if user
    session[:user_id] = user[:id]
    { status: 'ok', message: "OAuth login successful for #{user[:email]}" }.to_json
  else
    halt 401, { error: 'OAuth authentication failed' }.to_json
  end
end

get '/profile' do
  halt 401, { error: 'Unauthorized' }.to_json unless current_user
  authorize!(:read, :public_profile)
  current_user.slice(:id, :email, :role).to_json
end

get '/admin' do
  halt 401, { error: 'Unauthorized' }.to_json unless current_user
  authorize!(:access, :admin_panel)
  { message: "Welcome, Admin #{current_user[:email]}" }.to_json
end

delete '/posts/:id' do
  halt 401, { error: 'Unauthorized' }.to_json unless current_user
  authorize!(:delete, :posts)
  { message: "Post #{params[:id]} deleted by admin #{current_user[:email]}" }.to_json
end

get '/logout' do
  session.clear
  { status: 'ok', message: 'Logged out' }.to_json
end