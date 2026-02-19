<code_snippet>
# This variation represents a "Service-Oriented" developer style.
# It separates business logic from the web layer (controllers) into service objects.
# Controllers are thin and delegate work to services, improving testability and maintainability.

# --- Gemfile, Rakefile, Migrations, Seeds (same as previous variations) ---
# ... (omitted for brevity)

# --- config/environment.rb ---
# ENV['RACK_ENV'] ||= 'development'
# require 'bundler/setup'
# Bundler.require(:default, ENV['RACK_ENV'])
# require 'sinatra/activerecord'
# set :database_file, 'config/database.yml'
# Dir.glob('./{models,services,helpers,controllers}/*.rb').each { |file| require file }

# --- models/ (same as Variation 2) ---
# ... (omitted for brevity)

# --- services/base_service.rb ---
# class BaseService
#   def self.call(*args, &block)
#     new(*args, &block).call
#   end
#
#   class ServiceError < StandardError; end
#   class RecordNotFound < ServiceError; end
#   class ValidationError < ServiceError
#     attr_reader :errors
#     def initialize(message, errors)
#       super(message)
#       @errors = errors
#     end
#   end
# end

# --- services/user_service.rb ---
# class UserService < BaseService
#   def initialize(params = {})
#     @params = params
#   end
#
#   def call
#     # Default action, can be overridden
#   end
#
#   def find_all
#     scope = User.all
#     scope = scope.where(is_active: @params[:is_active]) if @params.key?(:is_active)
#     scope = scope.where(role: User.roles[@params[:role]]) if @params[:role]
#     scope
#   end
#
#   def find_one(id)
#     User.find_by(id: id) || raise(RecordNotFound, "User with id=#{id} not found")
#   end
#
#   def create(user_attrs)
#     user = User.new(user_attrs)
#     if user.save
#       user
#     else
#       raise ValidationError.new("User creation failed", user.errors.full_messages)
#     end
#   end
#
#   def update(id, user_attrs)
#     user = find_one(id)
#     if user.update(user_attrs)
#       user
#     else
#       raise ValidationError.new("User update failed", user.errors.full_messages)
#     end
#   end
#
#   def destroy(id)
#     user = find_one(id)
#     user.destroy
#   end
# end

# --- services/onboarding_service.rb ---
# class OnboardingService < BaseService
#   def initialize(user_attrs:, post_attrs:, role_names:)
#     @user_attrs = user_attrs
#     @post_attrs = post_attrs
#     @role_names = role_names
#   end
#
#   def call
#     result = {}
#     ActiveRecord::Base.transaction do
#       user = User.create!(@user_attrs)
#
#       roles = Role.where(name: @role_names)
#       raise RecordNotFound, "One or more roles not found" if roles.size != @role_names.size
#       user.roles = roles
#
#       post = user.posts.create!(@post_attrs)
#
#       result = { user: user, post: post }
#     end
#     result
#   rescue ActiveRecord::RecordInvalid => e
#     raise ValidationError.new("Onboarding failed", e.record.errors.full_messages)
#   end
# end

# --- helpers/json_helper.rb ---
# module JsonHelper
#   def parse_json_body
#     JSON.parse(request.body.read)
#   rescue
#     halt 400, { error: 'Invalid JSON' }.to_json
#   end
# end

# --- app.rb (The Sinatra App) ---
require 'sinatra'
require 'sinatra/activerecord'
require 'json'
require 'securerandom'

# Mocking file structure for self-contained example
# Models
class ApplicationRecord < ActiveRecord::Base; self.abstract_class = true; before_create -> { self.id ||= SecureRandom.uuid }; end
class User < ApplicationRecord; has_many :posts; has_and_belongs_to_many :roles; enum role: { ADMIN: 0, USER: 1 }; end
class Post < ApplicationRecord; belongs_to :user; enum status: { DRAFT: 0, PUBLISHED: 1 }; end
class Role < ApplicationRecord; has_and_belongs_to_many :users; end
# Services
class BaseService; def self.call(*a, &b) new(*a, &b).call; end; class ServiceError < StandardError; end; class RecordNotFound < ServiceError; end; class ValidationError < ServiceError; attr_reader :errors; def initialize(m, e) super(m); @errors=e; end; end; end
class UserService < BaseService; def initialize(p={}); @p=p; end; def find_all; User.where(@p); end; def find_one(id); User.find(id); end; def create(a); User.create!(a); end; def update(id, a); find_one(id).update!(a); end; def destroy(id); find_one(id).destroy; end; end
class OnboardingService < BaseService; def initialize(u:, p:, r:); @u=u; @p=p; @r=r; end; def call; r={}; ActiveRecord::Base.transaction { user=User.create!(@u); user.roles=Role.where(name:@r); post=user.posts.create!(@p); r={u:user, p:post}}; r; end; end
# Helpers
module JsonHelper; def parse_json_body; JSON.parse(request.body.read); rescue; halt 400; end; end

class App < Sinatra::Base
  helpers JsonHelper
  set :database_file, 'config/database.yml'

  before do
    content_type :json
  end

  # --- User Routes ---
  get '/users' do
    users = UserService.new(params).find_all
    users.to_json(include: :roles)
  end

  post '/users' do
    user = UserService.call.create(parse_json_body)
    status 201
    user.to_json
  end

  get '/users/:id' do
    user = UserService.call.find_one(params[:id])
    user.to_json(include: [:posts, :roles])
  end

  put '/users/:id' do
    user = UserService.call.update(params[:id], parse_json_body)
    user.to_json
  end

  delete '/users/:id' do
    UserService.call.destroy(params[:id])
    status 204
  end

  # --- Transaction Route ---
  post '/register_and_post' do
    payload = parse_json_body
    result = OnboardingService.call(
      user_attrs: payload['user'],
      post_attrs: payload['post'],
      role_names: payload['roles'] || ['USER']
    )
    status 201
    { user: result[:user], post: result[:post] }.to_json
  end

  # --- Error Handling ---
  error BaseService::RecordNotFound do |e|
    status 404
    { error: e.message }.to_json
  end

  error BaseService::ValidationError do |e|
    status 422
    { error: e.message, details: e.errors }.to_json
  end
end

# To run this app, you would have a config.ru file:
# require './app'
# run App
</code_snippet>