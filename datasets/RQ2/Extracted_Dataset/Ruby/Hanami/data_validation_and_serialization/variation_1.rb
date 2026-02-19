<code_snippet>
# frozen_string_literal: true

# --- DEPENDENCIES ---
# Gemfile:
# gem 'hanami', '~> 2.1'
# gem 'hanami-router', '~> 2.1'
# gem 'hanami-controller', '~> 2.1'
# gem 'hanami-validation', '~> 2.1'
# gem 'nokogiri', '~> 1.15'
# gem 'builder', '~> 3.2'

require 'hanami'
require 'hanami/router'
require 'hanami/controller'
require 'hanami/action'
require 'hanami/validation'
require 'securerandom'
require 'time'
require 'json'
require 'nokogiri'
require 'builder'

# --- DOMAIN MOCK ---
module MyApp
  module Entities
    User = Struct.new(:id, :email, :password_hash, :role, :is_active, :created_at, keyword_init: true)
    Post = Struct.new(:id, :user_id, :title, :content, :status, keyword_init: true)
  end

  module Contracts
    class UserContract < Hanami::Validation::Contract
      params do
        required(:email).filled(:string, format?: URI::MailTo::EMAIL_REGEXP)
        required(:password).filled(:string, min_size?: 10)
        required(:role).filled(:string, included_in?: %w[ADMIN USER])
        optional(:is_active).filled(:bool)
      end

      rule(:password) do
        key.failure('must contain at least one uppercase letter, one number, and one special character') unless
          value.match?(/(?=.*[A-Z])(?=.*[0-9])(?=.*[^A-Za-z0-9])/)
      end
    end

    class PostContract < Hanami::Validation::Contract
      params do
        required(:user_id).filled(:string, format?: /\A[0-9a-f]{8}-([0-9a-f]{4}-){3}[0-9a-f]{12}\z/i)
        required(:title).filled(:string, min_size?: 5, max_size?: 255)
        required(:content).filled(:string)
        required(:status).filled(:string, included_in?: %w[DRAFT PUBLISHED])
      end
    end
  end

  class Action < Hanami::Action
  end

  module Actions
    module Users
      class Create < MyApp::Action
        params Contracts::UserContract

        def handle(request, response)
          if request.params.valid?
            # Type coercion is handled by the contract
            user_data = request.params.to_h
            user = Entities::User.new(
              id: SecureRandom.uuid,
              email: user_data[:email],
              password_hash: "hashed_#{user_data[:password]}", # Mock hashing
              role: user_data[:role].upcase,
              is_active: user_data.fetch(:is_active, true),
              created_at: Time.now.utc
            )

            response.status = 201
            response.headers['Content-Type'] = 'application/json'
            # JSON Serialization
            response.body = { data: user.to_h }.to_json
          else
            response.status = 422
            response.headers['Content-Type'] = 'application/json'
            # Error Message Formatting
            response.body = { errors: request.params.errors.to_h }.to_json
          end
        end
      end
    end

    module Posts
      class CreateFromXml < MyApp::Action
        def handle(request, response)
          # XML Parsing
          xml_doc = Nokogiri::XML(request.body.read)
          post_data = {
            user_id: xml_doc.at_xpath('//post/user_id')&.text,
            title: xml_doc.at_xpath('//post/title')&.text,
            content: xml_doc.at_xpath('//post/content')&.text,
            status: xml_doc.at_xpath('//post/status')&.text
          }

          validation = Contracts::PostContract.new.call(post_data)

          if validation.success?
            post = Entities::Post.new(validation.to_h.merge(id: SecureRandom.uuid))
            response.status = 201
            response.headers['Content-Type'] = 'application/xml'
            # XML Generation
            response.body = generate_post_xml(post)
          else
            response.status = 422
            response.headers['Content-Type'] = 'application/json'
            response.body = { errors: validation.errors.to_h }.to_json
          end
        end

        private

        def generate_post_xml(post)
          xml = Builder::XmlMarkup.new(indent: 2)
          xml.instruct! :xml, version: "1.0", encoding: "UTF-8"
          xml.post do |p|
            p.id post.id
            p.user_id post.user_id
            p.title post.title
            p.content post.content
            p.status post.status
          end
        end
      end
    end
  end
end

# --- USAGE EXAMPLE ---
# puts "--- User Creation (Success) ---"
# action = MyApp::Actions::Users::Create.new
# req_mock = Hanami::Action::Request.new(env: { 'rack.input' => StringIO.new({ email: 'test@example.com', password: 'Password123!', role: 'USER' }.to_json) }, params: {})
# res_mock = Hanami::Action::Response.new
# action.call(req_mock, res_mock)
# puts res_mock.status
# puts res_mock.body.first

# puts "\n--- User Creation (Failure) ---"
# req_mock_fail = Hanami::Action::Request.new(env: { 'rack.input' => StringIO.new({ email: 'invalid', password: 'short', role: 'GUEST' }.to_json) }, params: {})
# res_mock_fail = Hanami::Action::Response.new
# action.call(req_mock_fail, res_mock_fail)
# puts res_mock_fail.status
# puts res_mock_fail.body.first

# puts "\n--- Post Creation from XML (Success) ---"
# post_action = MyApp::Actions::Posts::CreateFromXml.new
# xml_input = '<post><user_id>f8b5b1a0-5b5a-4b0a-8b0a-5b5a4b0a8b0a</user_id><title>My First Post</title><content>Hello World!</content><status>DRAFT</status></post>'
# req_xml_mock = Hanami::Action::Request.new(env: { 'rack.input' => StringIO.new(xml_input) }, params: {})
# res_xml_mock = Hanami::Action::Response.new
# post_action.call(req_xml_mock, res_xml_mock)
# puts res_xml_mock.status
# puts res_xml_mock.body.first
</code_snippet>