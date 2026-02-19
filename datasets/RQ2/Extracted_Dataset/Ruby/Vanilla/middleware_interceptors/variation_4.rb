require 'json'
require 'securerandom'
require 'time'
require 'stringio'

# ==============================================================================
# Domain & Data Mocks
# ==============================================================================
# Using simple hashes for domain objects in this variation
$DB = {
  users: {
    "f47ac10b-58cc-4372-a567-0e02b2c3d479" => { id: "f47ac10b-58cc-4372-a567-0e02b2c3d479", email: "admin@example.com", password_hash: "hash1", role: "ADMIN", is_active: true, created_at: Time.now }
  },
  posts: {
    "a1b2c3d4-e5f6-7890-1234-56789hijklm" => { id: "a1b2c3d4-e5f6-7890-1234-567890hijklm", user_id: "f47ac10b-58cc-4372-a567-0e02b2c3d479", title: "Pipeline Pattern", content: "A post about pipelines.", status: "PUBLISHED" }
  }
}

# ==============================================================================
# Middleware Implementation (Pipeline/Composition Style)
# ==============================================================================

# Each middleware is a module with a `call` method that mutates the context (ctx)
module PipelineSteps
  module ErrorHandler
    def self.call(ctx)
      yield
    rescue => e
      STDERR.puts "[PIPELINE-ERROR] #{e.class}: #{e.message}"
      ctx[:response][:status] = 500
      ctx[:response][:body] = { error: "Internal Server Error" }
      ctx[:halt] = true
    end
  end

  module Logger
    def self.call(ctx)
      req = ctx[:request]
      puts "[PIPE-LOG] --> #{req['REQUEST_METHOD']} #{req['PATH_INFO']} from #{req['REMOTE_ADDR']}"
      yield
      res = ctx[:response]
      puts "[PIPE-LOG] <-- #{res[:status]}"
    end
  end

  module Cors
    def self.call(ctx)
      req = ctx[:request]
      if req['REQUEST_METHOD'] == 'OPTIONS'
        ctx[:response][:status] = 204
        ctx[:response][:headers].merge!({
          'Access-Control-Allow-Origin' => '*',
          'Access-Control-Allow-Methods' => 'GET, POST, OPTIONS'
        })
        ctx[:halt] = true # Stop processing further
        return
      end
      yield
      ctx[:response][:headers]['Access-Control-Allow-Origin'] = '*'
    end
  end

  module RateLimiter
    @hits = {}
    LIMIT = 10
    WINDOW = 60

    def self.call(ctx)
      ip = ctx[:request]['REMOTE_ADDR']
      now = Time.now.to_i
      @hits[ip] = (@hits[ip] || []).select { |t| now - t < WINDOW }
      
      if @hits[ip].size >= LIMIT
        ctx[:response] = { status: 429, headers: {}, body: { error: "Rate limit hit" } }
        ctx[:halt] = true
        return
      end
      
      @hits[ip] << now
      yield
    end
  end

  module BodyParser
    def self.call(ctx)
      req = ctx[:request]
      if req['CONTENT_TYPE'] == 'application/json'
        body_str = req['rack.input'].read
        ctx[:params] = JSON.parse(body_str) unless body_str.empty?
        req['rack.input'].rewind
      end
      yield
    end
  end

  module Router
    def self.call(ctx)
      req = ctx[:request]
      res = ctx[:response]
      
      case [req['REQUEST_METHOD'], req['PATH_INFO']]
      when ['GET', '/posts']
        res[:status] = 200
        res[:body] = $DB[:posts].values
      when ['POST', '/posts']
        # raise "Simulating a random failure" if rand > 0.8
        new_post = {
          id: SecureRandom.uuid,
          user_id: "f47ac10b-58cc-4372-a567-0e02b2c3d479",
          title: ctx.dig(:params, 'title'),
          content: ctx.dig(:params, 'content'),
          status: "DRAFT"
        }
        $DB[:posts][new_post[:id]] = new_post
        res[:status] = 201
        res[:body] = new_post
      else
        res[:status] = 404
        res[:body] = { error: "Not Found" }
      end
      yield # In this setup, router is the last logical step before finalization
    end
  end

  module ResponseSerializer
    def self.call(ctx)
      yield
      res = ctx[:response]
      if res[:body].is_a?(Hash) || res[:body].is_a?(Array)
        res[:headers]['Content-Type'] = 'application/json'
        res[:body] = [JSON.generate(res[:body])]
      else
        res[:body] = [res[:body].to_s]
      end
    end
  end
end

# ==============================================================================
# The Pipeline Runner
# ==============================================================================
class Pipeline
  def initialize(steps)
    # Reverse the steps to build a nested block structure
    @stack = steps.reverse.reduce(->(ctx) {}) do |next_step, current_step|
      ->(ctx) { current_step.call(ctx) { next_step.call(ctx) unless ctx[:halt] } }
    end
  end

  def execute(env)
    # Initial context
    ctx = {
      request: env,
      response: { status: 200, headers: {}, body: [] },
      params: {},
      halt: false
    }
    
    @stack.call(ctx)
    
    # Return Rack-compatible response
    [ctx[:response][:status], ctx[:response][:headers], ctx[:response][:body]]
  end
end

# ==============================================================================
# Server Simulation
# ==============================================================================
if __FILE__ == $0
  # Define the order of execution for the pipeline
  PIPELINE_STEPS = [
    PipelineSteps::ErrorHandler,
    PipelineSteps::Logger,
    PipelineSteps::Cors,
    PipelineSteps::RateLimiter,
    PipelineSteps::BodyParser,
    PipelineSteps::Router,
    PipelineSteps::ResponseSerializer
  ]

  pipeline = Pipeline.new(PIPELINE_STEPS)

  puts "--- 1. Handling GET /posts ---"
  get_env = { 'REQUEST_METHOD' => 'GET', 'PATH_INFO' => '/posts', 'REMOTE_ADDR' => '127.0.0.1', 'rack.input' => StringIO.new }
  p pipeline.execute(get_env)

  puts "\n--- 2. Handling POST /posts (Success) ---"
  post_body = JSON.generate({ title: "Pipeline Post", content: "Content via pipeline." })
  post_env = { 'REQUEST_METHOD' => 'POST', 'PATH_INFO' => '/posts', 'REMOTE_ADDR' => '127.0.0.1', 'CONTENT_TYPE' => 'application/json', 'rack.input' => StringIO.new(post_body) }
  p pipeline.execute(post_env)

  puts "\n--- 3. Handling OPTIONS /posts (CORS) ---"
  options_env = { 'REQUEST_METHOD' => 'OPTIONS', 'PATH_INFO' => '/posts', 'REMOTE_ADDR' => '127.0.0.1', 'rack.input' => StringIO.new }
  p pipeline.execute(options_env)
end