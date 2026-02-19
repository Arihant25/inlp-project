# frozen_string_literal: true

# This variation represents the "Standard Rails Way" developer.
# Logic is kept within the conventional places: models and controllers.
# It uses standard ActiveRecord features like scopes, enums, and associations.
# The code is straightforward and follows Rails conventions closely.

# --- BOILERPLATE FOR STANDALONE EXECUTION ---
require 'active_record'
require 'securerandom'

# Setup in-memory SQLite database
ActiveRecord::Base.establish_connection(adapter: 'sqlite3', database: ':memory:')
ActiveRecord::Base.logger = nil # Suppress logs

# --- MIGRATIONS ---

# Helper to define migrations and run them
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
      t.integer :status, default: 0, null: false # DRAFT: 0, PUBLISHED: 1
      t.timestamps
    end
  end
end

# --- MODELS ---

# app/models/application_record.rb
class ApplicationRecord < ActiveRecord::Base
  self.abstract_class = true
  before_create -> { self.id = SecureRandom.uuid if self.id.nil? }
end

# app/models/role.rb
class Role < ApplicationRecord
  has_many :user_roles, dependent: :destroy
  has_many :users, through: :user_roles

  validates :name, presence: true, uniqueness: true, inclusion: { in: %w[ADMIN USER] }
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

  validates :email, presence: true, uniqueness: true, format: { with: URI::MailTo::EMAIL_REGEXP }

  scope :active, -> { where(is_active: true) }
  scope :with_role, ->(role_name) { joins(:roles).where(roles: { name: role_name }) }

  def admin?
    roles.exists?(name: 'ADMIN')
  end
end

# app/models/post.rb
class Post < ApplicationRecord
  belongs_to :user

  enum status: { DRAFT: 0, PUBLISHED: 1 }

  validates :title, presence: true, length: { minimum: 5 }
  validates :content, presence: true
end

# --- CONTROLLER (Simulated) ---

# app/controllers/users_controller.rb
class UsersController
  # Represents params from an HTTP request
  attr_accessor :params

  def initialize(params = {})
    @params = params
  end

  # GET /users
  # Demonstrates query building with filters
  def index
    users = User.active.includes(:roles, :posts)
    users = users.with_role(params[:role]) if params[:role]
    # In a real controller: render json: users
    users
  end

  # GET /users/:id
  def show
    User.find(params[:id])
  end

  # POST /users
  # Demonstrates a transaction for creating related objects
  def create
    user = User.new(user_params)
    default_role = Role.find_by!(name: 'USER')

    User.transaction do
      user.save!
      user.roles << default_role
    end
    # In a real controller: render json: user, status: :created
    user
  rescue ActiveRecord::RecordInvalid, ActiveRecord::RecordNotFound => e
    # In a real controller: render json: { errors: e.message }, status: :unprocessable_entity
    { errors: e.message }
  end

  # PATCH /users/:id
  def update
    user = User.find(params[:id])
    user.update!(user_params)
    user
  end

  # DELETE /users/:id
  def destroy
    user = User.find(params[:id])
    user.destroy!
    # In a real controller: head :no_content
    { message: "User #{user.id} deleted" }
  end

  private

  def user_params
    # Simulates strong parameters
    params.slice(:email, :password)
  end
end


# --- DEMONSTRATION ---
if __FILE__ == $0
  run_migrations

  # Seed Roles
  admin_role = Role.create!(name: 'ADMIN')
  user_role = Role.create!(name: 'USER')

  # --- CRUD Operations ---
  puts "--- CRUD Operations ---"
  users_controller = UsersController.new({ email: 'test@example.com', password: 'password123' })
  new_user = users_controller.create
  puts "User created: #{new_user.email}, Roles: #{new_user.roles.pluck(:name)}"

  # --- Read/Query ---
  puts "\n--- Querying ---"
  users_controller.params = { role: 'USER' }
  filtered_users = users_controller.index
  puts "Found #{filtered_users.count} user(s) with role 'USER'."

  # --- Update ---
  puts "\n--- Updating ---"
  users_controller.params = { id: new_user.id, email: 'updated@example.com' }
  updated_user = users_controller.update
  puts "User updated. New email: #{updated_user.email}"

  # --- One-to-Many Relationship ---
  puts "\n--- One-to-Many: User creates a Post ---"
  post = new_user.posts.create!(title: 'My First Post', content: 'This is the content.', status: :PUBLISHED)
  puts "Post '#{post.title}' created for user #{new_user.email}. Status: #{post.status}"
  puts "User has #{new_user.posts.count} post(s)."

  # --- Many-to-Many Relationship ---
  puts "\n--- Many-to-Many: Assigning a new role ---"
  new_user.roles << admin_role
  puts "User roles are now: #{new_user.roles.pluck(:name)}"

  # --- Transaction Rollback Example ---
  puts "\n--- Transaction Rollback ---"
  begin
    User.transaction do
      u = User.create!(email: 'rollback@example.com', password: 'password')
      puts "Created user inside transaction: #{u.email}"
      # This will fail because the role does not exist, causing a rollback.
      u.roles << Role.find_by!(name: 'NON_EXISTENT_ROLE')
    end
  rescue ActiveRecord::RecordNotFound => e
    puts "Transaction failed: #{e.message}"
  end
  puts "User 'rollback@example.com' exists? #{User.exists?(email: 'rollback@example.com')}" # Should be false

  # --- Delete ---
  puts "\n--- Deleting ---"
  users_controller.params = { id: new_user.id }
  puts users_controller.destroy[:message]
  puts "Total users now: #{User.count}"
end