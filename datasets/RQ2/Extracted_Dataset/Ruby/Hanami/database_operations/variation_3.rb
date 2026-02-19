<pre>
# frozen_string_literal: true

# Variation 3: The "Query Object" Proponent
# This developer extracts complex query logic into dedicated, reusable, and
# composable query objects. This keeps repositories clean and makes
# complex data retrieval logic easier to test and reason about.

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

# --- Query Objects ---
module Queries
  class ActiveUsers
    def call(relation)
      relation.where(is_active: true)
    end
  end

  class UsersWithRole
    def initialize(role_name)
      @role_name = role_name
    end

    def call(relation)
      relation.join(:users_roles).join(:roles, id: :role_id).where(roles[:name] =&gt; @role_name)
    end
  end

  class PublishedPosts
    def call(relation)
      relation.where(status: 'PUBLISHED')
    end
  end
end

# --- Repositories ---
module Repositories
  class UserRepo &lt; Hanami::Repository
    associations :posts, :roles

    def find_active
      users.query(Queries::ActiveUsers.new).to_a
    end

    def find_admins
      users.query(Queries::UsersWithRole.new('ADMIN')).to_a
    end

    # Transactional operation
    def create_user_as_admin(user_data)
      transaction do |t|
        new_user = create(user_data)
        admin_role = RoleRepo.new(t).roles.where(name: 'ADMIN').one
        # If role doesn't exist, this would fail and rollback
        assoc(:roles, new_user).add(admin_role)
        new_user
      end
    end
  end

  class PostRepo &lt; Hanami::Repository
    def find_published
      posts.query(Queries::PublishedPosts.new).to_a
    end

    def find_published_for_user(user_id)
      posts.where(user_id: user_id).query(Queries::PublishedPosts.new).to_a
    end
  end

  class RoleRepo &lt; Hanami::Repository; end
end

# --- Demo of Functionality ---
puts "&lt;-- Variation 3: Query Object Proponent --&gt;"

# Initialize Repositories
user_repo = Repositories::UserRepo.new
post_repo = Repositories::PostRepo.new
role_repo = Repositories::RoleRepo.new

# Seed Data
puts "\n1. Seeding Data..."
admin_role = role_repo.create(name: 'ADMIN')
user_role = role_repo.create(name: 'USER')

# CRUD: Create users
user1 = user_repo.create(uuid: SecureRandom.uuid, email: 'user1@example.com', password_hash: '...', is_active: true)
user2 = user_repo.create(uuid: SecureRandom.uuid, email: 'user2@example.com', password_hash: '...', is_active: false)
user_repo.assoc(:roles, user1).add(admin_role)
user_repo.assoc(:roles, user1).add(user_role)
user_repo.assoc(:roles, user2).add(user_role)

# CRUD: Create posts
post_repo.create(user_id: user1.id, uuid: SecureRandom.uuid, title: 'Admin Post', content: '...', status: 'PUBLISHED')
post_repo.create(user_id: user1.id, uuid: SecureRandom.uuid, title: 'Draft Post', content: '...', status: 'DRAFT')
post_repo.create(user_id: user2.id, uuid: SecureRandom.uuid, title: 'User 2 Post', content: '...', status: 'PUBLISHED')

# 2. Using Query Objects
puts "\n2. Using Query Objects..."
active_users = user_repo.find_active
puts "  - Found #{active_users.count} active user(s) via Query Object: #{active_users.map(&amp;:email).join(', ')}"

admins = user_repo.find_admins
puts "  - Found #{admins.count} admin(s) via Query Object: #{admins.map(&amp;:email).join(', ')}"

published_posts = post_repo.find_published
puts "  - Found #{published_posts.count} total published posts via Query Object."

user1_published = post_repo.find_published_for_user(user1.id)
puts "  - Found #{user1_published.count} published post(s) for user1."

# 3. One-to-Many and Many-to-Many are implicitly handled by setup and queries
user1_with_data = user_repo.find_with_posts_and_roles(user1.id)
puts "\n3. Checking relationships..."
puts "  - User1 has #{user1_with_data.posts.count} posts."
puts "  - User1 has roles: #{user1_with_data.roles.map(&amp;:name).join(', ')}"

# 4. Transaction Demo
puts "\n4. Transactional create..."
begin
  new_admin = user_repo.create_user_as_admin(uuid: SecureRandom.uuid, email: 'new-admin@example.com', password_hash: '...')
  puts "  - Successfully created new admin: #{new_admin.email}"
  new_admin_with_roles = user_repo.find_with_roles(new_admin.id)
  puts "  - New admin roles: #{new_admin_with_roles.roles.map(&amp;:name).join(', ')}"
rescue =&gt; e
  puts "  - Transaction failed: #{e.message}"
end

# 5. Transaction Rollback Demo
puts "\n5. Transaction Rollback Demo..."
class FailingUserRepo &lt; Repositories::UserRepo
  def create_user_as_admin(user_data)
    transaction do
      puts "  - Starting transaction..."
      new_user = create(user_data)
      puts "  - Created user, now forcing rollback."
      raise Sequel::Rollback
    end
  end
end

begin
  FailingUserRepo.new.create_user_as_admin(uuid: SecureRandom.uuid, email: 'fail-admin@example.com', password_hash: '...')
rescue Sequel::Rollback
  puts "  - Transaction rolled back."
end
puts "  - User 'fail-admin@example.com' exists? #{!user_repo.users.where(email: 'fail-admin@example.com').one.nil?}"

# 6. Cleanup
puts "\n6. Cleanup..."
user_repo.delete(user1.id)
user_repo.delete(user2.id)
user_repo.delete(new_admin.id)
puts "  - All users deleted."
</pre>