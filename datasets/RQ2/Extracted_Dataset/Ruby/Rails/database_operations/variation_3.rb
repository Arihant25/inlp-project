# frozen_string_literal: true

# This variation represents the "Concern & Query Object" developer.
# This developer values DRY (Don't Repeat Yourself) and separation of concerns.
# Reusable logic is extracted into `ActiveSupport::Concern` modules.
# Complex database queries are encapsulated in dedicated Query Objects,
# making them easy to test, reuse, and compose.

# --- BOILERPLATE FOR STANDALONE EXECUTION ---
require 'active_record'
require 'active_support/concern'
require 'securerandom'

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

# --- CONCERNS ---

# app/models/concerns/statusable.rb
module Statusable
  extend ActiveSupport::Concern

  included do
    enum status: { DRAFT: 0, PUBLISHED: 1 }

    scope :draft, -> { where(status: :DRAFT) }
    scope :published, -> { where(status: :PUBLISHED) }
  end

  def publish!
    update!(status: :PUBLISHED)
  end
end

# app/models/concerns/activatable.rb
module Activatable
  extend ActiveSupport::Concern

  included do
    scope :active, -> { where(is_active: true) }
    scope :inactive, -> { where(is_active: false) }
  end

  def activate!
    update!(is_active: true)
  end

  def deactivate!
    update!(is_active: false)
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
  validates :name, presence: true, uniqueness: true
end

# app/models/user_role.rb
class UserRole < ApplicationRecord
  belongs_to :user
  belongs_to :role
end

# app/models/user.rb
class User < ApplicationRecord
  include Activatable # Mixin the concern
  has_secure_password

  has_many :posts, dependent: :destroy
  has_many :user_roles, dependent: :destroy
  has_many :roles, through: :user_roles

  validates :email, presence: true, uniqueness: true

  # Transactional callback
  after_create :assign_default_role

  private

  def assign_default_role
    # This runs within the same transaction as the user creation
    user_role = Role.find_by(name: 'USER')
    roles << user_role if user_role && roles.empty?
  end
end

# app/models/post.rb
class Post < ApplicationRecord
  include Statusable # Mixin the concern
  belongs_to :user
  validates :title, presence: true
end

# --- QUERY OBJECT ---

# app/queries/post_search_query.rb
class PostSearchQuery
  def initialize(relation = Post.all)
    @relation = relation
  end

  def call(params = {})
    scoped = @relation
    scoped = scoped.where(status: Post.statuses[params[:status]]) if params[:status].present?
    scoped = scoped.joins(:user).where(users: { email: params[:user_email] }) if params[:user_email].present?
    scoped = scoped.where('title LIKE ?', "%#{params[:title_contains]}%") if params[:title_contains].present?
    scoped
  end
end

# --- CONTROLLER (Simulated) ---

# app/controllers/posts_controller.rb
class PostsController
  attr_accessor :params

  def initialize(params = {})
    @params = params
  end

  # GET /posts
  def index
    # Uses the query object to filter posts
    PostSearchQuery.new.call(params)
  end

  # POST /posts
  def create
    user = User.find(params[:user_id])
    # The model concern provides the `status` enum
    post = user.posts.create!(title: params[:title], content: params[:content], status: :DRAFT)
    # In a real controller: render json: post, status: :created
    post
  end
end

# --- DEMONSTRATION ---
if __FILE__ == $0
  run_migrations

  # Seed Roles
  Role.create!(name: 'ADMIN')
  Role.create!(name: 'USER')

  # --- Create User (demonstrates transactional callback) ---
  puts "--- Creating User (triggers `after_create` callback) ---"
  user1 = User.create!(email: 'user1@example.com', password: 'password')
  puts "User '#{user1.email}' created with roles: #{user1.roles.pluck(:name)}"
  user2 = User.create!(email: 'user2@example.com', password: 'password')

  # --- Using Model Concerns ---
  puts "\n--- Using Model Concerns ---"
  post1 = user1.posts.create!(title: 'A Draft Post', content: '...')
  puts "Post 1 status: #{post1.status}" # DRAFT
  post1.publish!
  puts "Post 1 status after `publish!`: #{post1.status}" # PUBLISHED

  user1.deactivate!
  puts "User 1 is active? #{user1.is_active}" # false
  puts "Active users count: #{User.active.count}" # Should be 1 (user2)

  # --- Using the Query Object ---
  puts "\n--- Using the Query Object ---"
  user2.posts.create!(title: 'Another Published Post', content: '...', status: :PUBLISHED)
  user2.posts.create!(title: 'A second draft', content: '...', status: :DRAFT)

  posts_controller = PostsController.new

  # Filter by status
  posts_controller.params = { status: 'PUBLISHED' }
  published_posts = posts_controller.index
  puts "Found #{published_posts.count} published post(s)."

  # Filter by user email and title
  posts_controller.params = { user_email: 'user2@example.com', title_contains: 'draft' }
  user2_drafts = posts_controller.index
  puts "Found #{user2_drafts.count} draft(s) for user2 containing 'draft'."

  # --- Transaction Rollback Example ---
  puts "\n--- Transaction Rollback ---"
  begin
    # This will fail validation and rollback the entire transaction
    User.create!(email: nil, password: 'password')
  rescue ActiveRecord::RecordInvalid => e
    puts "User creation failed as expected: #{e.message}"
  end
  puts "Total users: #{User.count}" # Should still be 2
end