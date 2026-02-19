<code_snippet>
# In a real Rails application, these would be in separate files.
# They are combined here for a self-contained example.
# This variation uses Query Objects for searching and Form Objects for C/U operations.

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
#     config.autoload_paths += %W(#{config.root}/app/forms #{config.root}/app/queries)
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
end

# --- app/models/post.rb ---
class Post < ApplicationRecord
  belongs_to :user
  enum status: { draft: 0, published: 1 }
end

# --- app/queries/user_query.rb ---
class UserQuery
  attr_reader :params

  def initialize(params)
    @params = params
  end

  def results
    scope = User.order(created_at: :desc)
    scope = scope.where(role: params[:role]) if params[:role].present?
    scope = scope.where(is_active: params[:is_active]) if params[:is_active].present?
    scope.page(params[:page]).per(10)
  end
end

# --- app/forms/user_form.rb ---
class UserForm
  include ActiveModel::Model
  include ActiveModel::Attributes

  attribute :email, :string
  attribute :password_hash, :string
  attribute :role, :string
  attribute :is_active, :boolean, default: true

  attr_reader :user

  validates :email, presence: true, format: { with: URI::MailTo::EMAIL_REGEXP }
  validates :password_hash, presence: true
  validates :role, inclusion: { in: User.roles.keys }
  validate :email_must_be_unique

  def initialize(attributes = {}, user_record: User.new)
    super(attributes)
    @user = user_record
  end

  def submit
    return false unless valid?
    @user.assign_attributes(attributes.except('user'))
    @user.save
  end

  private

  def email_must_be_unique
    if User.where.not(id: @user.id).exists?(email: email)
      errors.add(:email, :taken)
    end
  end
end

# --- config/routes.rb ---
Rails.application.routes.draw do
  resources :users, only: [:index, :show, :create, :update, :destroy]
end

# --- app/controllers/users_controller.rb ---
class UsersController < ApplicationController
  before_action :load_user, only: [:show, :update, :destroy]

  def index
    query = UserQuery.new(params.permit(:role, :is_active, :page))
    render json: query.results, status: :ok
  end

  def show
    render json: @target_user, status: :ok
  end

  def create
    form = UserForm.new(user_form_params)
    if form.submit
      render json: form.user, status: :created
    else
      render json: form.errors, status: :unprocessable_entity
    end
  end

  def update
    form = UserForm.new(user_form_params, user_record: @target_user)
    if form.submit
      render json: form.user, status: :ok
    else
      render json: form.errors, status: :unprocessable_entity
    end
  end

  def destroy
    @target_user.destroy
    head :no_content
  end

  private

  def load_user
    @target_user = User.find(params[:id])
  end

  def user_form_params
    params.require(:user).permit(:email, :password_hash, :role, :is_active)
  end
end

# --- db/seeds.rb ---
User.destroy_all
15.times { |i| User.create!(email: "user#{i+1}@example.com", password_hash: "pw", role: :user, is_active: [true, false].sample) }
5.times { |i| User.create!(email: "admin#{i+1}@example.com", password_hash: "pw", role: :admin, is_active: true) }
</code_snippet>