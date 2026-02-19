<raw_code>
# frozen_string_literal: true

# This variation demonstrates the "Classic Rails Way".
# Logic is primarily placed in the models and controllers.
# It uses standard features like ActiveModel::Validations, respond_to, and as_json overrides.

# --- MOCK RAILS ENVIRONMENT SETUP ---
require 'active_record'
require 'action_controller'
require 'active_model_serializers' # Used for XML generation
require 'securerandom'

# In-memory database
ActiveRecord::Base.establish_connection(adapter: 'sqlite3', database: ':memory:')

# Schema definition
ActiveRecord::Schema.define do
  enable_extension 'pgcrypto' unless extension_enabled?('pgcrypto')

  create_table :users, id: :uuid do |t|
    t.string :email, null: false
    t.string :password_hash, null: false
    t.integer :role, default: 1, null: false # 0: ADMIN, 1: USER
    t.boolean :is_active, default: true
    t.string :phone_number
    t.timestamps
  end
  add_index :users, :email, unique: true

  create_table :posts, id: :uuid do |t|
    t.uuid :user_id, null: false
    t.string :title, null: false
    t.text :content
    t.integer :status, default: 0, null: false # 0: DRAFT, 1: PUBLISHED
    t.timestamps
  end
  add_foreign_key :posts, :users
end

# --- CUSTOM VALIDATOR ---
# FILE: app/validators/phone_number_validator.rb
class PhoneNumberValidator < ActiveModel::EachValidator
  def validate_each(record, attribute, value)
    # Simple North American phone number format validation
    return if value.blank?
    unless value.match?(/\A\+?1?\s*\(?\d{3}\)?[-.\s]?\d{3}[-.\s]?\d{4}\z/)
      record.errors.add(attribute, (options[:message] || "is not a valid phone number"))
    end
  end
end

# --- MODELS ---
# FILE: app/models/post.rb
class Post < ActiveRecord::Base
  belongs_to :user
  enum status: { DRAFT: 0, PUBLISHED: 1 }

  validates :title, presence: true, length: { minimum: 5 }
  validates :status, presence: true
end

# FILE: app/models/user.rb
class User < ActiveRecord::Base
  has_many :posts
  enum role: { ADMIN: 0, USER: 1 }

  # Input validation
  validates :email, presence: true, uniqueness: true, format: { with: URI::MailTo::EMAIL_REGEXP }
  validates :password_hash, presence: true
  validates :role, presence: true
  validates :phone_number, phone_number: true, allow_blank: true

  # Customizing JSON serialization
  def as_json(options = {})
    super(options.merge(
      only: [:id, :email, :role, :is_active, :created_at],
      include: {
        posts: {
          only: [:id, :title, :status]
        }
      }
    ))
  end

  # Customizing XML serialization
  def to_xml(options = {})
    super(options.merge(
      only: [:id, :email, :role, :is_active, :created_at],
      include: {
        posts: {
          only: [:id, :title, :status]
        }
      }
    ))
  end
end

# --- CONTROLLER ---
# FILE: app/controllers/users_controller.rb
class UsersController < ActionController::Base
  # Mocking a request object for demonstration
  class MockRequest
    attr_accessor :format, :raw_post
    def initialize(format, raw_post)
      @format = format
      @raw_post = raw_post
    end
    def content_type
      "application/#{format}"
    end
  end

  # JSON/XML Deserialization and Type Coercion happens here via Strong Parameters
  def create
    @user = User.new(user_params)
    if @user.save
      respond_to do |format|
        format.json { render json: @user, status: :created }
        format.xml  { render xml: @user, status: :created }
      end
    else
      # Error message formatting
      respond_to do |format|
        format.json { render json: { errors: @user.errors.full_messages }, status: :unprocessable_entity }
        format.xml  { render xml: { errors: @user.errors.full_messages }.to_xml(root: 'errors'), status: :unprocessable_entity }
      end
    end
  end

  def show
    @user = User.find(params[:id])
    respond_to do |format|
      format.json { render json: @user }
      format.xml  { render xml: @user }
    end
  rescue ActiveRecord::RecordNotFound
    respond_to do |format|
      format.json { render json: { error: "User not found" }, status: :not_found }
      format.xml  { render xml: { error: "User not found" }.to_xml(root: 'error'), status: :not_found }
    end
  end

  private

  def user_params
    # Strong parameters for security and type coercion
    # For example, `is_active: "0"` will be coerced to `false`
    params.require(:user).permit(:email, :password_hash, :role, :is_active, :phone_number)
  end

  # Helper to simulate a Rails request
  def self.simulate_request(action, params, format = :json)
    controller = new
    controller.params = ActionController::Parameters.new(params)
    controller.request = MockRequest.new(format, params.to_json)
    puts "--- Simulating #{action.upcase} request with #{format.upcase} ---"
    controller.send(action)
    puts "Response Body:\n#{controller.response_body.first}\n"
  end
end

# --- DEMONSTRATION ---
# 1. Successful JSON creation
json_payload = { user: { email: 'dev1@example.com', password_hash: 'hash123', phone_number: '1 (555) 123-4567', role: 'ADMIN' } }
UsersController.simulate_request(:create, json_payload, :json)

# 2. Unsuccessful XML creation (validation failure)
xml_payload = { user: { email: 'invalid-email', password_hash: '', phone_number: '555-123' } }
UsersController.simulate_request(:create, xml_payload, :xml)

# 3. Show user as XML
user = User.first
UsersController.simulate_request(:show, { id: user.id }, :xml) if user
</raw_code>