<pre lang="ruby">
require 'sinatra'
require 'json'
require 'securerandom'
require 'time'

#
# Variation 2: The Sinatra `before`/`after`/`error` Filters Approach
# This is a highly idiomatic Sinatra pattern. It uses built-in filter blocks
# to intercept the request/response lifecycle. It's declarative, concise,
# and keeps all logic within the application's context.
#

# --- Configuration and Mock Data ---
configure do
  set :server, :webrick
  set :rate_limit_store, Hash.new { |h, k| h[k] = { requests: 0, timestamp: Time.now } }
  set :rate_limit_max, 100
  set :rate_limit_window, 60 # seconds
end

USERS = [ { id: SecureRandom.uuid, email: 'user@example.com', password_hash: '...', role: 'USER', is_active: true, created_at: Time.now.utc.iso8601 } ]
POSTS = [ { id: SecureRandom.uuid, user_id: USERS.first[:id], title: 'Using Sinatra Filters', content: 'This is effective.', status: 'PUBLISHED' } ]

# --- Middleware via Sinatra Filters ---

# 1. Request Logging
before do
  @start_time = Time.now
  logger.info "Request Start: #{request.request_method} #{request.path_info}"
end

after do
  duration = ((Time.now - @start_time) * 1000).round(2)
  logger.info "Request End: #{request.request_method} #{request.path_info} - Status: #{response.status} - Duration: #{duration}ms"
end

# 2. CORS Handling
before do
  headers['Access-Control-Allow-Origin'] = '*'
  headers['Access-Control-Allow-Methods'] = 'GET, POST, PUT, DELETE, OPTIONS'
  headers['Access-Control-Allow-Headers'] = 'Content-Type, Authorization'
end

options '*' do
  200
end

# 3. Rate Limiting
before do
  ip = request.ip
  store = settings.rate_limit_store
  
  if Time.now - store[ip][:timestamp] > settings.rate_limit_window
    store[ip] = { requests: 0, timestamp: Time.now }
  end

  store[ip][:requests] += 1

  if store[ip][:requests] > settings.rate_limit_max
    halt 429, { 'Content-Type' => 'application/json' }, { error: 'Rate limit exceeded' }.to_json
  end
end

# 4. Request/Response Transformation
before do
  if request.content_type =~ /application\/json/ && request.body.size > 0
    request.body.rewind
    begin
      @json_params = JSON.parse(request.body.read)
    rescue JSON::ParserError
      halt 400, { 'Content-Type' => 'application/json' }, { error: 'Invalid JSON format' }.to_json
    end
  end
end

after do
  if response.status.between?(200, 299) && response.content_type =~ /application\/json/ && body.is_a?(Array)
    original_body_str = body.join
    begin
      parsed_body = JSON.parse(original_body_str)
      unless parsed_body.is_a?(Hash) && parsed_body.key?('data')
        response.body = [{ data: parsed_body }.to_json]
      end
    rescue JSON::ParserError
      # Not valid JSON, leave it alone
    end
  end
end

# 5. Error Handling
error do
  e = env['sinatra.error']
  logger.error "Unhandled Error: #{e.message}\n#{e.backtrace.join("\n")}"
  content_type :json
  status 500
  { error: 'Internal Server Error', message: e.message }.to_json
end

not_found do
  content_type :json
  { error: 'Not Found' }.to_json
end

# --- API Routes ---

get '/posts' do
  content_type :json
  POSTS.to_json
end

post '/posts' do
  halt 400, { error: 'Missing JSON body' }.to_json unless @json_params

  new_post = {
    id: SecureRandom.uuid,
    user_id: USERS.first[:id],
    title: @json_params['title'],
    content: @json_params['content'],
    status: 'DRAFT'
  }
  POSTS << new_post
  
  status 201
  content_type :json
  new_post.to_json
end

get '/error' do
  raise "This is a deliberate error."
end
</pre>