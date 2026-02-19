require 'securerandom'
require 'time'
require 'dry-system'
require 'dry-configurable'

# --- Domain Schema ---
User = Struct.new(:id, :email, :password_hash, :role, :is_active, :created_at, keyword_init: true)
Post = Struct.new(:id, :user_id, :title, :content, :status, keyword_init: true)

# --- Caching Infrastructure: A simple LRU Cache implementation ---
# This cache will be used by the Cacheable concern.
class SimpleLruStore
  def initialize(size: 100)
    @size = size
    @data = {}
    @lru_order = []
  end

  def fetch(key, ttl: 60)
    if @data.key?(key)
      value, expires_at = @data[key]
      if Time.now > expires_at
        delete(key)
        return nil # Expired
      end
      @lru_order.delete(key)
      @lru_order.push(key)
      return value
    end
    nil
  end

  def write(key, value, ttl: 60)
    if @data.size >= @size && !@data.key?(key)
      oldest_key = @lru_order.shift
      @data.delete(oldest_key)
    end
    @lru_order.delete(key)
    @data[key] = [value, Time.now + ttl]
    @lru_order.push(key)
  end

  def delete(key)
    @data.delete(key)
    @lru_order.delete(key)
  end
end

# --- The Concern/Mixin for Cacheable Repositories ---
# Developer 4: "The Rails Veteran" - Likes to use Concerns (Modules) for reusable behavior.
module CacheableRepository
  attr_writer :cache_store

  # The core cache-aside logic is encapsulated here.
  def fetch_cached(key, ttl: 300, &block)
    # 1. Try to fetch from cache
    cached_value = @cache_store.fetch(key, ttl: ttl)
    if cached_value
      puts "CACHE HIT for key: #{key}"
      return cached_value
    end

    # 2. On miss, execute the block to get data from the primary source
    puts "CACHE MISS for key: #{key}"
    live_value = block.call

    # 3. Write the result to the cache
    @cache_store.write(key, live_value, ttl: ttl) if live_value

    live_value
  end

  def expire_from_cache(key)
    puts "CACHE EXPIRE for key: #{key}"
    @cache_store.delete(key)
  end
end

# --- Hanami-style Repository including the Concern ---
class UserRepository
  # Mix in the caching behavior
  include CacheableRepository

  def initialize
    # In a real app, this would be a database connection.
    @db = {}
  end

  # The find method is now enhanced with caching.
  def find(id)
    fetch_cached("user:#{id}", ttl: 600) do
      puts "DB QUERY: Finding user #{id}"
      @db[id]
    end
  end

  def save(user_attrs)
    id = user_attrs[:id] || SecureRandom.uuid
    user = User.new(user_attrs.merge(id: id, created_at: Time.now))
    puts "DB WRITE: Saving user #{id}"
    @db[user.id] = user
    # Invalidate cache on save/update
    expire_from_cache("user:#{user.id}")
    user
  end
end

# --- Hanami Application Setup ---
class MyApp < Hanami::App
  extend Dry::Configurable
  setting :container, default: Dry::System::Container.new

  # Register the global cache store
  container.register("cache.store", -> { SimpleLruStore.new })

  # Register the repository and inject the cache store into it.
  # This is a common pattern in Hanami/dry-system for wiring up dependencies.
  container.register("repositories.user") do
    repo = UserRepository.new
    repo.cache_store = container["cache.store"]
    repo
  end
end

# --- Demo Usage ---
puts "--- Variation 4: Concern/Mixin Approach ---"

# Resolve the repository from the container. It's already configured with caching.
user_repo = MyApp.container["repositories.user"]

# Create a user
user = user_repo.save(email: "dev4@example.com", role: "ADMIN", is_active: true)
puts "Created user: #{user.id}"
puts

# 1. First find (should be a cache miss)
puts "1. First find for user #{user.id}"
user_repo.find(user.id)
puts

# 2. Second find (should be a cache hit)
puts "2. Second find for user #{user.id}"
user_repo.find(user.id)
puts

# 3. Update the user (the save method automatically expires the cache)
puts "3. Updating user #{user.id}"
user_repo.save(user.to_h.merge(is_active: false))
puts

# 4. Find after update (should be a cache miss)
puts "4. Find after update for user #{user.id}"
updated_user = user_repo.find(user.id)
puts "Found user, is_active: #{updated_user.is_active}"
puts

# 5. Final find (should be a cache hit with the new data)
puts "5. Final find for user #{user.id}"
user_repo.find(user.id)
puts