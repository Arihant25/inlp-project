<raw_code>
# frozen_string_literal: true

# This variation demonstrates the "Form Object" or "Service Object" pattern.
# A non-persisted ActiveModel class is used to handle complex validation and
# business logic for a specific action (e.g., user registration),
# separating these concerns from the main ActiveRecord model.

# --- MOCK RAILS ENVIRONMENT SETUP ---
require 'active_record'
require 'action_controller'
require 'active_model'
require 'securerandom'

ActiveRecord::Base.establish_connection(adapter: 'sqlite3', database: ':memory:')

ActiveRecord::Schema.define do
  enable_extension 'pgcrypto' unless extension_enabled?('pgcrypto')
  create_table :users, id: :uuid do |t|
    t.string :email, null: false
    t.string :password_hash, null: false
    t.integer :role, default: 1, null: false
    t.boolean :is_active, default: true
    t.timestamps
  end
  add_index :users, :email, unique: true
  create_table :posts, id: :uuid do |t|
    t.uuid :user_id, null: false
    t.string :title, null: false
    t.text :content
    t.integer :status, default: 0, null: false
    t.timestamps
  end
  add_foreign_key :posts, :users
end

# --- MODELS (kept lean) ---
# FILE: app/models/post.rb
class Post < ActiveRecord::Base
  belongs_to :user
  enum status: { DRAFT: 0, PUBLISHED: 1 }
  validates :title, presence: true
end

# FILE: app/models/user.rb
class User < ActiveRecord::Base
  has_many :posts
  enum role: { ADMIN: 0, USER: 1 }
  # Core data integrity validations remain
  validates :email, presence: true, uniqueness: true
  validates :password_hash, presence: true
end

# --- FORM OBJECT (DTO with Validations) ---
# FILE: app/forms/user_creation_form.rb
class UserCreationForm
  include ActiveModel::Model
  include ActiveModel::Attributes

  # Define attributes for the form, enabling type coercion
  attribute :email, :string
  attribute :password, :string
  attribute :password_confirmation, :string
  attribute :role, :string, default: 'USER'
  attribute :terms_of_service, :boolean

  # Expose the created user
  attr_reader :user

  # Input validations specific to the creation process
  validates :email, presence: true, format: { with: URI::MailTo::EMAIL_REGEXP }
  validates :password, presence: true, length: { minimum: 8 }, confirmation: true
  validates :terms_of_service, acceptance: true

  # Custom validator for role
  validate :role_must_be_valid

  def save
    return false unless valid?

    # Transaction ensures atomicity
    ActiveRecord::Base.transaction do
      @user = User.new(
        email: email,
        password_hash: "hashed_#{password}", # In real app, use BCrypt
        role: role.upcase,
        is_active: true
      )
      @user.save!
    end
    true
  rescue ActiveRecord::RecordInvalid => e
    # Promote model errors to the form object
    e.record.errors.each do |error|
      errors.add(error.attribute, error.message)
    end
    false
  end

  private

  def role_must_be_valid
    return if role.blank?
    unless User.roles.key?(role.upcase)
      errors.add(:role, "is not a valid role")
    end
  end
end

# --- CONTROLLER ---
# FILE: app/controllers/users_controller.rb
class UsersController < ActionController::Base
  # JSON Deserialization is handled by instantiating the form object
  def create
    @form = UserCreationForm.new(user_creation_params)

    if @form.save
      # Serialization is handled by the model's `to_json` or a serializer
      render json: @form.user, status: :created
    else
      # Error message formatting comes from the form object
      render json: { errors: @form.errors.messages }, status: :unprocessable_entity
    end
  end

  private

  def user_creation_params
    params.require(:user).permit(:email, :password, :password_confirmation, :role, :terms_of_service)
  end

  # Helper to simulate a Rails request
  def self.simulate_request(action, params)
    controller = new
    controller.params = ActionController::Parameters.new(params)
    puts "--- Simulating #{action.upcase} request ---"
    controller.send(action)
    puts "Response Body:\n#{controller.response_body.first}\n"
  end
end

# --- DEMONSTRATION ---
# 1. Successful creation
payload_success = {
  user: {
    email: 'dev2@example.com',
    password: 'password123',
    password_confirmation: 'password123',
    role: 'ADMIN',
    terms_of_service: '1' # Type coercion to true
  }
}
UsersController.simulate_request(:create, payload_success)

# 2. Validation failure (password mismatch, invalid role)
payload_fail = {
  user: {
    email: 'dev2-fail@example.com',
    password: 'password123',
    password_confirmation: 'password456',
    role: 'SUPERUSER',
    terms_of_service: '1'
  }
}
UsersController.simulate_request(:create, payload_fail)

# 3. Validation failure (terms of service not accepted)
payload_terms_fail = {
  user: {
    email: 'dev2-terms@example.com',
    password: 'password123',
    password_confirmation: 'password123',
    terms_of_service: '0' # Coerced to false
  }
}
UsersController.simulate_request(:create, payload_terms_fail)
</raw_code>