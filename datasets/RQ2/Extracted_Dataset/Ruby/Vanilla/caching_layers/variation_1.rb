require 'securerandom'
require 'time'

# ==============================================================================
# Domain Models
# ==============================================================================
module Models
  # Enum-like modules for roles and statuses
  module UserRole
    ADMIN = :admin
    USER = :user
  end

  module PostStatus
    DRAFT = :draft
    PUBLISHED = :published
  end

  User = Struct.new(:id, :email, :password_hash, :role, :is_active, :created_at, keyword_init: true)
  Post = Struct.new(:id, :user_id, :title, :content, :status, keyword_init: true)
end

# ==============================================================================
# Caching Layer Implementation (LRU from scratch)
# ==============================================================================
module Cache
  class LRUCache
    # Node for the doubly-linked list
    class Node
      attr_accessor :key, :value, :expires_at, :prev, :next
      def initialize(key, value, ttl_seconds)
        @key = key
        @value = value
        @expires_at = ttl_seconds ? Time.now + ttl_seconds : nil
        @prev = nil
        @next = nil
      end

      def expired?
        @expires_at && Time.now > @expires_at
      end
    end

    def initialize(capacity)
      @capacity = capacity
      @map = {}
      @head = Node.new(nil, nil, nil) # Dummy head
      @tail = Node.new(nil, nil, nil) # Dummy tail
      @head.next = @tail
      @tail.prev = @head
    end

    def get(key)
      return nil unless @map.key?(key)

      node = @map[key]
      if node.expired?
        delete(key)
        return nil
      end

      move_to_front(node)
      node.value
    end

    def set(key, value, ttl_seconds: 300)
      if @map.key?(key)
        node = @map[key]
        node.value = value
        node.expires_at = ttl_seconds ? Time.now + ttl_seconds : nil
        move_to_front(node)
      else
        evict_if_needed
        new_node = Node.new(key, value, ttl_seconds)
        @map[key] = new_node
        add_to_front(new_node)
      end
    end

    def delete(key)
      return unless @map.key?(key)
      node = @map.delete(key)
      remove_node(node)
    end

    private

    def evict_if_needed
      return if @map.size < @capacity
      lru_node = @tail.prev
      delete(lru_node.key)
    end

    def move_to_front(node)
      remove_node(node)
      add_to_front(node)
    end

    def add_to_front(node)
      node.next = @head.next
      @head.next.prev = node
      @head.next = node
      node.prev = @head
    end

    def remove_node(node)
      prev_node = node.prev
      next_node = node.next
      prev_node.next = next_node
      next_node.prev = prev_node
    end
  end
end

# ==============================================================================
# Mock Database Layer
# ==============================================================================
class MockDatabase
  def initialize
    @users = {}
    @posts = {}
  end

  def find_user(id)
    puts "[DB]   Fetching user #{id}..."
    sleep 0.1 # Simulate network latency
    @users[id]
  end

  def save_user(user)
    puts "[DB]   Saving user #{user.id}..."
    sleep 0.1
    @users[user.id] = user
  end

  def find_post(id)
    puts "[DB]   Fetching post #{id}..."
    sleep 0.1
    @posts[id]
  end

  def save_post(post)
    puts "[DB]   Saving post #{post.id}..."
    sleep 0.1
    @posts[post.id] = post
  end
end

# ==============================================================================
# Repository Layer with Cache-Aside Pattern
# ==============================================================================
class UserRepository
  def initialize(database, cache)
    @db = database
    @cache = cache
  end

  def find(id)
    cache_key = "user:#{id}"
    
    # 1. Try to get from cache
    cached_user = @cache.get(cache_key)
    if cached_user
      puts "[CACHE] Hit for user #{id}."
      return cached_user
    end
    puts "[CACHE] Miss for user #{id}."

    # 2. Cache miss, get from DB
    user = @db.find_user(id)

    # 3. Set in cache
    @cache.set(cache_key, user, ttl_seconds: 60) if user
    
    user
  end

  def update_email(id, new_email)
    user = @db.find_user(id)
    return nil unless user

    user.email = new_email
    @db.save_user(user)

    # Invalidation strategy: delete from cache on update
    cache_key = "user:#{id}"
    puts "[CACHE] Invalidating user #{id} due to update."
    @cache.delete(cache_key)
    
    user
  end
end

# ==============================================================================
# Main Execution Block
# ==============================================================================
if __FILE__ == $0
  puts "--- Variation 1: Classic OOP Approach ---"

  # Setup
  db = MockDatabase.new
  user_cache = Cache::LRUCache.new(5)
  user_repo = UserRepository.new(db, user_cache)

  # Create a user and save to DB
  user1 = Models::User.new(
    id: SecureRandom.uuid,
    email: 'alice@example.com',
    password_hash: 'hash123',
    role: Models::UserRole::USER,
    is_active: true,
    created_at: Time.now
  )
  db.save_user(user1)

  # --- Demonstrate Cache-Aside ---
  puts "\n1. First fetch (cache miss):"
  user_repo.find(user1.id)

  puts "\n2. Second fetch (cache hit):"
  user_repo.find(user1.id)

  # --- Demonstrate Cache Invalidation ---
  puts "\n3. Updating user's email:"
  user_repo.update_email(user1.id, 'alice.new@example.com')

  puts "\n4. Fetching after invalidation (cache miss):"
  user_repo.find(user1.id)

  puts "\n5. Fetching again (cache hit):"
  user_repo.find(user1.id)

  # --- Demonstrate LRU Eviction ---
  puts "\n--- Demonstrating LRU Eviction ---"
  (1..6).each do |i|
    user = Models::User.new(id: "user-#{i}", email: "user#{i}@test.com", password_hash: '...', role: Models::UserRole::USER, is_active: true, created_at: Time.now)
    db.save_user(user)
    puts "Fetching user-#{i} to populate cache..."
    user_repo.find(user.id)
  end

  puts "\nCache is full. 'user-1' should be the LRU item."
  puts "Fetching user-1 again (should be a miss as it was evicted):"
  user_repo.find("user-1")
end