<pre lang="ruby">
require 'sinatra/base'
require 'json'
require 'securerandom'
require 'time'
require 'logger'
require 'rack'

#
# Variation 4: The "Pragmatic" Hybrid Approach
# This style mixes Rack middleware for broad, application-agnostic concerns
# (like global error handling or CORS) with Sinatra's internal filters for
# more application-specific logic (like JSON parsing or business-level checks).
# This is a common pattern in real-world applications.
#

# --- Rack Middleware for Cross-Cutting Concerns ---

class GlobalErrorHandler
  def initialize(app, logger: Logger.new($stdout))
    @app = app
    @logger = logger
  end

  def call(env)
    @app.call(env)
  rescue StandardError => e
    @logger.error("Rack Middleware caught exception: #{e.message}")
    [ 500, { 'Content-Type' => 'application/json' }, [{ error: 'A critical server error occurred' }.to_json] ]
  end
end

class SimpleCors
  def initialize(app)
    @app = app
  end

  def call(env)
    if env['REQUEST_METHOD'] == 'OPTIONS'
      return [204, cors_headers, []]
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
      'Access-Control-Allow-Headers' => 'Origin, Content-Type, Accept, Authorization'
    }
  end
end

# --- Sinatra Application using Filters for App-Specific Logic ---
class HybridApp < Sinatra::Base
  # Mock Data Store
  USERS = [ { id: SecureRandom.uuid, email: 'user@example.com', password_hash: '...', role: 'USER', is_active: true, created_at: Time.now.utc.iso8601 } ]
  POSTS = [ { id: SecureRandom.uuid, user_id: USERS.first[:id], title: 'Hybrid Post', content: 'Best of both worlds.', status: 'PUBLISHED' } ]

  # In-memory store for rate limiting.
  RATE_LIMIT_CACHE = {}

  # --- Sinatra Filters for App-Specific Middleware ---

  # 1. Request Logging & Rate Limiting
  before do
    logger.info "Processing: #{request.path_info}"
    
    ip = request.ip
    now = Time.now.to_i
    window = 60
    limit = 100
    
    RATE_LIMIT_CACHE[ip] ||= []
    RATE_LIMIT_CACHE[ip].reject! { |timestamp| timestamp < now - window }
    
    halt 429, { 'Content-Type' => 'application/json' }, { error: 'Too many requests' }.to_json if RATE_LIMIT_CACHE[ip].size >= limit
    
    RATE_LIMIT_CACHE[ip] << now
  end

  # 2. Request/Response Transformation
  before do
    if request.request_method == 'POST' && request.media_type == 'application/json'
      request.body.rewind
      @request_payload = JSON.parse(request.body.read) rescue halt(400, {error: 'Bad JSON'}.to_json)
    end
  end

  after do
    if response.successful? && response.media_type == 'application/json'
      body_content = body.is_a?(Array) ? body.join : body.to_s
      begin
        parsed = JSON.parse(body_content)
        unless parsed.is_a?(Hash) && (parsed.key?('data') || parsed.key?('error'))
          self.body = { data: parsed }.to_json
        end
      rescue JSON::ParserError
        # Not valid JSON, do nothing
      end
    end
  end

  # --- API Routes ---
  get '/posts' do
    content_type :json
    POSTS.to_json
  end

  post '/posts' do
    halt 400, { error: 'Invalid payload' }.to_json unless @request_payload

    new_post = { id: SecureRandom.uuid, user_id: USERS.first[:id], title: @request_payload['title'], content: @request_payload['content'], status: 'DRAFT' }
    POSTS << new_post
    
    status 201
    content_type :json
    new_post.to_json
  end

  get '/error' do
    raise "This error will be caught by the GlobalErrorHandler Rack middleware."
  end
end

# --- Rackup Configuration (in-file for self-containment) ---
if __FILE__ == $0
  app = Rack::Builder.new do
    use GlobalErrorHandler
    use SimpleCors
    run HybridApp
  end.to_app

  require 'rack/handler/webrick'
  Rack::Handler::WEBrick.run(app, Port: 4567)
end
</pre>