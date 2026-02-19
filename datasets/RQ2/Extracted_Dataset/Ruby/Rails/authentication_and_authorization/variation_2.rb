# This variation uses the Service Object and custom Concern pattern.
# Logic is extracted from controllers into dedicated service classes, promoting
# single responsibility and easier testing. Authorization is handled by a
# custom, more explicit concern instead of a third-party gem.

# --- Gemfile ---
# source 'https://rubygems.org'
# gem 'rails', '~> 7.0'
# gem 'pg'
# gem 'bcrypt', '~> 3.1.7'
# gem 'jwt', '~> 2.3'
# gem 'omniauth'
# gem 'omniauth-google-oauth2'

# --- db/migrate/enable_extensions_and_create_tables.rb ---
class EnableExtensionsAndCreateTables < ActiveRecord::Migration[7.0]
  def change
    enable_extension 'pgcrypto' unless extension_enabled?('pgcrypto')

    create_table :users, id: :uuid do |t|
      t.string :email, null: false, index: { unique: true }
      t.string :password_digest
      t.string :role, default: 'user', null: false # string-based role
      t.boolean :is_active, default: true, null: false
      t.timestamps
    end

    create_table :posts, id: :uuid do |t|
      t.references :user, type: :uuid, null: false, foreign_key: true
      t.string :title, null: false
      t.text :content
      t.string :status, default: 'draft', null: false # string-based status
      t.timestamps
    end
  end
end

# --- app/models/user.rb ---
class User < ApplicationRecord
  has_secure_password

  ROLES = %w[user admin].freeze

  has_many :posts, dependent: :destroy

  validates :email, presence: true, uniqueness: true
  validates :role, inclusion: { in: ROLES }

  def admin?
    role == 'admin'
  end
end

# --- app/models/post.rb ---
class Post < ApplicationRecord
  belongs_to :user
  STATUSES = %w[draft published].freeze
  validates :status, inclusion: { in: STATUSES }
end

# --- app/services/authentication_service.rb ---
class AuthenticationService
  def initialize(email, password)
    @email = email
    @password = password
  end

  def call
    user = User.find_by(email: @email)
    return nil unless user&.authenticate(@password) && user.is_active?
    user
  end
end

# --- app/services/oauth_user_service.rb ---
class OauthUserCreator
  def initialize(auth_hash)
    @auth_hash = auth_hash
  end

  def call
    user = User.find_or_initialize_by(email: @auth_hash.info.email)
    if user.new_record?
      user.password = SecureRandom.hex(16)
      user.is_active = true
    end
    user.save ? user : nil
  end
end

# --- app/lib/json_web_token.rb ---
# Note: lib is autoloaded by default in Rails
module JsonWebToken
  SECRET = Rails.application.credentials.secret_key_base

  def self.issue(payload, expires_in: 24.hours.from_now)
    payload[:exp] = expires_in.to_i
    JWT.encode(payload, SECRET)
  end

  def self.verify(token)
    JWT.decode(token, SECRET).first
  rescue JWT::DecodeError
    nil
  end
end

# --- app/controllers/concerns/authorization.rb ---
module Authorization
  extend ActiveSupport::Concern

  included do
    rescue_from NotAuthorizedError, with: :user_not_authorized
  end

  class NotAuthorizedError < StandardError; end

  private

  def authorize_admin!
    raise NotAuthorizedError unless current_user_context&.admin?
  end

  def authorize_record_owner!(record)
    raise NotAuthorizedError unless record.user_id == current_user_context&.id || current_user_context&.admin?
  end

  def user_not_authorized
    respond_to do |format|
      format.html { redirect_to root_path, alert: "You are not authorized to perform this action." }
      format.json { render json: { error: "Not Authorized" }, status: :forbidden }
    end
  end
end

# --- app/controllers/application_controller.rb ---
class ApplicationController < ActionController::Base
  include Authorization

  private

  def current_user_context
    return @current_user_context if defined?(@current_user_context)
    @current_user_context = User.find_by(id: session[:user_id])
  end
  helper_method :current_user_context

  def require_login
    redirect_to new_session_path unless current_user_context
  end
end

# --- app/controllers/sessions_controller.rb ---
class SessionsController < ApplicationController
  def create
    auth_service = AuthenticationService.new(params[:email], params[:password])
    user = auth_service.call

    if user
      session[:user_id] = user.id
      redirect_to root_path, notice: "Welcome back!"
    else
      flash.now[:alert] = "Invalid credentials."
      render :new, status: :unprocessable_entity
    end
  end

  def destroy
    session.delete(:user_id)
    redirect_to root_path, notice: "Logged out."
  end

  def omniauth_callback
    user = OauthUserCreator.new(request.env['omniauth.auth']).call
    if user
      session[:user_id] = user.id
      redirect_to root_path, notice: "Successfully authenticated."
    else
      redirect_to new_session_path, alert: "Authentication failed."
    end
  end
end

# --- app/controllers/posts_controller.rb ---
class PostsController < ApplicationController
  before_action :require_login, except: [:index]
  before_action :find_post, only: [:update, :destroy]
  before_action -> { authorize_record_owner!(@post) }, only: [:update, :destroy]

  def index
    # In a real app, this would have more complex scoping logic
    @posts = Post.all
  end

  def create
    @post = current_user_context.posts.build(post_params)
    # ... save and render
  end

  def update
    # ... update and render
  end

  def destroy
    @post.destroy
    # ... redirect
  end

  private

  def find_post
    @post = Post.find(params[:id])
  end

  def post_params
    params.require(:post).permit(:title, :content, :status)
  end
end

# --- app/controllers/api/v1/tokens_controller.rb ---
module Api
  module V1
    class TokensController < ActionController::API
      def create
        auth_service = AuthenticationService.new(params[:email], params[:password])
        user = auth_service.call

        if user
          jwt = JsonWebToken.issue({ user_id: user.id, role: user.role })
          render json: { token: jwt }, status: :created
        else
          render json: { error: "Invalid credentials" }, status: :unauthorized
        end
      end
    end
  end
end

# --- config/routes.rb ---
Rails.application.routes.draw do
  root 'posts#index'
  resources :posts, except: [:new, :edit]
  resource :session, only: [:new, :create, :destroy]

  get '/auth/:provider/callback', to: 'sessions#omniauth_callback'

  namespace :api do
    namespace :v1 do
      resource :token, only: [:create]
    end
  end
end