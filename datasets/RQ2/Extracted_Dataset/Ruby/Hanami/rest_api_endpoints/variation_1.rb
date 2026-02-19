<code_snippet>
# frozen_string_literal: true

# This is a self-contained, runnable Hanami REST API implementation.
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
# gem 'dry-types', '~> 1.7'
# gem 'bcrypt'
# gem 'json'

require 'hanami'
require 'hanami/api'
require 'hanami/router'
require 'hanami/controller'
require 'hanami/action'
require 'hanami/validation'
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
module Main
  class App < Hanami::App
  end

  # --- Entity Definition ---
  class Entities
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
  end

  # --- In-Memory Repository for Demonstration ---
  class Repositories
    class UserRepo
      def initialize(db = nil)
        @db = db || {
          "c8307439-59f0-4a34-a38a-920b5874258c" => { id: "c8307439-59f0-4a34-a38a-920b5874258c", email: "admin@example.com", password_hash: BCrypt::Password.create("password"), role: "ADMIN", is_active: true, created_at: Time.now.utc - 3600 },
          "f1f27891-2106-4742-b98a-4a8cb7c2f3a4" => { id: "f1f27891-2106-4742-b98a-4a8cb7c2f3a4", email: "user@example.com", password_hash: BCrypt::Password.create("password"), role: "USER", is_active: true, created_at: Time.now.utc - 1800 },
          "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11" => { id: "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11", email: "inactive@example.com", password_hash: BCrypt::Password.create("password"), role: "USER", is_active: false, created_at: Time.now.utc },
        }
      end

      def all
        @db.values.map { |attrs| Entities::User.new(attrs) }
      end

      def find(id)
        attrs = @db[id]
        attrs ? Entities::User.new(attrs) : nil
      end

      def find_by_email(email)
        attrs = @db.values.find { |u| u[:email] == email }
        attrs ? Entities::User.new(attrs) : nil
      end

      def create(data)
        id = SecureRandom.uuid
        new_user_attrs = data.merge(
          id: id,
          password_hash: BCrypt::Password.create(data[:password]),
          created_at: Time.now.utc
        )
        new_user_attrs.delete(:password)
        @db[id] = new_user_attrs
        Entities::User.new(new_user_attrs)
      end

      def update(id, data)
        return nil unless @db.key?(id)
        @db[id].merge!(data)
        Entities::User.new(@db[id])
      end

      def delete(id)
        @db.delete(id) ? true : false
      end

      def query(params)
        scope = @db.values
        scope = scope.select { |u| u[:role] == params[:role] } if params[:role]
        scope = scope.select { |u| u[:is_active] == (params[:is_active] == 'true') } if params.key?(:is_active)

        page = params.fetch(:page, 1).to_i
        per_page = params.fetch(:per_page, 10).to_i
        offset = (page - 1) * per_page

        paginated_scope = scope.slice(offset, per_page) || []
        paginated_scope.map { |attrs| Entities::User.new(attrs) }
      end
    end
  end

  # --- Mock Container ---
  # In a real app, this would be managed by Hanami's container.
  class Container
    def self.resolve(key)
      {
        "repositories.user_repo" => Repositories::UserRepo.new
      }[key]
    end
  end

  # --- Validations ---
  module Validations
    class Contract < Hanami::Validation::Contract
      config.messages.backend = :i18n
    end

    module Users
      class Create < Validations::Contract
        params do
          required(:email).filled(:string, format?: URI::MailTo::EMAIL_REGEXP)
          required(:password).filled(:string, min_size?: 8)
          required(:role).filled(Types::Role)
          required(:is_active).filled(:bool)
        end

        rule(:email) do
          key.failure("is already taken") if Container.resolve("repositories.user_repo").find_by_email(value)
        end
      end

      class Update < Validations::Contract
        params do
          optional(:email).filled(:string, format?: URI::MailTo::EMAIL_REGEXP)
          optional(:role).filled(Types::Role)
          optional(:is_active).filled(:bool)
        end
      end

      class Index < Validations::Contract
        params do
          optional(:page).filled(:integer, gt?: 0)
          optional(:per_page).filled(:integer, gt?: 0, lteq?: 100)
          optional(:role).filled(Types::Role)
          optional(:is_active).filled(:bool)
        end
      end
    end
  end

  # --- Base Action ---
  class Action < Hanami::Action
    def to_json(data)
      # Exclude sensitive fields for JSON response
      if data.is_a?(Array)
        data.map { |d| d.to_h.except(:password_hash) }.to_json
      elsif data
        data.to_h.except(:password_hash).to_json
      else
        "{}"
      end
    end
  end

  # --- Actions (Controllers) ---
  module Actions
    module Users
      class Index < Main::Action
        def handle(request, response)
          validation = Validations::Users::Index.new.call(request.params.to_h)
          if validation.success?
            repo = Container.resolve("repositories.user_repo")
            users = repo.query(validation.to_h)
            response.status = 200
            response.body = to_json(users)
          else
            response.status = 422
            response.body = validation.errors.to_h.to_json
          end
        end
      end

      class Create < Main::Action
        def handle(request, response)
          validation = Validations::Users::Create.new.call(request.params.to_h)
          if validation.success?
            repo = Container.resolve("repositories.user_repo")
            user = repo.create(validation.to_h)
            response.status = 201
            response.body = to_json(user)
          else
            response.status = 422
            response.body = validation.errors.to_h.to_json
          end
        end
      end

      class Show < Main::Action
        def handle(request, response)
          repo = Container.resolve("repositories.user_repo")
          user = repo.find(request.params[:id])
          if user
            response.status = 200
            response.body = to_json(user)
          else
            response.status = 404
            response.body = { error: "User not found" }.to_json
          end
        end
      end

      class Update < Main::Action
        def handle(request, response)
          repo = Container.resolve("repositories.user_repo")
          user = repo.find(request.params[:id])
          unless user
            response.status = 404
            response.body = { error: "User not found" }.to_json
            return
          end

          validation = Validations::Users::Update.new.call(request.params.to_h)
          if validation.success?
            updated_user = repo.update(user.id, validation.to_h)
            response.status = 200
            response.body = to_json(updated_user)
          else
            response.status = 422
            response.body = validation.errors.to_h.to_json
          end
        end
      end

      class Destroy < Main::Action
        def handle(request, response)
          repo = Container.resolve("repositories.user_repo")
          if repo.delete(request.params[:id])
            response.status = 204
          else
            response.status = 404
            response.body = { error: "User not found" }.to_json
          end
        end
      end
    end
  end

  # --- Router Definition ---
  class Router < Hanami::Router
    mount :users, to: ->(env) do
      # This simulates a sub-router for a slice
      router = Hanami::Router.new do
        get    "/", to: Actions::Users::Index.new
        post   "/", to: Actions::Users::Create.new
        get    "/:id", to: Actions::Users::Show.new
        patch  "/:id", to: Actions::Users::Update.new
        put    "/:id", to: Actions::Users::Update.new
        delete "/:id", to: Actions::Users::Destroy.new
      end
      router.call(env)
    end
  end
end

# --- Example Usage (for demonstration) ---
if __FILE__ == $0
  app = Main::Router.new
  
  puts "--- List all users ---"
  env = Rack::MockRequest.env_for("/users")
  status, headers, body = app.call(env)
  puts "Status: #{status}"
  puts "Body: #{JSON.pretty_generate(JSON.parse(body.join))}"
  
  puts "\n--- Get a specific user ---"
  user_id = "c8307439-59f0-4a34-a38a-920b5874258c"
  env = Rack::MockRequest.env_for("/users/#{user_id}")
  status, headers, body = app.call(env)
  puts "Status: #{status}"
  puts "Body: #{JSON.pretty_generate(JSON.parse(body.join))}"

  puts "\n--- Create a new user (Success) ---"
  params = { email: "new.dev@example.com", password: "a-strong-password", role: "USER", is_active: true }
  env = Rack::MockRequest.env_for("/users", method: "POST", params: params)
  status, headers, body = app.call(env)
  puts "Status: #{status}"
  puts "Body: #{JSON.pretty_generate(JSON.parse(body.join))}"

  puts "\n--- Create a new user (Failure) ---"
  params = { email: "admin@example.com", password: "weak", role: "INVALID_ROLE" }
  env = Rack::MockRequest.env_for("/users", method: "POST", params: params)
  status, headers, body = app.call(env)
  puts "Status: #{status}"
  puts "Body: #{JSON.pretty_generate(JSON.parse(body.join))}"
end
</code_snippet>