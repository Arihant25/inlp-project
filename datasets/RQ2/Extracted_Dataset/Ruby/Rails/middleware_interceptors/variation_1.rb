<#-- variation_1.rb -->
<#-- This variation represents a classic, modular, object-oriented approach. -->
<#-- Each piece of middleware is a separate, single-responsibility class in its own file. -->
<#-- This is a very common and maintainable pattern in large Rails applications. -->

<#-- config/application.rb -->
require_relative "boot"
require "rails/all"

# Define middleware classes inline for self-containment, but imagine they are in app/middleware/
module AppMiddleware
  class RequestLogger
    def initialize(app)
      @app = app
    end

    def call(env)
      request = ActionDispatch::Request.new(env)
      start_time = Time.now
      Rails.logger.info "--> [#{request.method}] #{request.path} from #{request.remote_ip} at #{start_time}"
      
      status, headers, response = @app.call(env)
      
      end_time = Time.now
      duration = ((end_time - start_time) * 1000).round(2)
      Rails.logger.info "<-- [#{status}] took #{duration}ms"
      
      [status, headers, response]
    end
  end

  class CorsHandler
    def initialize(app)
      @app = app
    end

    def call(env)
      if env['REQUEST_METHOD'] == 'OPTIONS'
        return [204, cors_headers, []]
      end

      status, headers, response = @app.call(env)
      headers.merge!(cors_headers)
      [status, headers, response]
    end

    private

    def cors_headers
      {
        'Access-Control-Allow-Origin' => '*',
        'Access-Control-Allow-Methods' => 'GET, POST, PUT, PATCH, DELETE, OPTIONS, HEAD',
        'Access-Control-Allow-Headers' => 'Origin, Content-Type, Accept, Authorization, Token'
      }
    end
  end

  class RateLimiter
    def initialize(app, options = {})
      @app = app
      @limit = options[:limit] || 100
      @period = options[:period] || 1.hour
    end

    def call(env)
      request = ActionDispatch::Request.new(env)
      cache_key = "rate_limit:#{request.remote_ip}"
      
      count = Rails.cache.read(cache_key) || 0
      
      if count >= @limit
        return [429, {'Content-Type' => 'application/json'}, [{ error: 'Too Many Requests' }.to_json]]
      end

      Rails.cache.write(cache_key, count + 1, expires_in: @period)
      
      @app.call(env)
    end
  end

  class ResponseTransformer
    def initialize(app)
      @app = app
    end

    def call(env)
      # Request Transformation: Parse a version header
      request = ActionDispatch::Request.new(env)
      api_version = request.headers['X-API-Version'] || 'v1'
      env['app.api_version'] = api_version

      status, headers, response = @app.call(env)

      # Response Transformation: Add a custom header
      headers['X-App-Processed'] = 'true'
      
      [status, headers, response]
    end
  end

  class ErrorHandler
    def initialize(app)
      @app = app
    end

    def call(env)
      @app.call(env)
    rescue => e
      handle_error(e)
    end

    private

    def handle_error(e)
      error_body = { error: { type: e.class.name, message: e.message } }.to_json
      status_code = case e
                    when ActiveRecord::RecordNotFound then 404
                    when ActiveRecord::RecordInvalid then 422
                    else 500
                    end
      
      [status_code, { 'Content-Type' => 'application/json' }, [error_body]]
    end
  end
end

module YourApi
  class Application < Rails::Application
    config.load_defaults 7.0
    config.api_only = true

    # Middleware Configuration
    config.middleware.insert_before 0, AppMiddleware::CorsHandler
    config.middleware.use AppMiddleware::RequestLogger
    config.middleware.use AppMiddleware::ErrorHandler
    config.middleware.use AppMiddleware::RateLimiter, limit: 100, period: 1.minute
    config.middleware.use AppMiddleware::ResponseTransformer
  end
end

# --- Mocked Rails Environment ---

# config/routes.rb
Rails.application.routes.draw do
  get "/posts", to: "posts#index"
  get "/posts/error", to: "posts#error"
  get "/posts/not_found", to: "posts#not_found"
end

# app/models/user.rb
class User < ApplicationRecord
  self.implicit_order_column = "created_at"
  enum role: { USER: 'user', ADMIN: 'admin' }
end

# app/models/post.rb
class Post < ApplicationRecord
  self.implicit_order_column = "created_at"
  belongs_to :user
  enum status: { DRAFT: 'draft', PUBLISHED: 'published' }
end

# app/controllers/posts_controller.rb
class PostsController < ActionController::API
  def index
    # Example of using transformed request data
    api_version = request.env['app.api_version']
    posts = [{ id: SecureRandom.uuid, title: "Post for #{api_version}" }]
    render json: { data: posts }
  end

  def error
    raise "A deliberate server error occurred."
  end

  def not_found
    raise ActiveRecord::RecordNotFound, "Couldn't find Post with 'id'=123"
  end
end