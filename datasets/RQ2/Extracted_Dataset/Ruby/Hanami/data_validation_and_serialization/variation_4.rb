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

# --- DOMAIN MOCKS ---
module Domain
  User = Struct.new(:id, :email, :password_hash, :role, :is_active, :created_at, keyword_init: true)
  Post = Struct.new(:id, :user_id, :title, :content, :status, keyword_init: true)
end

# --- FORM OBJECT PATTERN ---
module App
  module Forms
    class BaseForm < Hanami::Validation::Contract
      def self.call(params)
        new.call(params)
      end

      def valid?
        call(attributes).success?
      end

      def errors
        call(attributes).errors.to_h
      end
    end

    class UserCreationForm < BaseForm
      attr_reader :attributes, :user

      # 1. Define validation schema
      params do
        required(:email).filled(:string, format?: URI::MailTo::EMAIL_REGEXP)
        required(:password).filled(:string, min_size?: 8)
        required(:password_confirmation).filled(:string)
        required(:role).filled(:string, included_in?: %w[ADMIN USER])
        optional(:is_active).filled(:bool)
      end

      # 2. Add custom validation rules
      rule(:password, :password_confirmation) do
        key.failure('passwords do not match') if values[:password] != values[:password_confirmation]
      end

      def initialize(params = {})
        @attributes = params
        @user = nil
      end

      # 3. Encapsulate persistence logic
      def save
        return false unless valid?

        # On success, create the domain entity
        data = call(attributes).to_h
        @user = Domain::User.new(
          id: SecureRandom.uuid,
          email: data[:email],
          password_hash: "hashed_#{data[:password]}",
          role: data[:role],
          is_active: data.fetch(:is_active, true),
          created_at: Time.now.utc
        )
        true # Represents successful persistence
      end

      # 4. Encapsulate serialization logic
      def to_json(*_args)
        { data: user&.to_h }.to_json
      end

      def to_xml
        return nil unless @user
        xml = Builder::XmlMarkup.new(indent: 2)
        xml.user do |u|
          u.id @user.id
          u.email @user.email
          u.role @user.role
          u.is_active @user.is_active
          u.created_at @user.created_at.iso8601
        end
      end
    end
  end

  # --- CONTROLLER LAYER ---
  class Action < Hanami::Action; end

  module Actions
    module Users
      class Create < App::Action
        def handle(request, response)
          # Action is extremely thin, delegating everything to the form object
          form = Forms::UserCreationForm.new(request.params.to_h)

          if form.save
            response.status = 201
            # Can easily switch between serialization formats
            if request.content_type == 'application/xml'
              response.format = :xml
              response.body = form.to_xml
            else
              response.format = :json
              response.body = form.to_json
            end
          else
            response.status = 422
            response.format = :json
            response.body = { errors: form.errors }.to_json
          end
        end
      end
    end
  end
end

# --- USAGE EXAMPLE ---
# puts "--- Form Object Pattern (Success, JSON) ---"
# action = App::Actions::Users::Create.new
# params = {
#   email: 'form-user@example.com',
#   password: 'password123',
#   password_confirmation: 'password123',
#   role: 'USER',
#   is_active: 'true' # Demonstrates type coercion
# }
# req = Hanami::Action::Request.new(env: {}, params: params)
# res = Hanami::Action::Response.new
# action.call(req, res)
# puts res.status
# puts res.body.first

# puts "\n--- Form Object Pattern (Failure) ---"
# params_fail = {
#   email: 'form-user@example.com',
#   password: 'password123',
#   password_confirmation: 'passwordMISMATCH', # Mismatch
#   role: 'USER'
# }
# req_fail = Hanami::Action::Request.new(env: {}, params: params_fail)
# res_fail = Hanami::Action::Response.new
# action.call(req_fail, res_fail)
# puts res_fail.status
# puts res_fail.body.first

# puts "\n--- Form Object Pattern (Success, XML) ---"
# req_xml = Hanami::Action::Request.new(env: { 'CONTENT_TYPE' => 'application/xml' }, params: params)
# res_xml = Hanami::Action::Response.new
# action.call(req_xml, res_xml)
# puts res_xml.status
# puts res_xml.body.first
</code_snippet>