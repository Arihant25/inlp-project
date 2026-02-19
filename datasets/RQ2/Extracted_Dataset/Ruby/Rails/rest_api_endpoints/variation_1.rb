<code_snippet>
# In a real Rails application, these would be in separate files.
# They are combined here for a self-contained example.

# --- Gemfile ---
# source 'https://rubygems.org'
# gem 'rails', '~> 7.0'
# gem 'pg' # For UUID support
# gem 'kaminari' # For pagination

# --- config/application.rb ---
# require_relative "boot"
# require "rails/all"
# Bundler.require(*Rails.groups)
# module ApiProject
#   class Application < Rails::Application
#     config.load_defaults 7.0
#     config.api_only = true
#     # Configure generators to use UUIDs as primary keys
#     config.generators do |g|
#       g.orm :active_record, primary_key_type: :uuid
#     end
#   end
# end

# --- db/migrate/20230101000001_create_users_and_posts.rb ---
class CreateUsersAndPosts < ActiveRecord::Migration[7.0]
  def change
    enable_extension 'pgcrypto' unless extension_enabled?('pgcrypto')

    create_table :users, id: :uuid do |t|
      t.string :email, null: false, index: { unique: true }
      t.string :password_hash, null: false
      t.integer :role, default: 0 # 0: USER, 1: ADMIN
      t.boolean :is_active, default: true
      t.timestamps
    end

    create_table :posts, id: :uuid do |t|
      t.references :user, type: :uuid, foreign_key: true, null: false
      t.string :title, null: false
      t.text :content
      t.integer :status, default: 0 # 0: DRAFT, 1: PUBLISHED
      t.timestamps
    end
  end
end

# --- app/models/user.rb ---
class User < ApplicationRecord
  # NOTE: In a real app, use has_secure_password.
  # password_hash is used here to simplify the example.
  has_many :posts, dependent: :destroy

  enum role: { user: 0, admin: 1 }

  validates :email, presence: true, uniqueness: true, format: { with: URI::MailTo::EMAIL_REGEXP }
  validates :password_hash, presence: true
  validates :role, presence: true
end

# --- app/models/post.rb ---
class Post < ApplicationRecord
  belongs_to :user
  enum status: { draft: 0, published: 1 }
  validates :title, presence: true
end

# --- config/routes.rb ---
Rails.application.routes.draw do
  resources :users
end

# --- app/controllers/users_controller.rb ---
class UsersController < ApplicationController
  before_action :set_user, only: [:show, :update, :destroy]

  # GET /users
  # GET /users?role=admin&is_active=true&page=1
  def index
    @users = User.all

    # Filtering
    @users = @users.where(role: params[:role]) if params[:role].present?
    @users = @users.where(is_active: params[:is_active]) if params[:is_active].present?

    # Pagination
    @users = @users.order(created_at: :desc).page(params[:page]).per(10)

    render json: @users
  end

  # GET /users/1
  def show
    render json: @user
  end

  # POST /users
  def create
    @user = User.new(user_params)

    if @user.save
      render json: @user, status: :created, location: @user
    else
      render json: @user.errors, status: :unprocessable_entity
    end
  end

  # PATCH/PUT /users/1
  def update
    if @user.update(user_params)
      render json: @user
    else
      render json: @user.errors, status: :unprocessable_entity
    end
  end

  # DELETE /users/1
  def destroy
    @user.destroy
    head :no_content
  end

  private
    # Use callbacks to share common setup or constraints between actions.
    def set_user
      @user = User.find(params[:id])
    end

    # Only allow a list of trusted parameters through.
    def user_params
      # NOTE: In a real app, handle password separately (e.g., :password, :password_confirmation)
      params.require(:user).permit(:email, :password_hash, :role, :is_active)
    end
end

# --- db/seeds.rb ---
# This file should contain all the record creation needed to seed the database with its default values.
# The data can then be loaded with the bin/rails db:seed command (or created alongside the database with db:setup).
User.destroy_all
Post.destroy_all

puts "Seeding database..."

15.times do |i|
  User.create!(
    email: "user#{i+1}@example.com",
    password_hash: "hashed_password_#{i+1}",
    role: :user,
    is_active: [true, false].sample
  )
end

5.times do |i|
  user = User.create!(
    email: "admin#{i+1}@example.com",
    password_hash: "hashed_password_admin_#{i+1}",
    role: :admin,
    is_active: true
  )
  user.posts.create!(
    title: "Admin Post ##{i+1}",
    content: "This is a post by an admin.",
    status: :published
  )
end

puts "Seeding complete."
</code_snippet>