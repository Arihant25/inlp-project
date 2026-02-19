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
# Variation 1: Procedural / Functional Style
# - Uses modules to group related functions.
# - Data is passed around as simple Hashes.
# - Clear separation of concerns by function group.
#==============================================================================
module DatabaseMigration
  def self.run(db_conn)
    puts "\n[MIGRATION] Running database migrations..."
    db_conn.execute("CREATE TABLE IF NOT EXISTS users (id UUID, email TEXT, password_hash TEXT, is_active BOOLEAN, created_at TEXT)")
    db_conn.execute("CREATE TABLE IF NOT EXISTS posts (id UUID, user_id UUID, title TEXT, content TEXT, status TEXT)")
    db_conn.execute("CREATE TABLE IF NOT EXISTS roles (id INTEGER, name TEXT)")
    db_conn.execute("CREATE TABLE IF NOT EXISTS user_roles (user_id UUID, role_id INTEGER)")
    puts "[MIGRATION] Migrations complete."
  end
end

module UserRepo
  def self.create(db_conn, email:, password_hash:)
    user_id = SecureRandom.uuid
    sql = "INSERT INTO users (id, email, password_hash, is_active, created_at) VALUES (?, ?, ?, ?, ?) RETURNING *"
    params = [user_id, email, password_hash, true, Time.now.utc.iso8601]
    db_conn.execute(sql, params).first
  end

  def self.find_by_id(db_conn, id)
    db_conn.execute("SELECT * FROM users WHERE id = ?", [id]).first
  end

  def self.update_status(db_conn, id, is_active)
    db_conn.execute("UPDATE users SET is_active = ? WHERE id = ?", [is_active, id])
  end

  def self.delete(db_conn, id)
    db_conn.execute("DELETE FROM users WHERE id = ?", [id])
  end
end

module PostRepo
  def self.create(db_conn, user_id:, title:, content:)
    post_id = SecureRandom.uuid
    sql = "INSERT INTO posts (id, user_id, title, content, status) VALUES (?, ?, ?, ?, ?) RETURNING *"
    params = [post_id, user_id, title, content, 'DRAFT']
    db_conn.execute(sql, params).first
  end

  def self.find_by_user(db_conn, user_id)
    db_conn.execute("SELECT * FROM posts WHERE user_id = ?", [user_id])
  end
end

module RoleRepo
    def self.seed(db_conn)
        db_conn.execute("INSERT INTO roles (id, name) VALUES (?, ?)", [1, 'ADMIN'])
        db_conn.execute("INSERT INTO roles (id, name) VALUES (?, ?)", [2, 'USER'])
    end

    def self.find_by_name(db_conn, name)
        db_conn.execute("SELECT * FROM roles WHERE name = ?", [name]).first
    end

    def self.assign_to_user(db_conn, user_id:, role_id:)
        db_conn.execute("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)", [user_id, role_id])
    end

    def self.find_for_user(db_conn, user_id)
        sql = <<-SQL
            SELECT r.* FROM roles r
            JOIN user_roles ur ON r.id = ur.role_id
            WHERE ur.user_id = ?
        SQL
        db_conn.execute(sql, [user_id])
    end
end

module QueryBuilder
  def self.find_users(db_conn, filters)
    base_sql = "SELECT * FROM users"
    conditions = []
    params = []

    if filters.key?(:is_active)
      conditions << "is_active = ?"
      params << filters[:is_active]
    end
    
    if filters.key?(:email_like)
        conditions << "email LIKE ?"
        params << filters[:email_like]
    end

    unless conditions.empty?
      base_sql += " WHERE " + conditions.join(" AND ")
    end
    
    db_conn.execute(base_sql, params)
  end
end

# --- Main Execution ---
puts "--- VARIATION 1: PROCEDURAL/FUNCTIONAL ---"
db = MockDBConnection.new
DatabaseMigration.run(db)
RoleRepo.seed(db)

# CRUD Operations
puts "\n1. Creating a user..."
user = UserRepo.create(db, email: 'procedural@example.com', password_hash: 'hash123')
puts "  => Created: #{user.inspect}"

# One-to-Many Relationship
puts "\n2. Creating posts for the user..."
PostRepo.create(db, user_id: user[:id], title: 'First Post', content: 'Content 1')
PostRepo.create(db, user_id: user[:id], title: 'Second Post', content: 'Content 2')
user_posts = PostRepo.find_by_user(db, user[:id])
puts "  => Posts found for user: #{user_posts.map { |p| p[:title] }}"

# Many-to-Many Relationship
puts "\n3. Assigning roles to the user..."
admin_role = RoleRepo.find_by_name(db, 'ADMIN')
RoleRepo.assign_to_user(db, user_id: user[:id], role_id: admin_role[:id])
user_roles = RoleRepo.find_for_user(db, user[:id])
puts "  => Roles for user: #{user_roles.map { |r| r[:name] }}"

# Query Builder
puts "\n4. Using query builder to find active users..."
active_users = QueryBuilder.find_users(db, { is_active: true })
puts "  => Found active users: #{active_users.length}"

# Transaction and Rollback
puts "\n5. Demonstrating a transaction with a rollback..."
begin
  db.execute("BEGIN")
  puts "  Transaction started."
  UserRepo.create(db, email: 'temp@example.com', password_hash: 'temp_hash')
  puts "  Created temporary user."
  raise "Something went wrong!" # Simulate an error
  db.execute("COMMIT")
rescue => e
  puts "  Error caught: #{e.message}. Rolling back."
  db.execute("ROLLBACK")
end
puts "  Searching for temporary user after rollback..."
temp_user = db.execute("SELECT * FROM users WHERE email = ?", ['temp@example.com'])
puts "  => Found temp user? #{!temp_user.empty?}"