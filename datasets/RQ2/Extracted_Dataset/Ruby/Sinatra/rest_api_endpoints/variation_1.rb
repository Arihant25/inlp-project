require 'sinatra'
require 'json'
require 'securerandom'
require 'bcrypt'
require 'time'

# --- Configuration ---
# In a real app, use `set :port, 4567`, etc.
# For this snippet, we focus on the routes.

# --- In-Memory Database ---
USERS_DB = []
ROLES = ['ADMIN', 'USER'].freeze

# Seed data
25.times do |i|
  USERS_DB << {
    id: SecureRandom.uuid,
    email: "user#{i + 1}@example.com",
    password_hash: BCrypt::Password.create("password#{i + 1}"),
    role: i.even? ? 'ADMIN' : 'USER',
    is_active: i % 5 != 0,
    created_at: Time.now.utc.iso8601
  }
end

# --- Helpers ---
helpers do
  def find_user(id)
    USERS_DB.find { |user| user[:id] == id }
  end

  def parse_json_body
    request.body.rewind
    JSON.parse(request.body.read, symbolize_names: true)
  rescue JSON::ParserError
    halt 400, { error: 'Invalid JSON format' }.to_json
  end

  def serialize(user)
    user.dup.tap { |u| u.delete(:password_hash) }.to_json
  end
end

# --- Middleware ---
before do
  content_type :json
end

# --- API Endpoints ---

# [POST] /users - Create a new user
post '/users' do
  payload = parse_json_body

  if payload[:email].nil? || payload[:password].nil?
    halt 400, { error: 'Email and password are required' }.to_json
  end
  
  if USERS_DB.any? { |u| u[:email] == payload[:email] }
    halt 409, { error: 'Email already exists' }.to_json
  end

  new_user = {
    id: SecureRandom.uuid,
    email: payload[:email],
    password_hash: BCrypt::Password.create(payload[:password]),
    role: ROLES.include?(payload[:role]) ? payload[:role] : 'USER',
    is_active: payload[:is_active] || true,
    created_at: Time.now.utc.iso8601
  }

  USERS_DB << new_user
  status 201
  serialize(new_user)
end

# [GET] /users - List and search/filter users with pagination
get '/users' do
  # Filtering
  filtered_users = USERS_DB
  filtered_users = filtered_users.select { |u| u[:role] == params[:role] } if params[:role]
  if params[:is_active]
    is_active_bool = ['true', '1'].include?(params[:is_active].downcase)
    filtered_users = filtered_users.select { |u| u[:is_active] == is_active_bool }
  end

  # Pagination
  page = params.fetch('page', 1).to_i
  per_page = params.fetch('per_page', 10).to_i
  offset = (page - 1) * per_page
  
  paginated_users = filtered_users.slice(offset, per_page) || []

  # Response
  {
    metadata: {
      page: page,
      per_page: per_page,
      total_count: filtered_users.count,
      total_pages: (filtered_users.count.to_f / per_page).ceil
    },
    data: paginated_users.map { |u| u.dup.tap { |usr| usr.delete(:password_hash) } }
  }.to_json
end

# [GET] /users/:id - Get a single user
get '/users/:id' do
  user = find_user(params[:id])
  halt 404, { error: 'User not found' }.to_json unless user
  serialize(user)
end

# [PUT/PATCH] /users/:id - Update a user
put '/users/:id' do
  user = find_user(params[:id])
  halt 404, { error: 'User not found' }.to_json unless user

  payload = parse_json_body
  
  user[:email] = payload[:email] if payload.key?(:email)
  user[:role] = payload[:role] if payload.key?(:role) && ROLES.include?(payload[:role])
  user[:is_active] = payload[:is_active] if payload.key?(:is_active)

  if payload.key?(:password) && !payload[:password].empty?
    user[:password_hash] = BCrypt::Password.create(payload[:password])
  end

  serialize(user)
end

patch '/users/:id' do
  # In this simple implementation, PATCH behaves identically to PUT
  call env.merge("REQUEST_METHOD" => 'PUT')
end

# [DELETE] /users/:id - Delete a user
delete '/users/:id' do
  user = find_user(params[:id])
  halt 404, { error: 'User not found' }.to_json unless user

  USERS_DB.delete(user)
  status 204
end