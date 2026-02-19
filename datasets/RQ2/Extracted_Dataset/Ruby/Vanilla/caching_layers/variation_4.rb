require 'securerandom'
require 'time'

# --- Domain Model ---
User = Struct.new(:id, :email, :password_hash, :role, :is_active, :created_at, keyword_init: true)
Post = Struct.new(:id, :user_id, :title, :content, :status, keyword_init: true)

# --- Low-Level Cache Store (LRU) ---
module Caching
  class LruStore
    Node = Struct.new(:key, :value, :expires_at, :prev, :next)

    def initialize(capacity)
      @capacity = capacity
      @map = {}
      @head = Node.new
      @tail = Node.new
      @head.next = @tail
      @tail.prev = @head
      @mutex = Mutex.new
    end

    def fetch(key, ttl, &block)
      @mutex.synchronize do
        node = @map[key]
        if node && (node.expires_at.nil? || node.expires_at > Time.now)
          _promote(node)
          return node.value
        end

        # Cache miss or expired
        value = block.call
        if value
          _delete(key) if node
          _evict if @map.size >= @capacity
          new_node = Node.new(key, value, ttl ? Time.now + ttl : nil)
          @map[key] = new_node
          _add_to_front(new_node)
        end
        value
      end
    end

    def invalidate(key)
      @mutex.synchronize { _delete(key) }
    end

    private
    def _delete(key)
      node = @map.delete(key)
      _remove(node) if node
    end

    def _promote(node)
      _remove(node)
      _add_to_front(node)
    end

    def _add_to_front(node)
      node.next = @head.next
      @head.next.prev = node
      @head.next = node
      node.prev = @head
    end



    def _remove(node)
      node.prev.next = node.next
      node.next.prev = node.prev
    end

    def _evict
      lru_node = @tail.prev
      _delete(lru_node.key) if lru_node && lru_node.key
    end
  end
end

# --- Mock Database ---
class FakeDatabase
  attr_reader :users, :posts
  def initialize
    @users = {}
    @posts = {}
  end
end

# --- Metaprogramming / DSL for Caching Repositories ---
module CacheableRepository
  def self.included(base)
    base.extend(ClassMethods)
  end

  module ClassMethods
    def cache_method(method_name, key_template:, ttl:)
      original_method = instance_method(method_name)
      
      define_method(method_name) do |*args|
        # Assumes the first arg is the ID for simplicity
        id = args.first
        cache_key = key_template.gsub(':id', id.to_s)
        
        @cache.fetch(cache_key, ttl) do
          puts "[CACHE] MISS/FETCH for #{method_name} with key: #{cache_key}"
          original_method.bind(self).call(*args)
        end
      end
    end
  end
end

# --- Repositories using the DSL ---
class UserRepository
  include CacheableRepository

  def initialize(db_conn, cache_instance)
    @db = db_conn
    @cache = cache_instance
  end

  def find(id)
    puts "  [DB]   Executing find for User #{id}"
    sleep 0.05
    @db.users[id]
  end

  def update(user)
    puts "  [DB]   Executing update for User #{user.id}"
    sleep 0.05
    @db.users[user.id] = user
    # Invalidation
    @cache.invalidate("users/:id".gsub(':id', user.id.to_s))
    puts "[CACHE] INVALIDATED key for User #{user.id}"
    user
  end

  # Apply caching to the 'find' method
  cache_method :find, key_template: 'users/:id', ttl: 120
end

class PostRepository
  include CacheableRepository

  def initialize(db_conn, cache_instance)
    @db = db_conn
    @cache = cache_instance
  end

  def find_by_id(id)
    puts "  [DB]   Executing find_by_id for Post #{id}"
    sleep 0.05
    @db.posts[id]
  end
  
  # Apply caching to the 'find_by_id' method
  cache_method :find_by_id, key_template: 'posts/:id', ttl: 300
end

# --- Main Execution ---
if __FILE__ == $0
  puts "--- Variation 4: Metaprogramming/DSL Approach ---"

  # Setup
  db = FakeDatabase.new
  cache = Caching::LruStore.new(10)
  user_repo = UserRepository.new(db, cache)
  
  # Seed data
  user1 = User.new(id: SecureRandom.uuid, email: 'dave@example.com', password_hash: 'abc', role: :user, is_active: true, created_at: Time.now)
  db.users[user1.id] = user1

  # --- Demonstrate DSL-based Cache-Aside ---
  puts "\n1. First call to repo.find (triggers original method):"
  user_repo.find(user1.id)

  puts "\n2. Second call to repo.find (served from cache):"
  retrieved_user = user_repo.find(user1.id)
  puts "  Retrieved user: #{retrieved_user.email}"

  # --- Demonstrate Invalidation ---
  puts "\n3. Updating user record (triggers invalidation):"
  user1.email = 'dave.new@example.com'
  user_repo.update(user1)

  puts "\n4. Calling repo.find after update (triggers original method again):"
  user_repo.find(user1.id)

  puts "\n5. Calling repo.find one more time (served from cache again):"
  user_repo.find(user1.id)
end