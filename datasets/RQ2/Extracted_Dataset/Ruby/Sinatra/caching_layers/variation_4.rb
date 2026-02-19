require 'sinatra'
require 'json'
require 'securerandom'
require 'time'

# --- A Sophisticated, Self-Contained LRU Cache (simulating a gem) ---
# This implementation handles both LRU eviction and per-item TTL.
class AdvancedLruCache
  # Internal class to hold value and metadata
  Node = Struct.new(:key, :value, :expires_at, :prev, :next)

  def initialize(max_size:, default_ttl: 300)
    @max_size = max_size
    @default_ttl = default_ttl
    @store = {}
    @head = Node.new
    @tail = Node.new
    @head.next = @tail
    @tail.prev = @head
  end

  def get(key)
    node = @store[key]
    return nil unless node

    if node.expires_at && Time.now > node.expires_at
      puts "CACHE [EXPIRED]: #{key}"
      delete(key)
      return nil
    end

    puts "CACHE [HIT]: #{key}"
    _move_to_front(node)
    node.value
  end

  def set(key, value, ttl: nil)
    ttl_seconds = ttl || @default_ttl
    expires_at = Time.now + ttl_seconds

    if @store.key?(key)
      node = @store[key]
      node.value = value
      node.expires_at = expires_at
      _move_to_front(node)
    else
      _evict if @store.size >= @max_size
      node = Node.new(key, value, expires_at)
      @store[key] = node
      _add_to_front(node)
    end
    puts "CACHE [SET]: #{key} (TTL: #{ttl_seconds}s)"
  end

  def delete(key)
    node = @store.delete(key)
    return unless node
    
    puts "CACHE [DELETE]: #{key}"
    _remove_node(node)
  end

  private

  def _add_to_front(node)
    node.next = @head.next
    node.prev = @head
    @head.next.prev = node
    @head.next = node
  end

  def _remove_node(node)
    prev_node = node.prev
    next_node = node.next
    prev_node.next = next_node
    next_node.prev = prev_node
  end

  def _move_to_front(node)
    _remove_node(node)
    _add_to_front(node)
  end

  def _evict
    last_node = @tail.prev
    return if last_node == @head
    puts "CACHE [EVICT]: #{last_node.key}"
    delete(last_node.key)
  end
end

# --- Global Cache Instance ---
CACHE = AdvancedLruCache.new(max_size: 100, default_ttl: 600)

# --- Mock ORM/Data Layer ---
module DataSource
  # Using Structs to represent models
  User = Struct.new(:id, :email, :password_hash, :role, :is_active, :created_at)
  Post = Struct.new(:id, :user_id, :title, :content, :status)

  # In-memory store
  STORE = { users: {}, posts: {} }

  def self.seed
    user_id = SecureRandom.uuid
    STORE[:users][user_id] = User.new(user_id, 'test@dev.io', 'hashabc', 'USER', true, Time.now.utc)
    STORE[:posts][SecureRandom.uuid] = Post.new(SecureRandom.uuid, user_id, 'API Design', '...', 'PUBLISHED')
  end

  module UserMethods
    def find(id)
      puts "DATABASE LOOKUP: User(id: #{id})"
      sleep 0.05 # Simulate I/O
      STORE[:users][id]
    end

    def update(id, attrs)
      user = find(id)
      return nil unless user
      attrs.each { |k, v| user[k] = v if user.members.include?(k.to_sym) }
      user
    end
  end

  module PostMethods
    def find(id)
      puts "DATABASE LOOKUP: Post(id: #{id})"
      sleep 0.05 # Simulate I/O
      STORE[:posts][id]
    end

    def destroy(id)
      STORE[:posts].delete(id)
    end
  end
  
  User.extend(UserMethods)
  Post.extend(PostMethods)
end

DataSource.seed

# --- High-level Cache Service Module ---
module CacheService
  def self.fetch(key, ttl: nil)
    cached = CACHE.get(key)
    return cached if cached

    puts "CACHE [MISS]: #{key}"
    fresh_data = yield
    CACHE.set(key, fresh_data, ttl: ttl) if fresh_data
    fresh_data
  end

  def self.invalidate(key)
    CACHE.delete(key)
  end
end

# --- Sinatra Routes (Clean & Declarative) ---
set :bind, '0.0.0.0'

before do
  content_type :json
end

# Get a user, using the cache-aside service
get '/users/:id' do
  user = CacheService.fetch("user:#{params[:id]}", ttl: 3600) do
    DataSource::User.find(params[:id])
  end

  if user
    user.to_h.to_json
  else
    halt 404, { error: "User not found" }.to_json
  end
end

# Update a user, invalidating the cache
put '/users/:id' do
  user = DataSource::User.update(params[:id], { is_active: false })
  
  if user
    CacheService.invalidate("user:#{params[:id]}")
    user.to_h.to_json
  else
    halt 404, { error: "User not found" }.to_json
  end
end

# Get a post, using the cache-aside service with a shorter TTL
get '/posts/:id' do
  post = CacheService.fetch("post:#{params[:id]}", ttl: 120) do
    DataSource::Post.find(params[:id])
  end

  if post
    post.to_h.to_json
  else
    halt 404, { error: "Post not found" }.to_json
  end
end

# Delete a post, invalidating the cache
delete '/posts/:id' do
  post = DataSource::Post.destroy(params[:id])

  if post
    CacheService.invalidate("post:#{params[:id]}")
    status 204
  else
    halt 404, { error: "Post not found" }.to_json
  end
end