<code_snippet>
# This variation represents a "Classic Sinatra" style.
# All code is in a single file, using a procedural/functional approach.
# It's simple, direct, and common for smaller applications or prototypes.

# --- Gemfile ---
# source 'https://rubygems.org'
# gem 'sinatra'
# gem 'activerecord'
# gem 'sinatra-activerecord'
# gem 'rake'
# gem 'sqlite3'
# gem 'json'

# --- Rakefile ---
# require 'sinatra/activerecord/rake'
# require './app'

# --- config/database.yml ---
# development:
#   adapter: sqlite3
#   database: db/development.sqlite3
#   pool: 5
#   timeout: 5000

# --- db/migrate/20230101000001_create_users.rb ---
# class CreateUsers < ActiveRecord::Migration[7.0]
#   def change
#     # For PostgreSQL, use `enable_extension 'pgcrypto'` and `t.uuid :id, primary_key: true, default: 'gen_random_uuid()'`
#     create_table :users, id: :uuid do |t|
#       t.string :email, null: false, index: { unique: true }
#       t.string :password_hash, null: false
#       t.integer :role, default: 1, null: false # 0: ADMIN, 1: USER
#       t.boolean :is_active, default: true, null: false
#       t.timestamps
#     end
#   end
# end

# --- db/migrate/20230101000002_create_posts.rb ---
# class CreatePosts < ActiveRecord::Migration[7.0]
#   def change
#     create_table :posts, id: :uuid do |t|
#       t.references :user, type: :uuid, null: false, foreign_key: true
#       t.string :title, null: false
#       t.text :content
#       t.integer :status, default: 0, null: false # 0: DRAFT, 1: PUBLISHED
#       t.timestamps
#     end
#   end
# end

# --- db/migrate/20230101000003_create_roles_and_join_table.rb ---
# class CreateRolesAndJoinTable < ActiveRecord::Migration[7.0]
#   def change
#     create_table :roles do |t|
#       t.string :name, null: false, index: { unique: true }
#       t.timestamps
#     end
#
#     create_table :roles_users, id: false do |t|
#       t.references :user, type: :uuid, null: false, foreign_key: true
#       t.references :role, null: false, foreign_key: true
#     end
#   end
# end

# --- db/seeds.rb ---
# require 'securerandom'
#
# # Clear existing data
# User.destroy_all
# Post.destroy_all
# Role.destroy_all
#
# # Create Roles
# admin_role = Role.create!(name: 'ADMIN')
# user_role = Role.create!(name: 'USER')
# editor_role = Role.create!(name: 'EDITOR')
#
# # Create Users
# user1 = User.create!(
#   email: 'admin@example.com',
#   password_hash: 'hashed_password',
#   role: :ADMIN,
#   is_active: true,
#   roles: [admin_role, user_role]
# )
#
# user2 = User.create!(
#   email: 'user@example.com',
#   password_hash: 'hashed_password',
#   role: :USER,
#   is_active: true,
#   roles: [user_role]
# )
#
# user3 = User.create!(
#   email: 'inactive@example.com',
#   password_hash: 'hashed_password',
#   role: :USER,
#   is_active: false,
#   roles: [user_role]
# )
#
# # Create Posts
# Post.create!(
#   user: user1,
#   title: 'Admin First Post',
#   content: 'This is a post by the admin.',
#   status: :PUBLISHED
# )
#
# Post.create!(
#   user: user2,
#   title: 'User Draft Post',
#   content: 'This is a draft post by a user.',
#   status: :DRAFT
# )
#
# Post.create!(
#   user: user2,
#   title: 'User Published Post',
#   content: 'This is a published post by a user.',
#   status: :PUBLISHED
# )

# --- app.rb ---
require 'sinatra'
require 'sinatra/activerecord'
require 'json'
require 'securerandom'

# --- Model Definitions ---
class ApplicationRecord < ActiveRecord::Base
  self.abstract_class = true
  before_create :set_uuid

  private
  def set_uuid
    self.id ||= SecureRandom.uuid if self.class.column_names.include?('id')
  end
end

class User < ApplicationRecord
  has_many :posts, dependent: :destroy
  has_and_belongs_to_many :roles

  enum role: { ADMIN: 0, USER: 1 }

  validates :email, presence: true, uniqueness: true
  validates :password_hash, presence: true
end

class Post < ApplicationRecord
  belongs_to :user

  enum status: { DRAFT: 0, PUBLISHED: 1 }

  validates :title, presence: true
end

class Role < ApplicationRecord
  has_and_belongs_to_many :users
  validates :name, presence: true, uniqueness: true
end

# --- Sinatra Application ---
set :database_file, 'config/database.yml'

# --- Helpers ---
helpers do
  def json_params
    JSON.parse(request.body.read)
  rescue JSON::ParserError
    halt 400, { message: 'Invalid JSON' }.to_json
  end
end

before do
  content_type :json
end

# --- CRUD operations on User ---
# GET /users - List users with filtering
get '/users' do
  users = User.all
  users = users.where(is_active: params[:is_active]) if params[:is_active]
  users = users.where(role: User.roles[params[:role]]) if params[:role]
  users.to_json(include: :roles)
end

# POST /users - Create a new user
post '/users' do
  params = json_params
  user = User.new(
    email: params['email'],
    password_hash: params['password_hash'],
    role: params['role'] || 'USER'
  )
  if user.save
    status 201
    user.to_json
  else
    status 422
    { errors: user.errors.full_messages }.to_json
  end
end

# GET /users/:id - Get a single user
get '/users/:id' do
  user = User.find_by(id: params[:id])
  halt 404, { message: 'User not found' }.to_json unless user
  user.to_json(include: [:posts, :roles])
end

# PUT /users/:id - Update a user
put '/users/:id' do
  user = User.find_by(id: params[:id])
  halt 404, { message: 'User not found' }.to_json unless user

  if user.update(json_params)
    user.to_json
  else
    status 422
    { errors: user.errors.full_messages }.to_json
  end
end

# DELETE /users/:id - Delete a user
delete '/users/:id' do
  user = User.find_by(id: params[:id])
  if user
    user.destroy
    status 204
  else
    status 404
    { message: 'User not found' }.to_json
  end
end

# --- CRUD operations on Post ---
# GET /posts - List all posts
get '/posts' do
  posts = Post.all
  posts = posts.where(status: Post.statuses[params[:status]]) if params[:status]
  posts.to_json(include: :user)
end

# GET /users/:user_id/posts - List posts for a specific user
get '/users/:user_id/posts' do
  user = User.find_by(id: params[:user_id])
  halt 404, { message: 'User not found' }.to_json unless user
  user.posts.to_json
end

# POST /users/:user_id/posts - Create a post for a user
post '/users/:user_id/posts' do
  user = User.find_by(id: params[:user_id])
  halt 404, { message: 'User not found' }.to_json unless user

  post = user.posts.new(json_params)
  if post.save
    status 201
    post.to_json
  else
    status 422
    { errors: post.errors.full_messages }.to_json
  end
end

# --- Transaction Example ---
# Create a user, assign them a role, and create their first post in one transaction
post '/onboarding' do
  data = json_params
  user_data = data['user']
  post_data = data['post']
  role_name = data['role_name'] || 'USER'

  result = nil
  begin
    ActiveRecord::Base.transaction do
      user = User.create!(
        email: user_data['email'],
        password_hash: user_data['password_hash']
      )

      role = Role.find_by!(name: role_name)
      user.roles << role

      post = user.posts.create!(
        title: post_data['title'],
        content: post_data['content'],
        status: :PUBLISHED
      )
      result = { user: user.as_json, post: post.as_json, roles: user.roles.as_json }
    end
  rescue ActiveRecord::RecordInvalid, ActiveRecord::RecordNotFound => e
    status 422
    return { error: "Transaction failed: #{e.message}" }.to_json
  end

  status 201
  result.to_json
end
</code_snippet>