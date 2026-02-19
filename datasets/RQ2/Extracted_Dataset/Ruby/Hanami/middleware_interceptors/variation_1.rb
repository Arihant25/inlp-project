<ruby>
# VARIATION 1: The "Classic OOP" Developer
# STYLE: Clear, well-defined classes in a dedicated namespace. Verbose and descriptive naming.
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
      def initialize
        @data = {}
      end
      def read(key)
        @data[key]
      end
      def write(key, value, options = {})
        @data[key] = value
      end
      def increment(key, amount, options = {})
        @data[key] = (@data[key] || 0) + amount
      end
    end
  end
end
Rack::Attack.cache = Rack::Attack::Cache.new

# --- Middleware Definitions ---
module ClassicApp
  module Middleware
    # Middleware for logging request details and processing time.
    class RequestLogger
      def initialize(app, logger)
        @app = app
        @logger = logger
      end

      def call(env)
        start_time = Process.clock_gettime(Process::CLOCK_MONOTONIC)
        @logger.info("Request: #{env['REQUEST_METHOD']} #{env['PATH_INFO']}")

        status, headers, body = @app.call(env)

        end_time = Process.clock_gettime(Process::CLOCK_MONOTONIC)
        duration = (end_time - start_time).round(4)
        @logger.info("Response: #{status} in #{duration}s")

        [status, headers, body]
      end
    end

    # Middleware for transforming the response, e.g., adding a request ID.
    class ResponseTransformer
      def initialize(app)
        @app = app
      end

      def call(env)
        request_id = SecureRandom.uuid
        env['app.request_id'] = request_id

        status, headers, body = @app.call(env)

        headers['X-Request-Id'] = request_id
        [status, headers, body]
      end
    end

    # Middleware for handling application errors gracefully.
    class ErrorHandler
      def initialize(app, logger)
        @app = app
        @logger = logger
      end

      def call(env)
        @app.call(env)
      rescue => exception
        @logger.error("Unhandled Exception: #{exception.class} - #{exception.message}")
        @logger.error(exception.backtrace.join("\n"))

        error_response = { error: 'Internal Server Error' }.to_json
        [500, { 'Content-Type' => 'application/json' }, [error_response]]
      end
    end
  end
end

# --- Hanami Application Definition ---
module ClassicApp
  class App < Hanami::App
    # Configure the application logger
    config.logger = Logger.new($stdout)

    # --- Middleware Configuration (simulating config/app.rb) ---
    # 1. Error Handling (top of the stack to catch errors from all middleware below)
    config.middleware.use Middleware::ErrorHandler, config.logger

    # 2. Rate Limiting
    config.middleware.use Rack::Attack
    Rack::Attack.throttle('requests by ip', limit: 5, period: 10) do |request|
      request.ip
    end

    # 3. CORS Handling
    config.middleware.use Rack::Cors do
      allow do
        origins '*'
        resource '*', headers: :any, methods: [:get, :post, :put, :delete, :options]
      end
    end

    # 4. Request/Response Transformation
    config.middleware.use Middleware::ResponseTransformer

    # 5. Request Logging (bottom of the stack to log the actual request)
    config.middleware.use Middleware::RequestLogger, config.logger

    # --- Routes Configuration (simulating config/routes.rb) ---
    config.routes do
      get "/users", to: "users.index"
      get "/fail", to: "users.fail"
    end
  end
end

# --- Action Definitions ---
module ClassicApp
  module Actions
    module Users
      class Index < Hanami::Action
        def handle(request, response)
          response.status = 200
          response.json({ users: [{ id: SecureRandom.uuid, email: "user@example.com", role: "USER" }] })
        end
      end

      class Fail < Hanami::Action
        def handle(request, response)
          raise "This is a simulated application error."
        end
      end
    end
  end
end

# To run this file:
# require 'rack'
# require_relative 'this_file_name'
# Rack::Server.start(app: ClassicApp::App.new)
</ruby>