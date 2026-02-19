require 'sinatra/base'
require 'json'
require 'securerandom'
require 'time'

# --- LRU Cache Implementation ---
# A more sophisticated cache that evicts the least recently used item when full.
class LruCache
  def initialize(max_size)
    @max_size = max_size
    @store = {}
    @list_head = nil
    @list_tail = nil
  end

  def get(key)
    node = @store[key]
    return nil unless node

    # Move node to the front of the list (most recently used)
    move_to_front(node)
    
    puts "LRU CACHE HIT: #{key}"
    node[:value]
  end

  def set(key, value)
    if @store.key?(key)
      node = @store[key]
      node[:value] = value
      move_to_front(node)
    else
      evict if @store.size >= @max_size
      node = { key: key, value: value, prev: nil, nxt: @list_head }
      @list_head[:prev] = node if @list_head
      @list_head = node
      @list_tail = node unless @list_tail
      @store[key] = node
    end
    puts "LRU CACHE SET: #{key}"
    value
  end

  def delete(key)
    node = @store.delete(key)
    return unless node

    if node[:prev]
      node[:prev][:nxt] = node[:nxt]
    else
      @list_head = node[:nxt]
    end

    if node[:nxt]
      node[:nxt][:prev] = node[:prev]
    else
      @list_tail = node[:prev]
    end
    puts "LRU CACHE DELETE: #{key}"
  end

  private

  def move_to_front(node)
    return if node == @list_head
    
    node[:prev][:nxt] = node[:nxt]
    node[:nxt][:prev] = node[:prev] if node[:nxt]
    @list_tail = node[:prev] if node == @list_tail

    node[:prev] = nil
    node[:nxt] = @list_head
    @list_head[:prev] = node
    @list_head = node
  end

  def evict
    return unless @list_tail
    puts "LRU CACHE EVICT: #{@list_tail[:key]}"
    delete(@list_tail[:key])
  end
end

# --- Caching Logic as a Reusable Module ---
module Cacheable
  # Implements the cache-aside pattern
  def fetch(key, ttl: 300)
    cached_item = settings.cache.get(key)
    
    # This implementation doesn't have built-in TTL, so we wrap it
    if cached_item && Time.now < cached_item[:expires_at]
      return cached_item[:data]
    end

    # Cache miss or expired, fetch from source by executing the block
    puts "CACHE MISS/EXPIRED: #{key}"
    fresh_item = yield
    
    if fresh_item
      payload = { data: fresh_item, expires_at: Time.now + ttl }
      settings.cache.set(key, payload)
    end
    
    fresh_item
  end

  def invalidate(key)
    settings.cache.delete(key)
  end
end

# --- Modular Sinatra Application ---
class ModularApi < Sinatra::Base
  # Include the caching logic
  helpers Cacheable

  # --- Configuration ---
  configure do
    set :cache, LruCache.new(50) # LRU cache with a max size of 50 items
  end

  # --- Mock Data Models & Store ---
  User = Struct.new(:id, :email, :password_hash, :role, :is_active, :created_at)
  Post = Struct.new(:id, :user_id, :title, :content, :status)
  
  # Using class variables for a simple, shared data store
  @@users = {}
  @@posts = {}
  
  user_id = SecureRandom.uuid
  @@users[user_id] = User.new(user_id, 'admin@modular.com', 'hash789', 'ADMIN', true, Time.now.utc)
  @@posts[SecureRandom.uuid] = Post.new(SecureRandom.uuid, user_id, 'Modular Post', 'Content.', 'DRAFT')

  # --- Routes ---
  before do
    content_type :json
  end

  get '/users/:id' do
    user = fetch("user:#{params[:id]}", ttl: 600) do
      puts "DB FETCH: User #{params[:id]}"
      sleep 0.1 # Simulate latency
      @@users[params[:id]]
    end

    if user
      user.to_h.to_json
    else
      status 404
      { error: 'Not Found' }.to_json
    end
  end

  put '/users/:id' do
    user = @@users[params[:id]]
    halt 404, { error: 'Not Found' }.to_json unless user

    # Simulate update
    user.role = user.role == 'ADMIN' ? 'USER' : 'ADMIN'
    @@users[params[:id]] = user

    invalidate("user:#{params[:id]}")

    user.to_h.to_json
  end
  
  get '/posts/:id' do
    post = fetch("post:#{params[:id]}") do # Use default TTL
      puts "DB FETCH: Post #{params[:id]}"
      sleep 0.1 # Simulate latency
      @@posts[params[:id]]
    end

    if post
      post.to_h.to_json
    else
      status 404
      { error: 'Not Found' }.to_json
    end
  end
end

# To run this app: ModularApi.run!