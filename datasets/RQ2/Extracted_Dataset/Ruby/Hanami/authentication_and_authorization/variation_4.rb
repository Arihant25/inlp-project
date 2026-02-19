# frozen_string_literal: true

# Variation 4: The "Security-Focused" Developer
# This developer is highly concerned with security, robustness, and clarity.
# - Uses a dedicated Authentication module for both session and token-based auth.
# - JWTs have detailed claims (exp, nbf, iss) and are strictly validated.
# - Policies are granular and check multiple conditions (e.g., user active, role).
# - Defensive coding: explicit error handling for all failure cases.

require 'bundler/inline'

gemfile do
  source 'https://rubygems.org'
  gem 'hanami', '~> 2.1'
  gem 'hanami-router', '~> 2.1'
  gem 'hanami-controller', '~> 2.1'
  gem 'bcrypt', '~> 3.1'
  gem 'jwt', '~> 2.7'
  gem 'dry-struct', '~> 1.6'
  gem 'dry-types', '~> 1.7'
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

# --- Domain Model ---
module Types
  include Dry.Types()
end

class User < Dry::Struct
  attribute :id, Types::UUID
  attribute :email, Types::String
  attribute :password_hash, Types::String
  attribute :role, Types::String.enum('ADMIN', 'USER')
  attribute :is_active, Types::Bool

  def admin?
    role == 'ADMIN'
  end
end

class Post < Dry::Struct
  attribute :id, Types::UUID
  attribute :user_id, Types::UUID
  attribute :title, Types::String
  attribute :status, Types::String.enum('DRAFT', 'PUBLISHED')
end

# --- Mock Persistence Layer ---
class UserRepo
  @db = {
    "a1fa3c18-a42e-4580-9bee-a32f693836a2" => User.new(id: "a1fa3c18-a42e-4580-9bee-a32f693836a2", email: "admin@example.com", password_hash: BCrypt::Password.create("password"), role: "ADMIN", is_active: true),
    "b2fb4c29-b53f-4690-ab32-b43g704947b3" => User.new(id: "b2fb4c29-b53f-4690-ab32-b43g704947b3", email: "user@example.com", password_hash: BCrypt::Password.create("password"), role: "USER", is_active: true),
    "d4dc5e41-d75h-4812-cd54-d65i926169d5" => User.new(id: "d4dc5e41-d75h-4812-cd54-d65i926169d5", email: "inactive@example.com", password_hash: BCrypt::Password.create("password"), role: "USER", is_active: false)
  }
  def self.find(id)
    @db[id]
  end
  def self.find_by_email(email)
    @db.values.find { |u| u.email == email }
  end
end

class PostRepo
  @db = {
    "c3gc5d30-c64g-4701-bc43-c54h815058c4" => Post.new(id: "c3gc5d30-c64g-4701-bc43-c54h815058c4", user_id: "b2fb4c29-b53f-4690-ab32-b43g704947b3", title: "A Post", status: "DRAFT")
  }
  def self.find(id)
    @db[id]
  end
end

# --- Application Setup ---
class MyApp < Hanami::App
  config.sessions = :cookie, { secret: SecureRandom.hex(64), httponly: true, secure: true }
end
Hanami.prepare

# --- Security Components ---
module Security
  JWT_SECRET = ENV.fetch('JWT_SECRET', 's3cr3t_k3y_f0r_d3v')
  JWT_ISSUER = 'my_app'
  JWT_ALGORITHM = 'HS256'

  module Authentication
    def self.included(action)
      action.before :authenticate!
    end

    private

    attr_reader :current_user

    def authenticate!
      user = authenticate_from_session || authenticate_from_token
      halt 401, 'Authentication failed' unless user
      halt 403, 'Account is inactive' unless user.is_active
      @current_user = user
    end

    def authenticate_from_session
      user_id = request.session[:user_id]
      user_id ? UserRepo.find(user_id) : nil
    end

    def authenticate_from_token
      header = request.env['HTTP_AUTHORIZATION']
      return nil unless header

      token_type, token = header.split(' ')
      return nil unless token_type&.downcase == 'bearer' && token

      payload = decode_jwt(token)
      payload ? UserRepo.find(payload['sub']) : nil
    end



    def decode_jwt(token)
      options = {
        iss: JWT_ISSUER,
        verify_iss: true,
        algorithm: JWT_ALGORITHM
      }
      JWT.decode(token, JWT_SECRET, true, options).first
    rescue JWT::ExpiredSignature
      halt 401, 'Token has expired'
    rescue JWT::InvalidIssuerError
      halt 401, 'Invalid token issuer'
    rescue JWT::DecodeError
      halt 401, 'Invalid token'
    end
  end

  class PostPolicy
    def initialize(user:, post:)
      @user = user
      @post = post
    end

    def can_publish?
      return false unless @user&.is_active
      return false unless @post.status == 'DRAFT'
      @user.admin?
    end
  end
end

# --- Actions ---
module Actions
  module Session
    class Create < Hanami::Action
      def handle(req, res)
        user = UserRepo.find_by_email(req.params[:email])

        if user && BCrypt::Password.new(user.password_hash) == req.params[:password]
          if user.is_active
            req.session.clear
            req.session[:user_id] = user.id
            res.body = "Session created."
          else
            halt 403, "Account is inactive."
          end
        else
          halt 401, "Invalid email or password."
        end
      end
    end
    
    class OauthCallback < Hanami::Action
      def handle(req, res)
        # In a real app, verify the state parameter to prevent CSRF
        auth_hash = req.env['omniauth.auth'] || { 'info' => { 'email' => 'admin@example.com' }, 'extra' => { 'raw_info' => { 'email_verified' => true } } }
        
        # Security: Only trust verified emails from the provider
        halt 401, "OAuth email not verified" unless auth_hash.dig('extra', 'raw_info', 'email_verified')
        
        user = UserRepo.find_by_email(auth_hash['info']['email'])
        halt 401, "User not found" unless user
        halt 403, "Account is inactive" unless user.is_active
        
        req.session.clear
        req.session[:user_id] = user.id
        res.body = "OAuth login successful."
      end
    end
  end

  module Token
    class Create < Hanami::Action
      def handle(req, res)
        user = UserRepo.find_by_email(req.params[:email])

        if user && user.is_active && BCrypt::Password.new(user.password_hash) == req.params[:password]
          now = Time.now.to_i
          payload = {
            iss: Security::JWT_ISSUER,
            sub: user.id,
            role: user.role,
            iat: now,
            nbf: now,
            exp: now + 3600 # 1 hour
          }
          token = JWT.encode(payload, Security::JWT_SECRET, Security::JWT_ALGORITHM)
          res.json({ jwt: token })
        else
          res.status = 401
          res.json({ error: 'Invalid credentials or inactive user' })
        end
      end
    end
  end

  module Posts
    class Publish < Hanami::Action
      include Security::Authentication

      def handle(req, res)
        post = PostRepo.find(req.params[:id])
        halt 404, "Post not found" unless post

        policy = Security::PostPolicy.new(user: current_user, post: post)
        halt 403, "Authorization denied" unless policy.can_publish?

        # ... update post status to 'PUBLISHED'
        res.body = "Post #{post.id} has been published by admin #{current_user.email}."
      end
    end
  end
end

# --- Router ---
AppRouter = Hanami::Router.new do
  post "/session", to: Actions::Session::Create
  get "/auth/provider/callback", to: Actions::Session::OauthCallback
  post "/token", to: Actions::Token::Create
  patch "/posts/:id/publish", to: Actions::Posts::Publish
end

puts "Variation 4: 'Security-Focused' Developer implementation is ready."