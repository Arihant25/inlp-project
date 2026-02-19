<pre lang="ruby">
require 'sinatra/base'
require 'json'
require 'securerandom'
require 'time'

#
# Variation 3: The Modular Sinatra App with Helpers
# This approach uses a modular Sinatra application (`class MyApp < Sinatra::Base`)
# and organizes middleware logic into helper modules. This promotes code reuse
# and better organization for larger applications, while still leveraging
# Sinatra's convenient filter-based DSL.
#

# --- Middleware Logic encapsulated in Modules ---

module MiddlewareModules
  module JsonHelpers
    def parse_json_body!
      return unless request.content_type =~ /application\/json/ && request.body.size > 0
      request.body.rewind
      @parsed_body = JSON.parse(request.body.read)
    rescue JSON::ParserError
      halt 400, json_error_response('Invalid JSON format')
    end

    def json_response(status_code, data)
      status status_code
      content_type :json
      { data: data }.to_json
    end

    def json_error_response(message, status_code = 400)
      content_type :json
      status status_code
      { error: message }.to_json
    end
  end

  module SecurityHelpers
    def apply_cors_headers!
      headers['Access-Control-Allow-Origin'] = '*'
      headers['Access-Control-Allow-Methods'] = 'GET, POST, PUT, DELETE, OPTIONS'
      headers['Access-Control-Allow-Headers'] = 'Content-Type, Authorization'
    end

    def check_rate_limit!
      @rate_limit_store ||= Hash.new { |h, k| h[k] = { count: 0, reset_at: Time.now + 60 } }
      ip = request.ip
      
      if Time.now > @rate_limit_store[ip][:reset_at]
        @rate_limit_store[ip] = { count: 0, reset_at: Time.now + 60 }
      end

      @rate_limit_store[ip][:count] += 1

      halt 429, json_error_response('Rate limit exceeded', 429) if @rate_limit_store[ip][:count] > 100
    end
  end

  module LoggingHelpers
    def log_request
      @request_start_time = Time.now
      logger.info "--> #{request.request_method} #{request.path_info} from #{request.ip}"
    end

    def log_response
      return unless @request_start_time
      duration_ms = ((Time.now - @request_start_time) * 1000).round(2)
      logger.info "<-- #{response.status} in #{duration_ms}ms"
    end
  end
end

module ErrorHandlingExtension
  def self.registered(app)
    app.error StandardError do
      e = env['sinatra.error']
      app.logger.error "FATAL: #{e.class} - #{e.message}\n#{e.backtrace.join("\n  ")}"
      content_type :json
      status 500
      { error: 'An unexpected error occurred.' }.to_json
    end

    app.not_found do
      content_type :json
      { error: 'The requested resource was not found.' }.to_json
    end
  end
end

# --- Sinatra Modular Application ---
class ApiApplication < Sinatra::Base
  register ErrorHandlingExtension
  helpers MiddlewareModules::JsonHelpers, MiddlewareModules::SecurityHelpers, MiddlewareModules::LoggingHelpers

  # Mock Data Store
  USERS = [ { id: SecureRandom.uuid, email: 'admin@example.com', password_hash: '...', role: 'ADMIN', is_active: true, created_at: Time.now.utc.iso8601 } ]
  POSTS = [ { id: SecureRandom.uuid, user_id: USERS.first[:id], title: 'Modular Post', content: 'Well-organized code.', status: 'PUBLISHED' } ]

  configure do
    set :server, :webrick
  end

  before do
    log_request
    apply_cors_headers!
    check_rate_limit!
    parse_json_body! if ['POST', 'PUT'].include?(request.request_method)
  end

  after do
    log_response
  end

  options '*' do
    apply_cors_headers!
    200
  end

  get '/posts' do
    json_response(200, POSTS)
  end

  post '/posts' do
    halt 400, json_error_response('Missing post title') unless @parsed_body && @parsed_body['title']

    new_post = { id: SecureRandom.uuid, user_id: USERS.first[:id], title: @parsed_body['title'], content: @parsed_body['content'], status: 'DRAFT' }
    POSTS << new_post
    
    json_response(201, new_post)
  end

  get '/error' do
    raise "This is a deliberate error for testing."
  end

  run! if app_file == $0
end
</pre>