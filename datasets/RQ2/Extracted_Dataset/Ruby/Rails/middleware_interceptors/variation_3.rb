<#-- variation_3.rb -->
<#-- This variation demonstrates a more functional, "all-in-one-file" configuration style. -->
<#-- Simpler middleware (logging, response header) are implemented as inline lambdas (Procs). -->
<#-- More complex middleware (error handling, rate limiting) are defined as full classes, but kept within the -->
<#-- application.rb file to emphasize a centralized configuration approach. This is less common but valid. -->

<#-- config/application.rb -->
require_relative "boot"
require "rails/all"

module YourApi
  class Application < Rails::Application
    config.load_defaults 7.0
    config.api_only = true

    # --- Inline Middleware Class Definitions ---

    class GlobalErrorHandler
      def initialize(app)
        @app = app
      end

      def call(env)
        @app.call(env)
      rescue => e
        render_json_error(e)
      end

      private

      def render_json_error(e)
        status = ActionDispatch::ExceptionWrapper.new(Rails.env, e).status_code
        body = {
          error: {
            type: e.class.to_s,
            message: e.message,
            backtrace: Rails.env.development? ? e.backtrace[0..5] : nil
          }
        }.to_json
        [status, { 'Content-Type' => 'application/json' }, [body]]
      end
    end

    class IpRateLimiter
      def initialize(app)
        @app = app
      end

      def call(env)
        ip_addr = env['action_dispatch.remote_ip'].to_s
        cache_key = "req-count:#{ip_addr}"
        
        req_count = Rails.cache.read(cache_key) || 0
        
        if req_count > 100 # Hardcoded limit
          return [429, { 'Content-Type' => 'text/plain' }, ['Rate limit exceeded']]
        end
        
        Rails.cache.write(cache_key, req_count + 1, expires_in: 60.seconds)
        
        @app.call(env)
      end
    end

    # --- Middleware Pipeline Configuration ---

    # 1. Error Handling (catches exceptions from middleware below it)
    config.middleware.use GlobalErrorHandler

    # 2. CORS Handling (as a lambda)
    config.middleware.use ->(app) {
      lambda do |env|
        headers = {
          'Access-Control-Allow-Origin' => 'https://example.com',
          'Access-Control-Allow-Methods' => 'GET, POST, OPTIONS',
          'Access-Control-Allow-Headers' => 'Content-Type'
        }
        if env['REQUEST_METHOD'] == 'OPTIONS'
          [204, headers, []]
        else
          status, response_headers, body = app.call(env)
          [status, response_headers.merge(headers), body]
        end
      end
    }

    # 3. Rate Limiting
    config.middleware.use IpRateLimiter

    # 4. Request Logging (as a lambda)
    config.middleware.use ->(app) {
      lambda do |env|
        Rails.logger.info "Processing: #{env['REQUEST_METHOD']} #{env['PATH_INFO']}"
        app.call(env)
      end
    }

    # 5. Request/Response Transformation (as a lambda)
    # This lambda wraps the response body in a 'data' key.
    config.middleware.use ->(app) {
      lambda do |env|
        status, headers, response = app.call(env)
        if headers['Content-Type']&.include?('application/json')
          body_hash = JSON.parse(response.body)
          new_body = { data: body_hash }.to_json
          headers['Content-Length'] = new_body.bytesize.to_s
          response.body = [new_body]
        end
        [status, headers, response]
      end
    }
  end
end

# --- Mocked Rails Environment ---

# config/routes.rb
Rails.application.routes.draw do
  get "/posts", to: "posts#index"
  get "/posts/error", to: "posts#error"
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
    # Note: The response transformer middleware will wrap this automatically
    render json: [{ id: SecureRandom.uuid, title: "My First Post" }]
  end

  def error
    1 / 0 # Induce a ZeroDivisionError
  end
end