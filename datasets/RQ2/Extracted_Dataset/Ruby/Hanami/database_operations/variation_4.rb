<pre>
# frozen_string_literal: true

# Variation 4: The "Concise &amp; Pragmatic" Developer
# This developer values brevity and directness. They leverage the power of
# the underlying ROM-rb library, use shorter variable names, and might
# create a custom base repository to reduce boilerplate. The focus is on
# efficient, expressive code.

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
DB_CONN = Sequel.connect('sqlite::memory:')
DB_CONN.loggers &lt;&lt; Logger.new($stdout) if ENV['SQL_LOG']

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
DBMigrator.run(DB_CONN)

# --- Hanami Persistence Configuration (Identical to Variation 1) ---
class Persistence
  extend Hanami::DB::Config
  config do
    gateway(:default, :sql, DB_CONN)
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

# --- Repositories ---
module Repositories
  # A pragmatic base class to add common helpers
  class BaseRepo &lt; Hanami::Repository
    # Delegate missing class methods to an instance
    def self.method_missing(method, *args, &amp;block)
      new.send(method, *args, &amp;block)
    end
  end

  class UserRepo &lt; BaseRepo
    relations :users, :roles, :users_roles
    associations :posts, :roles

    # Leverage ROM's dynamic finders and chaining
    def find_active_with_posts
      users.where(is_active: true).combine(:posts).to_a
    end

    # More direct association management
    def assign_role(user, role_name)
      role = roles.where(name: role_name).one!
      users_roles.changeset(:create, user_id: user.id, role_id: role.id).commit
    end
  end

  class PostRepo &lt; BaseRepo
    relations :posts

    # Concise query method
    def for_user(user_id, status: nil)
      q = posts.where(user_id: user_id)
      q = q.where(status: status) if status
      q.to_a
    end
  end

  class RoleRepo &lt; BaseRepo
    relations :roles
  end
end

# --- Demo of Functionality ---
puts "&lt;-- Variation 4: Concise &amp; Pragmatic Developer --&gt;"

# Use class-level methods on the repo for brevity
puts "\n1. Seeding Roles..."
admin_role = Repositories::RoleRepo.create(name: 'ADMIN')
user_role = Repositories::RoleRepo.create(name: 'USER')
puts "  - Roles: #{Repositories::RoleRepo.all.map(&amp;:name).join(', ')}"

# CRUD Operations
puts "\n2. CRUD Operations..."
usr = Repositories::UserRepo.create(
  uuid: SecureRandom.uuid,
  email: 'pragmatic@example.com',
  password_hash: BCrypt::Password.create('pass123')
)
puts "  - Created user: #{usr.email}"

Repositories::UserRepo.update(usr.id, is_active: false)
usr = Repositories::UserRepo.find(usr.id)
puts "  - Updated user, is_active: #{usr.is_active}"

# Relationships (1-M and M-M)
puts "\n3. Managing Relationships..."
Repositories::UserRepo.assign_role(usr, 'ADMIN')
puts "  - Assigned ADMIN role."

Repositories::PostRepo.create(user_id: usr.id, uuid: SecureRandom.uuid, title: 'A Post', content: '...', status: 'PUBLISHED')
Repositories::PostRepo.create(user_id: usr.id, uuid: SecureRandom.uuid, title: 'Another Post', content: '...', status: 'DRAFT')
puts "  - Created 2 posts for user."

# Query Building
puts "\n4. Querying Data..."
user_with_data = Repositories::UserRepo.new.users.by_pk(usr.id).combine(:posts, :roles).one
puts "  - User has roles: #{user_with_data.roles.map(&amp;:name).join(', ')}"
puts "  - User has posts: #{user_with_data.posts.map(&amp;:title).join(', ')}"

published = Repositories::PostRepo.for_user(usr.id, status: 'PUBLISHED')
puts "  - Found #{published.count} published post for user."

# Transaction Demo
puts "\n5. Transaction Demo..."
# Directly use the gateway for a concise transaction
begin
  Hanami::DB.gateway.transaction(rollback: :always) do
    puts "  - Starting transaction..."
    u = Repositories::UserRepo.create(uuid: SecureRandom.uuid, email: 'tx-fail@example.com', password_hash: '...')
    Repositories::PostRepo.create(user_id: u.id, uuid: SecureRandom.uuid, title: 'TX Post', content: '...')
    puts "  - Created user and post inside transaction (will be rolled back)."
  end
rescue Sequel::Rollback
  # This block won't be hit because :always handles it, but good practice
end
puts "  - Transaction finished."
puts "  - User 'tx-fail@example.com' exists? #{!Repositories::UserRepo.new.users.where(email: 'tx-fail@example.com').one.nil?}"

# Cleanup
puts "\n6. Cleanup..."
Repositories::UserRepo.delete(usr.id)
puts "  - Deleted user. User count: #{Repositories::UserRepo.all.count}"
</pre>