require 'securerandom'
require 'time'
require 'dry-system'
require 'dry-configurable'
require 'concurrent'

# --- Domain Schema ---
User = Struct.new(:id, :email, :password_hash, :role, :is_active, :created_at, keyword_init: true)
Post = Struct.new(:id, :user_id, :title, :content, :status, keyword_init: true)

# --- Caching Infrastructure: A more robust, thread-safe cache store ---
# Developer 2: "The Architect" - Prefers clear boundaries and design patterns like Decorator.
# This cache store is designed to be more robust for a concurrent environment.
class ThreadSafeLruCacheStore
  def initialize(capacity: 100)
    @capacity = capacity
    @cache = Concurrent::Hash.new
    @lru_list = Concurrent::Array.new
    @lock = Mutex.new
  end

  def read(key)
    entry = @cache[key]
    return nil unless entry

    value, expires_at = entry
    if expires_at && Time.now.to_i > expires_at
      delete(key)
      return nil
    end

    touch(key)
    value
  end

  def write(key, value, expires_in: 60)
    @lock.synchronize do
      @cache.delete(@lru_list.first) if @lru_list.length >= @capacity && !@cache.key?(key)
      @lru_list.delete(key)
    end

    expires_at = expires_in ? Time.now.to_i + expires_in : nil
    @cache[key] = [value, expires_at]
    touch(key)
    value
  end

  def delete(key)
    @lock.synchronize do
      @cache.delete(key)
      @lru_list.delete(key)
    end
  end

  private

  def touch(key)
    @lock.synchronize do
      @lru_list.delete(key)
      @lru_list.push(key)
    end
  end
end

# --- Mock Data Source (The "Primary" Repository) ---
class PrimaryUserRepository
  def initialize
    @db_data = {}
  end

  def find(id)
    puts "PRIMARY DATA SOURCE: Querying DB for user ID: #{id}"
    @db_data[id]
  end

  def save(user)
    puts "PRIMARY DATA SOURCE: Writing to DB for user ID: #{user.id}"
    @db_data[user.id] = user
  end

  def delete(id)
    puts "PRIMARY DATA SOURCE: Deleting from DB for user ID: #{id}"
    @db_data.delete(id)
  end
end

# --- The Decorator implementing the Caching Layer ---
class CachingUserRepository
  def initialize(primary_repo:, cache_store:)
    @primary_repo = primary_repo
    @cache_store = cache_store
  end

  # Cache-Aside implementation
  def find(id)
    cache_key = "users/#{id}"
    cached_user = @cache_store.read(cache_key)
    return cached_user if cached_user

    puts "CACHE MISS: User #{id} not found in cache. Fetching from primary source."
    user = @primary_repo.find(id)
    @cache_store.write(cache_key, user, expires_in: 300) if user
    user
  end

  # Write-Through with Invalidation
  def save(user)
    @primary_repo.save(user)
    cache_key = "users/#{user.id}"
    puts "CACHE INVALIDATION: Deleting #{cache_key} due to save operation."
    @cache_store.delete(cache_key) # Invalidate old entry
    @cache_store.write(cache_key, user, expires_in: 300) # Prime cache with new data
    user
  end

  # Delete with Invalidation
  def delete(id)
    result = @primary_repo.delete(id)
    cache_key = "users/#{id}"
    puts "CACHE INVALIDATION: Deleting #{cache_key} due to delete operation."
    @cache_store.delete(cache_key)
    result
  end
end

# --- Hanami Application Setup ---
class MyApp < Hanami::App
  extend Dry::Configurable
  setting :container, default: Dry::System::Container.new

  # Register the cache store
  container.register("cache.default_store", -> { ThreadSafeLruCacheStore.new })

  # Register the primary (real) repository with a specific key
  container.register("repositories.user_primary", -> { PrimaryUserRepository.new })

  # Register the decorator as the main repository.
  # The rest of the app will use this and be unaware of the caching layer.
  container.register("repositories.user") do
    CachingUserRepository.new(
      primary_repo: container["repositories.user_primary"],
      cache_store: container["cache.default_store"]
    )
  end
end

# --- Demo Usage ---
puts "--- Variation 2: Repository Decorator Approach ---"

# The application code only ever asks for the main repository.
user_repo = MyApp.container["repositories.user"]

# Create a user for the demo
new_user = User.new(id: SecureRandom.uuid, email: "dev2@example.com", role: "ADMIN", is_active: true, created_at: Time.now)
user_repo.save(new_user)
puts

# 1. First fetch (should be a cache miss, as save invalidated and primed it, but let's prove the find logic)
user_repo.delete(new_user.id) # delete to clear cache
user_repo.save(new_user) # re-save to have it in DB
user_repo.delete(new_user.id) # delete again to ensure cache is empty
MyApp.container["repositories.user_primary"].save(new_user) # put it in DB without caching

puts "1. First fetch for user #{new_user.id}"
user = user_repo.find(new_user.id)
puts "Found user: #{user.email}"
puts

# 2. Second fetch (should be a cache hit)
puts "2. Second fetch for user #{new_user.id}"
user = user_repo.find(new_user.id)
puts "Found user from cache: #{user.email}"
puts

# 3. Update the user (save acts as update, invalidates and re-primes cache)
puts "3. Updating user #{new_user.id}"
updated_user = user.to_h.merge(is_active: false).then { |h| User.new(h) }
user_repo.save(updated_user)
puts

# 4. Fetch after update (should be a cache hit with the new data)
puts "4. Fetching user #{new_user.id} after update"
user = user_repo.find(new_user.id)
puts "Found updated user from cache: #{user.email}, active: #{user.is_active}"
puts