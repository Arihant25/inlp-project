# Variation 4: The "Modern" API-First Approach with Warden
# This variation uses the Warden gem, a Rack-based authentication framework,
# for a more robust and extensible authentication system. It's API-focused.

# --- Gem Dependencies ---
# gem 'sinatra'
# gem 'bcrypt'
# gem 'jwt'
# gem 'json'
# gem 'warden'

require 'sinatra'
require 'bcrypt'
require 'jwt'
# require 'warden' # In a real app, this would be in the Gemfile
require 'securerandom'
require 'json'
require 'time'

# --- Mock Data and User Model ---
# A simple User class and in-memory store
class User
  attr_reader :id, :email, :password_hash, :role

  def initialize(id:, email:, password_hash:, role:)
    @id, @email, @password_hash, @role = id, email, password_hash, role
  end

  def authenticate(password)
    BCrypt::Password.new(password_hash) == password
  end

  def self.repository
    @repository ||= {}
  end

  def self.find(id)
    repository[id]
  end

  def self.find_by_email(email)
    repository.values.find { |u| u.email == email }
  end

  def self.create(email:, password:, role:)
    id = SecureRandom.uuid
    user = new(id: id, email: email, password_hash: BCrypt::Password.create(password), role: role.to_sym)
    repository[id] = user
  end
end

User.create(email: 'admin@example.com', password: 'adminpass', role: :ADMIN)
User.create(email: 'user@example.com', password: 'userpass', role: :USER)

# --- Warden Configuration ---
# This setup would typically be in an initializer or config.ru
require 'warden'

use Warden::Manager do |manager|
  manager.default_strategies :password, :jwt_token
  manager.failure_app = ->(env) {
    [401, { 'Content-Type' => 'application/json' }, [{ error: 'Unauthorized', message: env['warden.options'][:message] }.to_json]]
  }
end

# Warden Strategy for password-based authentication
Warden::Strategies.add(:password) do
  def valid?
    # Strategy is valid if email and password are in the request body
    request.content_type == 'application/json' && parsed_body['email'] && parsed_body['password']
  end

  def authenticate!
    user = User.find_by_email(parsed_body['email'])
    if user&.authenticate(parsed_body['password'])
      success!(user)
    else
      fail!('Invalid email or password.')
    end
  end

  private
  def parsed_body
    @parsed_body ||= JSON.parse(request.body.read)
  rescue
    {}
  end
end

# Warden Strategy for JWT-based authentication
Warden::Strategies.add(:jwt_token) do
  def valid?
    # Strategy is valid if an Authorization header is present
    env['HTTP_AUTHORIZATION']&.start_with?('Bearer ')
  end

  def authenticate!
    token = env['HTTP_AUTHORIZATION'].split(' ').last
    secret = settings.jwt_secret
    begin
      payload = JWT.decode(token, secret, true, { algorithm: 'HS256' }).first
      user = User.find(payload['user_id'])
      user ? success!(user) : fail!('User not found.')
    rescue JWT::ExpiredSignature
      fail!('Token has expired.')
    rescue JWT::DecodeError
      fail!('Invalid token.')
    end
  end
end

# Warden serialization/deserialization (for sessions, if used)
Warden::Manager.serialize_into_session { |user| user.id }
Warden::Manager.serialize_from_session { |id| User.find(id) }

# --- Sinatra Application ---
set :jwt_secret, 'a_very_secure_secret_for_warden'
enable :sessions # Needed for Warden's session store, even if not primary auth
set :session_secret, SecureRandom.hex(64)

helpers do
  def warden
    request.env['warden']
  end

  def current_user
    warden.user
  end

  def authenticate!
    warden.authenticate!
  end

  def authorize_role!(role)
    authenticate!
    halt 403, { error: 'Forbidden', message: "Requires #{role} role." }.to_json unless current_user.role == role
  end

  def json_response(data, status: 200)
    content_type :json
    halt status, data.to_json
  end
end

# --- API Routes ---

# Public endpoint
get '/' do
  json_response({ message: 'Welcome to the Warden-powered API' })
end

# Login endpoint using the :password strategy
post '/auth/login' do
  warden.authenticate!(:password) # This runs the password strategy
  # If successful, warden.user is now set
  user = warden.user

  # Generate a JWT for the client
  payload = { user_id: user.id, role: user.role, exp: Time.now.to_i + 3600 }
  token = JWT.encode(payload, settings.jwt_secret, 'HS256')

  json_response({ token: token, user: { id: user.id, email: user.email, role: user.role } })
end

# Logout (clears session, invalidates JWT on client-side)
post '/auth/logout' do
  warden.logout
  json_response({ message: 'Logged out successfully.' })
end

# A protected endpoint that requires any valid JWT
get '/api/profile' do
  authenticate! # This will try all default strategies, including :jwt_token
  json_response({ id: current_user.id, email: current_user.email, role: current_user.role })
end

# An admin-only endpoint
get '/api/admin/users' do
  authorize_role!(:ADMIN)
  all_users = User.repository.values.map { |u| { id: u.id, email: u.email, role: u.role } }
  json_response(all_users)
end

# Mock OAuth2 flow - integrates with Warden
get '/auth/oauth/start' do
  # Redirect to provider
  redirect '/auth/oauth/callback?mock_code=abc123xyz'
end

get '/auth/oauth/callback' do
  # In a real app, exchange code for user info, then find/create a local user
  mock_user_email = 'user@example.com'
  user = User.find_by_email(mock_user_email)

  if user
    # Manually log the user in with Warden
    warden.set_user(user)
    json_response({ message: "OAuth login successful for #{user.email}" })
  else
    halt 404, { error: 'User not found for OAuth login' }.to_json
  end
end