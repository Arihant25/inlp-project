<code_snippet>
# frozen_string_literal: true

# This is a self-contained, runnable Hanami REST API implementation.
# Variation 2: Service-Oriented Architecture
#
# To run:
# 1. Save this file as `app.rb`.
# 2. Run `bundle install` after creating a Gemfile with the specified gems.
# 3. Run `ruby app.rb` to see example output.
#
# Gemfile:
# source 'https://rubygems.org'
# gem 'hanami', '~> 2.1'
# gem 'hanami-router', '~> 2.1'
# gem 'hanami-controller', '~> 2.1'
# gem 'hanami-validation', '~> 2.1'
# gem 'dry-monads', '~> 1.6'
# gem 'dry-types', '~> 1.7'
# gem 'bcrypt'
# gem 'json'

require 'hanami'
require 'hanami/api'
require 'hanami/router'
require 'hanami/controller'
require 'hanami/action'
require 'hanami/validation'
require 'dry-monads'
require 'dry-types'
require 'securerandom'
require 'json'
require 'time'
require 'bcrypt'

# --- Domain Schema and Types ---
module Types
  include Dry.Types()
  UUID = String.constrained(format: /\A[0-9a-f]{8}-([0-9a-f]{4}-){3}[0-9a-f]{12}\z/i)
  Role = String.enum("ADMIN", "USER")
end

# --- Mock Application Setup ---
module MyApp
  class App < Hanami::App; end

  # --- Entity Definition ---
  class User < Hanami::Entity
    attributes do
      attribute :id,          Types::UUID
      attribute :email,       Types::String
      attribute :password_hash, Types::String
      attribute :role,        Types::Role
      attribute :is_active,   Types::Bool
      attribute :created_at,  Types::Time
    end
  end

  # --- In-Memory Repository for Demonstration ---
  class UserRepository
    def initialize(db = nil)
      @db = db || {
        "c8307439-59f0-4a34-a38a-920b5874258c" => { id: "c8307439-59f0-4a34-a38a-920b5874258c", email: "admin@example.com", password_hash: BCrypt::Password.create("password"), role: "ADMIN", is_active: true, created_at: Time.now.utc - 3600 },
        "f1f27891-2106-4742-b98a-4a8cb7c2f3a4" => { id: "f1f27891-2106-4742-b98a-4a8cb7c2f3a4", email: "user@example.com", password_hash: BCrypt::Password.create("password"), role: "USER", is_active: true, created_at: Time.now.utc - 1800 },
      }
    end

    def find(id); attrs = @db[id]; attrs ? User.new(attrs) : nil; end
    def find_by_email(email); attrs = @db.values.find { |u| u[:email] == email }; attrs ? User.new(attrs) : nil; end
    def create(data); id = SecureRandom.uuid; new_user_attrs = data.merge(id: id, created_at: Time.now.utc); @db[id] = new_user_attrs; User.new(new_user_attrs); end
    def update(id, data); return nil unless @db.key?(id); @db[id].merge!(data); User.new(@db[id]); end
    def delete(id); @db.delete(id); end
    def query(page:, per_page:, filters: {}); scope = @db.values; filters.each { |k, v| scope = scope.select { |u| u[k].to_s == v.to_s } }; offset = (page - 1) * per_page; paginated = scope.slice(offset, per_page) || []; paginated.map { |attrs| User.new(attrs) }; end
  end

  # --- Mock Container ---
  class Container
    @registry = {}
    def self.register(key, component)
      @registry[key] = component
    end
    def self.resolve(key)
      @registry[key].is_a?(Proc) ? @registry[key].call : @registry[key]
    end
  end
  Container.register("repositories.user_repo", -> { UserRepository.new })

  # --- Service Objects / Interactors ---
  module Interactors
    module Users
      class Create
        include Dry::Monads[:result, :do]

        def initialize(user_repo: Container.resolve("repositories.user_repo"))
          @user_repo = user_repo
        end

        def call(params)
          yield check_email_uniqueness(params[:email])
          hashed_password = yield hash_password(params[:password])
          
          user_data = params.except(:password).merge(password_hash: hashed_password)
          user = @user_repo.create(user_data)

          Success(user)
        end

        private
        def check_email_uniqueness(email)
          @user_repo.find_by_email(email) ? Failure(:email_taken) : Success()
        end

        def hash_password(password)
          Success(BCrypt::Password.create(password))
        end
      end

      class List
        include Dry::Monads[:result]
        def initialize(user_repo: Container.resolve("repositories.user_repo"))
          @user_repo = user_repo
        end

        def call(page: 1, per_page: 10, filters: {})
          users = @user_repo.query(page: page, per_page: per_page, filters: filters)
          Success(users)
        end
      end
    end
  end
  Container.register("interactors.users.create", -> { Interactors::Users::Create.new })
  Container.register("interactors.users.list", -> { Interactors::Users::List.new })

  # --- Validations ---
  module Validations
    class UserCreateContract < Hanami::Validation::Contract
      params do
        required(:email).filled(:string)
        required(:password).filled(:string, min_size?: 8)
        required(:role).filled(Types::Role)
        required(:is_active).filled(:bool)
      end
    end
    class UserUpdateContract < Hanami::Validation::Contract
      params do
        optional(:role).filled(Types::Role)
        optional(:is_active).filled(:bool)
      end
    end
  end

  # --- Actions (Thin Controllers) ---
  module Actions
    class Base < Hanami::Action
      private
      def render_json(data, status: 200)
        response.status = status
        response.content_type = "application/json"
        response.body = data.to_h.except(:password_hash).to_json
      end

      def render_json_array(data, status: 200)
        response.status = status
        response.content_type = "application/json"
        response.body = data.map { |d| d.to_h.except(:password_hash) }.to_json
      end

      def render_errors(errors, status: 422)
        response.status = status
        response.content_type = "application/json"
        response.body = { errors: errors }.to_json
      end

      def render_not_found
        render_errors({ base: ["not found"] }, status: 404)
      end
    end

    module Users
      class Index < Base
        def handle(request, response)
          list_users = Container.resolve("interactors.users.list")
          page = request.params[:page] || 1
          per_page = request.params[:per_page] || 10
          filters = request.params.to_h.slice(:role, :is_active)

          result = list_users.call(page: page.to_i, per_page: per_page.to_i, filters: filters)
          render_json_array(result.value!)
        end
      end

      class Create < Base
        def handle(request, response)
          validation = Validations::UserCreateContract.new.call(request.params.to_h)
          return render_errors(validation.errors.to_h) unless validation.success?

          create_user = Container.resolve("interactors.users.create")
          result = create_user.call(validation.to_h)

          case result
          in Success(user)
            render_json(user, status: 201)
          in Failure(:email_taken)
            render_errors({ email: ["is already taken"] })
          in Failure(error)
            render_errors({ base: [error.to_s] }, status: 500)
          end
        end
      end

      class Show < Base
        def handle(request, response)
          user_repo = Container.resolve("repositories.user_repo")
          user = user_repo.find(request.params[:id])
          user ? render_json(user) : render_not_found
        end
      end

      class Update < Base
        def handle(request, response)
          user_repo = Container.resolve("repositories.user_repo")
          user = user_repo.find(request.params[:id])
          return render_not_found unless user

          validation = Validations::UserUpdateContract.new.call(request.params.to_h)
          return render_errors(validation.errors.to_h) unless validation.success?

          updated_user = user_repo.update(user.id, validation.to_h)
          render_json(updated_user)
        end
      end

      class Destroy < Base
        def handle(request, response)
          user_repo = Container.resolve("repositories.user_repo")
          user_repo.delete(request.params[:id])
          response.status = 204
        end
      end
    end
  end

  # --- Router Definition ---
  class Router < Hanami::Router
    scope "users" do
      get    "/", to: Actions::Users::Index.new
      post   "/", to: Actions::Users::Create.new
      get    "/:id", to: Actions::Users::Show.new
      patch  "/:id", to: Actions::Users::Update.new
      delete "/:id", to: Actions::Users::Destroy.new
    end
  end
end

# --- Example Usage (for demonstration) ---
if __FILE__ == $0
  app = MyApp::Router.new
  
  puts "--- List all users ---"
  env = Rack::MockRequest.env_for("/users")
  status, headers, body = app.call(env)
  puts "Status: #{status}"
  puts "Body: #{JSON.pretty_generate(JSON.parse(body.join))}"
  
  puts "\n--- Create a new user (Failure: email taken) ---"
  params = { email: "admin@example.com", password: "a-strong-password", role: "USER", is_active: true }
  env = Rack::MockRequest.env_for("/users", method: "POST", params: params)
  status, headers, body = app.call(env)
  puts "Status: #{status}"
  puts "Body: #{JSON.pretty_generate(JSON.parse(body.join))}"

  puts "\n--- Create a new user (Success) ---"
  params = { email: "new.dev@example.com", password: "a-strong-password", role: "USER", is_active: true }
  env = Rack::MockRequest.env_for("/users", method: "POST", params: params)
  status, headers, body = app.call(env)
  puts "Status: #{status}"
  puts "Body: #{JSON.pretty_generate(JSON.parse(body.join))}"
end
</code_snippet>