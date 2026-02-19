# frozen_string_literal: true

# This variation represents a "Modern/Functional-ish" developer.
# This style prefers explicit, exception-free flow control using Result objects
# (a concept from functional programming, similar to monads).
# Business logic is encapsulated in service objects that return Success or Failure,
# which the controller then unpacks. It also demonstrates Arel for complex,
# type-safe query construction.

# --- BOILERPLATE FOR STANDALONE EXECUTION ---
require 'active_record'
require 'securerandom'
require 'arel'

# Setup in-memory SQLite database
ActiveRecord::Base.establish_connection(adapter: 'sqlite3', database: ':memory:')
ActiveRecord::Base.logger = nil

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

# --- FUNCTIONAL RESULT MONAD (Simplified) ---
module Result
  Success = Struct.new(:value) do
    def success? = true
    def failure? = false
  end

  Failure = Struct.new(:error) do
    def success? = false
    def failure? = true
  end
end

# --- MODELS ---
class ApplicationRecord < ActiveRecord::Base
  self.abstract_class = true
  before_create -> { self.id = SecureRandom.uuid if self.id.nil? }
end

class Role < ApplicationRecord
  has_many :user_roles, dependent: :destroy
  has_many :users, through: :user_roles
  validates :name, presence: true, uniqueness: true
end

class UserRole < ApplicationRecord
  belongs_to :user
  belongs_to :role
end

class User < ApplicationRecord
  has_secure_password
  has_many :posts, dependent: :destroy
  has_many :user_roles, dependent: :destroy
  has_many :roles, through: :user_roles
  validates :email, presence: true, uniqueness: true

  # Demonstrates Arel for complex query building
  def self.find_admins_with_published_posts(title_keyword)
    users = Arel::Table.new(:users)
    roles = Arel::Table.new(:roles)
    user_roles = Arel::Table.new(:user_roles)
    posts = Arel::Table.new(:posts)

    query = users
      .join(user_roles).on(users[:id].eq(user_roles[:user_id]))
      .join(roles).on(user_roles[:role_id].eq(roles[:id]))
      .join(posts).on(users[:id].eq(posts[:user_id]))
      .where(roles[:name].eq('ADMIN'))
      .where(posts[:status].eq(Post.statuses[:PUBLISHED]))
      .where(posts[:title].matches("%#{title_keyword}%"))
      .project(users[Arel.star])
      .distinct

    User.find_by_sql(query.to_sql)
  end
end

class Post < ApplicationRecord
  belongs_to :user
  enum status: { DRAFT: 0, PUBLISHED: 1 }
  validates :title, presence: true, length: { minimum: 5 }
end

# --- SERVICE OBJECT with Result Pattern ---

# app/services/user_onboarding.rb
class UserOnboarding
  include Result

  def call(email:, password:, post_title:, post_content:)
    user = User.new(email: email, password: password)
    post = Post.new(title: post_title, content: post_content)
    role = Role.find_by(name: 'USER')

    return Failure.new("Default role 'USER' not found") unless role

    ActiveRecord::Base.transaction do
      user.save!
      user.roles << role
      user.posts << post
      post.save!
    end

    Success.new(user)
  rescue ActiveRecord::RecordInvalid => e
    # On validation failure, the transaction is rolled back automatically.
    Failure.new(e.record.errors.full_messages)
  end
end

# --- CONTROLLER (Simulated) ---

# app/controllers/users_controller.rb
class UsersController
  attr_accessor :params

  def initialize(params = {})
    @params = params
  end

  # POST /users
  def create
    service = UserOnboarding.new
    result = service.call(
      email: params[:email],
      password: params[:password],
      post_title: params[:post_title],
      post_content: params[:post_content]
    )

    # The controller handles the result without exceptions
    case result
    when Result::Success
      # render json: result.value, status: :created
      { status: :created, data: result.value }
    when Result::Failure
      # render json: { errors: result.error }, status: :unprocessable_entity
      { status: :unprocessable_entity, errors: result.error }
    end
  end
end

# --- DEMONSTRATION ---
if __FILE__ == $0
  run_migrations

  # Seed Roles
  Role.create!(name: 'ADMIN')
  Role.create!(name: 'USER')

  # --- Successful creation via Service Object ---
  puts "--- Successful Creation with Result Pattern ---"
  controller = UsersController.new(
    email: 'success@example.com',
    password: 'password123',
    post_title: 'My Onboarding Post',
    post_content: 'This was created successfully.'
  )
  response = controller.create
  puts "Response status: #{response[:status]}"
  puts "Created user: #{response[:data].email}"
  puts "User has post: #{response[:data].posts.first.title}"

  # --- Failed creation (transaction rollback) ---
  puts "\n--- Failed Creation (Rollback) with Result Pattern ---"
  controller = UsersController.new(
    email: 'fail@example.com',
    password: 'password123',
    post_title: 'Fail', # Too short, will fail validation
    post_content: 'This post will cause a rollback.'
  )
  response = controller.create
  puts "Response status: #{response[:status]}"
  puts "Errors: #{response[:errors]}"
  puts "User 'fail@example.com' exists? #{User.exists?(email: 'fail@example.com')}" # false
  puts "Post 'Fail' exists? #{Post.exists?(title: 'Fail')}" # false

  # --- Arel Query Demonstration ---
  puts "\n--- Arel Query Demonstration ---"
  admin_user = User.create!(email: 'admin@example.com', password: 'password')
  admin_user.roles << Role.find_by!(name: 'ADMIN')
  admin_user.posts.create!(title: 'Important Admin Announcement', content: '...', status: :PUBLISHED)
  admin_user.posts.create!(title: 'Draft Admin Notes', content: '...', status: :DRAFT)

  # Find admins with published posts containing 'Important'
  found_users = User.find_admins_with_published_posts('Important')
  puts "Found #{found_users.count} admin(s) via Arel query."
  puts "Found user email: #{found_users.first.email}"
end