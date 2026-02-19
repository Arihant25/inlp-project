require 'json'
require 'securerandom'
require 'time'
require 'stringio'

# ==============================================================================
# Domain Schema & Mock Data
# ==============================================================================

module Domain
  User = Struct.new(:id, :email, :password_hash, :role, :is_active, :created_at)
  Post = Struct.new(:id, :user_id, :title, :content, :status)

  class MockDB
    @@users = {
      "f47ac10b-58cc-4372-a567-0e02b2c3d479" => User.new(
        "f47ac10b-58cc-4372-a567-0e02b2c3d479",
        "admin@example.com",
        "hash1",
        :ADMIN,
        true,
        Time.now - 86400
      )
    }
    @@posts = {
      "a1b2c3d4-e5f6-7890-1234-567890abcdef" => Post.new(
        "a1b2c3d4-e5f6-7890-1234-567890abcdef",
        "f47ac10b-58cc-4372-a567-0e02b2c3d479",
        "OOP Middleware",
        "An example of OOP middleware in Ruby.",
        :PUBLISHED
      )
    }

    def self.find_all_posts
      @@posts.values
    end

    def self.save_post(post)
      @@posts[post.id] = post
    end
  end
end

# ==============================================================================
# Middleware Implementation (Object-Oriented Chain of Responsibility)
# ==============================================================================

class BaseMiddleware
  attr_reader :next_handler

  def initialize(next_handler)
    @next_handler = next_handler
  end

  def call(request_context)
    raise NotImplementedError, "#{self.class} has not implemented method 'call'"
  end
end

class LoggingMiddleware < BaseMiddleware
  def call(request_context)
    start_time = Time.now
    puts "[Logger] Incoming Request: #{request_context['REQUEST_METHOD']} #{request_context['PATH_INFO']}"
    
    status, headers, body = @next_handler.call(request_context)
    
    end_time = Time.now
    duration = ((end_time - start_time) * 1000).round(2)
    puts "[Logger] Outgoing Response: Status #{status} | Duration: #{duration}ms"
    
    [status, headers, body]
  end
end

class CorsHandlingMiddleware < BaseMiddleware
  def call(request_context)
    if request_context['REQUEST_METHOD'] == 'OPTIONS'
      return [
        204,
        {
          'Access-Control-Allow-Origin' => '*',
          'Access-Control-Allow-Methods' => 'GET, POST, OPTIONS',
          'Access-Control-Allow-Headers' => 'Content-Type, Authorization'
        },
        []
      ]
    end

    status, headers, body = @next_handler.call(request_context)
    headers['Access-Control-Allow-Origin'] = '*'
    [status, headers, body]
  end
end

class RateLimitingMiddleware < BaseMiddleware
  WINDOW = 60 # seconds
  MAX_REQUESTS = 20

  def initialize(next_handler)
    super(next_handler)
    @ip_store = {}
  end

  def call(request_context)
    client_ip = request_context['REMOTE_ADDR']
    current_time = Time.now.to_i

    @ip_store[client_ip] ||= []
    @ip_store[client_ip].reject! { |timestamp| current_time - timestamp > WINDOW }

    if @ip_store[client_ip].length >= MAX_REQUESTS
      puts "[RateLimiter] Throttling IP: #{client_ip}"
      return [429, { 'Content-Type' => 'application/json' }, ['{"error": "Rate limit exceeded"}']]
    end

    @ip_store[client_ip] << current_time
    @next_handler.call(request_context)
  end
end

class RequestResponseTransformerMiddleware < BaseMiddleware
  def call(request_context)
    # Transform incoming JSON request
    if request_context['CONTENT_TYPE']&.include?('application/json')
      body_io = request_context['rack.input']
      body_str = body_io.read
      request_context['app.parsed_body'] = body_str.empty? ? {} : JSON.parse(body_str)
      body_io.rewind
    end

    status, headers, body_object = @next_handler.call(request_context)

    # Transform outgoing object response to JSON
    if body_object.is_a?(Hash) || body_object.is_a?(Array)
      headers['Content-Type'] = 'application/json'
      return [status, headers, [JSON.generate(body_object)]]
    end

    [status, headers, body_object]
  end
end

class ErrorHandlingMiddleware < BaseMiddleware
  def call(request_context)
    @next_handler.call(request_context)
  rescue JSON::ParserError => e
    puts "[Error] Bad Request: #{e.message}"
    [400, { 'Content-Type' => 'application/json' }, ['{"error": "Invalid JSON format"}']]
  rescue => e
    puts "[Error] Unhandled exception: #{e.class} - #{e.message}"
    [500, { 'Content-Type' => 'application/json' }, ['{"error": "An unexpected error occurred"}']]
  end
end

# ==============================================================================
# Application Handler
# ==============================================================================

class ApplicationHandler
  def call(request_context)
    method = request_context['REQUEST_METHOD']
    path = request_context['PATH_INFO']

    if method == 'GET' && path == '/posts'
      posts = Domain::MockDB.find_all_posts.map(&:to_h)
      [200, {}, posts]
    elsif method == 'POST' && path == '/posts'
      # raise "Simulating a random failure" if rand > 0.8
      parsed_body = request_context['app.parsed_body']
      new_post = Domain::Post.new(
        SecureRandom.uuid,
        "f47ac10b-58cc-4372-a567-0e02b2c3d479", # Assume authenticated user
        parsed_body['title'],
        parsed_body['content'],
        :DRAFT
      )
      Domain::MockDB.save_post(new_post)
      [201, {}, new_post.to_h]
    else
      [404, {}, { 'message' => 'Resource not found' }]
    end
  end
end

# ==============================================================================
# Server Simulation
# ==============================================================================

if __FILE__ == $0
  # Build the middleware chain by composing objects
  app = ApplicationHandler.new
  stack = ErrorHandlingMiddleware.new(
    LoggingMiddleware.new(
      CorsHandlingMiddleware.new(
        RateLimitingMiddleware.new(
          RequestResponseTransformerMiddleware.new(
            app
          )
        )
      )
    )
  )

  puts "--- 1. Handling GET /posts ---"
  get_request = {
    'REQUEST_METHOD' => 'GET',
    'PATH_INFO' => '/posts',
    'REMOTE_ADDR' => '192.168.1.1',
    'rack.input' => StringIO.new
  }
  p stack.call(get_request)

  puts "\n--- 2. Handling POST /posts (Success) ---"
  post_body = JSON.generate({ title: "OOP Post", content: "Content here." })
  post_request = {
    'REQUEST_METHOD' => 'POST',
    'PATH_INFO' => '/posts',
    'REMOTE_ADDR' => '192.168.1.1',
    'CONTENT_TYPE' => 'application/json',
    'rack.input' => StringIO.new(post_body)
  }
  p stack.call(post_request)

  puts "\n--- 3. Handling POST /posts (Bad JSON) ---"
  bad_post_request = {
    'REQUEST_METHOD' => 'POST',
    'PATH_INFO' => '/posts',
    'REMOTE_ADDR' => '192.168.1.1',
    'CONTENT_TYPE' => 'application/json',
    'rack.input' => StringIO.new('{ "title": "bad json", }')
  }
  p stack.call(bad_post_request)
end