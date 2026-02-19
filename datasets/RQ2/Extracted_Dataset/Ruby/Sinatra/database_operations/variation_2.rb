<code_snippet>
# This variation represents a "Modular Sinatra" style.
# Code is organized into separate files and directories, promoting separation of concerns.
# It uses `Sinatra::Base` for creating modular, mountable applications.

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
# require './config/environment'

# --- config/database.yml ---
# development:
#   adapter: sqlite3
#   database: db/development.sqlite3

# --- config/environment.rb ---
# ENV['RACK_ENV'] ||= 'development'
#
# require 'bundler/setup'
# Bundler.require(:default, ENV['RACK_ENV'])
#
# require 'sinatra/activerecord'
# set :database_file, 'database.yml'
#
# # Load all application files
# Dir.glob('./{models,controllers}/*.rb').each { |file| require file }

# --- db/migrate/ (migrations are the same as Variation 1) ---
# ... (omitted for brevity, but assume they exist)

# --- db/seeds.rb (same as Variation 1) ---
# ... (omitted for brevity, but assume it exists)

# --- models/application_record.rb ---
# require 'securerandom'
#
# class ApplicationRecord < ActiveRecord::Base
#   self.abstract_class = true
#
#   before_create :assign_uuid
#
#   private
#
#   def assign_uuid
#     # For PostgreSQL, the DB would handle this with `gen_random_uuid()`
#     self.id ||= SecureRandom.uuid if self.class.column_names.include?('id')
#   end
# end

# --- models/user.rb ---
# class User < ApplicationRecord
#   has_many :posts, dependent: :destroy
#   has_and_belongs_to_many :roles
#
#   enum role: { ADMIN: 0, USER: 1 }
#
#   validates :email, presence: true, uniqueness: true
# end

# --- models/post.rb ---
# class Post < ApplicationRecord
#   belongs_to :user
#
#   enum status: { DRAFT: 0, PUBLISHED: 1 }
#
#   validates :title, presence: true
# end

# --- models/role.rb ---
# class Role < ApplicationRecord
#   has_and_belongs_to_many :users
#   validates :name, presence: true, uniqueness: true
# end

# --- controllers/application_controller.rb ---
# class ApplicationController < Sinatra::Base
#   configure do
#     set :views, 'views'
#     set :public_folder, 'public'
#   end
#
#   helpers do
#     def request_payload
#       @request_payload ||= JSON.parse(request.body.read)
#     rescue JSON::ParserError
#       halt 400, { error: 'Invalid JSON format' }.to_json
#     end
#
#     def not_found(entity = 'Resource')
#       halt 404, { error: "#{entity} not found" }.to_json
#     end
#
#     def unprocessable_entity(record)
#       halt 422, { errors: record.errors.full_messages }.to_json
#     end
#   end
#
#   before do
#     content_type :json
#   end
# end

# --- controllers/users_controller.rb ---
# class UsersController < ApplicationController
#   # GET /users?role=ADMIN&is_active=true - Query building
#   get '/users' do
#     scope = User.includes(:roles)
#     scope = scope.where(role: User.roles[params[:role]]) if params[:role]
#     scope = scope.where(is_active: params[:is_active]) if params.key?(:is_active)
#     scope.all.to_json(include: :roles)
#   end
#
#   # GET /users/:id
#   get '/users/:id' do
#     user = User.find_by(id: params[:id])
#     not_found('User') unless user
#     user.to_json(include: [:posts, :roles])
#   end
#
#   # POST /users
#   post '/users' do
#     user = User.new(request_payload)
#     user.save ? user.to_json(status: 201) : unprocessable_entity(user)
#   end
#
#   # PUT /users/:id
#   put '/users/:id' do
#     user = User.find_by(id: params[:id])
#     not_found('User') unless user
#     user.update(request_payload) ? user.to_json : unprocessable_entity(user)
#   end
#
#   # DELETE /users/:id
#   delete '/users/:id' do
#     user = User.find_by(id: params[:id])
#     user&.destroy
#     status 204
#   end
# end

# --- controllers/posts_controller.rb ---
# class PostsController < ApplicationController
#   # GET /users/:user_id/posts
#   get '/users/:user_id/posts' do
#     user = User.find_by(id: params[:user_id])
#     not_found('User') unless user
#     user.posts.to_json
#   end
#
#   # POST /users/:user_id/posts
#   post '/users/:user_id/posts' do
#     user = User.find_by(id: params[:user_id])
#     not_found('User') unless user
#
#     post = user.posts.new(request_payload)
#     post.save ? post.to_json(status: 201) : unprocessable_entity(post)
#   end
#
#   # GET /posts/:id
#   get '/posts/:id' do
#     post = Post.find_by(id: params[:id])
#     not_found('Post') unless post
#     post.to_json(include: :user)
#   end
# end

# --- controllers/transactions_controller.rb ---
# class TransactionsController < ApplicationController
#   # POST /user_with_post - Transaction example
#   post '/user_with_post' do
#     payload = request_payload
#     new_user = nil
#
#     ActiveRecord::Base.transaction do
#       admin_role = Role.find_by!(name: 'ADMIN')
#       new_user = User.create!(payload['user'])
#       new_user.roles << admin_role
#       new_user.posts.create!(payload['post'])
#     end
#
#     status 201
#     new_user.to_json(include: [:posts, :roles])
#   rescue ActiveRecord::RecordInvalid, ActiveRecord::RecordNotFound => e
#     halt 422, { error: "Transaction rolled back: #{e.message}" }.to_json
#   end
# end

# --- config.ru ---
require './config/environment'

# This file is used by Rack-based servers to start the application.
# To run: `bundle exec rackup`

# Mocking file requires for self-contained example
# In a real app, these would be actual `require` statements.
module MockFileLoader
  def self.load_files
    # Models
    Object.const_set('ApplicationRecord', Class.new(ActiveRecord::Base) do
      self.abstract_class = true
      before_create -> { self.id ||= SecureRandom.uuid if self.class.column_names.include?('id') }
    end)
    Object.const_set('User', Class.new(ApplicationRecord) do
      has_many :posts, dependent: :destroy
      has_and_belongs_to_many :roles
      enum role: { ADMIN: 0, USER: 1 }
      validates :email, presence: true, uniqueness: true
    end)
    Object.const_set('Post', Class.new(ApplicationRecord) do
      belongs_to :user
      enum status: { DRAFT: 0, PUBLISHED: 1 }
      validates :title, presence: true
    end)
    Object.const_set('Role', Class.new(ApplicationRecord) do
      has_and_belongs_to_many :users
      validates :name, presence: true, uniqueness: true
    end)

    # Controllers
    app_controller = Class.new(Sinatra::Base) do
      helpers do
        def request_payload; JSON.parse(request.body.read); rescue; halt 400; end
        def not_found(e='R'); halt 404, {e:"#{e} not found"}.to_json; end
        def unprocessable_entity(r); halt 422, {e:r.errors.full_messages}.to_json; end
      end
      before { content_type :json }
    end
    Object.const_set('ApplicationController', app_controller)

    Object.const_set('UsersController', Class.new(ApplicationController) do
      get('/users') { User.all.to_json(include: :roles) }
      get('/users/:id') { user = User.find(params[:id]); user.to_json(include: [:posts, :roles]) }
      post('/users') { u=User.new(request_payload); u.save ? u.to_json : unprocessable_entity(u) }
    end)
    Object.const_set('PostsController', Class.new(ApplicationController) do
      get('/users/:user_id/posts') { User.find(params[:user_id]).posts.to_json }
    end)
    Object.const_set('TransactionsController', Class.new(ApplicationController) do
      post('/user_with_post') do
        p=request_payload; u=nil; ActiveRecord::Base.transaction { u=User.create!(p['user']); u.posts.create!(p['post']) }; u.to_json(include: :posts)
      rescue => e; halt 422, {e: e.message}.to_json; end
    end)
  end
end
MockFileLoader.load_files

# Map controllers to routes
run Rack::URLMap.new({
  '/' => UsersController.new,
  '/posts' => PostsController.new,
  '/transactions' => TransactionsController.new
})
</code_snippet>