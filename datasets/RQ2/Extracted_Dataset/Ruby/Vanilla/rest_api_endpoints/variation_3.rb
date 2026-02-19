require 'socket'
require 'json'
require 'securerandom'
require 'time'
require 'uri'
require 'digest'

# --- Data Persistence Layer ---
module Persistence
  class UserStore
    def self.instance
      @instance ||= new
    end

    def initialize
      @db = {}
      seed_data
    end

    def find(id)
      @db[id]
    end

    def find_by_email(email)
      @db.values.find { |u| u.email == email }
    end

    def list_all
      @db.values
    end

    def persist(user_entity)
      @db[user_entity.id] = user_entity
      user_entity
    end

    def remove(id)
      @db.delete(id)
    end
    
    private
    
    def seed_data
      puts "Seeding initial user data..."
      5.times do |i|
        id = SecureRandom.uuid
        @db[id] = Domain::User.new(
          id: id,
          email: "user#{i + 1}@example.com",
          password_hash: Digest::SHA256.hexdigest("password"),
          role: i.even? ? 'ADMIN' : 'USER',
          is_active: i % 3 != 0,
          created_at: Time.now.utc.iso8601
        )
      end
      puts "#{@db.size} users seeded."
    end
  end
end

# --- Domain Model Layer ---
module Domain
  User = Struct.new(:id, :email, :password_hash, :role, :is_active, :created_at, keyword_init: true) do
    def to_public_h
      to_h.tap { |h| h.delete(:password_hash) }
    end
  end
end

# --- Service Layer (Business Logic) ---
class UserService
  def initialize(store = Persistence::UserStore.instance)
    @store = store
  end

  def list_users(filters)
    users = @store.list_all
    
    users = users.select { |u| u.role == filters[:role].upcase } if filters[:role]
    users = users.select { |u| u.is_active.to_s == filters[:is_active] } if filters[:is_active]
    
    users.sort_by(&:created_at).reverse
  end

  def find_user(id)
    @store.find(id)
  end

  def create_user(params)
    return { error: 'Email is required' } if params['email'].to_s.empty?
    return { error: 'Password is required' } if params['password'].to_s.empty?
    return { error: 'Email already taken' } if @store.find_by_email(params['email'])

    user = Domain::User.new(
      id: SecureRandom.uuid,
      email: params['email'],
      password_hash: Digest::SHA256.hexdigest(params['password']),
      role: ['ADMIN', 'USER'].include?(params['role']&.upcase) ? params['role'].upcase : 'USER',
      is_active: params.fetch('is_active', true),
      created_at: Time.now.utc.iso8601
    )
    @store.persist(user)
  end

  def update_user(id, params)
    user = @store.find(id)
    return nil unless user

    user.email = params['email'] if params['email']
    user.role = params['role'].upcase if ['ADMIN', 'USER'].include?(params['role']&.upcase)
    user.is_active = params['is_active'] unless params['is_active'].nil?
    
    @store.persist(user)
  end

  def delete_user(id)
    @store.remove(id)
  end
end

# --- Presentation Layer (HTTP Handling) ---
class UserHttpHandler
  def initialize(service = UserService.new)
    @service = service
  end

  def handle(request)
    method = request[:method]
    path = request[:path]
    
    # Simple regex-based routing
    case [method, path]
    when ['GET', '/users'] then list(request)
    when ['POST', '/users'] then create(request)
    when ['GET', %r{/users/([\w-]+)}] then show(request, $1)
    when ['PUT', %r{/users/([\w-]+)}], ['PATCH', %r{/users/([\w-]+)}] then update(request, $1)
    when ['DELETE', %r{/users/([\w-]+)}] then delete(request, $1)
    else Response.new(404, { error: 'Not Found' })
    end
  end

  private
  
  Response = Struct.new(:status, :body, :headers) do
    def initialize(status, body, headers = {'Content-Type' => 'application/json'})
      super(status, body, headers)
    end
  end

  def list(request)
    all_users = @service.list_users(request[:params])
    
    page = request.dig(:params, 'page')&.to_i || 1
    per_page = request.dig(:params, 'per_page')&.to_i || 3
    offset = (page - 1) * per_page
    
    paginated = all_users.slice(offset, per_page) || []
    
    body = {
      data: paginated.map(&:to_public_h),
      meta: { total: all_users.size, page: page, per_page: per_page }
    }
    Response.new(200, body)
  end

  def show(request, id)
    user = @service.find_user(id)
    user ? Response.new(200, user.to_public_h) : Response.new(404, { error: 'User not found' })
  end

  def create(request)
    params = JSON.parse(request[:body])
    result = @service.create_user(params)
    
    if result.is_a?(Domain::User)
      Response.new(201, result.to_public_h)
    else
      Response.new(400, result)
    end
  rescue JSON::ParserError
    Response.new(400, { error: 'Invalid JSON' })
  end

  def update(request, id)
    params = JSON.parse(request[:body])
    user = @service.update_user(id, params)
    
    user ? Response.new(200, user.to_public_h) : Response.new(404, { error: 'User not found' })
  rescue JSON::ParserError
    Response.new(400, { error: 'Invalid JSON' })
  end

  def delete(request, id)
    user = @service.delete_user(id)
    user ? Response.new(204, nil) : Response.new(404, { error: 'User not found' })
  end
end

# --- Server ---
class Server
  def initialize(port, handler)
    @server = TCPServer.new(port)
    @handler = handler
    puts "Service-oriented server listening on port #{port}"
  end

  def run
    loop { Thread.start(@server.accept) { |client| process(client) } }
  end

  def process(client)
    request_line = client.gets
    return unless request_line
    
    method, full_path, _ = request_line.split
    path, query = full_path.split('?', 2)
    params = query ? URI.decode_www_form(query).to_h : {}
    
    headers = {}
    while (line = client.gets) && !line.strip.empty?
      key, value = line.split(':', 2)
      headers[key.strip.downcase] = value.strip
    end
    body = headers['content-length'] ? client.read(headers['content-length'].to_i) : ''
    
    puts "[#{Time.now}] Received: #{method} #{full_path}"
    
    request = { method: method, path: path, params: params, headers: headers, body: body }
    response = @handler.handle(request)
    
    body_str = response.body ? response.body.to_json : ''
    client.print "HTTP/1.1 #{response.status}\r\n"
    response.headers['Content-Length'] = body_str.bytesize
    response.headers.each { |k, v| client.print "#{k}: #{v}\r\n" }
    client.print "\r\n"
    client.print body_str
  rescue => e
    puts "Error: #{e.message}"
    client.print "HTTP/1.1 500\r\nContent-Type: application/json\r\n\r\n{\"error\":\"Internal Server Error\"}"
  ensure
    client.close
  end
end

# --- Application Entrypoint ---
handler = UserHttpHandler.new
server = Server.new(8082, handler)
server.run