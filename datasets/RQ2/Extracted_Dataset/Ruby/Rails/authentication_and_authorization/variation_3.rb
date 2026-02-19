# This variation demonstrates an API-first, JWT-only approach. It's suitable for
# Single Page Applications (SPAs) or mobile clients. There is no web-based session
# management; every authenticated request must provide a valid JWT in the header.
# Authorization is handled via simple, direct role checks in controller before_actions.

# --- Gemfile ---
# source 'https://rubygems.org'
# gem 'rails', '~> 7.0'
# gem 'pg'
# gem 'bcrypt', '~> 3.1.7'
# gem 'jwt', '~> 2.3'
# gem 'omniauth'
# gem 'omniauth-google-oauth2'

# --- config/application.rb ---
# To make this a true API-only app, you would set:
# module YourApp
#   class Application < Rails::Application
#     # ...
#     config.api_only = true
#   end
# end

# --- db/migrate/enable_extensions_and_create_tables.rb ---
class EnableExtensionsAndCreateTables < ActiveRecord::Migration[7.0]
  def change
    enable_extension 'pgcrypto' unless extension_enabled?('pgcrypto')

    create_table :users, id: :uuid do |t|
      t.string :email, null: false, index: { unique: true }
      t.string :password_digest
      t.integer :role, default: 1, null: false # 1: USER, 2: ADMIN
      t.boolean :is_active, default: true, null: false
      t.timestamps
    end

    create_table :posts, id: :uuid do |t|
      t.references :user, type: :uuid, null: false, foreign_key: true
      t.string :title, null: false
      t.text :content
      t.integer :status, default: 0, null: false # 0: DRAFT, 1: PUBLISHED
      t.timestamps
    end
  end
end

# --- app/models/user.rb ---
class User < ApplicationRecord
  has_secure_password
  enum role: { USER: 1, ADMIN: 2 }
  has_many :posts
  validates :email, presence: true, uniqueness: true
end

# --- app/models/post.rb ---
class Post < ApplicationRecord
  belongs_to :user
  enum status: { DRAFT: 0, PUBLISHED: 1 }
end

# --- app/lib/token_provider.rb ---
class TokenProvider
  def self.encode(payload)
    payload[:exp] = 24.hours.from_now.to_i
    JWT.encode(payload, Rails.application.secret_key_base)
  end

  def self.decode(token)
    body = JWT.decode(token, Rails.application.secret_key_base)[0]
    HashWithIndifferentAccess.new(body)
  rescue JWT::ExpiredSignature, JWT::DecodeError
    nil
  end
end

# --- app/commands/authenticate_user.rb ---
# A simple command object for authentication logic.
class AuthenticateUser
  def initialize(email, password)
    @email = email
    @password = password
  end

  def call
    user = User.find_by(email: @email)
    return user if user&.authenticate(@password) && user.is_active?
    nil
  end
end

# --- app/controllers/api/api_controller.rb ---
module Api
  class ApiController < ActionController::API
    include ActionController::HttpAuthentication::Token::ControllerMethods

    attr_reader :current_user

    private

    def authenticate_token!
      authenticate_with_http_token do |token, _options|
        payload = TokenProvider.decode(token)
        if payload
          @current_user = User.find_by(id: payload[:user_id])
        else
          request_unauthorized
        end
      end || request_unauthorized
    end

    def require_admin!
      render json: { error: 'Forbidden' }, status: :forbidden unless current_user&.ADMIN?
    end

    def request_unauthorized
      render json: { error: 'Unauthorized' }, status: :unauthorized
    end
  end
end

# --- app/controllers/api/v1/authentication_controller.rb ---
module Api
  module V1
    class AuthenticationController < ApiController
      def login
        command = AuthenticateUser.new(params[:email], params[:password])
        user = command.call

        if user
          token = TokenProvider.encode(user_id: user.id, role: user.role)
          render json: { auth_token: token }
        else
          render json: { error: 'Invalid credentials' }, status: :unauthorized
        end
      end
    end
  end
end

# --- app/controllers/api/v1/oauth_controller.rb ---
module Api
  module V1
    class OauthController < ApiController
      # This assumes the client (e.g., a SPA) did the OAuth dance and got a token from Google.
      # The client then sends that provider token to this endpoint.
      def google
        # In a real app, you'd use a gem to verify the token with Google's servers.
        # For this example, we'll simulate a successful verification.
        # e.g., `GoogleIDToken::Validator.new.check(params[:token], ENV['GOOGLE_CLIENT_ID'])`
        
        # Mocked payload from a verified Google token:
        google_payload = { email: "user_from_google@example.com" }

        user = User.find_or_create_by(email: google_payload[:email]) do |u|
          u.password = SecureRandom.hex(10)
          u.role = 'USER'
          u.is_active = true
        end

        if user.persisted?
          token = TokenProvider.encode(user_id: user.id, role: user.role)
          render json: { auth_token: token }
        else
          render json: { error: 'Failed to authenticate with Google' }, status: :unprocessable_entity
        end
      end
    end
  end
end

# --- app/controllers/api/v1/posts_controller.rb ---
module Api
  module V1
    class PostsController < ApiController
      before_action :authenticate_token!
      before_action :require_admin!, only: [:destroy]

      def index
        # Scope could be based on user role
        posts = current_user.ADMIN? ? Post.all : Post.where(status: 'PUBLISHED').or(Post.where(user: current_user))
        render json: posts
      end

      def create
        post = current_user.posts.build(post_params)
        if post.save
          render json: post, status: :created
        else
          render json: post.errors, status: :unprocessable_entity
        end
      end

      def destroy
        post = Post.find(params[:id])
        # Admins can delete any post
        post.destroy
        head :no_content
      end

      private

      def post_params
        params.require(:post).permit(:title, :content, :status)
      end
    end
  end
end

# --- config/routes.rb ---
Rails.application.routes.draw do
  namespace :api do
    namespace :v1 do
      resources :posts, only: [:index, :create, :destroy]
      post 'login', to: 'authentication#login'
      post 'oauth/google', to: 'oauth#google'
    end
  end
end