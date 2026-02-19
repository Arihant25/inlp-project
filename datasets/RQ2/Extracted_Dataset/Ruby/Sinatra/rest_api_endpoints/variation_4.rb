require 'sinatra'
require 'json'
require 'securerandom'
require 'bcrypt'
require 'time'

# This variation uses an "ActiveRecord-lite" pattern.
# A User model class encapsulates data and persistence logic,
# making the Sinatra routes clean and expressive.

# --- Model ---
class User
  attr_accessor :id, :email, :role, :is_active, :created_at
  attr_writer :password

  # In-memory store
  @@db = {}
  ROLES = %w[ADMIN USER].freeze

  def initialize(attrs = {})
    @id = attrs[:id] || SecureRandom.uuid
    @email = attrs[:email]
    @password_hash = attrs[:password_hash]
    @role = attrs[:role] || 'USER'
    @is_active = attrs.fetch(:is_active, true)
    @created_at = attrs[:created_at] || Time.now.utc.iso8601
  end

  # --- Class Methods (Data Access) ---
  def self.create(attrs)
    # Basic validation
    return nil if attrs[:email].to_s.empty? || attrs[:password].to_s.empty?
    return nil if @@db.values.any? { |u| u.email == attrs[:email] }

    user = new(attrs)
    user.send(:hash_password, attrs[:password])
    user.save
  end

  def self.all
    @@db.values
  end

  def self.find(id)
    @@db[id]
  end

  def self.where(filters)
    results = all
    filters.each do |key, value|
      results = results.select do |user|
        # Handle boolean string from query params
        val_to_check = value
        if %w[true false].include?(value.to_s) && user.send(key).is_a?(TrueClass) || user.send(key).is_a?(FalseClass)
          val_to_check = (value.to_s == 'true')
        end
        user.send(key) == val_to_check
      end
    end
    results
  end

  # --- Instance Methods ---
  def save
    self.class.send(:_store, self)
    self
  end

  def update(attrs)
    attrs.each do |key, value|
      setter = "#{key}="
      self.send(setter, value) if self.respond_to?(setter)
    end
    hash_password(attrs[:password]) if attrs[:password]
    save
  end

  def destroy
    self.class.send(:_delete, self.id)
  end

  def to_h
    {
      id: @id,
      email: @email,
      role: @role,
      is_active: @is_active,
      created_at: @created_at
    }
  end

  private

  def hash_password(plain_password)
    @password_hash = BCrypt::Password.create(plain_password)
  end

  # Internal methods to manage the @@db hash
  def self._store(user)
    @@db[user.id] = user
  end

  def self._delete(id)
    @@db.delete(id)
  end
end

# --- Seed Data ---
25.times { |i| User.create(email: "test#{i}@domain.tld", password: "password", role: i.even? ? 'ADMIN' : 'USER', is_active: i.even?) }

# --- Sinatra Application ---
configure do
  set :server, :puma
end

before do
  content_type :json
end

# [POST] /users
post '/users' do
  payload = JSON.parse(request.body.read, symbolize_names: true)
  usr = User.create(payload)
  halt 422, { error: 'Invalid data or email already exists' }.to_json unless usr
  status 201
  usr.to_h.to_json
end

# [GET] /users
get '/users' do
  # Filtering
  filters = params.slice('role', 'is_active').transform_keys(&:to_sym)
  users = User.where(filters)

  # Pagination
  page = (params['page'] || 1).to_i
  per_page = (params['per_page'] || 10).to_i
  offset = (page - 1) * per_page
  paginated = users.slice(offset, per_page) || []

  {
    page: page,
    per_page: per_page,
    total: users.count,
    data: paginated.map(&:to_h)
  }.to_json
end

# [GET] /users/:id
get '/users/:id' do
  usr = User.find(params[:id])
  halt 404, { error: 'Not Found' }.to_json unless usr
  usr.to_h.to_json
end

# [PUT/PATCH] /users/:id
put '/users/:id' do
  usr = User.find(params[:id])
  halt 404, { error: 'Not Found' }.to_json unless usr

  payload = JSON.parse(request.body.read, symbolize_names: true)
  usr.update(payload)
  usr.to_h.to_json
end

patch '/users/:id' do
  # Forward to PUT as our update method handles partial updates
  forward
end

# [DELETE] /users/:id
delete '/users/:id' do
  usr = User.find(params[:id])
  halt 404, { error: 'Not Found' }.to_json unless usr
  usr.destroy
  status 204
  ''
end