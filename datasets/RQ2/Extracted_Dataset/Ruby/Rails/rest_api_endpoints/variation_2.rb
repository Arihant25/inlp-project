<code_snippet>
# In a real Rails application, these would be in separate files.
# They are combined here for a self-contained example.
# This variation uses Service Objects to encapsulate business logic.

# --- Gemfile ---
# source 'https://rubygems.org'
# gem 'rails', '~> 7.0'
# gem 'pg'
# gem 'kaminari'

# --- config/application.rb ---
# require_relative "boot"
# require "rails/all"
# Bundler.require(*Rails.groups)
# module ApiProject
#   class Application < Rails::Application
#     config.load_defaults 7.0
#     config.api_only = true
#     config.autoload_paths += %W(#{config.root}/app/services)
#     config.generators { |g| g.orm :active_record, primary_key_type: :uuid }
#   end
# end

# --- db/migrate/20230101000001_create_users_and_posts.rb ---
class CreateUsersAndPosts < ActiveRecord::Migration[7.0]
  def change
    enable_extension 'pgcrypto' unless extension_enabled?('pgcrypto')
    create_table :users, id: :uuid do |t|
      t.string :email, null: false, index: { unique: true }
      t.string :password_hash, null: false
      t.integer :role, default: 0
      t.boolean :is_active, default: true
      t.timestamps
    end
    create_table :posts, id: :uuid do |t|
      t.references :user, type: :uuid, foreign_key: true, null: false
      t.string :title, null: false
      t.text :content
      t.integer :status, default: 0
      t.timestamps
    end
  end
end

# --- app/models/user.rb ---
class User < ApplicationRecord
  has_many :posts, dependent: :destroy
  enum role: { user: 0, admin: 1 }
  validates :email, presence: true, uniqueness: true, format: { with: URI::MailTo::EMAIL_REGEXP }
  validates :password_hash, presence: true
end

# --- app/models/post.rb ---
class Post < ApplicationRecord
  belongs_to :user
  enum status: { draft: 0, published: 1 }
  validates :title, presence: true
end

# --- app/services/user_search_service.rb ---
class UserSearchService
  def initialize(params = {})
    @params = params
  end

  def call
    scope = User.order(created_at: :desc)
    scope = filter_by_role(scope)
    scope = filter_by_activity(scope)
    paginate(scope)
  end

  private

  attr_reader :params

  def filter_by_role(scope)
    params[:role].present? ? scope.where(role: params[:role]) : scope
  end

  def filter_by_activity(scope)
    params[:is_active].present? ? scope.where(is_active: params[:is_active]) : scope
  end

  def paginate(scope)
    scope.page(params[:page]).per(10)
  end
end

# --- app/services/user_management_service.rb ---
class UserManagementService
  Result = Struct.new(:success?, :record, :errors, keyword_init: true)

  def self.create(params)
    user = User.new(params)
    if user.save
      Result.new(success?: true, record: user)
    else
      Result.new(success?: false, errors: user.errors)
    end
  end

  def self.update(user, params)
    if user.update(params)
      Result.new(success?: true, record: user)
    else
      Result.new(success?: false, errors: user.errors)
    end
  end

  def self.destroy(user)
    user.destroy
    Result.new(success?: true)
  end
end

# --- config/routes.rb ---
Rails.application.routes.draw do
  resources :users, except: [:new, :edit]
end

# --- app/controllers/users_controller.rb ---
class UsersController < ApplicationController
  before_action :find_user, only: [:show, :update, :destroy]

  # GET /users
  def index
    users = UserSearchService.new(params).call
    render json: users
  end

  # GET /users/:id
  def show
    render json: @user_record
  end

  # POST /users
  def create
    result = UserManagementService.create(user_params)
    if result.success?
      render json: result.record, status: :created
    else
      render json: result.errors, status: :unprocessable_entity
    end
  end

  # PUT /users/:id
  def update
    result = UserManagementService.update(@user_record, user_params)
    if result.success?
      render json: result.record, status: :ok
    else
      render json: result.errors, status: :unprocessable_entity
    end
  end

  # DELETE /users/:id
  def destroy
    UserManagementService.destroy(@user_record)
    head :no_content
  end

  private

  def find_user
    @user_record = User.find(params[:id])
  end

  def user_params
    params.require(:user).permit(:email, :password_hash, :role, :is_active)
  end
end

# --- db/seeds.rb ---
User.destroy_all
15.times { |i| User.create!(email: "user#{i+1}@example.com", password_hash: "pw", role: :user, is_active: [true, false].sample) }
5.times { |i| User.create!(email: "admin#{i+1}@example.com", password_hash: "pw", role: :admin, is_active: true) }
</code_snippet>