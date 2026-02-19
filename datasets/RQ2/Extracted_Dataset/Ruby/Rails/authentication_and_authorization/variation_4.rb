# This variation uses the Sorcery gem, a popular, "less-magical" alternative to Devise.
# It provides core authentication features, leaving the developer to implement controllers
# and views. Authorization is handled by a simple, custom `authorize!` method in the
# ApplicationController, a lightweight approach for simpler RBAC needs.

# --- Gemfile ---
# source 'https://rubygems.org'
# gem 'rails', '~> 7.0'
# gem 'pg'
# gem 'sorcery'
# gem 'jwt', '~> 2.3'
# Note: bcrypt is a dependency of sorcery

# --- config/initializers/sorcery.rb ---
Rails.application.config.sorcery.configure do |config|
  config.user_class = 'User'
  config.user_config do |user|
    user.username_attribute_names = :email
    user.authentications_class = 'Authentication'
  end
  config.load_plugin(:external)
  config.external_providers = [:google]
end

# --- config/initializers/omniauth_for_sorcery.rb ---
Rails.application.config.middleware.use OmniAuth::Builder do
  provider :google, ENV['GOOGLE_CLIENT_ID'], ENV['GOOGLE_CLIENT_SECRET']
end

# --- db/migrate/sorcery_migration.rb ---
class SorceryCore < ActiveRecord::Migration[7.0]
  def change
    enable_extension 'pgcrypto' unless extension_enabled?('pgcrypto')

    create_table :users, id: :uuid do |t|
      t.string :email,            null: false, index: { unique: true }
      t.string :crypted_password
      t.string :salt
      t.integer :role, default: 0, null: false # 0: user, 1: admin
      t.boolean :is_active, default: true, null: false
      t.timestamps                null: false
    end

    create_table :posts, id: :uuid do |t|
      t.references :user, type: :uuid, null: false, foreign_key: true
      t.string :title, null: false
      t.text :content
      t.integer :status, default: 0, null: false # 0: draft, 1: published
      t.timestamps
    end
    
    # For Sorcery's external (OAuth) module
    create_table :authentications do |t|
      t.integer :user_id, null: false
      t.string :provider, :uid, null: false
      t.timestamps              null: false
    end
    add_index :authentications, [:provider, :uid]
  end
end

# --- app/models/user.rb ---
class User < ApplicationRecord
  authenticates_with_sorcery!

  has_many :authentications, dependent: :destroy
  accepts_nested_attributes_for :authentications

  has_many :posts, dependent: :destroy

  enum role: { user: 0, admin: 1 }

  validates :password, length: { minimum: 3 }, if: -> { new_record? || changes[:crypted_password] }
  validates :password, confirmation: true, if: -> { new_record? || changes[:crypted_password] }
  validates :password_confirmation, presence: true, if: -> { new_record? || changes[:crypted_password] }
  validates :email, uniqueness: true, presence: true
end

# --- app/models/post.rb ---
class Post < ApplicationRecord
  belongs_to :user
  enum status: { draft: 0, published: 1 }
end

# --- app/models/authentication.rb ---
class Authentication < ApplicationRecord
  belongs_to :user
end

# --- app/controllers/application_controller.rb ---
class ApplicationController < ActionController::Base
  # `current_user` is provided by Sorcery
  
  private

  def require_admin
    redirect_to root_path, alert: "Access Denied" unless current_user&.admin?
  end

  # A simple authorization method
  def authorize!(record)
    is_owner = record.user_id == current_user.id
    is_admin = current_user.admin?
    
    redirect_to root_path, alert: "Not Authorized" unless is_owner || is_admin
  end
end

# --- app/controllers/user_sessions_controller.rb ---
class UserSessionsController < ApplicationController
  def new
    @user = User.new
  end

  def create
    if @user = login(params[:email], params[:password])
      redirect_back_or_to(root_path, notice: 'Login successful')
    else
      flash.now[:alert] = 'Login failed'
      render action: 'new', status: :unprocessable_entity
    end
  end

  def destroy
    logout
    redirect_to(root_path, notice: 'Logged out!')
  end
end

# --- app/controllers/oauths_controller.rb ---
class OauthsController < ApplicationController
  def oauth
    login_at(params[:provider])
  end

  def callback
    provider = params[:provider]
    if @user = login_from(provider)
      redirect_to root_path, notice: "Logged in from #{provider.titleize}!"
    else
      begin
        @user = create_from(provider)
        reset_session
        auto_login(@user)
        redirect_to root_path, notice: "Logged in from #{provider.titleize}!"
      rescue
        redirect_to root_path, alert: "Failed to login from #{provider.titleize}!"
      end
    end
  end
end

# --- app/controllers/posts_controller.rb ---
class PostsController < ApplicationController
  before_action :require_login, except: [:index]
  before_action :set_post_and_authorize, only: [:edit, :update, :destroy]

  def index
    @posts = Post.all
  end

  def destroy
    @post.destroy
    redirect_to posts_url, notice: 'Post was successfully destroyed.'
  end

  private
  
  def set_post_and_authorize
    @post = Post.find(params[:id])
    authorize!(@post)
  end
end

# --- app/controllers/api/v1/jwt_tokens_controller.rb ---
module Api
  module V1
    class JwtTokensController < ActionController::API
      def create
        user = User.authenticate(params[:email], params[:password])
        if user && user.is_active?
          payload = { user_id: user.id, role: user.role, exp: 24.hours.from_now.to_i }
          token = JWT.encode(payload, Rails.application.secret_key_base)
          render json: { token: token }, status: :ok
        else
          render json: { error: 'Invalid credentials' }, status: :unauthorized
        end
      end
    end
  end
end

# --- config/routes.rb ---
Rails.application.routes.draw do
  root to: 'posts#index'
  resources :posts

  get 'login' => 'user_sessions#new', :as => :login
  post 'login' => 'user_sessions#create'
  post 'logout' => 'user_sessions#destroy', :as => :logout

  post "oauth/:provider" => "oauths#oauth", :as => :auth_at_provider
  get "oauth/:provider/callback" => "oauths#callback"

  namespace :api do
    namespace :v1 do
      post 'token', to: 'jwt_tokens#create'
    end
  end
end