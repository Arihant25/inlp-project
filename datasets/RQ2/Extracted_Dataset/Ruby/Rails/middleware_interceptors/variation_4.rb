<#-- variation_4.rb -->
<#-- This variation promotes a strong separation of concerns using a Service Object pattern. -->
<#-- Middleware classes are thin wrappers that delegate their logic to dedicated POROs (Plain Old Ruby Objects) or services. -->
<#-- This makes the core logic framework-agnostic and highly testable in isolation. -->

<#-- app/services/request_auditor.rb -->
class RequestAuditor
  def self.log_start(request)
    Rails.logger.info "AUDIT [START] | #{request.method} #{request.path} | IP: #{request.ip}"
  end

  def self.log_finish(status, duration)
    Rails.logger.info "AUDIT [END]   | Status: #{status} | Duration: #{duration.round(2)}ms"
  end
end

<#-- app/services/rate_limit_enforcer.rb -->
class RateLimitEnforcer
  attr_reader :request, :limit, :period

  def initialize(request, limit: 60, period: 1.minute)
    @request = request
    @limit = limit
    @period = period
  end

  def call
    return :ok if exceeded?
    :limit_exceeded
  end

  private

  def exceeded?
    count = Rails.cache.increment(cache_key, 1, expires_in: @period)
    count > @limit
  end

  def cache_key
    "rate_limit_enforcer:#{request.ip}"
  end
end

<#-- app/services/error_responder.rb -->
class ErrorResponder
  attr_reader :exception

  def initialize(exception)
    @exception = exception
  end

  def response
    [status_code, headers, [body]]
  end

  private

  def status_code
    case exception
    when ActiveRecord::RecordNotFound then 404
    else 500
    end
  end



  def body
    {
      errors: [{
        id: SecureRandom.uuid,
        status: status_code.to_s,
        title: exception.class.name,
        detail: exception.message
      }]
    }.to_json
  end
  
  def headers
    { 'Content-Type' => 'application/vnd.api+json' }
  end
end

# --- Middleware Classes (thin wrappers) ---

<#-- app/middleware/auditing_middleware.rb -->
class AuditingMiddleware
  def initialize(app)
    @app = app
  end

  def call(env)
    start_time = Time.now
    RequestAuditor.log_start(ActionDispatch::Request.new(env))
    
    status, headers, response = @app.call(env)
    
    duration = (Time.now - start_time) * 1000
    RequestAuditor.log_finish(status, duration)
    
    [status, headers, response]
  end
end

<#-- app/middleware/rate_limiting_middleware.rb -->
class RateLimitingMiddleware
  def initialize(app)
    @app = app
  end

  def call(env)
    request = ActionDispatch::Request.new(env)
    enforcer = RateLimitEnforcer.new(request, limit: 100, period: 1.minute)
    
    if enforcer.call == :limit_exceeded
      return [429, { 'Content-Type' => 'application/json' }, [{ error: 'Rate limit exceeded' }.to_json]]
    end
    
    @app.call(env)
  end
end

<#-- app/middleware/exception_handling_middleware.rb -->
class ExceptionHandlingMiddleware
  def initialize(app)
    @app = app
  end

  def call(env)
    @app.call(env)
  rescue => e
    ErrorResponder.new(e).response
  end
end

<#-- app/middleware/api_header_middleware.rb -->
class ApiHeaderMiddleware
  def initialize(app)
    @app = app
  end

  def call(env)
    # Request transformation: Add a trace ID to the environment
    env['app.trace_id'] = SecureRandom.hex(8)

    status, headers, response = @app.call(env)

    # Response transformation: Add trace ID to the response headers
    headers['X-Trace-Id'] = env['app.trace_id']
    
    [status, headers, response]
  end
end

# --- Configuration ---

<#-- config/application.rb -->
require_relative "boot"
require "rails/all"

module YourApi
  class Application < Rails::Application
    config.load_defaults 7.0
    config.api_only = true

    # Middleware Configuration
    config.middleware.use ExceptionHandlingMiddleware
    config.middleware.use AuditingMiddleware
    config.middleware.use RateLimitingMiddleware
    config.middleware.use ApiHeaderMiddleware
    # CORS would be another middleware here, often from the rack-cors gem
  end
end

# --- Mocked Rails Environment ---

# config/routes.rb
Rails.application.routes.draw do
  get "/posts", to: "posts#index"
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
    render json: { data: { message: "Success" }, meta: { trace_id: request.env['app.trace_id'] } }
  end

  def not_found
    raise ActiveRecord::RecordNotFound, "The requested post does not exist."
  end
end