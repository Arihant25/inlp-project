# frozen_string_literal: true

# Variation 2: The "Functional/Service Object" Developer
# This developer prefers to extract all business logic into service objects (Interactors).
# - Actions are very thin, delegating work to services.
# - Services return a Result object (Success/Failure) for clear control flow.
# - Dependencies (like repos) are injected into services.
# - Promotes high cohesion, low coupling, and easier testing.

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
  gem 'dry-monads', '~> 1.6'
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
require 'dry/monads'

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
end

class Post < Dry::Struct
  attribute :id, Types::UUID
  attribute :user_id, Types::UUID
  attribute :title, Types::String
  attribute :content, Types::String
  attribute :status, Types::String.enum('DRAFT', 'PUBLISHED')
end

class UserRepository
  def find_by_email(email)
    DB[:users].values.find { |u| u.email == email }
  end
  def find(id)
    DB[:users].values.find { |u| u.id == id }
  end
end

class PostRepository
  def find(id)
    DB[:posts].values.find { |p| p.id == id }
  end
  def update(id, attributes)
    # Mock update
    puts "Updating post #{id} with #{attributes}"
    true
  end
end

DB = {
  users: {
    "1" => User.new(id: "a1fa3c18-a42e-4580-9bee-a32f693836a2", email: "admin@example.com", password_hash: BCrypt::Password.create("password"), role: "ADMIN", is_active: true),
    "2" => User.new(id: "b2fb4c29-b53f-4690-ab32-b43g704947b3", email: "user@example.com", password_hash: BCrypt::Password.create("password"), role: "USER", is_active: true)
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

# --- Service Objects / Interactors ---
module Auth
  class AuthenticateUser
    include Dry::Monads[:result, :try]

    def call(email:, password:, user_repo: UserRepository.new)
      user = user_repo.find_by_email(email)
      return Failure(:not_found) unless user
      return Failure(:inactive) unless user.is_active
      
      Try[BCrypt::Errors::InvalidHash] { BCrypt::Password.new(user.password_hash) }.to_result.bind do |hash|
        if hash == password
          Success(user)
        else
          Failure(:invalid_password)
        end
      end
    end
  end

  class GenerateJwt
    include Dry::Monads[:result]

    def call(user:, secret: ENV.fetch('JWT_SECRET', 'secret'))
      payload = { sub: user.id, role: user.role, exp: Time.now.to_i + 3600 }
      token = JWT.encode(payload, secret, 'HS256')
      Success(token)
    end
  end
  
  class ProcessOAuth
    include Dry::Monads[:result]
    
    def call(auth_hash:, user_repo: UserRepository.new)
      email = auth_hash.dig('info', 'email')
      return Failure(:email_missing) unless email
      
      user = user_repo.find_by_email(email)
      # In a real app, you might create a new user here
      user ? Success(user) : Failure(:user_not_found)
    end
  end
end

module Posts
  class PublishPost
    include Dry::Monads[:result]

    def call(user:, post_id:, post_repo: PostRepository.new)
      return Failure(:unauthorized) unless user&.role == 'ADMIN'
      
      post = post_repo.find(post_id)
      return Failure(:not_found) unless post
      
      post_repo.update(post.id, status: 'PUBLISHED')
      Success(post)
    end
  end
end

# --- Base Action with Authentication ---
class AppAction < Hanami::Action
  private

  def current_user
    @current_user ||= UserRepository.new.find(request.session[:user_id]) if request.session[:user_id]
  end

  def require_authentication
    halt 401 unless current_user
  end
end

# --- Actions ---
module Actions::Sessions
  class Create < AppAction
    def handle(request, response)
      result = Auth::AuthenticateUser.new.call(
        email: request.params[:email],
        password: request.params[:password]
      )

      case result
      in Success(user)
        request.session[:user_id] = user.id
        response.body = "Login successful for #{user.email}"
      in Failure(error)
        response.status = 401
        response.body = "Login failed: #{error}"
      end
    end
  end
  
  class OmniauthCallback < AppAction
    def handle(request, response)
      auth_hash = request.env['omniauth.auth'] || { 'info' => { 'email' => 'admin@example.com' } }
      
      result = Auth::ProcessOAuth.new.call(auth_hash: auth_hash)
      
      case result
      in Success(user)
        request.session[:user_id] = user.id
        response.body = "OAuth login successful for #{user.email}"
      in Failure(error)
        response.status = 401
        response.body = "OAuth login failed: #{error}"
      end
    end
  end
end

module Actions::Api::Tokens
  class Create < Hanami::Action
    def handle(request, response)
      auth_result = Auth::AuthenticateUser.new.call(
        email: request.params[:email],
        password: request.params[:password]
      )

      response.headers['Content-Type'] = 'application/json'

      auth_result.bind do |user|
        Auth::GenerateJwt.new.call(user: user)
      end.either(
        ->(token) {
          response.body = { access_token: token }.to_json
        },
        ->(error) {
          response.status = 401
          response.body = { error: "Authentication failed: #{error}" }.to_json
        }
      )
    end
  end
end

module Actions::Posts
  class Publish < AppAction
    before :require_authentication

    def handle(request, response)
      result = Posts::PublishPost.new.call(
        user: current_user,
        post_id: request.params[:id]
      )

      case result
      in Success(post)
        response.body = "Post '#{post.title}' published."
      in Failure(:unauthorized)
        response.status = 403
        response.body = "You are not authorized to perform this action."
      in Failure(:not_found)
        response.status = 404
        response.body = "Post not found."
      end
    end
  end
end

# --- Router ---
AppRouter = Hanami::Router.new do
  post "/login", to: Actions::Sessions::Create
  get "/auth/callback", to: Actions::Sessions::OmniauthCallback
  patch "/posts/:id/publish", to: Actions::Posts::Publish
  post "/api/tokens", to: Actions::Api::Tokens::Create
end

puts "Variation 2: 'Functional/Service Object' Developer implementation is ready."