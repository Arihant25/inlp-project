<pre>
# frozen_string_literal: true

# Variation 2: The "Service Layer" Architect
# This developer encapsulates business logic in service objects, keeping
# repositories lean and focused on data access. This promotes testability
# and a clear separation of application logic from persistence.

require 'bundler/inline'

gemfile do
  source 'https://rubygems.org'
  gem 'hanami', '~&gt; 2.1'
  gem 'hanami-repo', '~&gt; 2.1'
  gem 'sqlite3'
  gem 'bcrypt'
end

require 'hanami'
require 'hanami/setup'
require 'hanami/console'
require 'bcrypt'
require 'securerandom'
require 'logger'

# --- Hanami Application Setup ---
module MyApp
  class App &lt; Hanami::App
  end
end

Hanami.prepare

# --- Database Configuration ---
connection = Sequel.connect('sqlite::memory:')
connection.loggers &lt;&lt; Logger.new($stdout) if ENV['SQL_LOG']

# --- Database Migrations (Identical to Variation 1) ---
class DBMigrator
  def self.run(conn)
    conn.create_table? :users do
      primary_key :id
      column :uuid, String, null: false, unique: true, default: Sequel.function(:hex, Sequel.function(:randomblob, 16))
      column :email, String, null: false, unique: true
      column :password_hash, String, null: false
      column :is_active, TrueClass, null: false, default: true
      column :created_at, DateTime, null: false, default: Sequel::CURRENT_TIMESTAMP
      column :updated_at, DateTime, null: false, default: Sequel::CURRENT_TIMESTAMP
    end
    conn.create_table? :posts do
      primary_key :id
      column :uuid, String, null: false, unique: true, default: Sequel.function(:hex, Sequel.function(:randomblob, 16))
      foreign_key :user_id, :users, on_delete: :cascade, null: false
      column :title, String, null: false
      column :content, String, text: true
      column :status, String, null: false, default: 'DRAFT'
      column :created_at, DateTime, null: false, default: Sequel::CURRENT_TIMESTAMP
      column :updated_at, DateTime, null: false, default: Sequel::CURRENT_TIMESTAMP
    end
    conn.create_table? :roles do
      primary_key :id
      column :name, String, null: false, unique: true
    end
    conn.create_table? :users_roles do
      foreign_key :user_id, :users, on_delete: :cascade, null: false
      foreign_key :role_id, :roles, on_delete: :cascade, null: false
      primary_key [:user_id, :role_id]
    end
  end
end
DBMigrator.run(connection)

# --- Hanami Persistence Configuration (Identical to Variation 1) ---
class Persistence
  extend Hanami::DB::Config
  config do
    gateway(:default, :sql, connection)
    relation(:users) { schema(infer: true) { associations { has_many :posts; has_many :users_roles; has_many :roles, through: :users_roles } } }
    relation(:posts) { schema(infer: true) { associations { belongs_to :user } } }
    relation(:roles) { schema(infer: true) { associations { has_many :users_roles; has_many :users, through: :users_roles } } }
    relation(:users_roles) { schema(infer: true) { associations { belongs_to :user; belongs_to :role } } }
  end
end
Hanami::DB.configure(Persistence)

# --- Entities (Identical to Variation 1) ---
module Entities
  class User &lt; Hanami::Entity; end
  class Post &lt; Hanami::Entity; end
  class Role &lt; Hanami::Entity; end
end

# --- Repositories (Leaner) ---
module Repositories
  class UserRepo &lt; Hanami::Repository
    associations :posts, :roles
    # Repositories are kept simple, mostly relying on default methods.
    # Complex operations are handled by services.
  end
  class PostRepo &lt; Hanami::Repository; end
  class RoleRepo &lt; Hanami::Repository; end
end

# --- Service Layer ---
module Services
  class UserRegistration
    attr_reader :user_repo, :role_repo

    def initialize(user_repo:, role_repo:)
      @user_repo = user_repo
      @role_repo = role_repo
    end

    # Transactional business logic
    def call(email:, password:)
      user_repo.transaction do
        # 1. Check for existing user
        return { error: 'Email already taken' } if user_repo.users.where(email: email).one

        # 2. Create user
        hashed_password = BCrypt::Password.create(password)
        new_user = user_repo.create(
          uuid: SecureRandom.uuid,
          email: email,
          password_hash: hashed_password
        )

        # 3. Assign default role
        default_role = role_repo.roles.where(name: 'USER').one
        user_repo.assoc(:roles, new_user).add(default_role) if default_role

        { user: new_user }
      end
    end
  end

  class PostManager
    attr_reader :post_repo

    def initialize(post_repo:)
      @post_repo = post_repo
    end

    def create_post(user:, title:, content:)
      post_repo.create(
        user_id: user.id,
        uuid: SecureRandom.uuid,
        title: title,
        content: content,
        status: 'DRAFT'
      )
    end

    def publish_post(post_uuid:)
      post = post_repo.posts.by_uuid(post_uuid).one
      return nil unless post
      post_repo.update(post.id, status: 'PUBLISHED')
    end

    # Query Building example
    def find_published_posts_for_user(user)
      post_repo.posts.where(user_id: user.id, status: 'PUBLISHED').to_a
    end
  end
end

# --- Demo of Functionality ---
puts "&lt;-- Variation 2: Service Layer Architect --&gt;"

# Initialize Repositories
user_repo = Repositories::UserRepo.new
post_repo = Repositories::PostRepo.new
role_repo = Repositories::RoleRepo.new

# Initialize Services with Dependency Injection
user_registration_service = Services::UserRegistration.new(user_repo: user_repo, role_repo: role_repo)
post_manager_service = Services::PostManager.new(post_repo: post_repo)

# Seed Roles
puts "\n1. Seeding Roles..."
role_repo.create(name: 'ADMIN')
role_repo.create(name: 'USER')

# 1. Use Service to register a user (covers Create, Transaction, M-M)
puts "\n2. Registering User via Service..."
result = user_registration_service.call(email: 'service-user@example.com', password: 'a-good-password')
new_user = result[:user]
puts "  - Service created user: #{new_user.email}"

user_with_roles = user_repo.find_with_roles(new_user.id)
puts "  - Assigned roles: #{user_with_roles.roles.map(&amp;:name).join(', ')}"

# 2. Use Service to manage posts (covers Create, Update, 1-M)
puts "\n3. Managing Posts via Service..."
draft_post = post_manager_service.create_post(user: new_user, title: 'My Draft Post', content: 'Content here.')
puts "  - Created draft post: '#{draft_post.title}' (Status: #{draft_post.status})"

published_post = post_manager_service.publish_post(post_uuid: draft_post.uuid)
puts "  - Published post: '#{published_post.title}' (Status: #{published_post.status})"

# 3. Use Service for Querying
puts "\n4. Querying via Service..."
all_published = post_manager_service.find_published_posts_for_user(new_user)
puts "  - Found #{all_published.count} published post(s) for user."

# 4. Transaction Rollback Demo (within a service context)
puts "\n5. Transaction Rollback Demo..."
class FailingRegistration &lt; Services::UserRegistration
  def call(email:, password:)
    super.tap do
      puts "  - Simulating failure and rolling back..."
      raise Sequel::Rollback
    end
  end
end

failing_service = FailingRegistration.new(user_repo: user_repo, role_repo: role_repo)
begin
  failing_service.call(email: 'fail@example.com', password: '...')
rescue Sequel::Rollback
  puts "  - Transaction correctly rolled back by service."
end
puts "  - User 'fail@example.com' exists? #{!user_repo.users.where(email: 'fail@example.com').one.nil?}"

# 5. Cleanup (Direct repo access is fine for simple ops)
puts "\n6. Deleting user..."
user_repo.delete(new_user.id)
puts "  - User count is now: #{user_repo.all.count}"
puts "  - Post count is now: #{post_repo.all.count} (cascading delete)"
</pre>