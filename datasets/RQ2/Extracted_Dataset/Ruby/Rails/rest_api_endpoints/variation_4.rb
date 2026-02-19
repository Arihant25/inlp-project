<code_snippet>
# In a real Rails application, these would be in separate files.
# They are combined here for a self-contained example.
# This variation uses an API-first, namespaced approach with custom error handling and presenters.

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
  enum role: { USER: 0, ADMIN: 1 }
  validates :email, presence: true, uniqueness: true, format: { with: URI::MailTo::EMAIL_REGEXP }
  validates :password_hash, presence: true
end

# --- app/models/post.rb ---
class Post < ApplicationRecord
  belongs_to :user
  enum status: { DRAFT: 0, PUBLISHED: 1 }
end

# --- app/presenters/user_presenter.rb ---
class UserPresenter
  def initialize(user)
    @user = user
  end

  def as_json
    {
      id: @user.id,
      email: @user.email,
      role: @user.role,
      is_active: @user.is_active,
      created_at: @user.created_at.iso8601
    }
  end
end

# --- config/routes.rb ---
Rails.application.routes.draw do
  namespace :api do
    namespace :v1 do
      resources :users
    end
  end
end

# --- app/controllers/api/v1/base_controller.rb ---
module Api
  module V1
    class BaseController < ApplicationController
      rescue_from ActiveRecord::RecordNotFound, with: :render_not_found
      rescue_from ActionController::ParameterMissing, with: :render_bad_request

      private

      def render_not_found(exception)
        render json: { error: { message: exception.message } }, status: :not_found
      end

      def render_bad_request(exception)
        render json: { error: { message: exception.message } }, status: :bad_request
      end
    end
  end
end

# --- app/controllers/api/v1/users_controller.rb ---
module Api
  module V1
    class UsersController < BaseController
      ALLOWED_FILTER_PARAMS = %i[role is_active].freeze

      # GET /api/v1/users
      def index
        users_scope = apply_filters(User.all)
        paginated_users = users_scope.order(created_at: :desc).page(params[:page]).per(10)

        render json: {
          data: paginated_users.map { |user| UserPresenter.new(user).as_json },
          meta: pagination_meta(paginated_users)
        }
      end

      # GET /api/v1/users/:id
      def show
        user = User.find(params[:id])
        render json: { data: UserPresenter.new(user).as_json }
      end

      # POST /api/v1/users
      def create
        user = User.new(user_creation_params)
        if user.save
          render json: { data: UserPresenter.new(user).as_json }, status: :created
        else
          render json: { errors: user.errors.full_messages }, status: :unprocessable_entity
        end
      end

      # PUT /api/v1/users/:id
      def update
        user = User.find(params[:id])
        if user.update(user_update_params)
          render json: { data: UserPresenter.new(user).as_json }
        else
          render json: { errors: user.errors.full_messages }, status: :unprocessable_entity
        end
      end

      # DELETE /api/v1/users/:id
      def destroy
        User.find(params[:id]).destroy!
        head :no_content
      end

      private

      def user_creation_params
        params.require(:user).permit(:email, :password_hash, :role, :is_active)
      end

      def user_update_params
        params.require(:user).permit(:role, :is_active) # Disallow email/password changes on update
      end

      def apply_filters(scope)
        params.slice(*ALLOWED_FILTER_PARAMS).each do |key, value|
          scope = scope.where(key => value) if value.present?
        end
        scope
      end

      def pagination_meta(collection)
        {
          current_page: collection.current_page,
          next_page: collection.next_page,
          prev_page: collection.prev_page,
          total_pages: collection.total_pages,
          total_count: collection.total_count
        }
      end
    end
  end
end

# --- db/seeds.rb ---
User.destroy_all
15.times { |i| User.create!(email: "user#{i+1}@example.com", password_hash: "pw", role: :USER, is_active: [true, false].sample) }
5.times { |i| User.create!(email: "admin#{i+1}@example.com", password_hash: "pw", role: :ADMIN, is_active: true) }
</code_snippet>