<code_snippet>
# frozen_string_literal: true

# --- DEPENDENCIES ---
# Gemfile:
# gem 'hanami', '~> 2.1'
# gem 'hanami-controller', '~> 2.1'
# gem 'dry-validation', '~> 1.10'
# gem 'dry-monads', '~> 1.6'
# gem 'dry-struct', '~> 1.6'
# gem 'nokogiri', '~> 1.15'
# gem 'builder', '~> 3.2'

require 'hanami/controller'
require 'hanami/action'
require 'dry-validation'
require 'dry/monads'
require 'dry/monads/do'
require 'dry-struct'
require 'securerandom'
require 'time'
require 'json'
require 'nokogiri'
require 'builder'

# --- TYPES & DOMAIN ---
module Core
  module Types
    include Dry.Types()
    UUID = Types::String.constrained(format: /\A[0-9a-f]{8}-([0-9a-f]{4}-){3}[0-9a-f]{12}\z/i)
    Role = Types::String.enum('ADMIN', 'USER')
    Status = Types::String.enum('DRAFT', 'PUBLISHED')
  end

  class User < Dry::Struct
    attribute :id, Types::UUID
    attribute :email, Types::String
    attribute :password_hash, Types::String
    attribute :role, Types::Role
    attribute :is_active, Types::Bool
    attribute :created_at, Types::Time
  end

  class Post < Dry::Struct
    attribute :id, Types::UUID
    attribute :user_id, Types::UUID
    attribute :title, Types::String
    attribute :content, Types::String
    attribute :status, Types::Status
  end
end

# --- VALIDATORS & OPERATIONS (BUSINESS LOGIC) ---
module Operations
  module Users
    class Create
      include Dry::Monads[:result, :try]
      include Dry::Monads::Do.for(:call)

      # Define validator in a separate namespace
      UserContract = Dry.Validation.Contract do
        params do
          required(:email).filled(Core::Types::String, format?: URI::MailTo::EMAIL_REGEXP)
          required(:password).filled(Core::Types::String, min_size?: 12)
          required(:role).filled(Core::Types::Role)
          optional(:is_active).value(Core::Types::Bool)
        end
      end

      def call(params)
        # Yield ensures the monad unwraps Success or returns Failure
        validated_params = yield validate(params)
        user_entity = yield create_user(validated_params)
        
        Success(user_entity)
      end

      private

      def validate(params)
        result = UserContract.new.call(params)
        result.success? ? Success(result.to_h) : Failure(result.errors)
      end

      def create_user(data)
        # Try monad can wrap operations that might fail (e.g. DB call)
        Try[StandardError] do
          Core::User.new(
            id: SecureRandom.uuid,
            email: data[:email],
            password_hash: "hashed_#{data[:password]}",
            role: data[:role],
            is_active: data.fetch(:is_active, true),
            created_at: Time.now.utc.iso8601
          )
        end.to_result
      end
    end
  end
end

# --- WEB INTERFACE ---
module Web
  class Action < Hanami::Action; end

  module Actions
    module Users
      class Create < Web::Action
        def handle(request, response)
          # The action is just a thin layer between HTTP and the operation
          operation = Operations::Users::Create.new
          result = operation.call(request.params.to_h)

          case result
          in Dry::Monads::Success(user)
            response.status = 201
            response.format = :json
            # Serialization from dry-struct is straightforward
            response.body = user.to_h.to_json
          in Dry::Monads::Failure(errors)
            response.status = 422
            response.format = :json
            response.body = { errors: errors.to_h }.to_json
          end
        end
      end
    end
  end
end

# --- USAGE EXAMPLE ---
# puts "--- Functional User Creation (Success) ---"
# action = Web::Actions::Users::Create.new
# params = { email: 'functional@example.com', password: 'VeryComplexPassword123', role: 'ADMIN', is_active: 'false' }
# req = Hanami::Action::Request.new(env: {}, params: params)
# res = Hanami::Action::Response.new
# action.call(req, res)
# puts res.status
# puts res.body.first # Note: is_active was coerced from string to boolean

# puts "\n--- Functional User Creation (Failure) ---"
# params_fail = { email: 'bad', password: 'short', role: 'INVALID' }
# req_fail = Hanami::Action::Request.new(env: {}, params: params_fail)
# res_fail = Hanami::Action::Response.new
# action.call(req_fail, res_fail)
# puts res_fail.status
# puts res_fail.body.first
</code_snippet>