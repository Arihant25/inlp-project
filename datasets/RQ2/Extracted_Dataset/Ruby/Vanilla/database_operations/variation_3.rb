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

  def transaction
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
# Variation 3: Service-Oriented / Command Pattern Style
# - A low-level Gateway handles all DB communication.
# - "Command" objects encapsulate a single database operation.
# - "Service" objects orchestrate commands to perform business logic.
# - Highly structured, verbose, but very explicit and testable.
#==============================================================================

# --- Low-Level Data Gateway ---
class DatabaseGateway
  def initialize(connection)
    @conn = connection
  end

  def query(sql, params = [])
    @conn.execute(sql, params)
  end

  def execute(sql, params = [])
    @conn.execute(sql, params)
  end

  def transaction(&block)
    @conn.transaction(&block)
  end
end

# --- Command Objects ---
module Commands
  class CreateUser
    def initialize(gateway, email:, password_hash:)
      @gateway = gateway
      @email = email
      @password_hash = password_hash
    end

    def execute
      sql = "INSERT INTO users (id, email, password_hash, is_active, created_at) VALUES (?, ?, ?, ?, ?)"
      params = [SecureRandom.uuid, @email, @password_hash, true, Time.now.utc.iso8601]
      @gateway.execute(sql, params).first
    end
  end

  class CreatePost
    def initialize(gateway, user_id:, title:, content:)
      @gateway = gateway
      @user_id = user_id
      @title = title
      @content = content
    end

    def execute
      sql = "INSERT INTO posts (id, user_id, title, content, status) VALUES (?, ?, ?, ?, ?)"
      params = [SecureRandom.uuid, @user_id, @title, @content, 'DRAFT']
      @gateway.execute(sql, params).first
    end
  end
  
  class FindUserPosts
    def initialize(gateway, user_id:)
        @gateway = gateway
        @user_id = user_id
    end
    
    def execute
        @gateway.query("SELECT * FROM posts WHERE user_id = ?", [@user_id])
    end
  end

  class AssignRole
    def initialize(gateway, user_id:, role_id:)
        @gateway = gateway
        @user_id = user_id
        @role_id = role_id
    end

    def execute
        @gateway.execute("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)", [@user_id, @role_id])
    end
  end
end

# --- Service Layer ---
class UserAccountService
  def initialize(gateway)
    @gateway = gateway
  end

  def register_user_with_default_role(email, password)
    user_data = nil
    @gateway.transaction do
      puts "  Transaction started for user registration."
      user_data = Commands::CreateUser.new(@gateway, email: email, password_hash: password).execute
      
      # In a real app, this might raise an error if the role isn't found
      user_role = @gateway.query("SELECT id FROM roles WHERE name = ?", ['USER']).first
      
      Commands::AssignRole.new(@gateway, user_id: user_data[:id], role_id: user_role[:id]).execute
      puts "  User and default role created successfully."
    end
    user_data
  end
  
  def find_users(filters)
    base_sql = "SELECT * FROM users"
    conditions = []
    params = []

    if filters.key?(:is_active)
      conditions << "is_active = ?"
      params << filters[:is_active]
    end

    base_sql += " WHERE " + conditions.join(" AND ") unless conditions.empty?
    @gateway.query(base_sql, params)
  end
end

class MigrationRunner
  def self.perform(gateway)
    puts "\n[MIGRATION] Running database migrations..."
    gateway.execute("CREATE TABLE IF NOT EXISTS users (id UUID, email TEXT, password_hash TEXT, is_active BOOLEAN, created_at TEXT)")
    gateway.execute("CREATE TABLE IF NOT EXISTS posts (id UUID, user_id UUID, title TEXT, content TEXT, status TEXT)")
    gateway.execute("CREATE TABLE IF NOT EXISTS roles (id INTEGER, name TEXT)")
    gateway.execute("CREATE TABLE IF NOT EXISTS user_roles (user_id UUID, role_id INTEGER)")
    gateway.execute("INSERT INTO roles (id, name) VALUES (?, ?)", [1, 'ADMIN'])
    gateway.execute("INSERT INTO roles (id, name) VALUES (?, ?)", [2, 'USER'])
    puts "[MIGRATION] Migrations complete."
  end
end

# --- Main Execution ---
puts "--- VARIATION 3: SERVICE-ORIENTED/COMMAND ---"
db_connection = MockDBConnection.new
db_gateway = DatabaseGateway.new(db_connection)
MigrationRunner.perform(db_gateway)

user_service = UserAccountService.new(db_gateway)

# Transactional Operation
puts "\n1. Registering a new user with a default role (transactional)..."
user = user_service.register_user_with_default_role('service@example.com', 'hash789')
puts "  => Registered user: #{user.inspect}"

# One-to-Many Relationship
puts "\n2. Creating posts for the user via commands..."
Commands::CreatePost.new(db_gateway, user_id: user[:id], title: 'Command Post 1', content: '...').execute
Commands::CreatePost.new(db_gateway, user_id: user[:id], title: 'Command Post 2', content: '...').execute
user_posts = Commands::FindUserPosts.new(db_gateway, user_id: user[:id]).execute
puts "  => Posts found for user: #{user_posts.map { |p| p[:title] }}"

# Many-to-Many Relationship
puts "\n3. Assigning an additional role..."
admin_role = db_gateway.query("SELECT * FROM roles WHERE name = ?", ['ADMIN']).first
Commands::AssignRole.new(db_gateway, user_id: user[:id], role_id: admin_role[:id]).execute
user_roles = db_gateway.query("SELECT r.* FROM roles r JOIN user_roles ur ON r.id = ur.role_id WHERE ur.user_id = ?", [user[:id]])
puts "  => Roles for user: #{user_roles.map { |r| r[:name] }}"

# Query Building
puts "\n4. Using service to find active users..."
active_users = user_service.find_users({ is_active: true })
puts "  => Found active users: #{active_users.length}"

# Transaction and Rollback (demonstrated via service failure)
puts "\n5. Demonstrating a transaction with a rollback..."
begin
  db_gateway.transaction do
    puts "  Transaction started."
    Commands::CreateUser.new(db_gateway, email: 'temp@example.com', password_hash: 'temp_hash').execute
    puts "  Created temporary user."
    raise "Infrastructure failure!"
  end
rescue => e
  puts "  Error caught: #{e.message}. Rolled back automatically."
end
found_user = db_gateway.query("SELECT * FROM users WHERE email = ?", ['temp@example.com']).first
puts "  => Found temp user after rollback? #{!found_user.nil?}"