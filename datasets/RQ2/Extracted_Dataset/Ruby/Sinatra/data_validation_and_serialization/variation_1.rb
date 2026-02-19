<pre>
# COMPILABILITY GUARANTEE:
# To run this code, you need a Gemfile with the following:
#
# source 'https://rubygems.org'
# gem 'sinatra'
# gem 'sinatra-contrib'
# gem 'dry-validation'
# gem 'dry-types'
# gem 'nokogiri'
# gem 'securerandom'
#
# Then run `bundle install` and `bundle exec ruby app.rb`

require 'sinatra/base'
require 'sinatra/json'
require 'securerandom'
require 'json'
require 'nokogiri'
require 'time'
require 'dry-validation'
require 'dry-types'

# --- Mock Database &amp; Model ---
DB = { users: {} }
User = Struct.new(:id, :email, :password_hash, :role, :is_active, :created_at, :phone_number, keyword_init: true)

# --- Custom Types for Validation ---
module Types
  include Dry.Types()
  Email = String.constrained(format: /\A[\w+\-.]+@[a-z\d\-]+(\.[a-z\d\-]+)*\.[a-z]+\z/i)
  Phone = String.constrained(format: /\A\+?\d{10,15}\z/)
  Role = String.enum('ADMIN', 'USER')
  Bool = Coercible::Bool
end

# --- Validation Schema ---
UserSchema = Dry::Validation::Contract.new do
  params do
    required(:email).filled(Types::Email)
    required(:password).filled(:string, min_size?: 8)
    required(:role).filled(Types::Role)
    optional(:is_active).value(Types::Bool)
    optional(:phone_number).value(Types::Phone)
  end

  # Custom validator rule
  rule(:password) do
    key.failure('must contain at least one letter and one number') if !value.match?(/\d/) || !value.match?(/[a-zA-Z]/)
  end
end

# --- Sinatra Application: The Pragmatic Minimalist ---
class PragmaticApp &lt; Sinatra::Base
  helpers Sinatra::JSON

  configure do
    set :show_exceptions, false
  end

  # --- Helpers ---
  helpers do
    def parse_request_body
      request.body.rewind
      content_type = request.content_type
      body = request.body.read
      if content_type&amp;.include?('application/json')
        return JSON.parse(body)
      elsif content_type&amp;.include?('application/xml')
        doc = Nokogiri::XML(body)
        return Hash[doc.root.children.map { |node| [node.name, node.text] if node.element? }.compact]
      end
      {}
    rescue JSON::ParserError, Nokogiri::XML::SyntaxError
      halt 400, json({ error: 'Invalid request body format' })
    end

    def serialize(data, type)
      case type
      when :json
        content_type :json
        data.to_json
      when :xml
        content_type :xml
        build_xml(data.to_h.except(:password_hash), 'user')
      else
        content_type :json
        data.to_json
      end
    end

    def build_xml(data_hash, root_element_name)
      builder = Nokogiri::XML::Builder.new do |xml|
        xml.send(root_element_name) do
          data_hash.each { |key, value| xml.send(key, value) }
        end
      end
      builder.to_xml
    end

    def format_errors(errors)
      { errors: errors.to_h }
    end
  end

  # --- Error Handling ---
  error do
    status 500
    json error: "An unexpected error occurred: #{env['sinatra.error'].message}"
  end

  error 404 do
    content_type :json
    { error: 'Not Found' }.to_json
  end

  # --- Routes ---
  post '/users' do
    params = parse_request_body
    validation_result = UserSchema.call(params)

    if validation_result.failure?
      status 422
      return json format_errors(validation_result.errors)
    end

    valid_data = validation_result.to_h
    new_user = User.new(
      id: SecureRandom.uuid,
      email: valid_data[:email],
      password_hash: "hashed_#{valid_data[:password]}", # In real app, use BCrypt
      role: valid_data[:role],
      is_active: valid_data.fetch(:is_active, true), # Type coercion from schema
      created_at: Time.now.utc.iso8601,
      phone_number: valid_data[:phone_number]
    )

    DB[:users][new_user.id] = new_user
    status 201
    json new_user.to_h.except(:password_hash)
  end

  get '/users/:id' do
    user = DB[:users][params[:id]]
    halt 404 unless user

    accept_type = request.accept.first&amp;.to_s
    if accept_type&amp;.include?('xml')
      serialize(user, :xml)
    else
      serialize(user, :json)
    end
  end
end

# To run this app, save it as app.rb and run `bundle exec ruby app.rb`
# Example POST JSON: curl -X POST -H "Content-Type: application/json" -d '{"email":"test@example.com", "password":"password123", "role":"USER", "phone_number":"+15551234567", "is_active": "true"}' http://localhost:4567/users
# Example POST XML: curl -X POST -H "Content-Type: application/xml" -d '&lt;user&gt;&lt;email&gt;test2@example.com&lt;/email&gt;&lt;password&gt;password123&lt;/password&gt;&lt;role&gt;ADMIN&lt;/role&gt;&lt;/user&gt;' http://localhost:4567/users
# Example GET JSON: curl http://localhost:4567/users/&lt;id&gt;
# Example GET XML: curl -H "Accept: application/xml" http://localhost:4567/users/&lt;id&gt;
</pre>