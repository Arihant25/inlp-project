<ruby>
# VARIATION 3: The "Configuration-Driven" Developer
# STYLE: Emphasizes flexibility and testability by making middleware behavior configurable via initializers.
# Gemfile:
#   gem 'hanami', '~> 2.1'
#   gem 'rack-cors', '~> 2.0'
#   gem 'rack-attack', '~> 6.6'

require 'hanami'
require 'rack/cors'
require 'rack/attack'
require 'json'
require 'securerandom'
require 'logger'

# --- Mock Rack::Attack Cache Store for standalone execution ---
class Rack::Attack::Cache
  attr_accessor :store
  def initialize
    @store = ActiveSupport::Cache::MemoryStore.new
  end
end
module ActiveSupport
  module Cache
    class MemoryStore
      def initialize; @data = {}; end
      def read(key); @data[key]; end
      def write(key, value, options = {}); @data[key] = value; end
      def increment(key, amount, options = {}); @data[key] = (@data[key] || 0) + amount; end
    end
  end
end
Rack::Attack.cache = Rack::Attack::Cache.new

# --- Middleware Definitions ---
module ConfigurableApp
  # Custom error types for mapping
  class AuthenticationError < StandardError; end

  module Middleware
    # Logs requests based on configured log level and format.
    class RequestAuditor
      def initialize(app, logger:, format: :default)
        @app = app
        @logger = logger
        @formatter = method("format_#{format}")
      end

      def call(env)
        start_time = Time.now
        response = @app.call(env)
      ensure
        duration = Time.now - start_time
        log_entry = @formatter.call(env, response || [], duration)
        @logger.info(log_entry)
      end

      private
      def format_default(env, response, duration)
        status = response[0] || 'N/A'
        "#{env['REQUEST_METHOD']} #{env['REQUEST_URI']} completed with #{status} in #{duration.round(4)}s"
      end
    end

    # Injects a configurable set of headers into the response.
    class HeaderAttacher
      def initialize(app, headers:)
        @app = app
        @headers = headers
      end

      def call(env)
        status, headers, body = @app.call(env)
        @headers.each { |k, v| headers[k.to_s] = v.is_a?(Proc) ? v.call(env) : v }
        [status, headers, body]
      end
    end

    # Handles errors by mapping exception classes to HTTP status codes.
    class SafeGuard
      def initialize(app, error_map:)
        @app = app
        @error_map = error_map
        @default_status = 500
      end

      def call(env)
        @app.call(env)
      rescue => e
        status = @error_map.find { |err_class, _| e.is_a?(err_class) }&.last || @default_status
        render_error(status, e)
      end

      private
      def render_error(status, exception)
        body = {
          error: {
            type: exception.class.name,
            message: exception.message
          }
        }.to_json
        [status, { 'Content-Type' => 'application/json' }, [body]]
      end
    end
  end
end

# --- Hanami Application Definition ---
module ConfigurableApp
  class App < Hanami::App
    # --- Middleware Configuration (simulating config/app.rb) ---
    # 1. Error Handling (top of stack)
    # Configure which errors map to which HTTP status codes.
    ERROR_MAPPING = {
      AuthenticationError => 401,
      ArgumentError => 400
    }
    config.middleware.use Middleware::SafeGuard, error_map: ERROR_MAPPING

    # 2. Rate Limiting
    config.middleware.use Rack::Attack
    Rack::Attack.safelist('allow from localhost') do |req|
      '127.0.0.1' == req.ip || '::1' == req.ip
    end
    Rack::Attack.throttle('api/ip', limit: 20, period: 60.seconds) { |req| req.ip }

    # 3. CORS Handling
    config.middleware.use Rack::Cors do
      allow do
        origins 'example.com', 'localhost:3000'
        resource '/api/*', headers: :any, methods: [:get, :post]
      end
    end

    # 4. Response Transformation
    # Configure headers to be added to every response.
    RESPONSE_HEADERS = {
      'X-Content-Type-Options' => 'nosniff',
      'X-Frame-Options' => 'SAMEORIGIN',
      'X-Request-ID' => ->(env) { env['rack.attack.req_id'] || SecureRandom.uuid }
    }
    config.middleware.use Middleware::HeaderAttacher, headers: RESPONSE_HEADERS

    # 5. Request Logging (bottom of stack)
    config.middleware.use Middleware::RequestAuditor, logger: config.logger, format: :default

    # --- Routes Configuration (simulating config/routes.rb) ---
    config.routes do
      get "/api/v1/posts", to: "posts.index"
      get "/api/v1/auth_error", to: "posts.auth_error"
    end
  end
end

# --- Action Definitions ---
module ConfigurableApp
  module Actions
    module Posts
      class Index < Hanami::Action
        def handle(_req, res)
          res.status = 200
          res.json({ posts: [{ id: SecureRandom.uuid, user_id: SecureRandom.uuid, status: "DRAFT" }] })
        end
      end

      class AuthError < Hanami::Action
        def handle(_req, res)
          raise AuthenticationError, "User token is invalid or expired."
        end
      end
    end
  end
end

# To run this file:
# require 'rack'
# require_relative 'this_file_name'
# Rack::Server.start(app: ConfigurableApp::App.new)
</ruby>