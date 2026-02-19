require 'sinatra'
require 'json'
require 'securerandom'
require 'time'

# --- Configuration & Mock Data ---

# Use a simple in-memory hash as our cache store.
# In a real app, this might be Redis, Memcached, etc.
configure do
  set :cache, {}
  set :cache_metadata, {} # To store TTL and other info
  set :default_ttl, 300 # 5 minutes
end

# Mock Database using Structs and Hashes
User = Struct.new(:id, :email, :password_hash, :role, :is_active, :created_at)
Post = Struct.new(:id, :user_id, :title, :content, :status)

DB = {
  users: {},
  posts: {}
}

# Seed the database with some data
user_id_1 = SecureRandom.uuid
DB[:users][user_id_1] = User.new(
  user_id_1,
  'admin@example.com',
  'hash123',
  'ADMIN',
  true,
  Time.now.utc
)

post_id_1 = SecureRandom.uuid
DB[:posts][post_id_1] = Post.new(
  post_id_1,
  user_id_1,
  'First Post',
  'This is the content of the first post.',
  'PUBLISHED'
)

# --- Caching Helper Methods (Functional Style) ---

helpers do
  def cache_set(key, value, ttl = settings.default_ttl)
    settings.cache[key] = value
    settings.cache_metadata[key] = { expires_at: Time.now + ttl }
    puts "CACHE SET: #{key}"
  end

  def cache_get(key)
    metadata = settings.cache_metadata[key]
    return nil if metadata.nil?

    if Time.now > metadata[:expires_at]
      puts "CACHE EXPIRED: #{key}"
      cache_delete(key)
      return nil
    end

    puts "CACHE HIT: #{key}"
    settings.cache[key]
  end

  def cache_delete(key)
    settings.cache.delete(key)
    settings.cache_metadata.delete(key)
    puts "CACHE DELETE: #{key}"
  end

  def find_user_from_db(id)
    puts "DB FETCH: User with id #{id}"
    sleep 0.1 # Simulate DB latency
    DB[:users][id]
  end

  def find_post_from_db(id)
    puts "DB FETCH: Post with id #{id}"
    sleep 0.1 # Simulate DB latency
    DB[:posts][id]
  end
end

# --- API Endpoints ---

# Set content type for all responses
before do
  content_type :json
end

# GET User (Cache-Aside Pattern)
get '/users/:id' do
  user_id = params['id']
  cache_key = "user:#{user_id}"

  # 1. Attempt to fetch from cache
  cached_user = cache_get(cache_key)
  if cached_user
    return cached_user.to_h.to_json
  end

  # 2. Cache miss: fetch from data source (DB)
  puts "CACHE MISS: #{cache_key}"
  user = find_user_from_db(user_id)

  if user
    # 3. Store in cache
    cache_set(cache_key, user)
    user.to_h.to_json
  else
    status 404
    { error: "User not found" }.to_json
  end
end

# UPDATE User (Cache Invalidation)
put '/users/:id' do
  user_id = params['id']
  user = DB[:users][user_id]

  halt 404, { error: "User not found" }.to_json unless user

  # In a real app, you'd update the user object from request body
  user.is_active = !user.is_active # Simulate an update
  DB[:users][user_id] = user

  # Invalidate the cache for this user
  cache_delete("user:#{user_id}")

  user.to_h.to_json
end

# GET Post (Cache-Aside Pattern)
get '/posts/:id' do
  post_id = params['id']
  cache_key = "post:#{post_id}"

  # 1. Attempt to fetch from cache
  cached_post = cache_get(cache_key)
  return cached_post.to_h.to_json if cached_post

  # 2. Cache miss: fetch from data source
  puts "CACHE MISS: #{cache_key}"
  post = find_post_from_db(post_id)

  if post
    # 3. Store in cache
    cache_set(cache_key, post, 60) # Shorter TTL for posts
    post.to_h.to_json
  else
    status 404
    { error: "Post not found" }.to_json
  end
end

# DELETE Post (Cache Invalidation)
delete '/posts/:id' do
  post_id = params['id']
  
  if DB[:posts].delete(post_id)
    # Invalidate the cache
    cache_delete("post:#{post_id}")
    status 204
  else
    status 404
    { error: "Post not found" }.to_json
  end
end