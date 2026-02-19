<pre>
# frozen_string_literal: true

# --- BOILERPLATE SETUP FOR A SELF-CONTAINED RAILS-LIKE ENVIRONMENT ---
require 'active_support/all'
require 'securerandom'
require 'logger'

# Mock Rails object
module Rails
  def self.cache
    @cache ||= ActiveSupport::Cache::MemoryStore.new(size: 64.megabytes) # LRU cache implementation
  end

  def self.logger
    @logger ||= Logger.new($stdout)
  end
end

# Mock Database
DB = {
  users: {},
  posts: {}
}.with_indifferent_access

# --- DOMAIN SCHEMA IMPLEMENTATION ---

class ApplicationRecord
  include ActiveModel::Model
  include ActiveModel::Attributes

  attribute :id, :string, default: -> { SecureRandom.uuid }
  attribute :created_at, :datetime, default: -> { Time.now }
  attribute :updated_at, :datetime, default: -> { Time.now }

  def self.find(id)
    record = DB[table_name][id]
    raise "Record not found" unless record
    new(record)
  end

  def self.where(conditions)
    DB[table_name].values.select do |record|
      conditions.all? { |key, value| record[key] == value }
    end.map { |attrs| new(attrs) }
  end

  def self.table_name
    name.downcase.pluralize
  end

  def save
    self.updated_at = Time.now
    DB[self.class.table_name][id] = attributes
    true
  end

  def destroy
    DB[self.class.table_name].delete(id)
    true
  end
end

class User &lt; ApplicationRecord
  attribute :email, :string
  attribute :password_hash, :string
  attribute :role, :string, default: 'USER'
  attribute :is_active, :boolean, default: true
end

class Post &lt; ApplicationRecord
  attribute :user_id, :string
  attribute :title, :string
  attribute :content, :string
  attribute :status, :string, default: 'DRAFT'
end

# --- CACHING PATTERN IMPLEMENTATION: The Functional Helper Developer ---

# This developer prefers utility modules over classes. Logic is organized by function (keys, fetching, invalidating)
# rather than by domain object. This approach is highly decoupled.

module CacheManager
  EXPIRATION_TIME = 15.minutes

  # Centralizes cache key generation to ensure consistency.
  module Keys
    def self.for_post(post_id)
      "v2:posts:#{post_id}"
    end

    def self.for_user_posts(user_id)
      # Using a version prefix (v2) is a good practice for sweeping caches.
      "v2:users:#{user_id}:posts"
    end
  end

  # Contains all fetch operations.
  module Fetch
    # Implements Cache-Aside for a Post
    def self.post(id)
      key = Keys.for_post(id)
      Rails.cache.fetch(key, expires_in: EXPIRATION_TIME) do
        Rails.logger.info "CACHE MISS: [CacheManager] Fetching Post##{id}."
        Post.find(id)
      end
    end

    # Implements Cache-Aside for a collection
    def self.user_posts(user_id)
      key = Keys.for_user_posts(user_id)
      Rails.cache.fetch(key, expires_in: EXPIRATION_TIME) do
        Rails.logger.info "CACHE MISS: [CacheManager] Fetching posts for User##{user_id}."
        Post.where(user_id: user_id)
      end
    end
  end

  # Contains all invalidation operations.
  module Invalidate
    # Implements explicit cache deletion for a Post
    def self.post(post_or_id)
      id = post_or_id.is_a?(Post) ? post_or_id.id : post_or_id
      key = Keys.for_post(id)
      Rails.logger.info "CACHE INVALIDATION: [CacheManager] Deleting key #{key}."
      Rails.cache.delete(key)
    end

    def self.user_posts(user_or_id)
      id = user_or_id.is_a?(User) ? user_or_id.id : user_or_id
      key = Keys.for_user_posts(id)
      Rails.logger.info "CACHE INVALIDATION: [CacheManager] Deleting key #{key}."
      Rails.cache.delete(key)
    end
  end
end

# --- DEMONSTRATION ---

# 1. Seed data
puts "--- Variation 3: Functional Helper Demo ---"
user = User.new(email: 'dev3@example.com')
user.save
post = Post.new(user_id: user.id, title: 'Functional Caching in Rails', content: '...')
post.save

# 2. First fetch (cache miss)
puts "\n1. First fetch for Post##{post.id}"
retrieved_post = CacheManager::Fetch.post(post.id)
puts "  - Retrieved: #{retrieved_post.title}"

# 3. Second fetch (cache hit)
puts "\n2. Second fetch for Post##{post.id}"
retrieved_post_cached = CacheManager::Fetch.post(post.id)
puts "  - Retrieved from cache: #{retrieved_post_cached.title}"

# 4. Fetch user posts collection (cache miss)
puts "\n3. Fetching user posts"
user_posts = CacheManager::Fetch.user_posts(user.id)
puts "  - Retrieved: #{user_posts.count} post(s)"

# 5. Invalidate both caches
puts "\n4. Invalidating caches for the post and the user's post list"
CacheManager::Invalidate.post(post)
CacheManager::Invalidate.user_posts(user)

# 6. Fetch again (cache miss)
puts "\n5. Fetching post after invalidation"
retrieved_post_after_invalidation = CacheManager::Fetch.post(post.id)
puts "  - Retrieved: #{retrieved_post_after_invalidation.title}"

puts "\n6. Fetching user posts after invalidation"
user_posts_after_invalidation = CacheManager::Fetch.user_posts(user.id)
puts "  - Retrieved: #{user_posts_after_invalidation.count} post(s)"
puts "----------------------------------------\n"
</pre>