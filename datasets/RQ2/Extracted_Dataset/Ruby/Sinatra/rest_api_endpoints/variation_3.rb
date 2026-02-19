require 'sinatra'
require 'json'
require 'securerandom'
require 'bcrypt'
require 'time'

# This variation demonstrates a Service-Oriented Architecture.
# - UserRepository: Handles direct data access (the "Model" or "DAL").
# - UserService: Contains business logic, using the repository.
# - Sinatra App: Acts as the "Controller", handling HTTP and delegating to the service.

# --- Data Access Layer (Repository) ---
class UserRepository
  ROLES = ['ADMIN', 'USER'].freeze
  
  # Using a class instance variable for the data store
  @data = []
  
  class << self
    attr_reader :data

    def bootstrap(count = 25)
      count.times do |i|
        @data << {
          id: SecureRandom.uuid,
          email: "employee-#{i}@startup.io",
          password_hash: BCrypt::Password.create("pass-#{i}"),
          role: i.odd? ? 'ADMIN' : 'USER',
          is_active: i % 3 != 0,
          created_at: Time.now.utc.iso8601
        }
      end
    end

    def all
      @data
    end

    def find(id)
      @data.find { |record| record[:id] == id }
    end
    
    def find_by_email(email)
      @data.find { |record| record[:email] == email }
    end

    def save(user_record)
      @data << user_record
      user_record
    end

    def update(id, attributes)
      user = find(id)
      return nil unless user
      user.merge!(attributes)
    end



    def delete(id)
      user = find(id)
      return nil unless user
      @data.delete(user)
    end
  end
end

# --- Business Logic Layer (Service) ---
class UserService
  def initialize(repository)
    @repo = repository
  end

  def list_users(params)
    users = @repo.all
    
    # Filtering
    users = users.select { |u| u[:role] == params[:role] } if params[:role]
    if params[:is_active]
      is_active_bool = params[:is_active] == 'true'
      users = users.select { |u| u[:is_active] == is_active_bool }
    end
    
    # Pagination
    page = params.fetch(:page, 1).to_i
    per_page = params.fetch(:per_page, 10).to_i
    offset = (page - 1) * per_page
    paginated_data = users.slice(offset, per_page) || []

    {
      data: paginated_data.map { |u| u.except(:password_hash) },
      meta: { page: page, per_page: per_page, total: users.count }
    }
  end

  def get_user(id)
    user = @repo.find(id)
    user ? user.except(:password_hash) : nil
  end

  def create_user(data)
    return { error: 'Email and password are required' } if data[:email].nil? || data[:password].nil?
    return { error: 'Email already in use' } if @repo.find_by_email(data[:email])

    new_user = {
      id: SecureRandom.uuid,
      email: data[:email],
      password_hash: BCrypt::Password.create(data[:password]),
      role: UserRepository::ROLES.include?(data[:role]) ? data[:role] : 'USER',
      is_active: data.fetch(:is_active, true),
      created_at: Time.now.utc.iso8601
    }
    
    @repo.save(new_user).except(:password_hash)
  end

  def update_user(id, data)
    user = @repo.find(id)
    return nil unless user

    update_attrs = {}
    update_attrs[:email] = data[:email] if data[:email]
    update_attrs[:role] = data[:role] if data[:role] && UserRepository::ROLES.include?(data[:role])
    update_attrs[:is_active] = data[:is_active] if data.key?(:is_active)
    update_attrs[:password_hash] = BCrypt::Password.create(data[:password]) if data[:password]

    @repo.update(id, update_attrs).except(:password_hash)
  end

  def delete_user(id)
    @repo.delete(id)
  end
end

# --- Presentation Layer (Sinatra Controller) ---
# Monkey-patch Hash for convenience
class Hash; def except(*keys); reject { |k, _| keys.include?(k) }; end; end

# Initialize repository and service
UserRepository.bootstrap
USER_SERVICE = UserService.new(UserRepository)

# Sinatra App
set :show_exceptions, false

before do
  content_type :json
end

error do
  { error: env['sinatra.error'].message }.to_json
end

# [POST] /users
post '/users' do
  payload = JSON.parse(request.body.read, symbolize_names: true)
  result = USER_SERVICE.create_user(payload)
  
  if result[:error]
    halt 422, result.to_json
  else
    status 201
    result.to_json
  end
end

# [GET] /users
get '/users' do
  result = USER_SERVICE.list_users(params)
  result.to_json
end

# [GET] /users/:id
get '/users/:id' do
  user = USER_SERVICE.get_user(params[:id])
  halt 404, { error: 'User not found' }.to_json unless user
  user.to_json
end

# [PUT/PATCH] /users/:id
put '/users/:id' do
  payload = JSON.parse(request.body.read, symbolize_names: true)
  updated_user = USER_SERVICE.update_user(params[:id], payload)
  halt 404, { error: 'User not found' }.to_json unless updated_user
  updated_user.to_json
end

patch '/users/:id' do
  # Delegate to PUT
  forward
end

# [DELETE] /users/:id
delete '/users/:id' do
  deleted_user = USER_SERVICE.delete_user(params[:id])
  halt 404, { error: 'User not found' }.to_json unless deleted_user
  status 204
end