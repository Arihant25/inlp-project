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
# Variation 2: Classic Object-Oriented (Data Mapper) Style
# - Domain objects (User, Post) are plain data containers.
# - Repository classes (UserRepository) handle all persistence logic.
# - Clear separation between in-memory objects and database representation.
#==============================================================================

# --- Domain Models ---
class User
  attr_accessor :id, :email, :password_hash, :is_active, :created_at

  def initialize(id: nil, email:, password_hash:, is_active: true, created_at: nil)
    @id = id || SecureRandom.uuid
    @email = email
    @password_hash = password_hash
    @is_active = is_active
    @created_at = created_at || Time.now.utc.iso8601
  end
end

class Post
  attr_accessor :id, :user_id, :title, :content, :status

  def initialize(id: nil, user_id:, title:, content:, status: 'DRAFT')
    @id = id || SecureRandom.uuid
    @user_id = user_id
    @title = title
    @content = content
    @status = status
  end
end

class Role
    attr_accessor :id, :name
    def initialize(id:, name:)
        @id = id
        @name = name
    end
end

# --- Persistence Layer (Repositories) ---
class BaseRepository
  def initialize(db_connection)
    @db = db_connection
  end

  protected

  def to_model(data, model_class)
    return nil unless data
    model_class.new(data.transform_keys(&:to_sym))
  end

  def to_models(data, model_class)
    data.map { |row| to_model(row, model_class) }
  end
end

class UserRepository < BaseRepository
  def save(user)
    existing = find(user.id)
    if existing
      sql = "UPDATE users SET email = ?, is_active = ? WHERE id = ?"
      @db.execute(sql, [user.email, user.is_active, user.id])
    else
      sql = "INSERT INTO users (id, email, password_hash, is_active, created_at) VALUES (?, ?, ?, ?, ?)"
      @db.execute(sql, [user.id, user.email, user.password_hash, user.is_active, user.created_at])
    end
    user
  end

  def find(id)
    data = @db.execute("SELECT * FROM users WHERE id = ?", [id]).first
    to_model(data, User)
  end

  def delete(id)
    @db.execute("DELETE FROM users WHERE id = ?", [id])
  end

  def find_with_filters(filters)
    base_sql = "SELECT * FROM users"
    conditions = []
    params = []

    if filters.key?(:is_active)
      conditions << "is_active = ?"
      params << filters[:is_active]
    end

    base_sql += " WHERE " + conditions.join(" AND ") unless conditions.empty?
    to_models(@db.execute(base_sql, params), User)
  end
end

class PostRepository < BaseRepository
    def save(post)
        sql = "INSERT INTO posts (id, user_id, title, content, status) VALUES (?, ?, ?, ?, ?)"
        @db.execute(sql, [post.id, post.user_id, post.title, post.content, post.status])
        post
    end

    def find_by_user_id(user_id)
        data = @db.execute("SELECT * FROM posts WHERE user_id = ?", [user_id])
        to_models(data, Post)
    end
end

class RoleRepository < BaseRepository
    def find_by_name(name)
        data = @db.execute("SELECT * FROM roles WHERE name = ?", [name]).first
        to_model(data, Role)
    end

    def assign_to_user(user_id, role_id)
        @db.execute("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)", [user_id, role_id])
    end

    def find_for_user(user_id)
        sql = "SELECT r.* FROM roles r JOIN user_roles ur ON r.id = ur.role_id WHERE ur.user_id = ?"
        data = @db.execute(sql, [user_id])
        to_models(data, Role)
    end
end

# --- Migration Service ---
class MigrationService
  def initialize(db_conn)
    @db = db_conn
  end

  def up
    puts "\n[MIGRATION] Running database migrations..."
    @db.execute("CREATE TABLE IF NOT EXISTS users (id UUID, email TEXT, password_hash TEXT, is_active BOOLEAN, created_at TEXT)")
    @db.execute("CREATE TABLE IF NOT EXISTS posts (id UUID, user_id UUID, title TEXT, content TEXT, status TEXT)")
    @db.execute("CREATE TABLE IF NOT EXISTS roles (id INTEGER, name TEXT)")
    @db.execute("CREATE TABLE IF NOT EXISTS user_roles (user_id UUID, role_id INTEGER)")
    @db.execute("INSERT INTO roles (id, name) VALUES (?, ?)", [1, 'ADMIN'])
    @db.execute("INSERT INTO roles (id, name) VALUES (?, ?)", [2, 'USER'])
    puts "[MIGRATION] Migrations complete."
  end
end

# --- Main Execution ---
puts "--- VARIATION 2: CLASSIC OBJECT-ORIENTED ---"
db_conn = MockDBConnection.new
MigrationService.new(db_conn).up

user_repo = UserRepository.new(db_conn)
post_repo = PostRepository.new(db_conn)
role_repo = RoleRepository.new(db_conn)

# CRUD Operations
puts "\n1. Creating a user object and saving it..."
user_obj = User.new(email: 'oop@example.com', password_hash: 'hash456')
user_repo.save(user_obj)
puts "  => Saved user with ID: #{user_obj.id}"

# One-to-Many Relationship
puts "\n2. Creating post objects for the user..."
post1 = Post.new(user_id: user_obj.id, title: "OOP Post 1", content: "...")
post2 = Post.new(user_id: user_obj.id, title: "OOP Post 2", content: "...")
post_repo.save(post1)
post_repo.save(post2)
user_posts = post_repo.find_by_user_id(user_obj.id)
puts "  => Posts found for user: #{user_posts.map(&:title)}"

# Many-to-Many Relationship
puts "\n3. Assigning roles to the user..."
admin_role = role_repo.find_by_name('ADMIN')
role_repo.assign_to_user(user_obj.id, admin_role.id)
user_roles = role_repo.find_for_user(user_obj.id)
puts "  => Roles for user: #{user_roles.map(&:name)}"

# Query Builder
puts "\n4. Using repository filter to find active users..."
active_users = user_repo.find_with_filters({ is_active: true })
puts "  => Found active users: #{active_users.length}"

# Transaction and Rollback
puts "\n5. Demonstrating a transaction with a rollback..."
begin
  db_conn.transaction do
    puts "  Transaction started."
    temp_user = User.new(email: 'temp@example.com', password_hash: 'temp_hash')
    user_repo.save(temp_user)
    puts "  Saved temporary user."
    raise "Simulating a failure!"
  end
rescue => e
  puts "  Error caught: #{e.message}. Rolled back automatically."
end
found_user = db_conn.execute("SELECT * FROM users WHERE email = ?", ['temp@example.com']).first
puts "  => Found temp user after rollback? #{!found_user.nil?}"