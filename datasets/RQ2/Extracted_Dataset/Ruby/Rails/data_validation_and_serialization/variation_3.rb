<raw_code>
# frozen_string_literal: true

# This variation demonstrates an "API-First" approach using the
# `active_model_serializers` gem. This pattern cleanly separates the
# representation (serialization) layer from the model layer, which is
# ideal for building robust JSON APIs.

# --- MOCK RAILS ENVIRONMENT SETUP ---
require 'active_record'
require 'action_controller'
require 'active_model_serializers'
require 'securerandom'

# Gemfile would contain: gem 'active_model_serializers'
ActiveRecord::Base.establish_connection(adapter: 'sqlite3', database: ':memory:')
ActiveModelSerializers.config.adapter = :json # Use standard JSON, not JSON:API

ActiveRecord::Schema.define do
  enable_extension 'pgcrypto' unless extension_enabled?('pgcrypto')
  create_table :users, id: :uuid do |t|
    t.string :email, null: false
    t.string :password_hash, null: false
    t.integer :role, default: 1, null: false
    t.boolean :is_active, default: true
    t.timestamps
  end
  add_index :users, :email, unique: true
  create_table :posts, id: :uuid do |t|
    t.uuid :user_id, null: false
    t.string :title, null: false
    t.text :content
    t.integer :status, default: 0, null: false
    t.timestamps
  end
  add_foreign_key :posts, :users
end

# --- MODELS ---
# FILE: app/models/post.rb
class Post < ActiveRecord::Base
  belongs_to :user
  enum status: { DRAFT: 0, PUBLISHED: 1 }
  validates :title, presence: true
end

# FILE: app/models/user.rb
class User < ActiveRecord::Base
  has_many :posts
  enum role: { ADMIN: 0, USER: 1 }
  validates :email, presence: true, uniqueness: true, format: { with: URI::MailTo::EMAIL_REGEXP }
  validates :password_hash, presence: true
end

# --- SERIALIZERS (The "V" in an API MVC) ---
# FILE: app/serializers/post_serializer.rb
class PostSerializer < ActiveModel::Serializer
  attributes :id, :title, :status, :published_at

  def published_at
    object.updated_at if object.PUBLISHED?
  end
end

# FILE: app/serializers/user_serializer.rb
class UserSerializer < ActiveModel::Serializer
  attributes :id, :email, :role, :is_active, :member_since

  # Example of a computed attribute
  def member_since
    object.created_at.strftime("%Y-%m-%d")
  end

  # Defines a relationship, which will use PostSerializer
  has_many :posts
end

# --- CONTROLLERS (API Namespaced) ---
# FILE: app/controllers/api/v1/base_controller.rb
class Api::V1::BaseController < ActionController::Base
  # Centralized error handling for the API
  rescue_from ActiveRecord::RecordNotFound, with: :record_not_found
  rescue_from ActiveRecord::RecordInvalid, with: :record_invalid

  private

  def record_not_found(error)
    render json: { error: { message: error.message } }, status: :not_found
  end

  def record_invalid(error)
    # Standardized error message format
    errors = error.record.errors.messages.map do |field, messages|
      { field: field, messages: messages }
    end
    render json: { error: { message: "Validation Failed", details: errors } }, status: :unprocessable_entity
  end
end

# FILE: app/controllers/api/v1/users_controller.rb
module Api; module V1
  class UsersController < BaseController
    # Deserialization and Type Coercion via Strong Params
    def create
      # Using create! to raise RecordInvalid on failure, handled by BaseController
      user = User.create!(user_params)
      # Serialization is handled by the serializer found by convention
      render json: user, status: :created
    end

    def show
      user = User.find(params[:id])
      # Explicitly using the serializer for rendering
      render json: user, serializer: UserSerializer
    end

    private

    def user_params
      params.require(:user).permit(:email, :password_hash, :role, :is_active)
    end

    # Helper to simulate a Rails request
    def self.simulate_request(action, params)
      controller = new
      controller.params = ActionController::Parameters.new(params)
      puts "--- Simulating API #{action.upcase} request ---"
      controller.send(action)
      puts "Response Body:\n#{controller.response_body.first}\n"
    end
  end
end; end

# --- DEMONSTRATION ---
# 1. Successful creation
payload_success = { user: { email: 'api-user@example.com', password_hash: 'hash123', role: 'USER' } }
Api::V1::UsersController.simulate_request(:create, payload_success)

# 2. Validation failure (handled by BaseController)
payload_fail = { user: { email: 'api-user@example.com', password_hash: '' } }
Api::V1::UsersController.simulate_request(:create, payload_fail)

# 3. Show user (demonstrates serialization)
user = User.first
user.posts.create!(title: 'My First API Post', status: 'PUBLISHED') if user
Api::V1::UsersController.simulate_request(:show, { id: user.id }) if user
</raw_code>