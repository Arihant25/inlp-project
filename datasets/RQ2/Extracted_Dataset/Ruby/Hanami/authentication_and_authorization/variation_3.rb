# frozen_string_literal: true

# Variation 3: The "Pragmatic/Rails-Style" Developer
# This developer values speed and conciseness, sometimes at the expense of strict separation.
# - Logic is often placed directly within the action's `handle` method.
# - Uses more direct checks like `halt 403 unless current_user.admin?` instead of formal policies.
# - Helper methods might be defined in a shared module for reuse.
# - Naming is often shorter and more conventional (e.g., `user` instead of `authenticated_user`).

require 'bundler/inline'

gemfile do
  source 'https://rubygems.org'
  gem 'hanami', '~> 2.1'
  gem 'hanami-router', '~> 2.1'
  gem 'hanami-controller', '~> 2.1'
  gem 'bcrypt', '~> 3.1'
  gem 'jwt', '~> 2.7'
end

require 'hanami'
require 'hanami/router'
require 'hanami/controller'
require 'bcrypt'
require 'jwt'
require 'securerandom'
require 'time'
require 'ostruct'

# --- Mock Domain and Persistence (using OpenStruct for simplicity) ---
class UserRepo
  @users = [
    OpenStruct.new(id: "a1fa3c18-a42e-4580-9bee-a32f693836a2", email: "admin@example.com", password_hash: BCrypt::Password.create("password"), role: "ADMIN", is_active: true),
    OpenStruct.new(id: "b2fb4c29-b53f-4690-ab32-b43g704947b3", email: "user@example.com", password_hash: BCrypt::Password.create("password"), role: "USER", is_active: true)
  ]
  def self.by_email(email)
    @users.find { |u| u.email == email }
  end
  def self.find(id)
    @users.find { |u| u.id == id }
  end
end

class PostRepo
  @posts = [
    OpenStruct.new(id: "c3gc5d30-c64g-4701-bc43-c54h815058c4", user_id: "b2fb4c29-b53f-4690-ab32-b43g704947b3", title: "A Post", status: "DRAFT")
  ]
  def self.find(id)
    @posts.find { |p| p.id == id }
  end
end

# --- Application Setup ---
class MyApp < Hanami::App
  config.sessions = :cookie, { secret: SecureRandom.hex(64) }
end
Hanami.prepare

# --- Shared Helpers ---
module AuthHelpers
  private

  def current_user
    return @current_user if defined? @current_user
    @current_user = UserRepo.find(request.session[:user_id])
  end

  def authenticate!
    halt 401, "Not authenticated" unless current_user
  end

  def create_jwt(user)
    payload = { sub: user.id, exp: Time.now.to_i + 3600 }
    JWT.encode(payload, 'secret')
  end
end

# --- Actions ---
module App
  module Actions
    module Sessions
      class Create < Hanami::Action
        include AuthHelpers

        def handle(req, res)
          user = UserRepo.by_email(req.params[:email])
          
          if user && BCrypt::Password.new(user.password_hash) == req.params[:password]
            req.session[:user_id] = user.id
            res.body = "Welcome #{user.email}"
          else
            res.status = 401
            res.body = "Bad email or password"
          end
        end
      end

      class Destroy < Hanami::Action
        def handle(req, res)
          req.session.clear
          res.body = "Logged out"
        end
      end
      
      class OauthCallback < Hanami::Action
        def handle(req, res)
          # Simplified OAuth handling
          auth_data = req.env['omniauth.auth'] || { 'info' => { 'email' => 'user@example.com' } }
          user = UserRepo.by_email(auth_data['info']['email'])
          
          if user
            req.session[:user_id] = user.id
            res.redirect_to '/dashboard'
            res.body = "OAuth login success"
          else
            halt 401, "User not found for OAuth email"
          end
        end
      end
    end

    module Tokens
      class Create < Hanami::Action
        include AuthHelpers
        
        def handle(req, res)
          user = UserRepo.by_email(req.params[:email])
          
          if user && BCrypt::Password.new(user.password_hash) == req.params[:password]
            res.headers['Content-Type'] = 'application/json'
            res.body = { token: create_jwt(user) }.to_json
          else
            halt 401, { error: 'Invalid credentials' }.to_json
          end
        end
      end
    end

    module Posts
      class Create < Hanami::Action
        include AuthHelpers
        before :authenticate!

        def handle(req, res)
          # Assume params are valid
          res.body = "Post created by user #{current_user.id}"
        end
      end

      class Publish < Hanami::Action
        include AuthHelpers
        before :authenticate!

        def handle(req, res)
          # RBAC check directly in the action
          halt 403, "Forbidden" unless current_user.role == "ADMIN"
          
          post = PostRepo.find(req.params[:id])
          halt 404, "Not Found" unless post

          # ... update post status ...
          post.status = "PUBLISHED"
          res.body = "Post #{post.id} published by #{current_user.email}"
        end
      end
    end
  end
end

# --- Router ---
AppRouter = Hanami::Router.new do
  post "/login", to: App::Actions::Sessions::Create
  delete "/logout", to: App::Actions::Sessions::Destroy
  get "/auth/callback", to: App::Actions::Sessions::OauthCallback
  
  post "/api/token", to: App::Actions::Tokens::Create

  post "/posts", to: App::Actions::Posts::Create
  patch "/posts/:id/publish", to: App::Actions::Posts::Publish
end

puts "Variation 3: 'Pragmatic/Rails-Style' Developer implementation is ready."