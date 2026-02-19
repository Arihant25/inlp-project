<pre>
# COMPILABILITY GUARANTEE:
# To run this code, you need a Gemfile with the following:
#
# source 'https://rubygems.org'
# gem 'sinatra'
# gem 'dry-validation'
# gem 'dry-types'
# gem 'multi_json'
# gem 'nokogiri'
# gem 'securerandom'
#
# Then run `bundle install` and `bundle exec ruby app.rb`

require 'sinatra/base'
require 'securerandom'
require 'multi_json'
require 'nokogiri'
require 'time'
require 'dry-validation'
require 'dry-types'

# --- Domain Models ---
module Models
  User = Struct.new(:id, :email, :password_hash, :role, :is_active, :created_at, :phone_number, keyword_init: true) do
    def to_h
      super.tap { |h| h.delete(:password_hash) } # Don't expose password hash
    end
  end
end

# --- Data Persistence (In-Memory) ---
class UserRepository
  @users = {}
  class &lt;&lt; self
    def save(user)
      @users[user.id] = user
    end

    def find(id)
      @users[id]
    end
  end
end

# --- Validation Contracts ---
module Validators
  module CustomTypes
    include Dry.Types()
    Email = String.constrained(format: /\A[\w+\-.]+@[a-z\d\-]+(\.[a-z\d\-]+)*\.[a-z]+\z/i)
    Phone = String.constrained(format: /\A\+?\d{10,15}\z/, message: "must be a valid phone number")
    Role = String.enum('ADMIN', 'USER')
    Bool = Coercible::Bool
  end

  class UserContract &lt; Dry::Validation::Contract
    params do
      required(:email).filled(CustomTypes::Email)
      required(:password).filled(:string, min_size?: 8)
      required(:role).filled(CustomTypes::Role)
      optional(:is_active).value(CustomTypes::Bool)
      optional(:phone_number).value(CustomTypes::Phone)
    end

    # Custom validator example
    rule(:password) do
      unless value.match?(/\d/) &amp;&amp; value.match?(/[a-zA-Z]/)
        key.failure('must contain at least one letter and one number')
      end
    end
  end
end

# --- Serializers ---
module Serializers
  class UserSerializer
    def initialize(user_model)
      @user = user_model
    end

    def as_json
      MultiJson.dump(@user.to_h)
    end

    def as_xml
      builder = Nokogiri::XML::Builder.new(encoding: 'UTF-8') do |xml|
        xml.user do
          @user.to_h.each do |key, value|
            xml.send(key, value.is_a?(Time) ? value.iso8601 : value)
          end
        end
      end
      builder.to_xml
    end
  end
end

# --- Sinatra Application: The OOP Enthusiast ---
class OopApp &lt; Sinatra::Base
  configure do
    set :show_exceptions, false
  end

  helpers do
    def parse_body
      request.body.rewind
      body = request.body.read
      return {} if body.empty?
      content_type = request.content_type
      if content_type&amp;.include?('application/json')
        MultiJson.load(body, symbolize_keys: true)
      elsif content_type&amp;.include?('application/xml')
        doc = Nokogiri::XML(body)
        doc.remove_namespaces!
        Hash[doc.xpath('/user/*').map { |node| [node.name.to_sym, node.text] }]
      else
        {}
      end
    rescue MultiJson::ParseError, Nokogiri::XML::SyntaxError
      halt 400, render_error('Invalid request body format')
    end

    def render_error(message, details = nil)
      content_type :json
      error_payload = { error: message }
      error_payload[:details] = details if details
      MultiJson.dump(error_payload)
    end
  end

  error do
    status 500
    render_error("An unexpected error occurred: #{env['sinatra.error'].message}")
  end

  post '/users' do
    user_params = parse_body
    validation = Validators::UserContract.new.call(user_params)

    if validation.failure?
      status 422
      return render_error('Validation failed', validation.errors.to_h)
    end

    validated_attrs = validation.to_h
    user = Models::User.new(
      id: SecureRandom.uuid,
      email: validated_attrs[:email],
      password_hash: "hashed_#{validated_attrs[:password]}",
      role: validated_attrs[:role],
      is_active: validated_attrs.fetch(:is_active, true),
      created_at: Time.now.utc,
      phone_number: validated_attrs[:phone_number]
    )

    UserRepository.save(user)

    status 201
    content_type :json
    Serializers::UserSerializer.new(user).as_json
  end

  get '/users/:id' do
    user = UserRepository.find(params[:id])
    halt 404, render_error('User not found') unless user

    serializer = Serializers::UserSerializer.new(user)
    if request.accept?('application/xml')
      content_type :xml
      serializer.as_xml
    else
      content_type :json
      serializer.as_json
    end
  end
end
</pre>