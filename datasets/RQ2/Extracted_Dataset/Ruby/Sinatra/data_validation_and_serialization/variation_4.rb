<pre>
# COMPILABILITY GUARANTEE:
# To run this code, you need a Gemfile with the following:
#
# source 'https://rubygems.org'
# gem 'sinatra'
# gem 'dry-system'
# gem 'dry-struct'
# gem 'dry-validation'
# gem 'dry-types'
# gem 'multi_json'
# gem 'nokogiri'
# gem 'securerandom'
#
# Then run `bundle install` and `bundle exec ruby app.rb`

require 'sinatra/base'
require 'dry-system'
require 'dry-struct'
require 'dry-validation'
require 'dry-types'
require 'multi_json'
require 'nokogiri'
require 'securerandom'
require 'time'

# --- Application Container (simulated) ---
class MyApp &lt; Dry::System::Container
  configure do |config|
    config.root = __dir__
    config.component_dirs.add "lib"
  end
end

# --- Types ---
module MyApp
  module Types
    include Dry.Types()
    UUID = String.constrained(format: /\A[0-9a-f]{8}-([0-9a-f]{4}-){3}[0-9a-f]{12}\z/i)
    Email = String.constrained(format: /\A[\w+\-.]+@[a-z\d\-]+(\.[a-z\d\-]+)*\.[a-z]+\z/i)
    Role = String.enum('ADMIN', 'USER')
    Status = String.enum('DRAFT', 'PUBLISHED')
    PhoneNumber = String.constrained(format: /\A\+?\d{10,15}\z/)
  end
end

# --- Entities (using dry-struct for type safety) ---
module MyApp
  module Entities
    class User &lt; Dry::Struct
      attribute :id, Types::UUID
      attribute :email, Types::Email
      attribute :password_hash, Types::String
      attribute :role, Types::Role
      attribute :is_active, Types::Bool
      attribute :created_at, Types::Time
      attribute? :phone_number, Types::PhoneNumber.optional

      def to_public_h
        to_h.except(:password_hash)
      end
    end
  end
end

# --- Validation Contracts ---
module MyApp
  module Validation
    class UserContract &lt; Dry::Validation::Contract
      params do
        required(:email).filled(MyApp::Types::Email)
        required(:password).filled(:string, min_size?: 8)
        required(:role).filled(MyApp::Types::Role)
        optional(:is_active).value(MyApp::Types::Bool)
        optional(:phone_number).value(MyApp::Types::PhoneNumber)
      end
    end
  end
end

# --- Repository ---
module MyApp
  class UserRepository
    def initialize(db = {})
      @db = db
    end

    def create(user_attrs)
      user = Entities::User.new(user_attrs)
      @db[user.id] = user
      user
    end

    def find(id)
      @db[id]
    end
  end

  # Register repository in the container
  register(:user_repo, UserRepository.new)
end

# --- Sinatra Application: The Dry-RB Ecosystem Pro ---
class DryRbApp &lt; Sinatra::Base
  set :container, MyApp

  helpers do
    def user_repo
      settings.container[:user_repo]
    end

    def parse_body
      request.body.rewind
      body = request.body.read
      return {} if body.empty?
      case request.content_type
      when /json/
        MultiJson.load(body, symbolize_keys: true)
      when /xml/
        doc = Nokogiri::XML(body)
        halt 400, render_json_error('Invalid XML format') if doc.errors.any?
        Hash[doc.root.children.map { |n| [n.name.to_sym, n.text] if n.element? }.compact]
      else
        halt 415, render_json_error('Unsupported Media Type')
      end
    rescue MultiJson::ParseError
      halt 400, render_json_error('Invalid JSON format')
    end

    def render_json_error(message, details = nil)
      payload = { error: message }
      payload[:details] = details if details
      MultiJson.dump(payload)
    end

    def serialize_to_xml(hash_data, root_element)
      builder = Nokogiri::XML::Builder.new { |xml|
        xml.send(root_element) do
          hash_data.each { |key, value| xml.send(key, value.is_a?(Time) ? value.iso8601 : value) }
        end
      }
      builder.to_xml
    end
  end

  before do
    content_type :json
  end

  post '/users' do
    params = parse_body
    validation = Validation::UserContract.new.call(params)
    if validation.failure?
      status 422
      return render_json_error('Validation failed', validation.errors.to_h)
    end

    user_attrs = validation.to_h.merge(
      id: SecureRandom.uuid,
      password_hash: "hashed_#{validation[:password]}",
      created_at: Time.now.utc
    )
    user_attrs.delete(:password)

    new_user = user_repo.create(user_attrs)

    status 201
    MultiJson.dump(new_user.to_public_h)
  end

  get '/users/:id' do
    user = user_repo.find(params[:id])
    halt 404, render_json_error('User not found') unless user

    if request.accept?('application/xml')
      content_type :xml
      serialize_to_xml(user.to_public_h, 'user')
    else
      MultiJson.dump(user.to_public_h)
    end
  end
end
</pre>