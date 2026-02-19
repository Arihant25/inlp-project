<#-- variation_2.rb -->
<#-- This variation uses a single, configurable "API Gateway" middleware. -->
<#-- Logic is separated into concerns (modules) that are included in the main class. -->
<#-- This approach centralizes configuration and simulates a developer who prefers a single point of control for related functionalities. -->

<#-- config/application.rb -->
require_relative "boot"
require "rails/all"

# --- Middleware Concerns (imagine these are in app/middleware/api_gateway/...) ---
module ApiGateway
  module ErrorHandling
    private
    def handle_errors
      yield
    rescue StandardError => e
      render_error(e)
    end

    def render_error(e)
      status, body = case e
      when ActiveRecord::RecordNotFound
        [404, { code: 'not_found', detail: e.message }]
      when ActiveRecord::RecordInvalid
        [422, { code: 'invalid_record', detail: e.message }]
      else
        [500, { code: 'internal_server_error', detail: 'An unexpected error occurred.' }]
      end
      [status, { 'Content-Type' => 'application/json' }, [{ error: body }.to_json]]
    end
  end

  module Cors
    private
    def handle_cors(env)
      if env['REQUEST_METHOD'] == 'OPTIONS'
        [204, cors_headers, []]
      else
        status, headers, response = yield
        headers.merge!(cors_headers)
        [status, headers, response]
      end
    end

    def cors_headers
      {
        'Access-Control-Allow-Origin' => '*',
        'Access-Control-Allow-Methods' => 'GET, POST, PUT, PATCH, DELETE, OPTIONS',
        'Access-Control-Allow-Headers' => 'Authorization, Content-Type'
      }
    end
  end

  module RateLimiting
    private
    def check_rate_limit(request)
      return yield unless @config[:rate_limiting_enabled]
      
      key = "rate-limit:#{request.ip}"
      count = Rails.cache.increment(key, 1, expires_in: @config[:rate_limit_period])

      if count > @config[:rate_limit_max]
        return [429, { 'Content-Type' => 'application/json' }, [{ error: { code: 'rate_limit_exceeded' } }.to_json]]
      end
      
      yield
    end
  end

  module LoggingAndTransformation
    private
    def log_and_transform(env)
      request = ActionDispatch::Request.new(env)
      start_time = Process.clock_gettime(Process::CLOCK_MONOTONIC)
      
      # Request Transformation
      env['app.request_id'] = request.request_id
      
      Rails.logger.info "Request Started: #{request.method} #{request.fullpath} ID: #{env['app.request_id']}"
      
      status, headers, response = yield
      
      # Response Transformation
      headers['X-Request-ID'] = env['app.request_id']
      
      duration = (Process.clock_gettime(Process::CLOCK_MONOTONIC) - start_time).round(4)
      Rails.logger.info "Request Finished: #{status} in #{duration}s"
      
      [status, headers, response]
    end
  end
end

# --- The Main Middleware Class (imagine in app/middleware/api_gateway_middleware.rb) ---
class ApiGatewayMiddleware
  include ApiGateway::ErrorHandling
  include ApiGateway::Cors
  include ApiGateway::RateLimiting
  include ApiGateway::LoggingAndTransformation

  def initialize(app, config = {})
    @app = app
    @config = {
      rate_limiting_enabled: true,
      rate_limit_max: 100,
      rate_limit_period: 1.minute
    }.merge(config)
  end

  def call(env)
    handle_errors do
      handle_cors(env) do
        log_and_transform(env) do
          check_rate_limit(ActionDispatch::Request.new(env)) do
            @app.call(env)
          end
        end
      end
    end
  end
end

module YourApi
  class Application < Rails::Application
    config.load_defaults 7.0
    config.api_only = true

    # Middleware Configuration
    config.middleware.use ApiGatewayMiddleware, {
      rate_limiting_enabled: true,
      rate_limit_max: 50,
      rate_limit_period: 1.minute
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
    render json: { data: [{ id: SecureRandom.uuid, title: "A Post" }], meta: { request_id: request.env['app.request_id'] } }
  end

  def error
    raise "Something went wrong"
  end
end