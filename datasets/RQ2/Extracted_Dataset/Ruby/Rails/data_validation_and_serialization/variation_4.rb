<raw_code>
# frozen_string_literal: true

# This variation demonstrates the "Concern & DRY" approach. Reusable logic for
# validation, serialization, and error handling is extracted into ActiveSupport::Concern
# modules. This keeps models and controllers thin and promotes code reuse.

# --- MOCK RAILS ENVIRONMENT SETUP ---
require 'active_record'
require 'action_controller'
require 'active_support/concern'
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

# --- CONCERNS ---
# FILE: app/models/concerns/json_serializable.rb
module JsonSerializable
  extend ActiveSupport::Concern

  included do
    # Define a class-level macro to configure serialization
    def self.serializes_as_json(options = {})
      define_method :as_json do |opts = {}|
        super(opts.merge(options))
      end
    end
  end
end

# FILE: app/controllers/concerns/error_formatter.rb
module ErrorFormatter
  extend ActiveSupport::Concern

  included do
    rescue_from ActiveRecord::RecordInvalid, with: :render_unprocessable_entity
    rescue_from ActiveRecord::RecordNotFound, with: :render_not_found
  end

  private

  def render_unprocessable_entity(exception)
    # Standardized error message formatting
    render json: {
      error: "Validation Failed",
      details: exception.record.errors.full_messages
    }, status: :unprocessable_entity
  end

  def render_not_found(exception)
    render json: {
      error: "Resource Not Found",
      details: "#{exception.model} with id=#{exception.id} not found"
    }, status: :not_found
  end
end

# --- MODELS (including concerns) ---
# FILE: app/models/post.rb
class Post < ActiveRecord::Base
  include JsonSerializable # Include serialization concern

  belongs_to :user
  enum status: { DRAFT: 0, PUBLISHED: 1 }
  validates :title, presence: true

  # Use the macro from the concern
  serializes_as_json only: [:id, :title, :status, :created_at]
end

# FILE: app/models/user.rb
class User < ActiveRecord::Base
  include JsonSerializable # Include serialization concern

  has_many :posts
  enum role: { ADMIN: 0, USER: 1 }

  validates :email, presence: true, uniqueness: true, format: { with: URI::MailTo::EMAIL_REGEXP }
  validates :password_hash, presence: true

  # Use the macro to define this model's JSON representation
  serializes_as_json only: [:id, :email, :role, :is_active], include: :posts
end

# --- CONTROLLERS (including concerns) ---
# FILE: app/controllers/application_controller.rb
class ApplicationController < ActionController::Base
  include ErrorFormatter # Include the centralized error handler
end

# FILE: app/controllers/users_controller.rb
class UsersController < ApplicationController
  # Controller is now very thin. It only defines actions and strong params.
  # Error handling and serialization are delegated to concerns and models.

  # XML Parsing and Generation demonstration
  def create
    # create! will raise an exception on failure, which is caught by the concern
    @user = User.create!(user_params)

    respond_to do |format|
      # JSON serialization is handled by the model's `as_json`
      format.json { render json: @user, status: :created }
      # XML generation uses Rails' default `to_xml`
      format.xml  { render xml: @user.to_xml(include: :posts), status: :created }
    end
  end

  def show
    @user = User.find(params[:id]) # find will raise RecordNotFound if not found
    render json: @user
  end

  private

  def user_params
    # Type coercion for boolean/enums happens here
    # This also handles deserialization from XML if Content-Type is application/xml
    params.require(:user).permit(:email, :password_hash, :role, :is_active)
  end

  # Helper to simulate a Rails request
  def self.simulate_request(action, params, format = :json)
    controller = new
    controller.params = ActionController::Parameters.new(params)
    # Mocking request object for `respond_to`
    controller.request = Class.new{ define_method(:format) { format } }.new
    puts "--- Simulating #{action.upcase} request with #{format.upcase} ---"
    controller.send(action)
    puts "Response Body:\n#{controller.response_body.first}\n"
  end
end

# --- DEMONSTRATION ---
# 1. Successful XML creation
xml_payload = { user: { email: 'concern-user@example.com', password_hash: 'hash123', role: 'ADMIN' } }
UsersController.simulate_request(:create, xml_payload, :xml)

# 2. Validation failure (handled by ErrorFormatter concern)
json_payload_fail = { user: { email: 'bad-email', password_hash: '' } }
UsersController.simulate_request(:create, json_payload_fail, :json)

# 3. Not Found failure (handled by ErrorFormatter concern)
UsersController.simulate_request(:show, { id: SecureRandom.uuid }, :json)
</raw_code>