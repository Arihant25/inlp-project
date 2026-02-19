require 'sinatra/base'
require 'json'
require 'securerandom'
require 'bcrypt'
require 'time'

# This variation uses a modular, class-based approach inheriting from Sinatra::Base.
# It's suitable for larger applications and can be mounted in a config.ru file.
# To run:
# 1. Save as user_api.rb
# 2. Create config.ru with:
#    require './user_api'
#    run UserApi
# 3. Run `rackup` in your terminal.

class UserApi < Sinatra::Base
  # --- In-Memory Data Store (encapsulated) ---
  @@users = []
  ROLES = ['ADMIN', 'USER'].freeze

  # --- Configuration ---
  configure do
    set :app_file, __FILE__
    # Seed data on application start
    25.times do |i|
      @@users << {
        id: SecureRandom.uuid,
        email: "dev#{i + 1}@company.com",
        password_hash: BCrypt::Password.create("S3cureP@ss#{i + 1}"),
        role: i > 20 ? 'ADMIN' : 'USER',
        is_active: i % 4 != 0,
        created_at: Time.now.utc.iso8601
      }
    end
  end

  # --- Middleware ---
  before do
    content_type :json
  end

  # --- Helpers ---
  helpers do
    def find_user_by_id(user_id)
      @@users.find { |usr| usr[:id] == user_id }
    end

    def json_params
      @json_params ||= begin
        request.body.rewind
        JSON.parse(request.body.read, symbolize_names: true)
      rescue JSON::ParserError
        halt 400, { message: 'Malformed JSON request' }.to_json
      end
    end
    
    def public_user_data(user_record)
      user_record.reject { |k, _| k == :password_hash }
    end
  end

  # --- Routes ---

  # Create a new user
  post '/users' do
    params = json_params
    halt 422, { message: 'Email and password fields are mandatory' }.to_json if params[:email].to_s.empty? || params[:password].to_s.empty?
    halt 409, { message: "User with email #{params[:email]} already exists" }.to_json if @@users.any? { |u| u[:email] == params[:email] }

    new_user_record = {
      id: SecureRandom.uuid,
      email: params[:email],
      password_hash: BCrypt::Password.create(params[:password]),
      role: ROLES.include?(params[:role]) ? params[:role] : 'USER',
      is_active: params.fetch(:is_active, true),
      created_at: Time.now.utc.iso8601
    }

    @@users << new_user_record
    status 201
    public_user_data(new_user_record).to_json
  end

  # List, filter, and paginate users
  get '/users' do
    collection = @@users

    # Filtering logic
    collection = collection.select { |u| u[:role] == params['role'] } if params['role']
    if params['is_active']
      is_active_val = params['is_active'] == 'true'
      collection = collection.select { |u| u[:is_active] == is_active_val }
    end

    # Pagination logic
    page_num = params.fetch('page', 1).to_i
    page_size = params.fetch('per_page', 10).to_i
    offset = (page_num - 1) * page_size
    
    paginated_collection = collection[offset, page_size] || []

    {
      pagination: {
        current_page: page_num,
        per_page: page_size,
        total_entries: collection.size,
        total_pages: (collection.size.to_f / page_size).ceil
      },
      users: paginated_collection.map { |u| public_user_data(u) }
    }.to_json
  end

  # Retrieve a specific user
  get '/users/:id' do
    user = find_user_by_id(params[:id])
    halt 404, { message: 'User not found' }.to_json unless user
    public_user_data(user).to_json
  end

  # Update a user (PUT/PATCH)
  ['/users/:id'].each do |path|
    put path do
      user = find_user_by_id(params[:id])
      halt 404, { message: 'User not found' }.to_json unless user

      update_params = json_params
      user[:email] = update_params[:email] if update_params.key?(:email)
      user[:role] = update_params[:role] if update_params.key?(:role) && ROLES.include?(update_params[:role])
      user[:is_active] = update_params[:is_active] if update_params.key?(:is_active)
      user[:password_hash] = BCrypt::Password.create(update_params[:password]) if update_params[:password]

      public_user_data(user).to_json
    end

    patch path do
      # Delegate to PUT for this implementation
      call env.merge("REQUEST_METHOD" => 'PUT', "PATH_INFO" => path)
    end
  end

  # Delete a user
  delete '/users/:id' do
    user_index = @@users.index { |u| u[:id] == params[:id] }
    halt 404, { message: 'User not found' }.to_json unless user_index

    @@users.delete_at(user_index)
    status 204
    body ''
  end
end