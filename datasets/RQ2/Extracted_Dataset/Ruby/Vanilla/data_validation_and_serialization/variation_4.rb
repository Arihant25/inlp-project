# Variation 4: Schema-Driven Generic Validator
# This approach uses a single, powerful validator class that takes a declarative
# schema (defined as a Hash). It's highly reusable and separates the validation
# rules (the "what") from the validation logic (the "how").

require 'json'
require 'rexml/document'
require 'securerandom'
require 'time'

class SchemaValidator
  attr_reader :errors

  EMAIL_REGEX = /\A[\w+\-.]+@[a-z\d\-]+(\.[a-z\d\-]+)*\.[a-z]+\z/i
  UUID_REGEX = /\A[0-9a-f]{8}-([0-9a-f]{4}-){3}[0-9a-f]{12}\z/i

  def initialize(schema)
    @schema = schema
    @errors = {}
    @data = {}
  end

  def validate(input_data)
    @errors.clear
    @data = input_data.transform_keys(&:to_sym)
    coerced_data = {}

    @schema.each do |field, rules|
      value = @data[field]

      # Rule: Required
      if rules[:required] && (value.nil? || value.to_s.strip.empty?)
        add_error(field, 'is required')
        next # No point in validating a missing required field further
      end
      
      # Skip validation for non-required empty fields
      next if value.nil?

      # Rule: Type Coercion & Validation
      if rules[:type]
        coerced_value = coerce(value, rules[:type])
        if coerced_value.nil? && value.nil? == false
          add_error(field, "must be a valid #{rules[:type]}")
          next
        end
        value = coerced_value
      end
      
      # Rule: Format
      if rules[:format] && !value.to_s.match?(rules[:format])
        add_error(field, 'has an invalid format')
      end

      # Rule: Enum
      if rules[:enum] && !rules[:enum].include?(value)
        add_error(field, "must be one of: #{rules[:enum].join(', ')}")
      end
      
      coerced_data[field] = value
    end

    [@errors.empty?, coerced_data.merge(@data)]
  end

  private

  def add_error(field, message)
    (@errors[field] ||= []) << message
  end

  def coerce(value, type)
    case type
    when :string    then value.to_s
    when :uuid      then value.to_s.match?(UUID_REGEX) ? value : nil
    when :email     then value.to_s.match?(EMAIL_REGEX) ? value : nil
    when :boolean
      return true if [true, 'true', 1, '1'].include?(value)
      return false if [false, 'false', 0, '0'].include?(value)
      nil
    when :timestamp
      begin
        value.is_a?(Time) ? value : Time.parse(value.to_s)
      rescue ArgumentError
        nil
      end
    else
      value
    end
  end
end

# --- Schemas and Serializers ---
module Schemas
  USER_SCHEMA = {
    id:            { type: :uuid },
    email:         { type: :email, required: true },
    password_hash: { type: :string, required: true },
    role:          { type: :string, enum: ['ADMIN', 'USER'] },
    is_active:     { type: :boolean },
    created_at:    { type: :timestamp }
  }

  POST_SCHEMA = {
    id:      { type: :uuid },
    user_id: { type: :uuid, required: true },
    title:   { type: :string, required: true },
    content: { type: :string },
    status:  { type: :string, enum: ['DRAFT', 'PUBLISHED'] }
  }
end

module DataSerializer
  def self.to_json(data)
    JSON.generate(data) do |obj|
      obj.is_a?(Time) ? obj.utc.iso8601 : obj.as_json
    end
  end

  def self.from_json(json_string)
    JSON.parse(json_string, symbolize_names: true)
  end

  def self.to_xml(data, root_name)
    doc = REXML::Document.new
    root = doc.add_element(root_name)
    data.each do |key, value|
      root.add_element(key.to_s).text = value.is_a?(Time) ? value.utc.iso8601 : value.to_s
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
  puts "--- Variation 4: Schema-Driven Generic Validator ---"

  # 1. Create and Validate a User using the schema
  user_data = {
    email: 'schema.user@example.com',
    password_hash: 'some_hash',
    is_active: 'true', # String to test coercion
    role: 'ADMIN',
    created_at: '2023-10-27T10:00:00Z'
  }

  user_validator = SchemaValidator.new(Schemas::USER_SCHEMA)
  is_valid, processed_user = user_validator.validate(user_data)

  if is_valid
    puts "User is valid."
    puts "Coerced is_active: #{processed_user[:is_active]} (type: #{processed_user[:is_active].class})"
    puts "Coerced created_at: #{processed_user[:created_at]} (type: #{processed_user[:created_at].class})"
  else
    puts "User validation failed: #{user_validator.errors}"
  end

  # 2. JSON Serialization
  user_json = DataSerializer.to_json(processed_user)
  puts "\nUser JSON: #{user_json}"

  # 3. XML Serialization
  user_xml = DataSerializer.to_xml(processed_user, 'user')
  puts "\nUser XML: #{user_xml}"

  # 4. Demonstrate Invalid Post
  invalid_post_data = { user_id: '123', title: nil, status: 'REVIEW' }
  post_validator = SchemaValidator.new(Schemas::POST_SCHEMA)
  is_post_valid, _ = post_validator.validate(invalid_post_data)
  unless is_post_valid
    puts "\nInvalid post validation errors: #{post_validator.errors}"
  end
  
  # 5. Deserialization and Re-validation
  raw_from_xml = DataSerializer.from_xml(user_xml)
  puts "\nRaw data from XML: #{raw_from_xml}"
  is_valid_again, final_user = user_validator.validate(raw_from_xml)
  puts "Is data from XML valid after re-validation and coercion? #{is_valid_again}"
  puts "Final user role: #{final_user[:role]}"
end