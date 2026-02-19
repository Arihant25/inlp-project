<pre>
# frozen_string_literal: true

# Variation 1: The "By-the-Book" Hanami Developer
# This developer follows the Hanami guides closely, resulting in clean,
# explicit, and easily maintainable code. They prefer clear method names
# in repositories and a strict separation of concerns.

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
require 'hanami/db/sequel'
connection = Sequel.connect('sqlite::memory:')
connection.loggers &lt;&lt; Logger.new($stdout) if ENV['SQL_LOG']

# --- Database Migrations ---
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
      column :status, String, null: false, default: 'DRAFT' # DRAFT, PUBLISHED
      column :created_at, DateTime, null: false, default: Sequel::CURRENT_TIMESTAMP
      column :updated_at, DateTime, null: false, default: Sequel::CURRENT_TIMESTAMP
    end

    conn.create_table? :roles do
      primary_key :id
      column :name, String, null: false, unique: true # ADMIN, USER
    end

    conn.create_table? :users_roles do
      foreign_key :user_id, :users, on_delete: :cascade, null: false
      foreign_key :role_id, :roles, on_delete: :cascade, null: false
      primary_key [:user_id, :role_id]
    end
  end
end

DBMigrator.run(connection)

# --- Hanami Persistence Configuration ---
class Persistence
  extend Hanami::DB::Config

  config do
    gateway(:default, :sql, connection)

    # --- Relations ---
    relation(:users) do
      schema(infer: true) do
        associations do
          has_many :posts
          has_many :users_roles
          has_many :roles, through: :users_roles
        end
      end
    end

    relation(:posts) do
      schema(infer: true) do
        associations do
          belongs_to :user
        end
      end
    end

    relation(:roles) do
      schema(infer: true) do
        associations do
          has_many :users_roles
          has_many :users, through: :users_roles
        end
      end
    end

    relation(:users_roles) do
      schema(infer: true) do
        associations do
          belongs_to :user
          belongs_to :role
        end
      end
    end
  end
end

Hanami::DB.configure(Persistence)

# --- Entities ---
module Entities
  class User &lt; Hanami::Entity
    attributes do
      attribute :id, Types::Integer
      attribute :uuid, Types::String
      attribute :email, Types::String
      attribute :password_hash, Types::String
      attribute :is_active, Types::Bool
      attribute :created_at, Types::Time
      attribute :updated_at, Types::Time

      attribute :posts, Types::Collection(of: 'Entities::Post')
      attribute :roles, Types::Collection(of: 'Entities::Role')
    end
  end

  class Post &lt; Hanami::Entity
    attributes do
      attribute :id, Types::Integer
      attribute :uuid, Types::String
      attribute :user_id, Types::Integer
      attribute :title, Types::String
      attribute :content, Types::String
      attribute :status, Types::String
      attribute :created_at, Types::Time
      attribute :updated_at, Types::Time
    end
  end

  class Role &lt; Hanami::Entity
    attributes do
      attribute :id, Types::Integer
      attribute :name, Types::String
    end
  end
end

# --- Repositories ---
module Repositories
  class UserRepo &lt; Hanami::Repository
    associations :posts, :roles

    def find_by_email(email)
      users.where(email: email).one
    end

    def find_with_posts(uuid)
      users.by_uuid(uuid).combine(:posts).one
    end

    def find_active_users
      users.where(is_active: true).to_a
    end

    def add_role_to_user(user, role)
      assoc(:roles, user).add(role)
    end

    # Transactional operation
    def create_with_initial_post(user_data, post_data)
      transaction do
        created_user = create(user_data)
        # In a real app, you'd get the ID from the created_user
        # For simplicity, we assume post_data includes user_id
        # but let's link it properly.
        post_repo = PostRepo.new
        post_repo.create(post_data.merge(user_id: created_user.id))
        created_user
      end
    end
  end

  class PostRepo &lt; Hanami::Repository
    def find_by_uuid(uuid)
      posts.by_uuid(uuid).one
    end

    def find_published_posts_for_user(user_id)
      posts.where(user_id: user_id, status: 'PUBLISHED').to_a
    end
  end

  class RoleRepo &lt; Hanami::Repository
    def find_by_name(name)
      roles.where(name: name).one
    end
  end
end

# --- Demo of Functionality ---
puts "&lt;-- Variation 1: By-the-Book Developer --&gt;"

# Initialize Repositories
user_repo = Repositories::UserRepo.new
post_repo = Repositories::PostRepo.new
role_repo = Repositories::RoleRepo.new

# CRUD: Roles
puts "\n1. Seeding Roles..."
admin_role = role_repo.create(name: 'ADMIN')
user_role = role_repo.create(name: 'USER')
puts "  - Created roles: #{role_repo.all.map(&amp;:name).join(', ')}"

# CRUD: User
puts "\n2. CRUD Operations for User..."
user_uuid = SecureRandom.uuid
new_user_data = {
  uuid: user_uuid,
  email: 'by-the-book@example.com',
  password_hash: BCrypt::Password.create('secret'),
  is_active: true
}
created_user = user_repo.create(new_user_data)
puts "  - Created User: #{created_user.email} (ID: #{created_user.id})"

found_user = user_repo.find(created_user.id)
puts "  - Found User by ID: #{found_user.email}"

updated_user = user_repo.update(found_user.id, is_active: false)
puts "  - Updated User, is_active: #{updated_user.is_active}"

# Many-to-Many Relationship
puts "\n3. Many-to-Many (User &lt;-&gt; Roles)..."
user_repo.add_role_to_user(updated_user, admin_role)
user_repo.add_role_to_user(updated_user, user_role)
user_with_roles = user_repo.find_with_roles(updated_user.id)
puts "  - User roles: #{user_with_roles.roles.map(&amp;:name).join(', ')}"

# One-to-Many Relationship &amp; Query Building
puts "\n4. One-to-Many (User -&gt; Posts) &amp; Querying..."
post_repo.create(user_id: created_user.id, uuid: SecureRandom.uuid, title: 'First Post', content: '...', status: 'PUBLISHED')
post_repo.create(user_id: created_user.id, uuid: SecureRandom.uuid, title: 'Second Post', content: '...', status: 'DRAFT')

user_with_posts = user_repo.find_with_posts(user_uuid)
puts "  - User has #{user_with_posts.posts.count} total posts."

published_posts = post_repo.find_published_posts_for_user(created_user.id)
puts "  - User has #{published_posts.count} published posts. Title: '#{published_posts.first.title}'"

# Transaction and Rollback
puts "\n5. Transaction Demo..."
begin
  user_repo.transaction do
    puts "  - Starting transaction..."
    user_repo.create(uuid: SecureRandom.uuid, email: 'tx-user@example.com', password_hash: '...')
    puts "  - Created user inside transaction."
    raise Sequel::Rollback, "Something went wrong!"
  end
rescue Sequel::Rollback
  puts "  - Transaction rolled back as expected."
end
puts "  - User 'tx-user@example.com' exists? #{!user_repo.find_by_email('tx-user@example.com').nil?}"

# Cleanup
user_repo.delete(created_user.id)
puts "\n6. Deleted user. Total users now: #{user_repo.all.count}"
</pre>