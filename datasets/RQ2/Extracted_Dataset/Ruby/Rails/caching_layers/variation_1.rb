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

# Base model for common attributes
class ApplicationRecord
  include ActiveModel::Model
  include ActiveModel::Attributes

  attribute :id, :string, default: -> { SecureRandom.uuid }
  attribute :created_at, :datetime, default: -> { Time.now }

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
  attribute :role, :string, default: 'USER' # Enum: ADMIN, USER
  attribute :is_active, :boolean, default: true
end

class Post &lt; ApplicationRecord
  attribute :user_id, :string
  attribute :title, :string
  attribute :content, :string
  attribute :status, :string, default: 'DRAFT' # Enum: DRAFT, PUBLISHED
end

# --- CACHING PATTERN IMPLEMENTATION: The Service Object Developer ---

# This developer prefers to encapsulate caching logic within dedicated service objects.
# This approach keeps models and controllers clean, centralizing caching concerns for a domain entity.

class PostCachingService
  CACHE_EXPIRATION = 5.minutes

  # Implements Cache-Aside pattern for fetching a single post.
  def self.find(id)
    cache_key = "post:#{id}"
    
    # get/set logic is handled by `fetch`
    Rails.cache.fetch(cache_key, expires_in: CACHE_EXPIRATION) do
      Rails.logger.info "CACHE MISS: Fetching Post##{id} from database."
      Post.find(id)
    end
  end

  # Implements Cache-Aside for a collection query.
  def self.find_published_by_user(user)
    # A more complex key is needed for collections.
    # The user's updated_at timestamp could be part of the key for automatic invalidation.
    cache_key = "user:#{user.id}:published_posts"

    Rails.cache.fetch(cache_key, expires_in: CACHE_EXPIRATION) do
      Rails.logger.info "CACHE MISS: Fetching published posts for User##{user.id} from database."
      Post.where(user_id: user.id, status: 'PUBLISHED')
    end
  end

  # Implements explicit cache invalidation (delete).
  def self.invalidate(post)
    Rails.logger.info "CACHE INVALIDATION: Deleting cache for Post##{post.id}."
    Rails.cache.delete("post:#{post.id}")
    
    # Also invalidate the collection cache for the post's author.
    user = User.find(post.user_id)
    Rails.cache.delete("user:#{user.id}:published_posts")
  end
end

# --- DEMONSTRATION ---

# 1. Seed data
puts "--- Variation 1: Service Object Demo ---"
user = User.new(email: 'dev1@example.com')
user.save
post = Post.new(user_id: user.id, title: 'Service Objects in Rails', content: '...', status: 'PUBLISHED')
post.save

# 2. First fetch (cache miss)
puts "\n1. First fetch for Post##{post.id}"
retrieved_post = PostCachingService.find(post.id)
puts "  - Retrieved: #{retrieved_post.title}"

# 3. Second fetch (cache hit)
puts "\n2. Second fetch for Post##{post.id}"
retrieved_post_cached = PostCachingService.find(post.id)
puts "  - Retrieved from cache: #{retrieved_post_cached.title}"

# 4. Fetch collection (cache miss)
puts "\n3. First fetch for user's posts"
user_posts = PostCachingService.find_published_by_user(user)
puts "  - Retrieved: #{user_posts.map(&amp;:title)}"

# 5. Fetch collection (cache hit)
puts "\n4. Second fetch for user's posts"
user_posts_cached = PostCachingService.find_published_by_user(user)
puts "  - Retrieved from cache: #{user_posts_cached.map(&amp;:title)}"

# 6. Invalidate cache
puts "\n5. Updating post and invalidating cache"
post.title = "Updated: Service Objects in Rails"
post.save
PostCachingService.invalidate(post)

# 7. Fetch again (cache miss)
puts "\n6. Fetching post after invalidation"
retrieved_post_after_invalidation = PostCachingService.find(post.id)
puts "  - Retrieved new title: #{retrieved_post_after_invalidation.title}"
puts "----------------------------------------\n"
</pre>