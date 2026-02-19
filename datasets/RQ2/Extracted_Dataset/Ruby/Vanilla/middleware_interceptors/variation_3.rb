require 'json'
require 'securerandom'
require 'time'
require 'stringio'

# ==============================================================================
# Custom Request/Response Objects
# ==============================================================================
class HttpRequest
  attr_accessor :method, :path, :headers, :body, :ip_address, :params

  def initialize(env)
    @method = env['REQUEST_METHOD']
    @path = env['PATH_INFO']
    @headers = Hash[env.select { |k, _| k.start_with?('HTTP_') }.map { |k, v| [k.sub(/^HTTP_/, '').split('_').map(&:capitalize).join('-'), v] }]
    @headers['Content-Type'] = env['CONTENT_TYPE'] if env['CONTENT_TYPE']
    @body = env['rack.input']
    @ip_address = env['REMOTE_ADDR']
    @params = {}
  end
end

class HttpResponse
  attr_accessor :status, :headers, :body

  def initialize(status = 200, headers = {}, body = [])
    @status = status
    @headers = headers
    @body = body
  end

  def to_rack_response
    body_array = @body.is_a?(Array) ? @body : [@body.to_s]
    [@status, @headers, body_array]
  end
end

# ==============================================================================
# Domain Models & Storage
# ==============================================================================
module Models
  User = Struct.new(:id, :email, :password_hash, :role, :is_active, :created_at)
  Post = Struct.new(:id, :user_id, :title, :content, :status)
end

class InMemoryStorage
  def self.instance
    @instance ||= new
  end

  attr_accessor :users, :posts

  private def initialize
    @users = {
      "f47ac10b-58cc-4372-a567-0e02b2c3d479" => Models::User.new("f47ac10b-58cc-4372-a567-0e02b2c3d479", "admin@example.com", "hash1", :ADMIN, true, Time.now)
    }
    @posts = {
      "a1b2c3d4-e5f6-7890-1234-567890abcdef" => Models::Post.new("a1b2c3d4-e5f6-7890-1234-567890abcdef", "f47ac10b-58cc-4372-a567-0e02b2c3d479", "Decorator Pattern", "A post about decorators.", :PUBLISHED)
    }
  end
end

# ==============================================================================
# Middleware Implementation (Decorator Pattern)
# ==============================================================================
module Middlewares
  class BaseDecorator
    def initialize(wrapped_handler)
      @wrapped_handler = wrapped_handler
    end

    def handle(request)
      @wrapped_handler.handle(request)
    end
  end

  class ErrorHandlingDecorator < BaseDecorator
    def handle(request)
      @wrapped_handler.handle(request)
    rescue => e
      $stderr.puts "Error caught by decorator: #{e.class} - #{e.message}"
      HttpResponse.new(500, { 'Content-Type' => 'application/json' }, JSON.generate({ error: 'Server Error' }))
    end
  end

  class LoggingDecorator < BaseDecorator
    def handle(request)
      puts "[ACCESS] #{request.ip_address} - \"#{request.method} #{request.path}\""
      response = @wrapped_handler.handle(request)
      puts "[ACCESS] Responded with status #{response.status}"
      response
    end
  end

  class CorsDecorator < BaseDecorator
    def handle(request)
      if request.method == 'OPTIONS'
        headers = {
          'Access-Control-Allow-Origin' => '*',
          'Access-Control-Allow-Methods' => 'GET, POST, OPTIONS',
        }
        return HttpResponse.new(204, headers, [])
      end
      response = @wrapped_handler.handle(request)
      response.headers['Access-Control-Allow-Origin'] = '*'
      response
    end
  end

  class RateLimiterDecorator < BaseDecorator
    LIMIT = 15
    PERIOD = 60 # seconds
    
    def initialize(wrapped_handler)
      super
      @requests = Hash.new { |h, k| h[k] = [] }
    end

    def handle(request)
      now = Time.now.to_i
      @requests[request.ip_address].reject! { |t| now - t > PERIOD }
      
      if @requests[request.ip_address].size >= LIMIT
        return HttpResponse.new(429, { 'Content-Type' => 'application/json' }, JSON.generate({ error: 'Too many requests' }))
      end
      
      @requests[request.ip_address] << now
      @wrapped_handler.handle(request)
    end
  end

  class JsonTransformerDecorator < BaseDecorator
    def handle(request)
      if request.headers['Content-Type'] == 'application/json'
        body_content = request.body.read
        request.params = JSON.parse(body_content) unless body_content.empty?
        request.body.rewind
      end

      response = @wrapped_handler.handle(request)

      if response.body.is_a?(Hash) || response.body.is_a?(Array)
        response.headers['Content-Type'] = 'application/json'
        response.body = JSON.generate(response.body)
      end
      response
    end
  end
end

# ==============================================================================
# Application Handler
# ==============================================================================
class PostsHandler
  def handle(request)
    case [request.method, request.path]
    when ['GET', '/posts']
      posts = InMemoryStorage.instance.posts.values.map(&:to_h)
      HttpResponse.new(200, {}, posts)
    when ['POST', '/posts']
      # raise "Simulating a random failure" if rand > 0.8
      new_post = Models::Post.new(
        SecureRandom.uuid,
        "f47ac10b-58cc-4372-a567-0e02b2c3d479",
        request.params['title'],
        request.params['content'],
        :DRAFT
      )
      InMemoryStorage.instance.posts[new_post.id] = new_post
      HttpResponse.new(201, {}, new_post.to_h)
    else
      HttpResponse.new(404, {}, { 'error' => 'Not Found' })
    end
  end
end

# ==============================================================================
# Server Simulation
# ==============================================================================
if __FILE__ == $0
  # Build the stack by decorating the core handler
  app = PostsHandler.new
  stack = Middlewares::ErrorHandlingDecorator.new(
    Middlewares::LoggingDecorator.new(
      Middlewares::CorsDecorator.new(
        Middlewares::RateLimiterDecorator.new(
          Middlewares::JsonTransformerDecorator.new(
            app
          )
        )
      )
    )
  )

  def call_stack(stack, env)
    request = HttpRequest.new(env)
    response = stack.handle(request)
    response.to_rack_response
  end

  puts "--- 1. Handling GET /posts ---"
  get_env = { 'REQUEST_METHOD' => 'GET', 'PATH_INFO' => '/posts', 'REMOTE_ADDR' => '10.0.0.1', 'rack.input' => StringIO.new }
  p call_stack(stack, get_env)

  puts "\n--- 2. Handling POST /posts (Success) ---"
  post_body = JSON.generate({ title: "Decorated Post", content: "This is the content." })
  post_env = { 'REQUEST_METHOD' => 'POST', 'PATH_INFO' => '/posts', 'REMOTE_ADDR' => '10.0.0.1', 'CONTENT_TYPE' => 'application/json', 'rack.input' => StringIO.new(post_body) }
  p call_stack(stack, post_env)

  puts "\n--- 3. Handling 404 Not Found ---"
  not_found_env = { 'REQUEST_METHOD' => 'GET', 'PATH_INFO' => '/users', 'REMOTE_ADDR' => '10.0.0.1', 'rack.input' => StringIO.new }
  p call_stack(stack, not_found_env)
end