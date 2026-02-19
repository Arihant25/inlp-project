<code_snippet>
# frozen_string_literal: true

# --- DEPENDENCIES ---
# Gemfile:
# gem 'hanami', '~> 2.1'
# gem 'hanami-controller', '~> 2.1'
# gem 'hanami-validation', '~> 2.1'
# gem 'nokogiri', '~> 1.15'
# gem 'builder', '~> 3.2'

require 'hanami/controller'
require 'hanami/action'
require 'hanami/validation'
require 'securerandom'
require 'time'
require 'json'
require 'nokogiri'
require 'builder'

# --- DOMAIN & SERVICE LAYER ---
module ProductionApp
  # Mock Domain Entities
  User = Struct.new(:id, :email, :password_hash, :role, :is_active, :created_at, keyword_init: true)
  Post = Struct.new(:id, :user_id, :title, :content, :status, keyword_init: true)

  module Services
    class UserCreator
      # Validation contract is an internal detail of the service
      class Contract < Hanami::Validation::Contract
        params do
          required(:email).filled(:string)
          required(:password).filled(:string)
          required(:role).filled(:string, included_in?: %w[ADMIN USER])
          optional(:is_active).filled(:bool)
          optional(:phone_number).filled(:string)
        end

        rule(:email) do
          key.failure('is not a valid email format') unless URI::MailTo::EMAIL_REGEXP.match?(value)
        end

        # Custom validator for phone number
        rule(:phone_number) do
          key.failure('must be a valid 10-digit US phone number') if values[:phone_number] && !/^\d{10}$/.match?(value)
        end
      end

      def self.call(params)
        validation_result = Contract.new.call(params)

        if validation_result.success?
          user_dto = validation_result.to_h
          # Mock persistence and entity creation
          new_user = User.new(
            id: SecureRandom.uuid,
            email: user_dto[:email],
            password_hash: "hashed_#{user_dto[:password]}",
            role: user_dto[:role].upcase.to_sym,
            is_active: user_dto.fetch(:is_active, true),
            created_at: Time.now.utc
          )
          { success: true, user: new_user }
        else
          { success: false, errors: validation_result.errors.to_h }
        end
      end
    end

    class PostSerializer
      def self.to_json(post)
        {
          data: {
            id: post.id,
            author_id: post.user_id,
            post_title: post.title,
            post_content: post.content,
            current_status: post.status
          }
        }.to_json
      end

      def self.from_xml(xml_string)
        doc = Nokogiri::XML(xml_string)
        {
          user_id: doc.at_css('author_id')&.text,
          title: doc.at_css('title')&.text,
          content: doc.at_css('content')&.text,
          status: doc.at_css('status')&.text&.upcase
        }
      end
    end
  end

  # --- WEB/CONTROLLER LAYER ---
  class Action < Hanami::Action; end

  module Web
    module Actions
      module Users
        class Create < ProductionApp::Action
          def handle(request, response)
            # Action is thin, delegates to service
            service_result = Services::UserCreator.call(request.params.to_h)

            if service_result[:success]
              response.status = 201
              response.format = :json
              # Serialization is also handled by a dedicated object
              response.body = Services::PostSerializer.to_json(service_result[:user])
            else
              response.status = 422
              response.format = :json
              response.body = { errors: service_result[:errors] }.to_json
            end
          end
        end
      end

      module Posts
        class Create < ProductionApp::Action
          # This action consumes XML and produces JSON
          def handle(request, response)
            # Deserialization from XML
            post_params = Services::PostSerializer.from_xml(request.body.read)

            # Validation (could be in its own service too)
            validation = Hanami::Validation.Contract do
              params do
                required(:user_id).filled(:string)
                required(:title).filled(:string)
                required(:content).filled(:string)
                required(:status).filled(:string, included_in?: %w[DRAFT PUBLISHED])
              end
            end.new.call(post_params)

            if validation.success?
              # Mock creation
              post = ProductionApp::Post.new(validation.to_h.merge(id: SecureRandom.uuid))
              response.status = 201
              response.format = :json
              response.body = Services::PostSerializer.to_json(post)
            else
              response.status = 422
              response.format = :json
              response.body = { errors: validation.errors.to_h }.to_json
            end
          end
        end
      end
    end
  end
end

# --- USAGE EXAMPLE ---
# puts "--- User Creation via Service (Success) ---"
# user_action = ProductionApp::Web::Actions::Users::Create.new
# user_params = { email: 'dev@example.com', password: 'a_valid_password', role: 'ADMIN', phone_number: '1234567890' }
# req_mock = Hanami::Action::Request.new(env: {}, params: user_params)
# res_mock = Hanami::Action::Response.new
# user_action.call(req_mock, res_mock)
# puts res_mock.status
# puts res_mock.body.first

# puts "\n--- User Creation via Service (Failure) ---"
# user_params_fail = { email: 'dev@example.com', password: 'a_valid_password', role: 'ADMIN', phone_number: 'invalid-phone' }
# req_mock_fail = Hanami::Action::Request.new(env: {}, params: user_params_fail)
# res_mock_fail = Hanami::Action::Response.new
# user_action.call(req_mock_fail, res_mock_fail)
# puts res_mock_fail.status
# puts res_mock_fail.body.first

# puts "\n--- Post Creation XML -> JSON ---"
# post_action = ProductionApp::Web::Actions::Posts::Create.new
# xml_input = '<post><author_id>f8b5b1a0-5b5a-4b0a-8b0a-5b5a4b0a8b0a</author_id><title>My Post</title><content>Content here</content><status>draft</status></post>'
# req_xml_mock = Hanami::Action::Request.new(env: { 'rack.input' => StringIO.new(xml_input) }, params: {})
# res_xml_mock = Hanami::Action::Response.new
# post_action.call(req_xml_mock, res_xml_mock)
# puts res_xml_mock.status
# puts res_xml_mock.body.first
</code_snippet>