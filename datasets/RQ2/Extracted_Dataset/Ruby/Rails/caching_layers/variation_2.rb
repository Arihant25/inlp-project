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

# Mock ActiveRecord::Base for callbacks and associations
class ApplicationRecord
  include ActiveModel::Model
  include ActiveModel::Attributes
  extend ActiveModel::Callbacks

  define_model_callbacks :commit

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
    run_callbacks(:commit)
    true
  end

  def destroy
    DB[self.class.table_name].delete(id)
    run_callbacks(:commit)
    true
  end
end

# --- CACHING PATTERN IMPLEMENTATION: The Concern &amp; Callback Developer ---

# This developer prefers to keep caching logic close to the model using Concerns.
# Invalidation is handled automatically via ActiveRecord callbacks.

module Cacheable
  extend ActiveSupport::Concern

  included do
    after_commit :flush_cache
  end

  def flush_cache
    Rails.logger.info "CACHE INVALIDATION: Flushing cache for #{self.class.name}##{id} via callback."
    Rails.cache.delete(self.class.cache_key(id))
  end

  class_methods do
    def cache_key(id)
      "#{name.downcase}:#{id}"
    end

    # Implements Cache-Aside pattern
    def cached_find(id)
      Rails.cache.fetch(cache_key(id), expires_in: 10.minutes) do
        Rails.logger.info "CACHE MISS: Fetching #{name}##{id} from database."
        find(id)
      end
    end
  end
end

class User &lt; ApplicationRecord
  include Cacheable # Users can be cached too
  attribute :email, :string
  attribute :password_hash, :string
  attribute :role, :string, default: 'USER'
  attribute :is_active, :boolean, default: true

  # Mock association
  def posts
    Post.where(user_id: id)
  end
end

class Post &lt; ApplicationRecord
  include Cacheable
  attribute :user_id, :string
  attribute :title, :string
  attribute :content, :string
  attribute :status, :string, default: 'DRAFT'

  # Mock association with `touch: true` behavior for cache invalidation
  def user
    user_record = User.find(user_id)
    # Simulate `touch: true` by updating the user's timestamp, which would
    # be used in a collection cache key.
    user_record.save 
    user_record
  end
end

# --- DEMONSTRATION ---

# 1. Seed data
puts "--- Variation 2: Concern &amp; Callback Demo ---"
user = User.new(email: 'dev2@example.com')
user.save
post = Post.new(user_id: user.id, title: 'Concerns in Rails', content: '...')
post.save # after_commit callback is triggered here, but there's nothing in cache yet.

# 2. First fetch (cache miss)
puts "\n1. First fetch for Post##{post.id}"
retrieved_post = Post.cached_find(post.id)
puts "  - Retrieved: #{retrieved_post.title}"

# 3. Second fetch (cache hit)
puts "\n2. Second fetch for Post##{post.id}"
retrieved_post_cached = Post.cached_find(post.id)
puts "  - Retrieved from cache: #{retrieved_post_cached.title}"

# 4. Update the post. The `after_commit` callback will automatically invalidate the cache.
puts "\n3. Updating post, which triggers `after_commit` to invalidate cache"
post.title = "Updated: Concerns in Rails"
post.save # This triggers `flush_cache`

# 5. Fetch again (cache miss)
puts "\n4. Fetching post after automatic invalidation"
retrieved_post_after_invalidation = Post.cached_find(post.id)
puts "  - Retrieved new title: #{retrieved_post_after_invalidation.title}"

# 6. Demonstrate `touch` behavior for collection caching
user_cache_key = "user_posts:#{user.id}:#{user.updated_at.to_i}"
puts "\n5. Caching a collection with a timestamp-based key: #{user_cache_key}"
Rails.cache.fetch(user_cache_key) { user.posts }

# 7. Update a post, which "touches" the user, changing their updated_at
puts "\n6. Updating a post, which should invalidate the user's collection cache"
post_to_update = user.posts.first
post_to_update.content = "New content."
post_to_update.save # This will also call `user.save` due to our mock `touch`

# 8. The old cache key is now stale
new_user_cache_key = "user_posts:#{user.id}:#{User.find(user.id).updated_at.to_i}"
puts "  - New key is now: #{new_user_cache_key}"
puts "  - Old key is stale. Cache for collection is effectively invalidated."
puts "----------------------------------------\n"
</pre>