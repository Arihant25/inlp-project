# This variation demonstrates the "Classic Rails Way" using standard sessions for web,
# JWT for a namespaced API, and the Pundit gem for robust, policy-based authorization.
# It's a very common, scalable, and maintainable pattern in the Rails community.

# --- Gemfile ---
# source 'https://rubygems.org'
# gem 'rails', '~> 7.0'
# gem 'pg'
# gem 'bcrypt', '~> 3.1.7'
# gem 'jwt', '~> 2.3'
# gem 'pundit'
# gem 'omniauth'
# gem 'omniauth-google-oauth2'

# --- config/initializers/pundit.rb ---
# frozen_string_literal: true
# Include Pundit behavior in all controllers.
class ApplicationController < ActionController::Base
  include Pundit::Authorization
end

# --- config/initializers/jwt.rb ---
# frozen_string_literal: true
module JsonWebToken
  SECRET_KEY = ENV.fetch('JWT_SECRET_KEY', Rails.application.secret_key_base)

  def self.encode(payload, exp = 24.hours.from_now)
    payload[:exp] = exp.to_i
    JWT.encode(payload, SECRET_KEY)
  end

  def self.decode(token)
    decoded = JWT.decode(token, SECRET_KEY)[0]
    HashWithIndifferentAccess.new(decoded)
  rescue JWT::DecodeError => e
    nil
  end
end

# --- config/initializers/omniauth.rb ---
# frozen_string_literal: true
Rails.application.config.middleware.use OmniAuth::Builder do
  provider :google_oauth2, ENV['GOOGLE_CLIENT_ID'], ENV['GOOGLE_CLIENT_SECRET']
end

# --- db/migrate/enable_extensions_and_create_tables.rb ---
class EnableExtensionsAndCreateTables < ActiveRecord::Migration[7.0]
  def change
    enable_extension 'pgcrypto' unless extension_enabled?('pgcrypto')

    create_table :users, id: :uuid do |t|
      t.string :email, null: false, index: { unique: true }
      t.string :password_digest
      t.integer :role, default: 0, null: false # 0: user, 1: admin
      t.boolean :is_active, default: true, null: false
      t.timestamps
    end

    create_table :posts, id: :uuid do |t|
      t.references :user, type: :uuid, null: false, foreign_key: true
      t.string :title, null: false
      t.text :content
      t.integer :status, default: 0, null: false # 0: draft, 1: published
      t.timestamps
    end
  end
end

# --- app/models/user.rb ---
class User < ApplicationRecord
  has_secure_password

  has_many :posts, dependent: :destroy

  enum role: { user: 0, admin: 1 }

  validates :email, presence: true, uniqueness: true, format: { with: URI::MailTo::EMAIL_REGEXP }
  validates :password, length: { minimum: 8 }, if: -> { new_record? || !password.nil? }

  def self.from_omniauth(auth)
    where(email: auth.info.email).first_or_initialize do |user|
      user.password = user.password_confirmation = SecureRandom.hex
      user.is_active = true
      # You might want to handle user creation more robustly
    end
  end
end

# --- app/models/post.rb ---
class Post < ApplicationRecord
  belongs_to :user
  enum status: { draft: 0, published: 1 }
  validates :title, :content, presence: true
end

# --- app/policies/post_policy.rb ---
class PostPolicy < ApplicationPolicy
  def index?
    true
  end

  def show?
    true
  end

  def create?
    user.present?
  end

  def update?
    user.present? && (record.user == user || user.admin?)
  end

  def destroy?
    user.present? && (record.user == user || user.admin?)
  end

  class Scope < Scope
    def resolve
      if user.admin?
        scope.all
      else
        scope.where(status: :published).or(scope.where(user: user))
      end
    end
  end
end

# --- app/controllers/application_controller.rb ---
class ApplicationController < ActionController::Base
  protect_from_forgery with: :exception
  helper_method :current_user, :logged_in?

  private

  def current_user
    @current_user ||= User.find_by(id: session[:user_id]) if session[:user_id]
  end

  def logged_in?
    !!current_user
  end

  def authenticate_user!
    redirect_to login_path, alert: 'You must be logged in to access this page.' unless logged_in?
  end
end

# --- app/controllers/sessions_controller.rb ---
class SessionsController < ApplicationController
  def new
  end

  def create
    user = User.find_by(email: params[:session][:email].downcase)
    if user&.authenticate(params[:session][:password]) && user.is_active?
      session[:user_id] = user.id
      redirect_to root_path, notice: 'Logged in successfully.'
    else
      flash.now[:alert] = 'Invalid email or password.'
      render :new
    end
  end

  def destroy
    session[:user_id] = nil
    redirect_to root_path, notice: 'Logged out successfully.'
  end

  def omniauth
    user = User.from_omniauth(request.env['omniauth.auth'])
    if user.save
      session[:user_id] = user.id
      redirect_to root_path, notice: 'Logged in successfully via Google.'
    else
      redirect_to login_path, alert: 'Could not log in via Google.'
    end
  end
end

# --- app/controllers/posts_controller.rb ---
class PostsController < ApplicationController
  before_action :authenticate_user!, except: [:index, :show]
  before_action :set_post, only: [:show, :edit, :update, :destroy]

  def index
    @posts = policy_scope(Post.all)
  end

  def show
    authorize @post
  end



  def create
    @post = current_user.posts.build(post_params)
    authorize @post
    if @post.save
      # redirect
    else
      # render
    end
  end

  def update
    authorize @post
    if @post.update(post_params)
      # redirect
    else
      # render
    end
  end

  def destroy
    authorize @post
    @post.destroy
    # redirect
  end

  private

  def set_post
    @post = Post.find(params[:id])
  end

  def post_params
    params.require(:post).permit(:title, :content, :status)
  end
end

# --- app/controllers/api/v1/base_controller.rb ---
module Api
  module V1
    class BaseController < ActionController::API
      before_action :authenticate_request

      private

      def authenticate_request
        header = request.headers['Authorization']
        header = header.split(' ').last if header
        begin
          @decoded = JsonWebToken.decode(header)
          @current_user = User.find(@decoded[:user_id])
        rescue ActiveRecord::RecordNotFound => e
          render json: { errors: e.message }, status: :unauthorized
        rescue JWT::DecodeError => e
          render json: { errors: e.message }, status: :unauthorized
        end
      end

      attr_reader :current_user
    end
  end
end

# --- app/controllers/api/v1/authentications_controller.rb ---
module Api
  module V1
    class AuthenticationsController < ActionController::API
      def create
        user = User.find_by(email: params[:email])
        if user&.authenticate(params[:password]) && user.is_active?
          token = JsonWebToken.encode(user_id: user.id)
          render json: { token: token, exp: 24.hours.from_now.strftime("%m-%d-%Y %H:%M") }, status: :ok
        else
          render json: { error: 'unauthorized' }, status: :unauthorized
        end
      end
    end
  end
end

# --- config/routes.rb ---
Rails.application.routes.draw do
  root 'posts#index'
  resources :posts

  get 'login', to: 'sessions#new'
  post 'login', to: 'sessions#create'
  delete 'logout', to: 'sessions#destroy'

  get '/auth/:provider/callback', to: 'sessions#omniauth'

  namespace :api do
    namespace :v1 do
      post 'authenticate', to: 'authentications#create'
    end
  end
end