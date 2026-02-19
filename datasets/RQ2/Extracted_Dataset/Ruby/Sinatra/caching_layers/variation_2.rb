require 'sinatra/base'
require 'json'
require 'securerandom'
require 'time'

# --- Domain Models ---
User = Struct.new(:id, :email, :password_hash, :role, :is_active, :created_at)
Post = Struct.new(:id, :user_id, :title, :content, :status)

# --- Mock Data Source ---
class Database
  attr_accessor :users, :posts

  def initialize
    @users = {}
    @posts = {}
    seed
  end

  def find_user(id)
    puts "DATABASE: Querying for User ID: #{id}"
    sleep 0.1 # Simulate latency
    @users[id]
  end

  def find_post(id)
    puts "DATABASE: Querying for Post ID: #{id}"
    sleep 0.1 # Simulate latency
    @posts[id]
  end

  private

  def seed
    user_id = SecureRandom.uuid
    @users[user_id] = User.new(user_id, 'user@corp.com', 'hash456', 'USER', true, Time.now.utc)
    @posts[SecureRandom.uuid] = Post.new(SecureRandom.uuid, user_id, 'A Post by a User', 'Content here.', 'PUBLISHED')
  end
end

# --- Caching Layer (OOP Style) ---
class CacheService
  def initialize(ttl: 300)
    @store = {}
    @expirations = {}
    @default_ttl = ttl
  end

  def read(key)
    return nil unless @store.key?(key)

    if Time.now > @expirations[key]
      puts "CACHE: Expired entry for #{key}"
      evict(key)
      return nil
    end

    puts "CACHE: Hit for #{key}"
    @store[key]
  end

  def write(key, value, ttl = nil)
    ttl_to_use = ttl || @default_ttl
    puts "CACHE: Writing entry for #{key} with TTL #{ttl_to_use}s"
    @store[key] = value
    @expirations[key] = Time.now + ttl_to_use
  end

  def evict(key)
    puts "CACHE: Evicting entry for #{key}"
    @store.delete(key)
    @expirations.delete(key)
  end
end

# --- Data Repository (Handles Cache-Aside Logic) ---
class UserRepository
  def initialize(database, cache_service)
    @db = database
    @cache = cache_service
  end

  def find_by_id(id)
    cache_key = "user:#{id}"
    
    # 1. Check cache
    cached_user = @cache.read(cache_key)
    return cached_user if cached_user

    # 2. On miss, fetch from DB
    puts "CACHE: Miss for #{cache_key}"
    user = @db.find_user(id)

    # 3. Write to cache if found
    @cache.write(cache_key, user) if user
    
    user
  end

  def update_activity(id, is_active)
    user = @db.find_user(id)
    return nil unless user
    
    user.is_active = is_active
    @db.users[id] = user
    
    # Invalidate cache on update
    @cache.evict("user:#{id}")
    
    user
  end
end

# --- Sinatra Application (Controller Layer) ---
class ObjectOrientedApi < Sinatra::Base
  configure do
    set :db, Database.new
    set :cache, CacheService.new(ttl: 600)
  end

  before do
    content_type :json
    @user_repository = UserRepository.new(settings.db, settings.cache)
  end

  get '/users/:id' do
    user = @user_repository.find_by_id(params[:id])
    if user
      user.to_h.to_json
    else
      status 404
      { message: "User not found" }.to_json
    end
  end

  put '/users/:id' do
    request.body.rewind
    payload = JSON.parse(request.body.read)
    
    user = @user_repository.update_activity(params[:id], payload['is_active'])
    if user
      user.to_h.to_json
    else
      status 404
      { message: "User not found" }.to_json
    end
  end
  
  # A simple post endpoint to show the pattern can be extended
  get '/posts/:id' do
    cache_key = "post:#{params[:id]}"
    
    cached_post = settings.cache.read(cache_key)
    return cached_post.to_h.to_json if cached_post
    
    puts "CACHE: Miss for #{cache_key}"
    post = settings.db.find_post(params[:id])
    
    if post
      settings.cache.write(cache_key, post, 120) # Different TTL for posts
      post.to_h.to_json
    else
      status 404
      { message: "Post not found" }.to_json
    end
  end
end

# To run this app: ObjectOrientedApi.run!