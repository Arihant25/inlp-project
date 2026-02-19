require 'securerandom'
require 'time'
require 'json'

#==============================================================================
# Mock Database Engine (Standard Library Only)
# Simulates a basic SQL database using in-memory Hashes and Arrays.
# This allows the example to be self-contained and runnable.
#==============================================================================
class MockDBConnection
  def initialize
    @tables = {}
    @transaction_active = false
    puts "MockDBConnection initialized."
  end

  def execute(sql, params = [])
    log_query(sql, params)
    # Basic SQL parser for demonstration purposes
    case sql
    when /\A\s*CREATE TABLE IF NOT EXISTS (\w+)/i
      table_name = $1.downcase
      @tables[table_name] ||= { columns: [], data: [] }
    when /\A\s*INSERT INTO (\w+)/i
      table_name = $1.downcase
      data = Hash[params.keys.zip(params.values)]
      @tables[table_name][:data] << data
      [data] # Simulate RETURNING *
    when /\A\s*SELECT (.+?) FROM (\w+)\s*(WHERE .+)?/i
      table_name = $2.downcase
      where_clause = $3 || ""
      
      return [] unless @tables[table_name]

      if where_clause.include?('JOIN') # Simplified M2M join handler
        return handle_join_query(sql, params)
      end

      results = @tables[table_name][:data].select do |row|
        evaluate_where(row, where_clause, params)
      end
      results.map(&:dup)
    when /\A\s*UPDATE (\w+)/i
      table_name = $1.downcase
      where_clause = sql.split('WHERE').last
      
      @tables[table_name][:data].each do |row|
        if evaluate_where(row, where_clause, params)
          params.each { |k, v| row[k.to_sym] = v if row.key?(k.to_sym) }
        end
      end
    when /\A\s*DELETE FROM (\w+)/i
      table_name = $1.downcase
      where_clause = sql.split('WHERE').last
      @tables[table_name][:data].reject! do |row|
        evaluate_where(row, where_clause, params)
      end
    when /\A\s*BEGIN/i
      @transaction_active = true
    when /\A\s*COMMIT/i
      @transaction_active = false
    when /\A\s*ROLLBACK/i
      # In a real DB this would revert changes. Here we just log it.
      @transaction_active = false
      puts "--- ROLLED BACK ---"
    end
  end

  def transaction(&block)
    execute("BEGIN")
    yield
    execute("COMMIT")
  rescue => e
    execute("ROLLBACK")
    raise e
  end

  private

  def log_query(sql, params)
    puts "SQL: #{sql.strip} -- PARAMS: #{params.inspect}"
  end

  def evaluate_where(row, where_clause, params)
    return true if where_clause.strip.empty?
    
    conditions = where_clause.gsub(/\bWHERE\b/i, '').split(/\bAND\b/i)
    param_idx = 0
    conditions.all? do |cond|
      match = cond.match(/(\w+)\s*=\s*\?/)
      next true unless match
      
      col = match[1].downcase.to_sym
      val = params[param_idx]
      param_idx += 1
      row[col] == val
    end
  end
  
  def handle_join_query(sql, params)
    # Very specific parser for the Users <-> Roles query
    user_id = params.first
    user_roles = @tables['user_roles'][:data].select { |ur| ur[:user_id] == user_id }
    role_ids = user_roles.map { |ur| ur[:role_id] }
    @tables['roles'][:data].select { |r| role_ids.include?(r[:id]) }
  end
end

#==============================================================================
# Variation 4: Active Record-like Style
# - A base class provides persistence logic (e.g., .find, .create, #save).
# - Models inherit from the base class and are "smart," knowing how to save
#   and query for themselves.
# - Concise and conventional in the Ruby world.
#==============================================================================
class ApplicationRecord
  # --- Class-level methods for querying and connection handling ---
  def self.set_connection(db_conn)
    @connection = db_conn
  end

  def self.connection
    @connection
  end

  def self.table_name(name = nil)
    @table_name = name if name
    @table_name || self.name.downcase + 's'
  end

  def self.find(id)
    row = connection.execute("SELECT * FROM #{table_name} WHERE id = ?", [id]).first
    row ? new(row) : nil
  end

  def self.where(filters)
    base_sql = "SELECT * FROM #{table_name}"
    conditions = filters.map { |k, _| "#{k} = ?" }.join(" AND ")
    params = filters.values
    base_sql += " WHERE #{conditions}" unless filters.empty?
    
    connection.execute(base_sql, params).map { |row| new(row) }
  end

  def self.create(attributes)
    instance = new(attributes)
    instance.save
    instance
  end

  def self.transaction(&block)
    connection.transaction(&block)
  end

  # --- Instance-level methods for persistence ---
  attr_reader :attributes

  def initialize(attributes = {})
    @attributes = attributes.transform_keys(&:to_sym)
    @is_new_record = !@attributes.key?(:id)
    
    if @is_new_record
      @attributes[:id] ||= SecureRandom.uuid
    end
  end

  def method_missing(method_name, *args)
    if attributes.key?(method_name)
      attributes[method_name]
    elsif method_name.to_s.end_with?('=')
      key = method_name.to_s.chomp('=').to_sym
      attributes[key] = args.first
    else
      super
    end
  end

  def respond_to_missing?(method_name, include_private = false)
    attributes.key?(method_name) || super
  end

  def save
    if @is_new_record
      insert_record
    else
      update_record
    end
    @is_new_record = false
    true
  end

  def destroy
    self.class.connection.execute("DELETE FROM #{self.class.table_name} WHERE id = ?", [id])
  end

  private

  def insert_record
    cols = attributes.keys.join(', ')
    placeholders = (['?'] * attributes.size).join(', ')
    sql = "INSERT INTO #{self.class.table_name} (#{cols}) VALUES (#{placeholders})"
    self.class.connection.execute(sql, attributes.values)
  end

  def update_record
    updates = attributes.keys.reject { |k| k == :id }.map { |k| "#{k} = ?" }.join(', ')
    params = attributes.values.reject { |v| v == id } + [id]
    sql = "UPDATE #{self.class.table_name} SET #{updates} WHERE id = ?"
    self.class.connection.execute(sql, params)
  end
end

# --- Models ---
class User < ApplicationRecord
  def initialize(attrs = {})
    super({ is_active: true, created_at: Time.now.utc.iso8601 }.merge(attrs))
  end

  # One-to-Many relationship
  def posts
    Post.where(user_id: self.id)
  end

  # Many-to-Many relationship
  def roles
    sql = "SELECT r.* FROM roles r JOIN user_roles ur ON r.id = ur.role_id WHERE ur.user_id = ?"
    self.class.connection.execute(sql, [self.id]).map { |row| Role.new(row) }
  end

  def add_role(role)
    UserRole.create(user_id: self.id, role_id: role.id)
  end
end

class Post < ApplicationRecord
  def initialize(attrs = {})
    super({ status: 'DRAFT' }.merge(attrs))
  end
end

class Role < ApplicationRecord
end

class UserRole < ApplicationRecord
  table_name 'user_roles'
end

# --- Migrations ---
def run_migrations(conn)
  puts "\n[MIGRATION] Running database migrations..."
  conn.execute("CREATE TABLE IF NOT EXISTS users (id UUID, email TEXT, password_hash TEXT, is_active BOOLEAN, created_at TEXT)")
  conn.execute("CREATE TABLE IF NOT EXISTS posts (id UUID, user_id UUID, title TEXT, content TEXT, status TEXT)")
  conn.execute("CREATE TABLE IF NOT EXISTS roles (id INTEGER, name TEXT)")
  conn.execute("CREATE TABLE IF NOT EXISTS user_roles (user_id UUID, role_id INTEGER)")
  puts "[MIGRATION] Migrations complete."
end

def seed_roles
    Role.create(id: 1, name: 'ADMIN') unless Role.find(1)
    Role.create(id: 2, name: 'USER') unless Role.find(2)
end

# --- Main Execution ---
puts "--- VARIATION 4: ACTIVE RECORD-LIKE ---"
db = MockDBConnection.new
ApplicationRecord.set_connection(db)
run_migrations(db)
seed_roles

# CRUD Operations
puts "\n1. Creating a user via the model's .create method..."
user = User.create(email: 'activerecord@example.com', password_hash: 'hash012')
puts "  => Created user with ID: #{user.id}"
user.is_active = false
user.save
puts "  => Updated user's active status to #{user.is_active}"

# One-to-Many Relationship
puts "\n2. Creating posts for the user..."
Post.create(user_id: user.id, title: 'AR Post 1', content: '...')
Post.create(user_id: user.id, title: 'AR Post 2', content: '...')
puts "  => Posts for user: #{user.posts.map(&:title)}"

# Many-to-Many Relationship
puts "\n3. Assigning roles to the user..."
admin_role = Role.where(name: 'ADMIN').first
user.add_role(admin_role)
puts "  => Roles for user: #{user.roles.map(&:name)}"

# Query Building
puts "\n4. Using model's .where method to find users..."
inactive_users = User.where(is_active: false)
puts "  => Found inactive users: #{inactive_users.length}"

# Transaction and Rollback
puts "\n5. Demonstrating a transaction with a rollback..."
begin
  ApplicationRecord.transaction do
    puts "  Transaction started."
    User.create(email: 'temp@example.com', password_hash: 'temp_hash')
    puts "  Created temporary user."
    raise "A critical error occurred!"
  end
rescue => e
  puts "  Error caught: #{e.message}. Rolled back automatically."
end
found_user = User.where(email: 'temp@example.com').first
puts "  => Found temp user after rollback? #{!found_user.nil?}"