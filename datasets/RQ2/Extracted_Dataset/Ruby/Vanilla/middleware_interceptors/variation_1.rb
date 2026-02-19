require 'json'
require 'securerandom'
require 'time'
require 'stringio'

# ==============================================================================
# Domain Models & Mock Data Store
# ==============================================================================

# Using Structs for a bit more structure than Hashes
User = Struct.new(:id, :email, :password_hash, :role, :is_active, :created_at)
Post = Struct.new(:id, :user_id, :title, :content, :status)

# In-memory database
class DataStore
  def self.users
    @users ||= {
      "f47ac10b-58cc-4372-a567-0e02b2c3d479" => User.new(
        "f47ac10b-58cc-4372-a567-0e02b2c3d479",
        "admin@example.com",
        "hash1",
        :ADMIN,
        true,
        Time.now - 86400
      )
    }
  end

  def self.posts
    @posts ||= {
      "a1b2c3d4-e5f6-7890-1234-567890abcdef" => Post.new(
        "a1b2c3d4-e5f6-7890-1234-567890abcdef",
        "f47ac10b-58cc-4372-a567-0e02b2c3d479",
        "Hello Ruby!",
        "This is a post about middleware in vanilla Ruby.",
        :PUBLISHED
      )
    }
  end
end

# ==============================================================================
# Middleware Implementation (Functional/Proc-based)
# ==============================================================================

module Middleware
  # Logs request and response details
  Logging = ->(app) {
    ->(env) {
      puts "[Log] Request: #{env['REQUEST_METHOD']} #{env['PATH_INFO']} at #{Time.now}"
      status, headers, body = app.call(env)
      puts "[Log] Response: #{status}"
      [status, headers, body]
    }
  }

  # Handles CORS pre-flight and adds headers
  CORS = ->(app) {
    ->(env) {
      if env['REQUEST_METHOD'] == 'OPTIONS'
        headers = {
          'Access-Control-Allow-Origin' => '*',
          'Access-Control-Allow-Methods' => 'GET, POST, OPTIONS',
          'Access-Control-Allow-Headers' => 'Content-Type'
        }
        return [204, headers, []]
      end

      status, headers, body = app.call(env)
      headers['Access-Control-Allow-Origin'] = '*'
      [status, headers, body]
    }
  }

  # Simple in-memory rate limiting
  RateLimiter = ->(app, limit: 10, period: 60) {
    requests = {}
    ->(env) {
      ip = env['REMOTE_ADDR']
      now = Time.now.to_i

      requests.delete_if { |_, time| now - time > period }

      if requests.values_at(ip).compact.size >= limit
        puts "[RateLimit] IP #{ip} has been rate limited."
        return [429, { 'Content-Type' => 'application/json' }, ['{"error":"Too Many Requests"}']]
      end

      requests[ip] = now
      app.call(env)
    }
  }

  # Transforms JSON request/response bodies
  JsonTransformer = ->(app) {
    ->(env) {
      # Request transformation
      if env['CONTENT_TYPE'] == 'application/json'
        body = env['rack.input'].read
        env['parsed_body'] = body.empty? ? {} : JSON.parse(body)
        env['rack.input'].rewind
      end

      status, headers, body = app.call(env)

      # Response transformation
      if body.is_a?(Hash) || body.is_a?(Array)
        headers['Content-Type'] = 'application/json'
        body_string = JSON.generate(body)
        return [status, headers, [body_string]]
      end

      [status, headers, body]
    }
  }

  # Catches exceptions and returns a standard error response
  ErrorHandler = ->(app) {
    ->(env) {
      begin
        app.call(env)
      rescue => e
        puts "[Error] Exception caught: #{e.message}"
        puts e.backtrace.join("\n")
        [500, { 'Content-Type' => 'application/json' }, ['{"error":"Internal Server Error"}']]
      end
    }
  }
end

# ==============================================================================
# Application Logic
# ==============================================================================

App = ->(env) {
  case [env['REQUEST_METHOD'], env['PATH_INFO']]
  when ['GET', '/posts']
    posts_array = DataStore.posts.values.map(&:to_h)
    [200, {}, posts_array]
  when ['POST', '/posts']
    # raise "Simulating a random failure" if rand > 0.8
    user_id = "f47ac10b-58cc-4372-a567-0e02b2c3d479" # Hardcoded for example
    new_post_data = env['parsed_body']
    post = Post.new(
      SecureRandom.uuid,
      user_id,
      new_post_data['title'],
      new_post_data['content'],
      :DRAFT
    )
    DataStore.posts[post.id] = post
    [201, {}, post.to_h]
  else
    [404, {}, { 'error' => 'Not Found' }]
  end
}

# ==============================================================================
# Server Simulation
# ==============================================================================

if __FILE__ == $0
  # Build the middleware stack by wrapping the app
  # The order is important: outer -> inner
  stack = Middleware::ErrorHandler.call(
    Middleware::Logging.call(
      Middleware::CORS.call(
        Middleware::RateLimiter.call(
          Middleware::JsonTransformer.call(
            App
          )
        )
      )
    )
  )

  puts "--- 1. Handling GET /posts ---"
  get_request_env = {
    'REQUEST_METHOD' => 'GET',
    'PATH_INFO' => '/posts',
    'REMOTE_ADDR' => '127.0.0.1',
    'rack.input' => StringIO.new('')
  }
  p stack.call(get_request_env)

  puts "\n--- 2. Handling POST /posts (Success) ---"
  post_body = JSON.generate({ title: "New Post", content: "Content of the new post." })
  post_request_env = {
    'REQUEST_METHOD' => 'POST',
    'PATH_INFO' => '/posts',
    'REMOTE_ADDR' => '127.0.0.1',
    'CONTENT_TYPE' => 'application/json',
    'rack.input' => StringIO.new(post_body)
  }
  p stack.call(post_request_env)

  puts "\n--- 3. Handling OPTIONS /posts (CORS Pre-flight) ---"
  options_request_env = {
    'REQUEST_METHOD' => 'OPTIONS',
    'PATH_INFO' => '/posts',
    'REMOTE_ADDR' => '127.0.0.1',
    'rack.input' => StringIO.new('')
  }
  p stack.call(options_request_env)

  puts "\n--- 4. Handling 404 Not Found ---"
  not_found_env = {
    'REQUEST_METHOD' => 'GET',
    'PATH_INFO' => '/nonexistent',
    'REMOTE_ADDR' => '127.0.0.1',
    'rack.input' => StringIO.new('')
  }
  p stack.call(not_found_env)
end