<ruby>
# VARIATION 2: The "Functional/Concise" Developer
# STYLE: Prefers smaller, more focused components, shorter names, and functional style where appropriate.
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
module ConciseApp
  module Mid
    # Simple logger
    class Log
      def initialize(app, logger: $stdout)
        @app = app
        @log = logger.is_a?(Logger) ? logger : Logger.new(logger)
      end

      def call(env)
        start = Process.clock_gettime(Process::CLOCK_MONOTONIC)
        status, headers, body = @app.call(env)
        duration = Process.clock_gettime(Process::CLOCK_MONOTONIC) - start
        @log.info "[#{env['REQUEST_METHOD']}] #{env['PATH_INFO']} -> #{status} (#{duration.round(4)}s)"
        [status, headers, body]
      end
    end

    # Simple error catcher
    class Catcher
      def initialize(app)
        @app = app
      end

      def call(env)
        @app.call(env)
      rescue => e
        body = { err: "server_error", msg: e.class.name }.to_json
        [500, { 'Content-Type' => 'application/json' }, [body]]
      end
    end

    # A lambda-based middleware for adding a header
    AddHeader = ->(app, header, value_proc) {
      ->(env) {
        status, headers, body = app.call(env)
        headers[header] = value_proc.call
        [status, headers, body]
      }
    }
  end
end

# --- Hanami Application Definition ---
module ConciseApp
  class App < Hanami::App
    # --- Middleware Configuration (simulating config/app.rb) ---
    config.middleware.use Mid::Catcher
    
    config.middleware.use Rack::Attack
    Rack::Attack.throttle("req/ip", limit: 10, period: 20) { |req| req.ip }

    config.middleware.use Rack::Cors do
      allow do
        origins "*"
        resource "*", headers: :any, methods: :any
      end
    end

    # Using a lambda-based middleware for transformation
    config.middleware.use AddHeader, 'X-Trace-Id', -> { SecureRandom.hex(8) }

    config.middleware.use Mid::Log, logger: config.logger

    # --- Routes Configuration (simulating config/routes.rb) ---
    config.routes do
      get "/posts", to: "posts.list"
      get "/oops", to: "posts.oops"
    end
  end
end

# --- Action Definitions ---
module ConciseApp
  module Actions
    module Posts
      class List < Hanami::Action
        def handle(_req, res)
          res.status = 200
          res.json({ data: [{ id: SecureRandom.uuid, title: "Concise Hanami", status: "PUBLISHED" }] })
        end
      end

      class Oops < Hanami::Action
        def handle(_req, res)
          raise ArgumentError, "Invalid parameter provided"
        end
      end
    end
  end
end

# To run this file:
# require 'rack'
# require_relative 'this_file_name'
# Rack::Server.start(app: ConciseApp::App.new)
</ruby>