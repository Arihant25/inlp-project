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

  # In a real Repository pattern, the data source would be more abstract.
  # For this demo, the models still know how to talk to the mock DB.
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

  def save!
    self.updated_at = Time.now
    DB[self.class.table_name][id] = attributes
    self
  end

  def destroy!
    DB[self.class.table_name].delete(id)
    self
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

# --- CACHING PATTERN IMPLEMENTATION: The Repository Pattern Developer ---

# This developer uses the Repository pattern to completely abstract the data source (DB vs. Cache).
# The rest of the application only interacts with the repository, not the ActiveRecord models directly.
# Caching is an implementation detail hidden inside the repository.

class PostRepository
  def initialize(cache_store: Rails.cache, data_source: Post)
    @cache = cache_store
    @source = data_source
  end

  # Implements Cache-Aside pattern internally.
  def find(id)
    key = post_cache_key(id)
    @cache.fetch(key, expires_in: 1.hour) do
      Rails.logger.info "CACHE MISS (Repo): Fetching Post##{id} from source."
      @source.find(id)
    end
  end

  def find_by_user(user)
    key = user_posts_cache_key(user.id)
    @cache.fetch(key, expires_in: 1.hour) do
      Rails.logger.info "CACHE MISS (Repo): Fetching posts for User##{user.id} from source."
      @source.where(user_id: user.id)
    end
  end

  # Write operations automatically handle cache invalidation.
  def save(post)
    post.save!
    invalidate_for_post(post)
    post
  end

  def destroy(post)
    invalidate_for_post(post) # Invalidate before deleting
    post.destroy!
    post
  end

  private

  # Invalidation logic is a private implementation detail.
  def invalidate_for_post(post)
    Rails.logger.info "CACHE INVALIDATION (Repo): Invalidating caches related to Post##{post.id}."
    @cache.delete(post_cache_key(post.id))
    @cache.delete(user_posts_cache_key(post.user_id))
  end

  def post_cache_key(id)
    "repo:post:#{id}"
  end

  def user_posts_cache_key(user_id)
    "repo:user:#{user_id}:posts"
  end
end

# --- DEMONSTRATION ---

# 1. Seed data
puts "--- Variation 4: Repository Pattern Demo ---"
user = User.new(email: 'dev4@example.com')
user.save!
post_attrs = { user_id: user.id, title: 'Repository Pattern in Rails', content: '...' }
post = Post.new(post_attrs)

# 2. Instantiate the repository. In a real app, this would be injected.
post_repo = PostRepository.new

# 3. Create a post via the repository, which also warms the cache.
puts "\n1. Saving post via repository"
post_repo.save(post) # This saves and invalidates (though nothing is cached yet)

# 4. First fetch (cache miss)
puts "\n2. First fetch for Post##{post.id}"
retrieved_post = post_repo.find(post.id)
puts "  - Retrieved: #{retrieved_post.title}"

# 5. Second fetch (cache hit)
puts "\n3. Second fetch for Post##{post.id}"
retrieved_post_cached = post_repo.find(post.id)
puts "  - Retrieved from cache: #{retrieved_post_cached.title}"

# 6. Update the post via the repository. Invalidation is automatic.
puts "\n4. Updating post via repository, which handles invalidation"
post.title = "Updated: Repository Pattern"
post_repo.save(post)

# 7. Fetch again (cache miss, because `save` invalidated it)
puts "\n5. Fetching post after repository save"
retrieved_post_after_save = post_repo.find(post.id)
puts "  - Retrieved new title: #{retrieved_post_after_save.title}"

# 8. Destroy a post
puts "\n6. Destroying post via repository"
post_to_destroy = Post.new(user_id: user.id, title: "To Be Deleted")
post_repo.save(post_to_destroy) # Save it first
post_repo.find(post_to_destroy.id) # Cache it
post_repo.destroy(post_to_destroy) # Destroy and invalidate
puts "  - Post destroyed. Cache key repo:post:#{post_to_destroy.id} was deleted."
puts "----------------------------------------\n"
</pre>