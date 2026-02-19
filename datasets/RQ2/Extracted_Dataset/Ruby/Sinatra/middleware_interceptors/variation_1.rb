<pre lang="ruby">
require 'sinatra/base'
require 'json'
require 'securerandom'
require 'time'
require 'logger'
require 'rack'

#
# Variation 1: The Classic Rack Middleware Approach
# This style uses distinct, single-purpose classes that conform to the Rack
# middleware interface (initialize(app) and call(env)). The application
# is built by chaining these middleware components together.
# It's a very decoupled and testable approach.
#

# --- Middleware Classes ---

# 1. Error Handling Middleware
class ErrorHandler
  def initialize(app)
    @app = app
  end

  def call(env)
    begin
      @app.call(env)
    rescue => e
      env['rack.logger'].error("App Error: #{e.message}")
      env['rack.logger'].error(e.backtrace.join("\n"))
      [
        500,
        { 'Content-Type' => 'application/json' },
        [{ error: 'Internal Server Error', message: e.message }.to_json]
      ]
    end
  end
end

# 2. Request Logging Middleware
class RequestLogger
  def initialize(app, logger)
    @app = app
    @logger = logger
  end

  def call(env)
    start_time = Time.now
    status, headers, body = @app.call(env)
    end_time = Time.now
    @logger.info(
      "\"#{env['REQUEST_METHOD']} #{env['PATH_INFO']}\" " +
      "#{status} " +
      "#{((end_time - start_time) * 1000).round(2)}ms"
    )
    [status, headers, body]
  end
end

# 3. CORS Handling Middleware
class CorsHandler
  def initialize(app)
    @app = app
  end

  def call(env)
    if env['REQUEST_METHOD'] == 'OPTIONS'
      return [ 200, cors_headers, [] ]
    end

    status, headers, body = @app.call(env)
    headers.merge!(cors_headers)
    [status, headers, body]
  end

  private
  def cors_headers
    {
      'Access-Control-Allow-Origin' => '*',
      'Access-Control-Allow-Methods' => 'GET, POST, PUT, DELETE, OPTIONS',
      'Access-Control-Allow-Headers' => 'Content-Type, Authorization'
    }
  end
end

# 4. Rate Limiting Middleware
class RateLimiter
  REQUESTS = Hash.new { |h, k| h[k] = { count: 0, first_request_at: Time.now } }
  LIMIT = 100
  PERIOD = 60 # seconds

  def initialize(app)
    @app = app
  end

  def call(env)
    ip = env['REMOTE_ADDR']
    data = REQUESTS[ip]

    if Time.now - data[:first_request_at] > PERIOD
      data[:count] = 0
      data[:first_request_at] = Time.now
    end

    data[:count] += 1

    if data[:count] > LIMIT
      return [ 429, { 'Content-Type' => 'application/json' }, [{ error: 'Too Many Requests' }.to_json] ]
    end

    @app.call(env)
  end
end

# 5. Request/Response Transformation Middleware
class JsonTransformer
  def initialize(app)
    @app = app
  end

  def call(env)
    if env['CONTENT_TYPE'] =~ /application\/json/
      body = env['rack.input'].read
      unless body.empty?
        env['rack.input'].rewind
        env['app.parsed_json'] = JSON.parse(body) rescue nil
      end
    end

    status, headers, body_proxy = @app.call(env)
    
    if status.between?(200, 299) && headers['Content-Type'] =~ /application\/json/
      original_body = []
      body_proxy.each { |part| original_body << part }
      body_proxy.close if body_proxy.respond_to?(:close)

      parsed_body = JSON.parse(original_body.join)
      new_body = { data: parsed_body }.to_json
      headers['Content-Length'] = new_body.bytesize.to_s
      return [status, headers, [new_body]]
    end

    [status, headers, body_proxy]
  end
end

# --- Sinatra Application ---
class MainApp < Sinatra::Base
  set :logger, Logger.new($stdout)

  # Mock Data Store
  USERS = [ { id: SecureRandom.uuid, email: 'admin@example.com', password_hash: '...', role: 'ADMIN', is_active: true, created_at: Time.now.utc.iso8601 } ]
  POSTS = [ { id: SecureRandom.uuid, user_id: USERS.first[:id], title: 'First Post', content: 'Hello Sinatra!', status: 'PUBLISHED' } ]

  get '/posts' do
    content_type :json
    POSTS.to_json
  end

  post '/posts' do
    json_params = env['app.parsed_json']
    halt 400, { error: 'Invalid JSON body' }.to_json unless json_params && json_params['title']

    new_post = { id: SecureRandom.uuid, user_id: USERS.first[:id], title: json_params['title'], content: json_params['content'], status: 'DRAFT' }
    POSTS << new_post
    
    status 201
    content_type :json
    new_post.to_json
  end

  get '/error' do
    raise "This is a deliberate error."
  end
end

# --- Rackup Configuration (in-file for self-containment) ---
if __FILE__ == $0
  app = Rack::Builder.new do
    # Middleware is layered, outside-in. ErrorHandler is the outermost layer.
    use ErrorHandler
    use RequestLogger, Logger.new($stdout)
    use RateLimiter
    use CorsHandler
    use JsonTransformer
    run MainApp
  end.to_app

  require 'rack/handler/webrick'
  Rack::Handler::WEBrick.run(app, Port: 4567)
end
</pre>