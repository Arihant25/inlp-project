# frozen_string_literal: true

# Variation 1: The "By-the-Book" Developer
# This developer follows the official Hanami guides closely.
# - Uses `before` callbacks for authentication.
# - Implements authorization using Hanami::Action::Policy.
# - Clear separation of concerns between actions, policies, and helpers.
# - Verbose and explicit naming conventions.

require 'bundler/inline'

gemfile do
  source 'https://rubygems.org'
  gem 'hanami', '~> 2.1'
  gem 'hanami-router', '~> 2.1'
  gem 'hanami-controller', '~> 2.1'
  gem 'bcrypt', '~> 3.1'
  gem 'jwt', '~> 2.7'
  gem 'dry-types', '~> 1.7'
  gem 'dry-struct', '~> 1.6'
end

require 'hanami'
require 'hanami/router'
require 'hanami/controller'
require 'bcrypt'
require 'jwt'
require 'securerandom'
require 'time'
require 'dry-struct'
require 'dry-types'

# --- Mock Domain and Persistence ---
module Types
  include Dry.Types()
end

class User < Dry::Struct
  attribute :id, Types::UUID
  attribute :email, Types::String
  attribute :password_hash, Types::String
  attribute :role, Types::String.enum('ADMIN', 'USER')
  attribute :is_active, Types::Bool
  attribute :created_at, Types::Time

  def admin?
    role == 'ADMIN'
  end
end

class Post < Dry::Struct
  attribute :id, Types::UUID
  attribute :user_id, Types::UUID
  attribute :title, Types::String
  attribute :content, Types::String
  attribute :status, Types::String.enum('DRAFT', 'PUBLISHED')
end

# Mock Repository
class UserRepository
  def self.find(id)
    DB[:users].values.find { |u| u.id == id }
  end

  def self.find_by_email(email)
    DB[:users].values.find { |u| u.email == email }
  end
end

# Mock Database
DB = {
  users: {
    "1" => User.new(id: "a1fa3c18-a42e-4580-9bee-a32f693836a2", email: "admin@example.com", password_hash: BCrypt::Password.create("password"), role: "ADMIN", is_active: true, created_at: Time.now),
    "2" => User.new(id: "b2fb4c29-b53f-4690-ab32-b43g704947b3", email: "user@example.com", password_hash: BCrypt::Password.create("password"), role: "USER", is_active: true, created_at: Time.now)
  },
  posts: {
    "1" => Post.new(id: "c3gc5d30-c64g-4701-bc43-c54h815058c4", user_id: "b2fb4c29-b53f-4690-ab32-b43g704947b3", title: "User Post", content: "...", status: "DRAFT")
  }
}

# --- Application Setup ---
class MyApp < Hanami::App
  config.sessions = :cookie, { secret: SecureRandom.hex(64) }
end

Hanami.prepare

# --- Security & Authentication Helpers ---
module Web
  class Action < Hanami::Action
    # Expose current_user to views and actions
    expose :current_user

    private

    # Fetches the user from the session
    def current_user
      @current_user ||= UserRepository.find(request.session[:user_id]) if request.session[:user_id]
    end

    # A `before` callback to ensure a user is logged in
    def require_authentication
      halt 401 unless current_user
    end
  end
end

module Api
  class Action < Hanami::Action
    private

    def current_user
      return @current_user if defined?(@current_user)
      
      token = request.env['HTTP_AUTHORIZATION']&.split(' ')&.last
      return @current_user = nil unless token

      payload = JWT.decode(token, ENV.fetch('JWT_SECRET', 'secret'), true, { algorithm: 'HS256' }).first
      @current_user = UserRepository.find(payload['sub'])
    rescue JWT::DecodeError
      @current_user = nil
    end

    def require_authentication
      halt 401 unless current_user
    end
  end
end

# --- Policies for Authorization (RBAC) ---
class PostPolicy
  attr_reader :user, :post

  def initialize(user, post)
    @user = user
    @post = post
  end

  def publish?
    user&.admin?
  end
end

# --- Actions ---
module Web::Actions::Sessions
  class Create < Web::Action
    params do
      required(:email).filled(:string)
      required(:password).filled(:string)
    end

    def handle(request, response)
      user = UserRepository.find_by_email(request.params[:email])

      if user && user.is_active && BCrypt::Password.new(user.password_hash) == request.params[:password]
        request.session[:user_id] = user.id
        response.body = "Logged in successfully."
      else
        response.status = 401
        response.body = "Invalid credentials."
      end
    end
  end

  class Destroy < Web::Action
    def handle(request, response)
      request.session.clear
      response.body = "Logged out."
    end
  end
  
  # Mock OAuth2 Callback
  class OmniauthCallback < Web::Action
    def handle(request, response)
      auth_hash = request.env['omniauth.auth'] # In a real app, this comes from the omniauth middleware
      # Mocked for this example
      auth_hash ||= { 'info' => { 'email' => 'oauth_user@example.com' }, 'uid' => '12345' }
      
      user = UserRepository.find_by_email(auth_hash['info']['email'])
      # Logic to find or create user from auth_hash would go here
      if user
        request.session[:user_id] = user.id
        response.body = "Logged in via OAuth."
      else
        response.status = 401
        response.body = "Could not authenticate via OAuth."
      end
    end
  end
end

module Api::Actions::Tokens
  class Create < Api::Action
    params do
      required(:email).filled(:string)
      required(:password).filled(:string)
    end

    def handle(request, response)
      user = UserRepository.find_by_email(request.params[:email])

      if user && user.is_active && BCrypt::Password.new(user.password_hash) == request.params[:password]
        payload = { sub: user.id, role: user.role, exp: Time.now.to_i + 3600 }
        token = JWT.encode(payload, ENV.fetch('JWT_SECRET', 'secret'), 'HS256')
        response.body = { access_token: token }.to_json
        response.headers['Content-Type'] = 'application/json'
      else
        response.status = 401
        response.body = { error: 'Invalid credentials' }.to_json
        response.headers['Content-Type'] = 'application/json'
      end
    end
  end
end

module Web::Actions::Posts
  class Create < Web::Action
    before :require_authentication

    params do
      required(:post).hash do
        required(:title).filled(:string)
        required(:content).filled(:string)
      end
    end

    def handle(request, response)
      # In a real app, you would create a post associated with the current_user
      response.body = "Post created by #{current_user.email}."
    end
  end

  class Publish < Web::Action
    before :require_authentication
    # Use Hanami's built-in policy integration
    policy :post

    def handle(request, response)
      # In a real app, find the post by ID from params
      mock_post = DB[:posts]["1"]
      
      # The policy method is automatically called. If it returns false, a 403 is raised.
      # `authorize!` is a conventional name for the policy check method.
      authorize!(:publish?, mock_post)

      # Logic to update post status to 'PUBLISHED'
      response.body = "Post published by admin #{current_user.email}."
    end

    private
    
    # This method is called by the policy integration to check authorization.
    def authorize!(rule, post)
      halt 403 unless post_policy.new(current_user, post).public_send(rule)
    end
  end
end

# --- Router ---
AppRouter = Hanami::Router.new do
  scope 'web' do
    post '/login', to: Web::Actions::Sessions::Create
    delete '/logout', to: Web::Actions::Sessions::Destroy
    get '/auth/github/callback', to: Web::Actions::Sessions::OmniauthCallback
    
    post '/posts', to: Web::Actions::Posts::Create
    patch '/posts/:id/publish', to: Web::Actions::Posts::Publish
  end

  scope 'api' do
    post '/tokens', to: Api::Actions::Tokens::Create
  end
end

# Example of how to run (for demonstration)
# require 'rack'
# Rack::Server.start(app: AppRouter, Port: 2300)
puts "Variation 1: 'By-the-Book' Developer implementation is ready."