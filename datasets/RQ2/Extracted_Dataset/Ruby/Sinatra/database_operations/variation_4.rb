<code_snippet>
# This variation represents an "API-First" developer style.
# It focuses on building a well-structured, versioned JSON API.
# It uses namespaces for versioning, structured JSON responses, and extensive helpers.

# --- Gemfile, Rakefile, Migrations, Seeds (same as previous variations) ---
# ... (omitted for brevity)

# --- config/environment.rb ---
# ENV['RACK_ENV'] ||= 'development'
# require 'bundler/setup'
# Bundler.require(:default, ENV['RACK_ENV'])
# require 'sinatra/activerecord'
# set :database_file, 'config/database.yml'
# Dir.glob('./{models,helpers,api/v1}/*.rb').each { |file| require file }

# --- models/ (same as Variation 2) ---
# ... (omitted for brevity)

# --- helpers/api_helpers.rb ---
# module ApiHelpers
#   def json_response(code, resource)
#     status code
#     { data: resource }.to_json
#   end
#
#   def error_response(code, message, details = nil)
#     halt code, {
#       error: {
#         message: message,
#         details: details
#       }
#     }.to_json
#   end
#
#   def parsed_body
#     @parsed_body ||= JSON.parse(request.body.read)
#   rescue JSON::ParserError
#     error_response(400, "Bad Request: Malformed JSON")
#   end
#
#   def find_user_or_404(user_id)
#     User.find_by(id: user_id) || error_response(404, "Not Found: User with ID #{user_id} does not exist.")
#   end
# end

# --- app.rb (Main application file) ---
require 'sinatra'
require 'sinatra/namespace'
require 'sinatra/activerecord'
require 'json'
require 'securerandom'

# Mocking file structure for self-contained example
# Models
class ApplicationRecord < ActiveRecord::Base; self.abstract_class = true; before_create -> { self.id ||= SecureRandom.uuid }; end
class User < ApplicationRecord; has_many :posts; has_and_belongs_to_many :roles; enum role: { ADMIN: 0, USER: 1 }; end
class Post < ApplicationRecord; belongs_to :user; enum status: { DRAFT: 0, PUBLISHED: 1 }; end
class Role < ApplicationRecord; has_and_belongs_to_many :users; end
# Helpers
module ApiHelpers
  def json_response(code, resource); status code; { data: resource }.to_json; end
  def error_response(code, msg, det=nil); halt code, {error:{message:msg, details:det}}.to_json; end
  def parsed_body; @pb ||= JSON.parse(request.body.read); rescue; error_response(400, "Bad JSON"); end
  def find_user_or_404(id); User.find_by(id: id) || error_response(404, "User not found"); end
end

class ApiApplication < Sinatra::Base
  register Sinatra::Namespace
  helpers ApiHelpers
  set :database_file, 'config/database.yml'

  before do
    content_type :json
  end

  # --- API v1 ---
  namespace '/api/v1' do
    # --- User Endpoints ---
    namespace '/users' do
      # GET /api/v1/users/search?status=active&role=ADMIN
      get '/search' do
        query = User.includes(:roles)
        query = query.where(is_active: params[:status] == 'active') if params[:status]
        query = query.where(role: User.roles[params[:role].upcase]) if params[:role]
        json_response(200, query.all.as_json(include: :roles))
      end

      get '/:id' do
        user = find_user_or_404(params[:id])
        json_response(200, user.as_json(include: [:posts, :roles]))
      end

      post do
        user = User.new(parsed_body)
        if user.save
          json_response(201, user)
        else
          error_response(422, "Unprocessable Entity", user.errors.full_messages)
        end
      end

      patch '/:id' do
        user = find_user_or_404(params[:id])
        if user.update(parsed_body)
          json_response(200, user)
        else
          error_response(422, "Unprocessable Entity", user.errors.full_messages)
        end
      end

      delete '/:id' do
        user = find_user_or_404(params[:id])
        user.destroy
        status 204
        ''
      end
    end

    # --- Post Endpoints (nested under users) ---
    namespace '/users/:user_id/posts' do
      get do
        user = find_user_or_404(params[:user_id])
        json_response(200, user.posts)
      end

      post do
        user = find_user_or_404(params[:user_id])
        post = user.posts.new(parsed_body)
        if post.save
          json_response(201, post)
        else
          error_response(422, "Unprocessable Entity", post.errors.full_messages)
        end
      end
    end

    # --- Transactional Endpoint ---
    # Creates a user, assigns multiple roles, and publishes a post atomically.
    post '/accounts' do
      payload = parsed_body
      user_data = payload['user']
      post_data = payload['post']
      role_names = payload['roles'] # e.g., ["USER", "EDITOR"]

      new_account_data = nil
      begin
        ActiveRecord::Base.transaction do
          # 1. Create User
          new_user = User.create!(user_data)

          # 2. Find and assign roles
          roles = Role.where(name: role_names)
          error_response(422, "Invalid Roles", "One or more roles not found") if roles.length != role_names.length
          new_user.roles = roles

          # 3. Create initial post
          new_post = new_user.posts.create!(post_data.merge(status: :PUBLISHED))

          new_account_data = {
            user: new_user.as_json,
            roles: new_user.roles.as_json,
            initial_post: new_post.as_json
          }
        end
      rescue ActiveRecord::RecordInvalid => e
        error_response(422, "Transaction Failed", e.record.errors.full_messages)
      rescue => e
        error_response(500, "Internal Server Error", e.message)
      end

      json_response(201, new_account_data)
    end
  end
end

# To run this app, you would have a config.ru file:
# require './app'
# run ApiApplication
</code_snippet>