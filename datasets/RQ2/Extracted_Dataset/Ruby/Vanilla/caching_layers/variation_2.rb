require 'securerandom'
require 'time'

# --- Domain Model Definitions ---
module Domain
  User = Struct.new(:id, :email, :password_hash, :role, :is_active, :created_at, keyword_init: true)
  Post = Struct.new(:id, :user_id, :title, :content, :status, keyword_init: true)
  
  ROLES = { admin: 'ADMIN', user: 'USER' }.freeze
  STATUSES = { draft: 'DRAFT', published: 'PUBLISHED' }.freeze
end

# --- In-Memory LRU Cache Implementation ---
class LruCache
  Node = Struct.new(:key, :value, :expires_at, :prev, :next) do
    def expired?
      expires_at && Time.now > expires_at
    end
  end

  def initialize(capacity)
    @capacity = capacity
    @store = {}
    @head = Node.new
    @tail = Node.new
    @head.next = @tail
    @tail.prev = @head
  end

  def read(key)
    node = @store[key]
    return nil unless node

    if node.expired?
      purge(key)
      return nil
    end

    promote(node)
    node.value
  end

  def write(key, value, ttl: 300)
    node = @store[key]
    if node
      node.value = value
      node.expires_at = Time.now + ttl if ttl
      promote(node)
    else
      evict if @store.size >= @capacity
      new_node = Node.new(key, value, ttl ? Time.now + ttl : nil)
      @store[key] = new_node
      attach(new_node)
    end
  end

  def purge(key)
    node = @store.delete(key)
    detach(node) if node
  end

  private

  def promote(node)
    detach(node)
    attach(node)
  end

  def attach(node)
    node.next = @head.next
    node.prev = @head
    @head.next.prev = node
    @head.next = node
  end

  def detach(node)
    node.prev.next = node.next
    node.next.prev = node.prev
  end

  def evict
    lru_node = @tail.prev
    purge(lru_node.key)
  end
end

# --- Mock Persistence Layer (Module-based) ---
module Persistence
  @users = {}
  @posts = {}

  def self.find_user_by_id(id)
    puts "[DB] Querying for user with id: #{id}"
    sleep 0.05 # Simulate I/O
    @users[id]&.dup
  end

  def self.update_user_record(user)
    puts "[DB] Updating user record for id: #{user.id}"
    sleep 0.05
    @users[user.id] = user
  end
end

# --- Service Layer (Module-based) ---
module UserService
  def self.fetch_user(id, cache:)
    cache_key = "user:#{id}"
    
    # 1. Cache-Aside: Read from cache
    cached_user = cache.read(cache_key)
    if cached_user
      puts "[CACHE] HIT for #{cache_key}"
      return cached_user
    end
    puts "[CACHE] MISS for #{cache_key}"

    # 2. On miss, read from persistence
    user = Persistence.find_user_by_id(id)

    # 3. Write to cache
    cache.write(cache_key, user, ttl: 60) if user
    
    user
  end

  def self.change_user_status(id, is_active, cache:)
    user = Persistence.find_user_by_id(id)
    return unless user

    user.is_active = is_active
    Persistence.update_user_record(user)

    # Invalidation strategy
    cache_key = "user:#{id}"
    puts "[CACHE] PURGE for #{cache_key} due to status change"
    cache.purge(cache_key)
    
    user
  end
end

# --- Main Execution ---
if __FILE__ == $0
  puts "--- Variation 2: Module-based/Functional Style ---"

  # Setup
  app_cache = LruCache.new(5)
  
  # Seed database
  user1 = Domain::User.new(
    id: SecureRandom.uuid,
    email: 'bob@example.com',
    password_hash: 'hash456',
    role: Domain::ROLES[:admin],
    is_active: true,
    created_at: Time.now
  )
  Persistence.update_user_record(user1)

  # Demonstrate Cache-Aside
  puts "\n1. First call to fetch_user (DB query expected):"
  UserService.fetch_user(user1.id, cache: app_cache)

  puts "\n2. Second call to fetch_user (Cache hit expected):"
  UserService.fetch_user(user1.id, cache: app_cache)

  # Demonstrate Invalidation
  puts "\n3. Changing user status (invalidates cache):"
  UserService.change_user_status(user1.id, false, cache: app_cache)

  puts "\n4. Fetching user after status change (DB query expected):"
  UserService.fetch_user(user1.id, cache: app_cache)

  # Demonstrate Time-based Expiration
  puts "\n--- Demonstrating TTL Expiration ---"
  short_ttl_user_id = SecureRandom.uuid
  short_ttl_user = Domain::User.new(id: short_ttl_user_id, email: 'ttl@test.com', password_hash: '...', role: Domain::ROLES[:user], is_active: true, created_at: Time.now)
  Persistence.update_user_record(short_ttl_user)
  
  puts "Writing user to cache with 1 second TTL..."
  app_cache.write("user:#{short_ttl_user_id}", short_ttl_user, ttl: 1)
  puts "Reading immediately (should be a hit): #{app_cache.read("user:#{short_ttl_user_id}").email}"
  
  puts "Sleeping for 1.1 seconds..."
  sleep 1.1
  
  puts "Reading after TTL (should be a miss): #{app_cache.read("user:#{short_ttl_user_id}").inspect}"
end