# Variation 2: Functional/Module-based Approach
# This variation avoids classes for models and instead uses Hashes to represent data.
# Logic is grouped into modules containing pure functions for validation and serialization.
# This style is common in scripts or services where stateful objects are less necessary.

require 'json'
require 'rexml/document'
require 'securerandom'
require 'time'

module UserSchema
  ROLES = ['ADMIN', 'USER'].freeze
  EMAIL_REGEX = /\A[\w+\-.]+@[a-z\d\-]+(\.[a-z\d\-]+)*\.[a-z]+\z/i
  UUID_REGEX = /\A[0-9a-f]{8}-([0-9a-f]{4}-){3}[0-9a-f]{12}\z/i

  def self.validate(user_hash)
    errors = {}
    
    # Required fields
    [:email, :password_hash].each do |field|
      if user_hash[field].nil? || user_hash[field].to_s.strip.empty?
        (errors[field] ||= []) << 'is required'
      end
    end

    # Format validation
    if user_hash[:email] && !user_hash[:email].match?(EMAIL_REGEX)
      (errors[:email] ||= []) << 'is not a valid email address'
    end
    if user_hash[:id] && !user_hash[:id].match?(UUID_REGEX)
      (errors[:id] ||= []) << 'is not a valid UUID'
    end

    # Enum validation
    if user_hash[:role] && !ROLES.include?(user_hash[:role])
      (errors[:role] ||= []) << "must be one of: #{ROLES.join(', ')}"
    end

    # Type check
    if user_hash.key?(:is_active) && ![true, false].include?(user_hash[:is_active])
      (errors[:is_active] ||= []) << 'must be a boolean'
    end

    [errors.empty?, errors]
  end

  def self.coerce(user_hash)
    coerced = user_hash.dup
    coerced[:id] ||= SecureRandom.uuid
    coerced[:role] ||= 'USER'
    coerced[:is_active] = false unless coerced.key?(:is_active)
    coerced[:created_at] ||= Time.now.utc

    # Coerce string to boolean
    if coerced[:is_active].is_a?(String)
      coerced[:is_active] = ['true', '1'].include?(coerced[:is_active].downcase)
    end
    
    # Coerce string to time
    if coerced[:created_at].is_a?(String)
      begin
        coerced[:created_at] = Time.parse(coerced[:created_at])
      rescue ArgumentError
        # In a real app, you'd add an error here
      end
    end
    coerced
  end
end

module PostSchema
  STATUSES = ['DRAFT', 'PUBLISHED'].freeze
  UUID_REGEX = /\A[0-9a-f]{8}-([0-9a-f]{4}-){3}[0-9a-f]{12}\z/i

  def self.validate(post_hash)
    errors = {}
    [:user_id, :title].each do |field|
      if post_hash[field].nil? || post_hash[field].to_s.strip.empty?
        (errors[field] ||= []) << 'is required'
      end
    end

    if post_hash[:id] && !post_hash[:id].match?(UUID_REGEX)
      (errors[:id] ||= []) << 'is not a valid UUID'
    end
    if post_hash[:user_id] && !post_hash[:user_id].match?(UUID_REGEX)
      (errors[:user_id] ||= []) << 'is not a valid UUID'
    end

    if post_hash[:status] && !STATUSES.include?(post_hash[:status])
      (errors[:status] ||= []) << "must be one of: #{STATUSES.join(', ')}"
    end
    [errors.empty?, errors]
  end
end

module Serializer
  def self.to_json(data_hash)
    # Custom converter to handle Time objects
    JSON.generate(data_hash) do |obj|
      obj.is_a?(Time) ? obj.utc.iso8601 : obj.as_json
    end
  end

  def self.from_json(json_string)
    JSON.parse(json_string, symbolize_names: true)
  end

  def self.to_xml(data_hash, root_name)
    doc = REXML::Document.new
    root = doc.add_element(root_name.to_s)
    data_hash.each do |key, value|
      element = root.add_element(key.to_s)
      element.text = value.is_a?(Time) ? value.utc.iso8601 : value.to_s
    end
    doc.to_s
  end

  def self.from_xml(xml_string)
    doc = REXML::Document.new(xml_string)
    doc.root.elements.each_with_object({}) do |el, hash|
      hash[el.name.to_sym] = el.text
    end
  end
end

# --- Main Execution ---
if __FILE__ == $0
  puts "--- Variation 2: Functional/Module-based ---"

  # 1. Create and Validate a User hash
  user_data = {
    email: 'functional.dev@example.com',
    password_hash: 'xyz987',
    role: 'USER',
    is_active: 'true' # String to test coercion
  }
  
  coerced_user = UserSchema.coerce(user_data)
  is_valid, errors = UserSchema.validate(coerced_user)

  if is_valid
    puts "User data is valid. Coerced active status: #{coerced_user[:is_active]} (#{coerced_user[:is_active].class})"
  else
    puts "User data validation failed: #{errors}"
  end

  # 2. JSON Serialization/Deserialization
  user_json = Serializer.to_json(coerced_user)
  puts "\nUser JSON: #{user_json}"
  deserialized_user_data = Serializer.from_json(user_json)
  puts "Deserialized User Email: #{deserialized_user_data[:email]}"

  # 3. XML Serialization/Deserialization
  user_xml = Serializer.to_xml(coerced_user, 'user')
  puts "\nUser XML: #{user_xml}"
  deserialized_from_xml = Serializer.from_xml(user_xml)
  # Note: XML deserialization here is naive and returns all strings. Coercion would be a separate step.
  coerced_from_xml = UserSchema.coerce(deserialized_from_xml)
  puts "Deserialized & Coerced User from XML Created At: #{coerced_from_xml[:created_at].class}"

  # 4. Demonstrate Invalid Post
  invalid_post_data = { user_id: SecureRandom.uuid, title: nil, status: 'ARCHIVED' }
  is_post_valid, post_errors = PostSchema.validate(invalid_post_data)
  unless is_post_valid
    puts "\nInvalid post data validation errors: #{post_errors}"
  end
end