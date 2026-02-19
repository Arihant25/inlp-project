require 'securerandom'
require 'time'
require 'dry-system'
require 'dry-configurable'

# --- Domain Schema ---
User = Struct.new(:id, :email, :password_hash, :role, :is_active, :created_at, keyword_init: true)
Post = Struct.new(:id, :user_id, :title, :content, :status, keyword_init: true)

# --- Caching Infrastructure: A simple Hash-based cache with TTL ---
# Developer 3: "The Functional Purist" - Prefers simple data structures and composing functions.
class SimpleTimedCache
  def initialize
    @store = {}
  end

  def read(key)
    payload = @store[key]
    return nil unless payload
    return payload[:value] if Time.now < payload[:expires_at]

    # Expired
    @store.delete(key)
    nil
  end

  def write(key, value, ttl_in_seconds)
    @store[key] = {
      value: value,
      expires_at: Time.now + ttl_in_seconds
    }
  end

  def purge(key)
    @store.delete(key)
  end
end

# --- Mock Data Source ---
# A simple module with functions, avoiding classes where possible.
module Persistence
  module UserRepo
    DB = {}

    module_function

    def find(id)
      puts "DB_READ: Looking up user ##{id}"
      DB[id]
    end

    def persist(user_data)
      id = user_data[:id] || SecureRandom.uuid
      user = User.new(user_data.merge(id: id, created_at: Time.now))
      puts "DB_WRITE: Persisting user ##{id}"
      DB[id] = user
    end
  end
end

# --- Hanami Application Setup ---
class MyApp < Hanami::App
  extend Dry::Configurable
  setting :container, default: Dry::System::Container.new

  # Register components
  container.register("persistence.user_repo", Persistence::UserRepo)
  container.register("cache.user_cache", -> { SimpleTimedCache.new })

  # --- Functional Operations ---
  # Registering business logic as Procs/lambdas in the container.

  # Cache-Aside Logic
  container.register("operations.find_user") do
    user_repo = container["persistence.user_repo"]
    user_cache = container["cache.user_cache"]
    cache_key_gen = ->(id) { "user-v1-#{id}" }

    ->(id) do
      cache_key = cache_key_gen.call(id)

      # 1. Check cache
      cached_user = user_cache.read(cache_key)
      if cached_user
        puts "CACHE_HIT: User ##{id}"
        return cached_user
      end

      # 2. On miss, call persistence
      puts "CACHE_MISS: User ##{id}"
      user = user_repo.find(id)

      # 3. Write to cache if found
      user_cache.write(cache_key, user, 300) if user
      user
    end
  end

  # Cache Invalidation Logic
  container.register("operations.update_user_email") do
    user_repo = container["persistence.user_repo"]
    user_cache = container["cache.user_cache"]
    cache_key_gen = ->(id) { "user-v1-#{id}" }

    ->(id, new_email) do
      user = user_repo.find(id)
      return unless user

      updated_user = user_repo.persist(user.to_h.merge(email: new_email))

      # Invalidate cache
      puts "CACHE_INVALIDATE: User ##{id}"
      user_cache.purge(cache_key_gen.call(id))

      updated_user
    end
  end
end

# --- Demo Usage ---
puts "--- Variation 3: Functional/Proc-based Approach ---"

# Resolve the operations (functions) from the container
find_user_op = MyApp.container["operations.find_user"]
update_user_op = MyApp.container["operations.update_user_email"]
user_repo = MyApp.container["persistence.user_repo"]

# Seed data
user = user_repo.persist(email: "dev3@example.com", role: "USER", is_active: true)
puts "Seeded user with ID: #{user.id}"
puts

# 1. First call to the find operation (cache miss)
puts "1. First find operation call"
find_user_op.call(user.id)
puts

# 2. Second call (cache hit)
puts "2. Second find operation call"
find_user_op.call(user.id)
puts

# 3. Call the update operation (invalidates cache)
puts "3. Update operation call"
update_user_op.call(user.id, "dev3-updated@example.com")
puts

# 4. Find after update (cache miss)
puts "4. Find operation after update"
found_user = find_user_op.call(user.id)
puts "Found user with new email: #{found_user.email}"
puts

# 5. Final find (cache hit)
puts "5. Final find operation call"
find_user_op.call(user.id)
puts