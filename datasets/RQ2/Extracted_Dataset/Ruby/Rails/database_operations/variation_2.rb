# frozen_string_literal: true

# This variation represents the "Service Object" developer.
# Business logic is extracted from controllers and models into dedicated service classes.
# This promotes "thin controllers, thin models" and makes complex operations
# more testable and reusable. It also introduces a Query Object for filtering.

# --- BOILERPLATE FOR STANDALONE EXECUTION ---
require 'active_record'
require 'securerandom'

# Setup in-memory SQLite database
ActiveRecord::Base.establish_connection(adapter: 'sqlite3', database: ':memory:')
ActiveRecord::Base.logger = nil # Suppress logs

# --- MIGRATIONS ---
def run_migrations
  ActiveRecord::Schema.define do
    enable_extension 'pgcrypto' unless extension_enabled?('pgcrypto')

    create_table :users, id: :uuid do |t|
      t.string :email, null: false, index: { unique: true }
      t.string :password_digest, null: false
      t.boolean :is_active, default: true, null: false
      t.timestamps
    end

    create_table :roles, id: :uuid do |t|
      t.string :name, null: false, index: { unique: true }
      t.timestamps
    end

    create_table :user_roles, id: false do |t|
      t.references :user, type: :uuid, null: false, foreign_key: true
      t.references :role, type: :uuid, null: false, foreign_key: true
      t.index [:user_id, :role_id], unique: true
    end

    create_table :posts, id: :uuid do |t|
      t.references :user, type: :uuid, null: false, foreign_key: true
      t.string :title, null: false
      t.text :content
      t.integer :status, default: 0, null: false
      t.timestamps
    end
  end
end

# --- MODELS (Leaner) ---

# app/models/application_record.rb
class ApplicationRecord < ActiveRecord::Base
  self.abstract_class = true
  before_create -> { self.id = SecureRandom.uuid if self.id.nil? }
end

# app/models/role.rb
class Role < ApplicationRecord
  has_many :user_roles, dependent: :destroy
  has_many :users, through: :user_roles
  validates :name, presence: true, uniqueness: true
end

# app/models/user_role.rb
class UserRole < ApplicationRecord
  belongs_to :user
  belongs_to :role
end

# app/models/user.rb
class User < ApplicationRecord
  has_secure_password
  has_many :posts, dependent: :destroy
  has_many :user_roles, dependent: :destroy
  has_many :roles, through: :user_roles
  validates :email, presence: true, uniqueness: true
end

# app/models/post.rb
class Post < ApplicationRecord
  belongs_to :user
  enum status: { DRAFT: 0, PUBLISHED: 1 }
  validates :title, presence: true
end

# --- QUERY OBJECT ---

# app/queries/user_query.rb
class UserQuery
  attr_reader :relation

  def initialize(relation = User.all)
    @relation = relation.includes(:roles, :posts)
  end

  def find_by_id(id)
    @relation.find(id)
  end

  def active_users
    @relation = @relation.where(is_active: true)
    self
  end

  def with_role(role_name)
    @relation = @relation.joins(:roles).where(roles: { name: role_name })
    self
  end
end

# --- SERVICE OBJECTS ---

# app/services/user_management_service.rb
class UserManagementService
  class CreationError < StandardError; end

  def self.create_user_with_post(user_params:, post_params:, role_name: 'USER')
    user = User.new(user_params)
    role = Role.find_by(name: role_name)
    raise CreationError, "Role '#{role_name}' not found." unless role

    ActiveRecord::Base.transaction do
      user.save!
      user.roles << role
      user.posts.create!(post_params)
    end
    user
  rescue ActiveRecord::RecordInvalid => e
    raise CreationError, e.message
  end

  def self.deactivate_and_anonymize(user_id:)
    user = User.find(user_id)
    ActiveRecord::Base.transaction do
      user.update!(is_active: false, email: "anonymized_#{user.id}@example.com")
      # Potentially delete or anonymize posts
      user.posts.update_all(title: "Content Removed", content: "Content Removed")
    end
    user
  end
end

# --- CONTROLLER (Simulated, very thin) ---

# app/controllers/users_controller.rb
class UsersController
  attr_accessor :params

  def initialize(params = {})
    @params = params
  end

  # GET /users
  def index
    query = UserQuery.new.active_users
    query = query.with_role(params[:role]) if params[:role]
    query.relation
  end

  # GET /users/:id
  def show
    UserQuery.new.find_by_id(params[:id])
  end

  # POST /users
  def create
    user = UserManagementService.create_user_with_post(
      user_params: user_params,
      post_params: post_params,
      role_name: 'USER'
    )
    # render json: user, status: :created
    user
  rescue UserManagementService::CreationError => e
    # render json: { error: e.message }, status: :unprocessable_entity
    { error: e.message }
  end

  # DELETE /users/:id
  def destroy
    UserManagementService.deactivate_and_anonymize(user_id: params[:id])
    # head :no_content
    { message: "User #{params[:id]} deactivated and anonymized." }
  end

  private

  def user_params
    params.slice(:email, :password)
  end

  def post_params
    params.slice(:title, :content)
  end
end

# --- DEMONSTRATION ---
if __FILE__ == $0
  run_migrations

  # Seed Roles
  Role.create!(name: 'ADMIN')
  Role.create!(name: 'USER')

  # --- Create Operation via Service Object ---
  puts "--- Create User via Service Object ---"
  create_params = {
    email: 'service.user@example.com',
    password: 'password123',
    title: 'My First Post via Service',
    content: 'This was created in a transaction.'
  }
  controller = UsersController.new(create_params)
  result = controller.create

  if result.is_a?(User)
    puts "User created: #{result.email}"
    puts "User roles: #{result.roles.pluck(:name)}"
    puts "User posts: #{result.posts.first.title}"
  else
    puts "Creation failed: #{result[:error]}"
  end

  # --- Transaction Rollback Example (handled by service) ---
  puts "\n--- Transaction Rollback via Service ---"
  invalid_params = {
    email: 'invalid.user@example.com',
    password: 'password123',
    title: 'This should not be created',
    content: '' # This will fail validation
  }
  controller = UsersController.new(invalid_params)
  result = controller.create
  puts "Creation failed as expected: #{result[:error]}"
  puts "User 'invalid.user@example.com' exists? #{User.exists?(email: 'invalid.user@example.com')}"

  # --- Querying via Query Object ---
  puts "\n--- Querying via Query Object ---"
  controller.params = { role: 'USER' }
  users = controller.index
  puts "Found #{users.count} active user(s) with role 'USER'."

  # --- Delete/Anonymize via Service Object ---
  puts "\n--- Deleting/Anonymizing via Service ---"
  user_to_delete = User.first
  controller.params = { id: user_to_delete.id }
  puts controller.destroy[:message]
  user_to_delete.reload
  puts "User is now inactive: #{!user_to_delete.is_active}"
  puts "User email is anonymized: #{user_to_delete.email}"
end