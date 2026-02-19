<ruby>
# VARIATION 4: The "Modern/DSL" Developer
# STYLE: Creates a declarative DSL for configuring the middleware stack, hiding implementation details.
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

# --- Middleware Definitions (can be standard classes) ---
module DslApp
  module Middleware
    class RequestLogger
      def initialize(app, logger)
        @app = app
        @logger = logger
      end

      def call(env)
        @logger.info ">> #{env['REQUEST_METHOD']} #{env['PATH_INFO']}"
        status, headers, body = @app.call(env)
        @logger.info "<< #{status}"
        [status, headers, body]
      end
    end

    class JsonErrorHandler
      def initialize(app)
        @app = app
      end

      def call(env)
        @app.call(env)
      rescue => e
        [500, { 'Content-Type' => 'application/json' }, [{ error: e.class.name }.to_json]]
      end
    end

    class RequestIdInjector
      def initialize(app)
        @app = app
      end

      def call(env)
        status, headers, body = @app.call(env)
        headers['X-Request-ID'] = SecureRandom.uuid
        [status, headers, body]
      end
    end
  end

  # --- The DSL Module ---
  module MiddlewareDSL
    def configure_middleware(&block)
      instance_eval(&block)
    end

    def log_requests(with:)
      config.middleware.use Middleware::RequestLogger, with
    end

    def handle_errors_as_json
      config.middleware.use Middleware::JsonErrorHandler
    end

    def inject_request_id
      config.middleware.use Middleware::RequestIdInjector
    end

    def enable_cors(&block)
      config.middleware.use Rack::Cors, &block
    end

    def throttle_requests(name, options, &block)
      config.middleware.use Rack::Attack
      Rack::Attack.throttle(name, options, &block)
    end
  end
end

# --- Hanami Application Definition ---
module DslApp
  class App < Hanami::App
    # Extend the App class with our DSL
    extend MiddlewareDSL

    # --- Middleware Configuration using the DSL ---
    configure_middleware do
      # The DSL makes the configuration clean and readable.
      # Order matters: top is outer, bottom is inner.
      handle_errors_as_json

      throttle_requests('requests/ip', limit: 15, period: 30) do |req|
        req.ip
      end

      enable_cors do
        allow do
          origins 'https://app.example.com'
          resource '*', headers: :any, methods: [:get, :post, :options]
        end
      end

      inject_request_id

      log_requests with: config.logger
    end

    # --- Routes Configuration (simulating config/routes.rb) ---
    config.routes do
      get "/users/:id", to: "users.show"
      get "/panic", to: "users.panic"
    end
  end
end

# --- Action Definitions ---
module DslApp
  module Actions
    module Users
      class Show < Hanami::Action
        def handle(req, res)
          user = { id: req.params[:id], email: "admin@example.com", role: "ADMIN", is_active: true }
          res.status = 200
          res.json({ user: user })
        end
      end

      class Panic < Hanami::Action
        def handle(_req, _res)
          # This will be caught by the JsonErrorHandler
          1 / 0
        end
      end
    end
  end
end

# To run this file:
# require 'rack'
# require_relative 'this_file_name'
# Rack::Server.start(app: DslApp::App.new)
</ruby>