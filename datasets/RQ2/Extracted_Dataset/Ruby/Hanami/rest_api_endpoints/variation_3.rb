<code_snippet>
# frozen_string_literal: true

# This is a self-contained, runnable Hanami REST API implementation.
# Variation 3: Concise & Functional Style
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

# --- Application Namespace ---
module FunctionalApi
  # --- Types & Entities ---
  module Types
    include Dry.Types()
    UUID = String.constrained(format: /\A[0-9a-f]{8}-([0-9a-f]{4}-){3}[0-9a-f]{12}\z/i)
    Role = String.enum("ADMIN", "USER")
  end

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

  # --- In-Memory Repository ---
  class UserRepo
    include Dry::Monads[:maybe]

    def initialize(db = nil)
      @db = db || {
        "c8307439-59f0-4a34-a38a-920b5874258c" => { id: "c8307439-59f0-4a34-a38a-920b5874258c", email: "admin@example.com", password_hash: BCrypt::Password.create("password"), role: "ADMIN", is_active: true, created_at: Time.now.utc - 3600 },
      }
    end

    def find(id) = Maybe(@db[id]).fmap { |attrs| User.new(attrs) }
    def find_by_email(email) = Maybe(@db.values.find { |u| u[:email] == email }).fmap { |attrs| User.new(attrs) }
    def create(data) = User.new(data.merge(id: SecureRandom.uuid, created_at: Time.now.utc)).tap { |u| @db[u.id] = u.to_h }
    def update(id, data) = find(id).fmap { |u| User.new(u.to_h.merge(data)).tap { |updated| @db[id] = updated.to_h } }
    def delete(id) = Maybe(@db.delete(id)).fmap { true }
    def search(filters)
      @db.values
         .then { |users| filters[:role] ? users.select { |u| u[:role] == filters[:role] } : users }
         .then { |users| filters.key?(:is_active) ? users.select { |u| u[:is_active] == filters[:is_active] } : users }
         .map { |attrs| User.new(attrs) }
    end
  end

  # --- Mock Container ---
  Deps = {
    "repo.user" => UserRepo.new
  }.freeze

  # --- Validations ---
  module Contracts
    class UserContract < Hanami::Validation::Contract
      json do
        required(:email).filled(:string, format?: URI::MailTo::EMAIL_REGEXP)
        required(:password).filled(:string, min_size?: 8)
        required(:role).filled(Types::Role)
        required(:is_active).filled(:bool)
      end
      rule(:email) do
        key.failure("is already taken") if Deps["repo.user"].find_by_email(value).some?
      end
    end
    class UserUpdateContract < Hanami::Validation::Contract
      json do
        optional(:role).filled(Types::Role)
        optional(:is_active).filled(:bool)
      end
    end
  end

  # --- Base Action with Functional Helpers ---
  class Action < Hanami::Action
    include Dry::Monads[:result, :do]

    private

    def validate(contract, params)
      result = contract.call(params)
      result.success? ? Success(result.to_h) : Failure([422, { errors: result.errors.to_h }.to_json])
    end

    def find_user(id)
      Deps["repo.user"].find(id).to_result.or { Failure([404, { error: "not found" }.to_json]) }
    end

    def serialize(data)
      case data
      when User then data.to_h.except(:password_hash).to_json
      when Array then data.map { |u| u.to_h.except(:password_hash) }.to_json
      else data.to_json
      end
    end

    def handle_result(result, response, success_status: 200)
      case result
      in Success(payload)
        response.status = success_status
        response.body = serialize(payload)
      in Failure((status, body))
        response.status = status
        response.content_type = "application/json"
        response.body = body
      end
    end
  end

  # --- Actions ---
  module Actions::Users
    class Index < Action
      def handle(request, response)
        filters = request.params.to_h.slice(:role, :is_active)
        filters[:is_active] = filters[:is_active] == 'true' if filters.key?(:is_active)
        users = Deps["repo.user"].search(filters)
        response.body = serialize(users)
      end
    end

    class Create < Action
      def handle(request, response)
        result = Do.call do
          params = yield validate(Contracts::UserContract.new, request.params.to_h)
          hashed_password = BCrypt::Password.create(params[:password])
          user = Deps["repo.user"].create(params.except(:password).merge(password_hash: hashed_password))
          Success(user)
        end
        handle_result(result, response, success_status: 201)
      end
    end

    class Show < Action
      def handle(request, response)
        result = find_user(request.params[:id])
        handle_result(result, response)
      end
    end

    class Update < Action
      def handle(request, response)
        result = Do.call do
          user = yield find_user(request.params[:id])
          params = yield validate(Contracts::UserUpdateContract.new, request.params.to_h)
          updated_user = yield Deps["repo.user"].update(user.id, params).to_result
          Success(updated_user)
        end
        handle_result(result, response)
      end
    end

    class Destroy < Action
      def handle(request, response)
        result = Do.call do
          user = yield find_user(request.params[:id])
          yield Deps["repo.user"].delete(user.id).to_result
          Success(nil)
        end
        
        case result
        in Success(_)
          response.status = 204
        in Failure((status, body))
          response.status = status
          response.content_type = "application/json"
          response.body = body
        end
      end
    end
  end

  # --- Router ---
  App = Hanami::API.new do
    scope "users" do
      get    "/", to: Actions::Users::Index.new
      post   "/", to: Actions::Users::Create.new
      get    "/:id", to: Actions::Users::Show.new
      patch  "/:id", to: Actions::Users::Update.new
      delete "/:id", to: Actions::Users::Destroy.new
    end
  end
end

# --- Example Usage ---
if __FILE__ == $0
  app = FunctionalApi::App

  puts "--- Get existing user (Success) ---"
  user_id = "c8307439-59f0-4a34-a38a-920b5874258c"
  env = Rack::MockRequest.env_for("/users/#{user_id}")
  status, headers, body = app.call(env)
  puts "Status: #{status}"
  puts "Body: #{JSON.pretty_generate(JSON.parse(body.join))}"

  puts "\n--- Get non-existent user (Failure) ---"
  env = Rack::MockRequest.env_for("/users/non-existent-id")
  status, headers, body = app.call(env)
  puts "Status: #{status}"
  puts "Body: #{JSON.pretty_generate(JSON.parse(body.join))}"

  puts "\n--- Create a new user (Success) ---"
  params = { email: "new.dev@example.com", password: "a-strong-password", role: "USER", is_active: true }.to_json
  env = Rack::MockRequest.env_for("/users", method: "POST", "CONTENT_TYPE" => "application/json", input: params)
  status, headers, body = app.call(env)
  puts "Status: #{status}"
  puts "Body: #{JSON.pretty_generate(JSON.parse(body.join))}"

  puts "\n--- Delete user ---"
  env = Rack::MockRequest.env_for("/users/#{user_id}", method: "DELETE")
  status, headers, body = app.call(env)
  puts "Status: #{status}"
  puts "Body: #{body.join}"
end
</code_snippet>