require 'securerandom'
require 'time'
require 'dry-system'
require 'dry-configurable'

# --- Domain Schema ---
User = Struct.new(:id, :email, :password_hash, :role, :is_active, :created_at, keyword_init: true)
Post = Struct.new(:id, :user_id, :title, :content, :status, keyword_init: true)

# --- Caching Infrastructure: A custom LRU Cache with TTL ---
# Developer 1: "The Pragmatist" - A straightforward, custom-built LRU cache class.
class LruCache
  def initialize(max_size = 100)
    @max_size = max_size
    @cache = {}
    @lru_keys = []
  end

  def get(key)
    return nil unless @cache.key?(key)

    value, expires_at = @cache[key]

    if expires_at && Time.now > expires_at
      delete(key)
      return nil
    end

    # Move key to the end to mark it as recently used
    @lru_keys.delete(key)
    @lru_keys.push(key)

    value
  end

  def set(key, value, ttl_seconds: 60)
    evict! if @cache.size >= @max_size && !@cache.key?(key)

    expires_at = ttl_seconds ? Time.now + ttl_seconds : nil
    @cache[key] = [value, expires_at]
    @lru_keys.delete(key)
    @lru_keys.push(key)
    value
  end

  def delete(key)
    @cache.delete(key)
    @lru_keys.delete(key)
  end

  private

  def evict!
    oldest_key = @lru_keys.shift
    @cache.delete(oldest_key) if oldest_key
  end
end

# --- Mock Data Source ---
class MockUserRepository
  def initialize
    @users = {}
  end

  def find(id)
    puts "DATABASE HIT: Searching for user #{id}"
    @users[id]
  end

  def create(attrs)
    id = SecureRandom.uuid
    user = User.new(attrs.merge(id: id, created_at: Time.now))
    @users[id] = user
  end

  def update(id, attrs)
    return nil unless @users[id]
    puts "DATABASE HIT: Updating user #{id}"
    @users[id] = @users[id].to_h.merge(attrs).then { |h| User.new(h) }
  end
end

# --- Hanami Application Setup ---
class MyApp < Hanami::App
  extend Dry::Configurable
  setting :container, default: Dry::System::Container.new

  # Register components in the Hanami-style container
  container.register("cache.user_cache", -> { LruCache.new(10) })
  container.register("repositories.user_repo", -> { MockUserRepository.new })

  # Interactor for fetching a user, implementing cache-aside
  container.register("interactors.fetch_user") do
    Class.new do
      include Dry::Monads[:result]

      attr_reader :user_repo, :user_cache

      def initialize(user_repo: MyApp.container["repositories.user_repo"], user_cache: MyApp.container["cache.user_cache"])
        @user_repo = user_repo
        @user_cache = user_cache
      end

      def call(id)
        # 1. Cache-Aside: Try to get from cache first
        cached_user = user_cache.get("user:#{id}")
        if cached_user
          puts "CACHE HIT: Found user #{id} in cache."
          return Success(cached_user)
        end

        puts "CACHE MISS: User #{id} not in cache."

        # 2. On miss, get from data source
        user = user_repo.find(id)

        if user
          # 3. Store in cache
          user_cache.set("user:#{id}", user, ttl_seconds: 300)
          Success(user)
        else
          Failure(:not_found)
        end
      end
    end.new
  end

  # Interactor for updating a user, implementing cache invalidation
  container.register("interactors.update_user") do
    Class.new do
      attr_reader :user_repo, :user_cache

      def initialize(user_repo: MyApp.container["repositories.user_repo"], user_cache: MyApp.container["cache.user_cache"])
        @user_repo = user_repo
        @user_cache = user_cache
      end

      def call(id, data)
        updated_user = user_repo.update(id, data)
        if updated_user
          # Cache Invalidation: Delete the key on update
          puts "CACHE INVALIDATION: Deleting user #{id} from cache."
          user_cache.delete("user:#{id}")
        end
        updated_user
      end
    end.new
  end
end

# --- Demo Usage ---
puts "--- Variation 1: Classic Interactor Approach ---"

# Setup
user_repo = MyApp.container["repositories.user_repo"]
fetch_user = MyApp.container["interactors.fetch_user"]
update_user = MyApp.container["interactors.update_user"]

# Create a user directly in the repo for the demo
user = user_repo.create(email: "dev1@example.com", role: "USER", is_active: true)
puts "Created user with ID: #{user.id}"
puts

# 1. First fetch (should be a cache miss)
puts "1. First fetch for user #{user.id}"
fetch_user.call(user.id)
puts

# 2. Second fetch (should be a cache hit)
puts "2. Second fetch for user #{user.id}"
fetch_user.call(user.id)
puts

# 3. Update the user (should invalidate the cache)
puts "3. Updating user #{user.id}"
update_user.call(user.id, { is_active: false })
puts

# 4. Fetch after update (should be a cache miss again)
puts "4. Fetching user #{user.id} after update"
fetch_user.call(user.id)
puts

# 5. Final fetch (should be a cache hit with new data)
puts "5. Final fetch for user #{user.id}"
fetch_user.call(user.id)
puts