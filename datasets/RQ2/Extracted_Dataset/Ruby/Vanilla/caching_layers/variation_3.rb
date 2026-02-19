require 'securerandom'
require 'time'

# --- Domain Entities ---
module Domain
  User = Struct.new(:id, :email, :password_hash, :role, :is_active, :created_at, keyword_init: true)
  Post = Struct.new(:id, :user_id, :title, :content, :status, keyword_init: true)
end

# --- Mock Data Source ---
module MockDB
  STORE = {
    users: {},
    posts: {}
  }

  def self.query(table, id)
    puts "  [DataSource] Reading #{table.to_s.chop} ##{id}"
    sleep 0.08 # Simulate latency
    STORE[table][id]
  end

  def self.persist(table, record)
    puts "  [DataSource] Writing #{table.to_s.chop} ##{record.id}"
    sleep 0.08
    STORE[table][record.id] = record
  end
end

# --- Compact LRU Cache Implementation ---
class InMemoryCache
  class Entry
    attr_accessor :key, :payload, :expires, :prev, :next
    def initialize(k, p, ttl)
      @key, @payload = k, p
      @expires = ttl ? Time.now + ttl : nil
    end
    def is_expired?
      @expires && Time.now > @expires
    end
  end

  def initialize(max_size)
    @max_size = max_size
    @lookup = {}
    @head = Entry.new(nil, nil, nil)
    @tail = Entry.new(nil, nil, nil)
    @head.next = @tail
    @tail.prev = @head
  end

  def get(key)
    entry = @lookup[key]
    return nil unless entry
    if entry.is_expired?
      delete(key)
      return nil
    end
    _promote(entry)
    entry.payload
  end

  def set(key, payload, ttl_sec: 300)
    delete(key) if @lookup.key?(key)
    _evict if @lookup.size >= @max_size
    entry = Entry.new(key, payload, ttl_sec)
    @lookup[key] = entry
    _push(entry)
  end

  def delete(key)
    entry = @lookup.delete(key)
    _unlink(entry) if entry
  end

  private
  def _push(entry)
    entry.next = @head.next
    entry.prev = @head
    @head.next.prev = entry
    @head.next = entry
  end

  def _unlink(entry)
    entry.prev.next = entry.next
    entry.next.prev = entry.prev
  end

  def _promote(entry)
    _unlink(entry)
    _push(entry)
  end

  def _evict
    lru_entry = @tail.prev
    delete(lru_entry.key)
  end
end

# --- Service Object Pattern ---
class FetchUserService
  def initialize(cache_provider)
    @cache = cache_provider
  end

  def call(id:)
    cache_key = "user_v1:#{id}"
    
    # 1. Attempt to fetch from cache
    cached_data = @cache.get(cache_key)
    if cached_data
      puts "[Service:FetchUser] Cache HIT for #{id}"
      return cached_data
    end
    puts "[Service:FetchUser] Cache MISS for #{id}"

    # 2. On miss, fetch from primary data source
    user_record = MockDB.query(:users, id)

    # 3. Store in cache for subsequent requests
    @cache.set(cache_key, user_record, ttl_sec: 120) if user_record

    user_record
  end
end

class UpdatePostService
  def initialize(cache_provider)
    @cache = cache_provider
  end

  def call(id:, new_title:)
    post = MockDB.query(:posts, id)
    return nil unless post

    post.title = new_title
    MockDB.persist(:posts, post)

    # Invalidation: Remove the old entry from cache
    cache_key = "post_v1:#{id}"
    puts "[Service:UpdatePost] Invalidating cache for post #{id}"
    @cache.delete(cache_key)
    
    post
  end
end

# --- Main Execution Block ---
if __FILE__ == $0
  puts "--- Variation 3: Service Object Pattern ---"

  # Setup
  shared_cache = InMemoryCache.new(10)
  
  # Create and persist some domain objects
  user = Domain::User.new(id: SecureRandom.uuid, email: 'charlie@example.com', password_hash: 'hash789', role: :user, is_active: false, created_at: Time.now)
  post = Domain::Post.new(id: SecureRandom.uuid, user_id: user.id, title: "Initial Title", content: "...", status: :draft)
  MockDB.persist(:users, user)
  MockDB.persist(:posts, post)

  # --- Demonstrate Cache-Aside via Service Object ---
  fetch_user_service = FetchUserService.new(shared_cache)
  
  puts "\n1. Executing FetchUserService (expect cache miss):"
  fetch_user_service.call(id: user.id)

  puts "\n2. Executing FetchUserService again (expect cache hit):"
  fetch_user_service.call(id: user.id)

  # --- Demonstrate Cache Invalidation via Service Object ---
  update_post_service = UpdatePostService.new(shared_cache)
  
  # First, we need to get the post into the cache. We'll do it manually for this demo.
  shared_cache.set("post_v1:#{post.id}", post)
  puts "\nManually cached post: #{shared_cache.get("post_v1:#{post.id}").title}"

  puts "\n3. Executing UpdatePostService (invalidates cache):"
  update_post_service.call(id: post.id, new_title: "A Much Better Title")

  puts "\n4. Checking cache for post after update (expect nil):"
  cached_post = shared_cache.get("post_v1:#{post.id}")
  puts "  Result: #{cached_post.inspect}"
end