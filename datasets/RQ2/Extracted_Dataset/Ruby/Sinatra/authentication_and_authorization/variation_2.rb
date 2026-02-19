# Variation 2: The Modular Sinatra App (OOP Style)
# This variation structures the app using Sinatra::Base, separating concerns
# into models, helpers, and route controllers. This is better for larger apps.
# For self-containment, all classes are in one file, but they are designed to be split.

# --- Gem Dependencies ---
# gem 'sinatra'
# gem 'bcrypt'
# gem 'jwt'
# gem 'json'

require 'sinatra/base'
require 'bcrypt'
require 'jwt'
require 'securerandom'
require 'json'
require 'time'

# --- Mock Data Store ---
# A simple, globally accessible store for our models.
class DataStore
  def self.users
    @users ||= {}
  end

  def self.posts
    @posts ||= {}
  end
end

# --- Models ---
# In a real app, this would be in `models/user.rb`
class User
  attr_reader :id, :email, :password_hash, :role, :is_active, :created_at

  def initialize(id:, email:, password_hash:, role:, is_active:, created_at:)
    @id = id
    @email = email
    @password_hash = password_hash
    @role = role.to_sym
    @is_active = is_active
    @created_at = created_at
  end

  def self.create(email:, password:, role:, is_active: true)
    id = SecureRandom.uuid
    instance = new(
      id: id,
      email: email,
      password_hash: BCrypt::Password.create(password),
      role: role,
      is_active: is_active,
      created_at: Time.now.utc
    )
    DataStore.users[id] = instance
    instance
  end

  def self.find(id)
    DataStore.users[id]
  end

  def self.find_by_email(email)
    DataStore.users.values.find { |u| u.email == email }
  end

  def authenticate(password)
    is_active && BCrypt::Password.new(password_hash) == password
  end

  def admin?
    role == :ADMIN
  end

  def to_h
    { id: @id, email: @email, role: @role }
  end
end

# Seed data
User.create(email: 'admin@example.com', password: 'adminpass', role: :ADMIN)
User.create(email: 'user@example.com', password: 'userpass', role: :USER)

# --- Application Base Class ---
# Common configuration and helpers. Other controllers inherit from this.
class ApplicationController < Sinatra::Base
  configure do
    enable :sessions
    set :session_secret, SecureRandom.hex(64)
    set :jwt_secret, 'a_different_super_secret_key'
    set :show_exceptions, false # Production-like setting
  end

  helpers do
    def json_params
      @json_params ||= JSON.parse(request.body.read)
    rescue JSON::ParserError
      halt 400, { message: 'Invalid JSON' }.to_json
    end

    def current_session_user
      return @current_session_user if defined?(@current_session_user)
      @current_session_user = User.find(session[:user_id]) if session[:user_id]
    end

    def authenticate_session!
      halt 401, { message: 'Not authorized' }.to_json unless current_session_user
    end

    def authorize_session_role!(required_role)
      authenticate_session!
      halt 403, { message: 'Forbidden' }.to_json unless current_session_user.role == required_role.to_sym
    end
  end

  error do |e|
    content_type :json
    status 500
    { error: "Application Error: #{e.message}" }.to_json
  end
end

# --- API Controller with JWT ---
# In a real app, this would be in `controllers/api_controller.rb`
class ApiController < ApplicationController
  helpers do
    def current_jwt_user
      @current_jwt_user
    end

    def authenticate_jwt!
      auth_header = request.env['HTTP_AUTHORIZATION']
      halt 401, { message: 'Authorization header missing' }.to_json unless auth_header && auth_header.start_with?('Bearer ')

      token = auth_header.split(' ').last
      begin
        payload = JWT.decode(token, settings.jwt_secret, true, { algorithm: 'HS256' }).first
        @current_jwt_user = User.find(payload['user_id'])
        halt 401, { message: 'Invalid user in token' }.to_json unless @current_jwt_user
      rescue JWT::ExpiredSignature
        halt 401, { message: 'Token has expired' }.to_json
      rescue JWT::DecodeError
        halt 401, { message: 'Invalid token' }.to_json
      end
    end

    def authorize_jwt_role!(required_role)
      authenticate_jwt!
      halt 403, { message: 'Forbidden' }.to_json unless current_jwt_user.role == required_role.to_sym
    end
  end

  before do
    content_type :json
  end

  post '/login' do
    user = User.find_by_email(json_params['email'])
    if user&.authenticate(json_params['password'])
      payload = { user_id: user.id, exp: Time.now.to_i + 7200 } # 2 hours
      token = JWT.encode(payload, settings.jwt_secret, 'HS256')
      { token: token }.to_json
    else
      halt 401, { message: 'Authentication failed' }.to_json
    end
  end

  get '/posts' do
    authenticate_jwt!
    # Mocked posts for simplicity
    posts = [{ id: SecureRandom.uuid, title: "API Post 1" }, { id: SecureRandom.uuid, title: "API Post 2" }]
    posts.to_json
  end

  delete '/posts/:id' do
    authorize_jwt_role!(:ADMIN)
    { message: "Admin #{current_jwt_user.email} deleted post #{params[:id]}" }.to_json
  end
end

# --- Web UI Controller with Sessions ---
# In a real app, this would be in `controllers/web_controller.rb`
class WebController < ApplicationController
  get '/' do
    'Modular Sinatra App - Public Home'
  end

  post '/login' do
    user = User.find_by_email(params[:email])
    if user&.authenticate(params[:password])
      session[:user_id] = user.id
      redirect '/profile'
    else
      'Invalid credentials. <a href="/">Try again</a>'
    end
  end

  get '/logout' do
    session.clear
    'You have been logged out. <a href="/">Home</a>'
  end

  get '/profile' do
    authenticate_session!
    "Welcome to your profile, #{current_session_user.email}! Your role is #{current_session_user.role}."
  end

  get '/admin' do
    authorize_session_role!(:ADMIN)
    "Secret Admin Area. Welcome, #{current_session_user.email}."
  end
end

# To run this modular app, you would typically use a `config.ru` file:
#
# require_relative 'app'
#
# run Rack::URLMap.new({
#   "/api" => ApiController,
#   "/"    => WebController
# })
#
# For self-containment, we can simulate this by running the controllers directly
# if this file is executed. In a real scenario, only the config.ru would be the entry point.
# This part is for demonstration and is not part of the core library code.
# To run: `ruby this_file.rb` and access `/` for Web and `/api/login` for API.
# Note: Sinatra runs the first `Sinatra::Base` subclass it finds.
# To run both, a config.ru is necessary. We'll just present the code structure.