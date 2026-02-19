# Variation 1: Classic OOP with Service Objects (Validator/Serializer Classes)
# This approach uses dedicated classes for models, validation, and serialization,
# promoting strong separation of concerns, similar to patterns found in larger frameworks.

require 'json'
require 'rexml/document'
require 'securerandom'
require 'time'

# --- Domain Models ---
module Models
  class User
    attr_accessor :id, :email, :password_hash, :role, :is_active, :created_at

    ROLES = ['ADMIN', 'USER'].freeze

    def initialize(attrs = {})
      @id = attrs[:id] || SecureRandom.uuid
      @email = attrs[:email]
      @password_hash = attrs[:password_hash]
      @role = attrs[:role] || 'USER'
      @is_active = attrs.key?(:is_active) ? attrs[:is_active] : false
      @created_at = attrs[:created_at] || Time.now.utc
    end
  end

  class Post
    attr_accessor :id, :user_id, :title, :content, :status

    STATUSES = ['DRAFT', 'PUBLISHED'].freeze

    def initialize(attrs = {})
      @id = attrs[:id] || SecureRandom.uuid
      @user_id = attrs[:user_id]
      @title = attrs[:title]
      @content = attrs[:content]
      @status = attrs[:status] || 'DRAFT'
    end
  end
end

# --- Validation Service ---
module Validators
  class BaseValidator
    attr_reader :errors

    def initialize(record)
      @record = record
      @errors = {}
    end

    def valid?
      @errors.clear
      validate
      @errors.empty?
    end

    private

    def add_error(field, message)
      (@errors[field] ||= []) << message
    end

    def validate_required(field)
      value = @record.public_send(field)
      add_error(field, 'is required') if value.nil? || (value.is_a?(String) && value.strip.empty?)
    end

    def validate_uuid(field)
      value = @record.public_send(field)
      return if value.nil?
      add_error(field, 'is not a valid UUID') unless value.match?(/\A[0-9a-f]{8}-([0-9a-f]{4}-){3}[0-9a-f]{12}\z/i)
    end
  end

  class UserValidator < BaseValidator
    EMAIL_REGEX = /\A[\w+\-.]+@[a-z\d\-]+(\.[a-z\d\-]+)*\.[a-z]+\z/i

    def validate
      validate_required(:email)
      validate_required(:password_hash)
      validate_uuid(:id)

      # Custom email validation
      if @record.email && !@record.email.match?(EMAIL_REGEX)
        add_error(:email, 'is not a valid email address')
      end

      # Enum validation
      if @record.role && !Models::User::ROLES.include?(@record.role)
        add_error(:role, "must be one of: #{Models::User::ROLES.join(', ')}")
      end

      # Type validation/coercion check
      unless [true, false].include?(@record.is_active)
        add_error(:is_active, 'must be a boolean (true or false)')
      end
    end
  end

  class PostValidator < BaseValidator
    def validate
      validate_required(:user_id)
      validate_required(:title)
      validate_uuid(:id)
      validate_uuid(:user_id)

      # Custom length validator
      if @record.title && @record.title.length > 255
        add_error(:title, 'cannot be longer than 255 characters')
      end

      # Enum validation
      if @record.status && !Models::Post::STATUSES.include?(@record.status)
        add_error(:status, "must be one of: #{Models::Post::STATUSES.join(', ')}")
      end
    end
  end
end

# --- Serialization Service ---
module Serializers
  class JsonSerializer
    def self.serialize(record)
      hash = record.instance_variables.each_with_object({}) do |var, h|
        key = var.to_s.delete('@').to_sym
        value = record.instance_variable_get(var)
        h[key] = value.is_a?(Time) ? value.utc.iso8601 : value
      end
      JSON.generate(hash)
    end

    def self.deserialize(json_string, model_class)
      data = JSON.parse(json_string, symbolize_names: true)
      # Type Coercion
      if data[:created_at] && data[:created_at].is_a?(String)
        data[:created_at] = Time.parse(data[:created_at])
      end
      if data.key?(:is_active) && !([true, false].include?(data[:is_active]))
         data[:is_active] = ['true', '1'].include?(data[:is_active].to_s.downcase)
      end
      model_class.new(data)
    end
  end

  class XmlSerializer
    def self.serialize(record, root_name)
      doc = REXML::Document.new
      root = doc.add_element(root_name)
      record.instance_variables.each do |var|
        key = var.to_s.delete('@')
        value = record.instance_variable_get(var)
        element = root.add_element(key)
        element.text = value.is_a?(Time) ? value.utc.iso8601 : value
      end
      doc.to_s
    end

    def self.deserialize(xml_string, model_class)
      doc = REXML::Document.new(xml_string)
      attrs = {}
      doc.root.elements.each do |el|
        key = el.name.to_sym
        value = el.text
        # Type Coercion
        value = Time.parse(value) if key == :created_at && value
        value = ['true', '1'].include?(value.to_s.downcase) if key == :is_active
        attrs[key] = value
      end
      model_class.new(attrs)
    end
  end
end

# --- Main Execution ---
if __FILE__ == $0
  puts "--- Variation 1: OOP with Service Objects ---"

  # 1. Create and Validate a User
  user_data = {
    email: 'test@example.com',
    password_hash: 'abc123xyz',
    is_active: true,
    role: 'ADMIN'
  }
  user = Models::User.new(user_data)
  user_validator = Validators::UserValidator.new(user)

  if user_validator.valid?
    puts "User is valid."
  else
    puts "User validation failed: #{user_validator.errors}"
  end

  # 2. JSON Serialization/Deserialization
  user_json = Serializers::JsonSerializer.serialize(user)
  puts "\nUser JSON: #{user_json}"
  deserialized_user = Serializers::JsonSerializer.deserialize(user_json, Models::User)
  puts "Deserialized User Email: #{deserialized_user.email}"
  puts "Deserialized User Created At (Type): #{deserialized_user.created_at.class}"

  # 3. XML Serialization/Deserialization
  user_xml = Serializers::XmlSerializer.serialize(user, 'user')
  puts "\nUser XML: #{user_xml}"
  deserialized_user_xml = Serializers::XmlSerializer.deserialize(user_xml, Models::User)
  puts "Deserialized User from XML Is Active: #{deserialized_user_xml.is_active}"
  puts "Deserialized User from XML Is Active (Type): #{deserialized_user_xml.is_active.class}"


  # 4. Demonstrate Invalid Post
  invalid_post = Models::Post.new(user_id: 'not-a-uuid', title: '')
  post_validator = Validators::PostValidator.new(invalid_post)
  unless post_validator.valid?
    puts "\nInvalid post validation errors: #{post_validator.errors}"
  end
end