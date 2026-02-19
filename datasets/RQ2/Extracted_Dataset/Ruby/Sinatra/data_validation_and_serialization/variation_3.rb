<pre>
# COMPILABILITY GUARANTEE:
# To run this code, you need a Gemfile with the following:
#
# source 'https://rubygems.org'
# gem 'sinatra'
# gem 'dry-validation'
# gem 'dry-types'
# gem 'dry-monads'
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
require 'dry/monads/all'

# --- Domain Model ---
User = Struct.new(:id, :email, :password_hash, :role, :is_active, :created_at, :phone_number, keyword_init: true)

# --- In-Memory Store ---
class UserStore
  @data = {}
  class &lt;&lt; self
    attr_reader :data
    def create(attrs)
      user = User.new(attrs)
      @data[user.id] = user
      user
    end
    def find(id)
      @data[id]
    end
  end
end

# --- Validation ---
class UserCreationValidator &lt; Dry::Validation::Contract
  module Types
    include Dry.Types()
    Email = String.constrained(format: /\A[\w+\-.]+@[a-z\d\-]+(\.[a-z\d\-]+)*\.[a-z]+\z/i)
    Phone = String.constrained(format: /\A\+?\d{10,15}\z/)
    Role = String.enum('ADMIN', 'USER')
    Bool = Coercible::Bool
  end

  params do
    required(:email).filled(Types::Email)
    required(:password).filled(:string, min_size?: 8)
    required(:role).filled(Types::Role)
    optional(:is_active).value(Types::Bool)
    optional(:phone_number).maybe(Types::Phone) # Use maybe for optional fields
  end
end

# --- Service Object ---
class UserCreator
  include Dry::Monads[:result, :do]

  def call(params)
    validated_params = yield validate(params)
    user_attributes  = yield build_attributes(validated_params)
    user             = yield persist(user_attributes)
    Success(user)
  end

  private

  def validate(params)
    result = UserCreationValidator.new.call(params)
    result.success? ? Success(result.to_h) : Failure([:validation_failed, result.errors.to_h])
  end

  def build_attributes(params)
    password_hash = "hashed_#{params[:password]}"
    Success(
      params.except(:password).merge(
        id: SecureRandom.uuid,
        password_hash: password_hash,
        is_active: params.fetch(:is_active, true),
        created_at: Time.now.utc.iso8601
      )
    )
  end

  def persist(attributes)
    Success(UserStore.create(attributes))
  end
end

# --- Presenter ---
class UserPresenter
  def initialize(user)
    @user = user
  end

  def as_json
    MultiJson.dump(user_data)
  end

  def as_xml
    builder = Nokogiri::XML::Builder.new do |xml|
      xml.user do
        user_data.each { |key, value| xml.send(key, value) }
      end
    end
    builder.to_xml
  end

  private

  def user_data
    {
      id: @user.id,
      email: @user.email,
      role: @user.role,
      is_active: @user.is_active,
      created_at: @user.created_at,
      phone_number: @user.phone_number
    }
  end
end

# --- Sinatra Application (Controller Layer): The Service Layer Architect ---
class ServiceLayerApp &lt; Sinatra::Base
  helpers do
    def parse_request_body
      request.body.rewind
      body = request.body.read
      return {} if body.empty?
      case request.content_type
      when /json/
        MultiJson.load(body, symbolize_keys: true)
      when /xml/
        doc = Nokogiri::XML(body)
        Hash[doc.root.children.map { |n| [n.name.to_sym, n.text] if n.element? }.compact]
      else
        {}
      end
    rescue MultiJson::ParseError, Nokogiri::XML::SyntaxError
      halt 400, MultiJson.dump({ error: 'Invalid request body' })
    end
  end

  post '/users' do
    service = UserCreator.new
    result = service.call(parse_request_body)

    result.either(
      lambda { |user|
        status 201
        content_type :json
        UserPresenter.new(user).as_json
      },
      lambda { |(code, messages)|
        status 422
        content_type :json
        MultiJson.dump({ error: code, details: messages })
      }
    )
  end

  get '/users/:id' do
    user = UserStore.find(params[:id])
    halt 404, MultiJson.dump({ error: 'User not found' }) unless user

    presenter = UserPresenter.new(user)
    if request.accept?('application/xml')
      content_type :xml
      presenter.as_xml
    else
      content_type :json
      presenter.as_json
    end
  end
end
</pre>