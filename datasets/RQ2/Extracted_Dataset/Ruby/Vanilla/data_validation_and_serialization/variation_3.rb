# Variation 3: Struct-based Models with Mixins
# This approach uses Ruby's lightweight Struct for data objects and includes
# validation and serialization logic via modules (mixins). It offers a good
# balance between full-blown classes and simple hashes, promoting code reuse.

require 'json'
require 'rexml/document'
require 'securerandom'
require 'time'

module Validatable
  attr_reader :errors

  # Custom validator for phone numbers (not in schema, but demonstrates custom logic)
  def self.validate_phone_number(value)
    return true if value.nil? # Not required
    value.match?(/\A\+?\d{1,3}[-.\s]?\(?\d{1,3}\)?[-.\s]?\d{1,4}[-.\s]?\d{1,4}[-.\s]?\d{1,9}\z/)
  end

  def valid?
    @errors = {}
    self.class.validations.each do |field, rules|
      value = self[field]
      rules.each do |rule, options|
        case rule
        when :required
          add_error(field, 'is required') if value.nil? || (value.is_a?(String) && value.strip.empty?)
        when :format
          add_error(field, "has an invalid format") unless value.to_s.match?(options)
        when :inclusion
          add_error(field, "is not in the list of allowed values") unless options.include?(value)
        when :type
          add_error(field, "must be of type #{options}") unless value.is_a?(options)
        when :custom
          # options is a lambda/proc
          error_message = options.call(value)
          add_error(field, error_message) if error_message
        end
      end
    end
    @errors.empty?
  end

  private

  def add_error(field, message)
    (@errors[field] ||= []) << message
  end

  module ClassMethods
    def validations
      @validations ||= {}
    end

    def validates(field, rules)
      validations[field] = rules
    end
  end

  def self.included(base)
    base.extend(ClassMethods)
  end
end

module Serializable
  def to_h
    self.members.each_with_object({}) do |member, hash|
      value = self[member]
      hash[member] = value.is_a?(Time) ? value.utc.iso8601 : value
    end
  end

  def to_json(*_args)
    JSON.generate(to_h)
  end

  def to_xml(root_name)
    doc = REXML::Document.new
    root = doc.add_element(root_name)
    to_h.each do |key, value|
      root.add_element(key.to_s).text = value.to_s
    end
    doc.to_s
  end
end

# --- Domain Models as Structs ---
User = Struct.new(:id, :email, :password_hash, :role, :is_active, :created_at, :phone_number) do
  include Validatable
  include Serializable

  # A DSL-like way to define validations
  validates :id,            required: true, format: /\A[0-9a-f]{8}-([0-9a-f]{4}-){3}[0-9a-f]{12}\z/i
  validates :email,         required: true, format: /\A[\w+\-.]+@[a-z\d\-]+(\.[a-z\d\-]+)*\.[a-z]+\z/i
  validates :password_hash, required: true
  validates :role,          inclusion: ['ADMIN', 'USER']
  validates :is_active,     type: [TrueClass, FalseClass].include?(self.class) ? self.class : "Boolean" # Hack for older ruby
  validates :phone_number,  custom: ->(v) { "is not a valid phone number" unless Validatable.validate_phone_number(v) }


  def initialize(attrs = {})
    super(
      attrs[:id] || SecureRandom.uuid,
      attrs[:email],
      attrs[:password_hash],
      attrs[:role] || 'USER',
      attrs.key?(:is_active) ? self.class.coerce_bool(attrs[:is_active]) : false,
      attrs[:created_at] ? self.class.coerce_time(attrs[:created_at]) : Time.now.utc,
      attrs[:phone_number]
    )
  end

  def self.coerce_bool(val)
    return true if [true, 'true', 1, '1'].include?(val)
    return false if [false, 'false', 0, '0'].include?(val)
    val
  end

  def self.coerce_time(val)
    val.is_a?(String) ? Time.parse(val) : val
  end
end

Post = Struct.new(:id, :user_id, :title, :content, :status) do
  include Validatable
  include Serializable

  validates :id,      required: true, format: /\A[0-9a-f]{8}-([0-9a-f]{4}-){3}[0-9a-f]{12}\z/i
  validates :user_id, required: true, format: /\A[0-9a-f]{8}-([0-9a-f]{4}-){3}[0-9a-f]{12}\z/i
  validates :title,   required: true
  validates :status,  inclusion: ['DRAFT', 'PUBLISHED']

  def initialize(attrs = {})
    super(
      attrs[:id] || SecureRandom.uuid,
      attrs[:user_id],
      attrs[:title],
      attrs[:content],
      attrs[:status] || 'DRAFT'
    )
  end
end

# --- Main Execution ---
if __FILE__ == $0
  puts "--- Variation 3: Struct-based Models with Mixins ---"

  # 1. Create and Validate a User
  user = User.new(
    email: 'struct.user@example.com',
    password_hash: 'a_very_secure_hash',
    is_active: '1', # Test coercion
    phone_number: '+1 (555) 123-4567'
  )

  if user.valid?
    puts "User is valid. Phone: #{user.phone_number}"
  else
    puts "User validation failed: #{user.errors}"
  end

  # 2. JSON Serialization
  puts "\nUser JSON: #{user.to_json}"

  # 3. XML Serialization
  puts "\nUser XML: #{user.to_xml('user')}"

  # 4. Demonstrate Invalid Post
  invalid_post = Post.new(user_id: user.id, title: '', status: 'PENDING')
  unless invalid_post.valid?
    puts "\nInvalid post validation errors: #{invalid_post.errors}"
  end
  
  # 5. Deserialization (manual mapping)
  json_data = JSON.parse(user.to_json, symbolize_names: true)
  rehydrated_user = User.new(json_data)
  puts "\nRehydrated user from JSON is valid? #{rehydrated_user.valid?}"
  puts "Rehydrated user created_at type: #{rehydrated_user.created_at.class}"
end