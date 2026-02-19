<code_snippet>
# frozen_string_literal: true

# This is a self-contained, runnable Hanami REST API implementation.
# Variation 4: Explicit & Verbose Style
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

# --- Application Namespace ---
module VerboseApp
  # --- Custom Types for Domain Schema ---
  module DomainTypes
    include Dry.Types()
    UUID = String.constrained(format: /\A[0-9a-f]{8}-([0-9a-f]{4}-){3}[0-9a-f]{12}\z/i)
    UserRole = String.enum("ADMIN", "USER")
  end

  # --- Entity Definition ---
  class User < Hanami::Entity
    attributes do
      attribute :id,          DomainTypes::UUID
      attribute :email,       DomainTypes::String
      attribute :password_hash, DomainTypes::String
      attribute :role,        DomainTypes::UserRole
      attribute :is_active,   DomainTypes::Bool
      attribute :created_at,  DomainTypes::Time
    end
  end

  # --- In-Memory Repository for Demonstration ---
  class UserRepository
    def initialize
      @database_table = {
        "c8307439-59f0-4a34-a38a-920b5874258c" => { id: "c8307439-59f0-4a34-a38a-920b5874258c", email: "admin@example.com", password_hash: BCrypt::Password.create("password"), role: "ADMIN", is_active: true, created_at: Time.now.utc - 3600 },
        "f1f27891-2106-4742-b98a-4a8cb7c2f3a4" => { id: "f1f27891-2106-4742-b98a-4a8cb7c2f3a4", email: "user@example.com", password_hash: BCrypt::Password.create("password"), role: "USER", is_active: true, created_at: Time.now.utc - 1800 },
      }
    end

    def find_by_id(user_id)
      record = @database_table[user_id]
      record ? User.new(record) : nil
    end

    def find_by_email(email_address)
      record = @database_table.values.find { |user_record| user_record[:email] == email_address }
      record ? User.new(record) : nil
    end

    def persist_new_user(user_attributes)
      new_id = SecureRandom.uuid
      password_digest = BCrypt::Password.create(user_attributes[:password])
      
      new_record = {
        id: new_id,
        email: user_attributes[:email],
        password_hash: password_digest,
        role: user_attributes[:role],
        is_active: user_attributes[:is_active],
        created_at: Time.now.utc
      }
      
      @database_table[new_id] = new_record
      return User.new(new_record)
    end

    def update_existing_user(user_id, attributes_to_update)
      return nil unless @database_table.key?(user_id)
      @database_table[user_id].merge!(attributes_to_update)
      return User.new(@database_table[user_id])
    end

    def delete_user(user_id)
      @database_table.delete(user_id)
    end

    def search_and_paginate(page: 1, per_page: 10, filters: {})
      all_records = @database_table.values
      
      filtered_records = all_records.select do |record|
        matches_role = filters[:role] ? record[:role] == filters[:role] : true
        matches_active = filters.key?(:is_active) ? record[:is_active] == (filters[:is_active] == 'true') : true
        matches_role && matches_active
      end

      start_index = (page - 1) * per_page
      paginated_records = filtered_records.slice(start_index, per_page) || []
      
      paginated_records.map { |record| User.new(record) }
    end
  end

  # --- Mock Dependency Injection ---
  class Dependencies
    def self.user_repository
      @user_repository ||= UserRepository.new
    end
  end

  # --- Validation Contracts ---
  module Validations
    class UserCreationContract < Hanami::Validation::Contract
      params do
        required(:email).filled(:string, format?: URI::MailTo::EMAIL_REGEXP)
        required(:password).filled(:string, min_size?: 8)
        required(:role).filled(DomainTypes::UserRole)
        required(:is_active).filled(:bool)
      end
      rule(:email) do
        key.failure("is already in use") if Dependencies.user_repository.find_by_email(value)
      end
    end

    class UserUpdateContract < Hanami::Validation::Contract
      params do
        optional(:role).filled(DomainTypes::UserRole)
        optional(:is_active).filled(:bool)
      end
    end
  end

  # --- Base Action with Explicit Helpers ---
  class Action < Hanami::Action
    private

    # Serializes a user entity to a JSON-safe hash, excluding sensitive data.
    def serialize_user(user_entity)
      {
        id: user_entity.id,
        email: user_entity.email,
        role: user_entity.role,
        is_active: user_entity.is_active,
        created_at: user_entity.created_at.iso8601
      }
    end

    # Sets a successful JSON response.
    def render_success(response, data, status_code: 200)
      response.status = status_code
      response.content_type = "application/json"
      response.body = data.to_json
    end

    # Sets an error JSON response.
    def render_error(response, error_messages, status_code: 400)
      response.status = status_code
      response.content_type = "application/json"
      response.body = { errors: error_messages }.to_json
    end

    # A specific helper to render a 404 Not Found response.
    def render_not_found(response, entity_name: "User")
      render_error(response, { base: ["#{entity_name} not found"] }, status_code: 404)
    end
  end

  # --- Action Classes ---
  module Actions::Users
    class Index < Action
      def handle(request, response)
        page_number = request.params[:page]&.to_i || 1
        items_per_page = request.params[:per_page]&.to_i || 10
        filter_params = request.params.to_h.slice(:role, :is_active)

        user_records = Dependencies.user_repository.search_and_paginate(
          page: page_number,
          per_page: items_per_page,
          filters: filter_params
        )
        
        serialized_users = user_records.map { |user| serialize_user(user) }
        render_success(response, serialized_users)
      end
    end

    class Create < Action
      def handle(request, response)
        validation_result = Validations::UserCreationContract.new.call(request.params.to_h)

        if validation_result.success?
          user_attributes = validation_result.to_h
          newly_created_user = Dependencies.user_repository.persist_new_user(user_attributes)
          render_success(response, serialize_user(newly_created_user), status_code: 201)
        else
          render_error(response, validation_result.errors.to_h, status_code: 422)
        end
      end
    end

    class Show < Action
      def handle(request, response)
        user_id = request.params[:id]
        user_record = Dependencies.user_repository.find_by_id(user_id)

        if user_record
          render_success(response, serialize_user(user_record))
        else
          render_not_found(response)
        end
      end
    end

    class Update < Action
      def handle(request, response)
        user_id = request.params[:id]
        user_to_update = Dependencies.user_repository.find_by_id(user_id)
        return render_not_found(response) unless user_to_update

        validation_result = Validations::UserUpdateContract.new.call(request.params.to_h)
        if validation_result.success?
          attributes_for_update = validation_result.to_h
          updated_user_record = Dependencies.user_repository.update_existing_user(user_id, attributes_for_update)
          render_success(response, serialize_user(updated_user_record))
        else
          render_error(response, validation_result.errors.to_h, status_code: 422)
        end
      end
    end

    class Destroy < Action
      def handle(request, response)
        user_id = request.params[:id]
        user_to_delete = Dependencies.user_repository.find_by_id(user_id)
        return render_not_found(response) unless user_to_delete

        Dependencies.user_repository.delete_user(user_id)
        response.status = 204
      end
    end
  end

  # --- Router Definition ---
  Router = Hanami::Router.new do
    scope "users" do
      get    "/", to: Actions::Users::Index.new
      post   "/", to: Actions::Users::Create.new
      get    "/:id", to: Actions::Users::Show.new
      put    "/:id", to: Actions::Users::Update.new
      patch  "/:id", to: Actions::Users::Update.new
      delete "/:id", to: Actions::Users::Destroy.new
    end
  end
end

# --- Example Usage ---
if __FILE__ == $0
  app = VerboseApp::Router

  puts "--- List users with filter ---"
  env = Rack::MockRequest.env_for("/users?role=ADMIN")
  status, headers, body = app.call(env)
  puts "Status: #{status}"
  puts "Body: #{JSON.pretty_generate(JSON.parse(body.join))}"

  puts "\n--- Update a user (Success) ---"
  user_id = "f1f27891-2106-4742-b98a-4a8cb7c2f3a4"
  params = { is_active: false }
  env = Rack::MockRequest.env_for("/users/#{user_id}", method: "PATCH", params: params)
  status, headers, body = app.call(env)
  puts "Status: #{status}"
  puts "Body: #{JSON.pretty_generate(JSON.parse(body.join))}"

  puts "\n--- Update a user (Failure: invalid role) ---"
  params = { role: "SUPER_ADMIN" }
  env = Rack::MockRequest.env_for("/users/#{user_id}", method: "PATCH", params: params)
  status, headers, body = app.call(env)
  puts "Status: #{status}"
  puts "Body: #{JSON.pretty_generate(JSON.parse(body.join))}"
end
</code_snippet>