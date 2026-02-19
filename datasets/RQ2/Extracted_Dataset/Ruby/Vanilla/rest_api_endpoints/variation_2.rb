require 'socket'
require 'json'
require 'securerandom'
require 'time'
require 'uri'

# --- Data Layer ---
class UserRepository
  def initialize
    @users = {}
    seed
  end

  def find(id)
    @users[id]
  end

  def all
    @users.values.sort_by { |u| u.created_at }.reverse
  end

  def save(user)
    @users[user.id] = user
  end

  def delete(id)
    @users.delete(id)
  end

  private

  def seed
    puts "Seeding initial user data..."
    5.times do |i|
      user = User.new(
        email: "user#{i + 1}@example.com",
        password: "password#{i}",
        role: i.even? ? 'ADMIN' : 'USER',
        is_active: i % 3 != 0
      )
      save(user)
    end
    puts "#{@users.size} users seeded."
  end
end

# --- Model Layer ---
User = Struct.new(:id, :email, :password_hash, :role, :is_active, :created_at, keyword_init: true) do
  ROLES = ['ADMIN', 'USER'].freeze

  def initialize(email:, password:, role: 'USER', is_active: true, **_args)
    super(
      id: SecureRandom.uuid,
      email: email,
      password_hash: Digest::SHA256.hexdigest(password), # Mock hashing
      role: ROLES.include?(role.upcase) ? role.upcase : 'USER',
      is_active: is_active,
      created_at: Time.now.utc.iso8601
    )
  end

  def to_h
    super.tap { |h| h.delete(:password_hash) }
  end
end

# --- Controller Layer ---
class UsersController
  def initialize(repository)
    @repository = repository
  end

  def index(params)
    users = @repository.all
    
    # Filtering
    users = users.select { |u| u.role == params['role'].upcase } if params['role']
    users = users.select { |u| u.is_active.to_s == params['is_active'] } if params['is_active']

    # Pagination
    page = params.fetch('page', 1).to_i
    per_page = params.fetch('per_page', 3).to_i
    offset = (page - 1) * per_page
    paginated_users = users.slice(offset, per_page) || []

    body = {
      data: paginated_users.map(&:to_h),
      meta: { total: users.size, page: page, per_page: per_page }
    }.to_json
    [200, { 'Content-Type' => 'application/json' }, body]
  end

  def show(id)
    user = @repository.find(id)
    if user
      [200, { 'Content-Type' => 'application/json' }, user.to_h.to_json]
    else
      [404, { 'Content-Type' => 'application/json' }, { error: 'User not found' }.to_json]
    end
  end

  def create(body)
    data = JSON.parse(body)
    user = User.new(email: data['email'], password: data['password'], role: data['role'], is_active: data['is_active'])
    @repository.save(user)
    [201, { 'Content-Type' => 'application/json' }, user.to_h.to_json]
  rescue JSON::ParserError, KeyError => e
    [400, { 'Content-Type' => 'application/json' }, { error: "Bad Request: #{e.message}" }.to_json]
  end

  def update(id, body)
    user = @repository.find(id)
    return [404, { 'Content-Type' => 'application/json' }, { error: 'User not found' }.to_json] unless user

    data = JSON.parse(body)
    user.email = data['email'] if data['email']
    user.role = data['role'].upcase if data['role'] && User::ROLES.include?(data['role'].upcase)
    user.is_active = data['is_active'] unless data['is_active'].nil?
    @repository.save(user)
    [200, { 'Content-Type' => 'application/json' }, user.to_h.to_json]
  rescue JSON::ParserError => e
    [400, { 'Content-Type' => 'application/json' }, { error: "Bad Request: #{e.message}" }.to_json]
  end

  def destroy(id)
    user = @repository.delete(id)
    if user
      [204, {}, '']
    else
      [404, { 'Content-Type' => 'application/json' }, { error: 'User not found' }.to_json]
    end
  end
end

# --- Server & Routing ---
class AppServer
  def initialize(port)
    @port = port
    @repository = UserRepository.new
    @controller = UsersController.new(@repository)
  end

  def start
    server = TCPServer.new(@port)
    puts "OOP server listening on port #{@port}"
    loop do
      Thread.start(server.accept) do |client|
        handle_connection(client)
      end
    end
  end

  private

  def handle_connection(client)
    request_line = client.gets
    return unless request_line
    
    method, full_path, _ = request_line.split
    path, query_string = full_path.split('?', 2)
    params = query_string ? URI.decode_www_form(query_string).to_h : {}

    headers = read_headers(client)
    body = client.read(headers['content-length'].to_i) if headers['content-length']

    puts "[#{Time.now}] Received: #{method} #{full_path}"
    
    status, headers, body = route(method, path, params, body)
    
    send_response(client, status, headers, body)
  ensure
    client.close
  end

  def read_headers(client)
    headers = {}
    while (line = client.gets) && !line.strip.empty?
      key, value = line.split(':', 2)
      headers[key.strip.downcase] = value.strip
    end
    headers
  end

  def route(method, path, params, body)
    case [method, path]
    when ['GET', '/users']
      @controller.index(params)
    when ['POST', '/users']
      @controller.create(body)
    when ['GET', %r{/users/(.+)}]
      user_id = path.match(%r{/users/(.+)})[1]
      @controller.show(user_id)
    when ['PUT', %r{/users/(.+)}], ['PATCH', %r{/users/(.+)}]
      user_id = path.match(%r{/users/(.+)})[1]
      @controller.update(user_id, body)
    when ['DELETE', %r{/users/(.+)}]
      user_id = path.match(%r{/users/(.+)})[1]
      @controller.destroy(user_id)
    else
      [404, { 'Content-Type' => 'application/json' }, { error: 'Not Found' }.to_json]
    end
  end

  def send_response(client, status, headers, body)
    client.print "HTTP/1.1 #{status}\r\n"
    headers['Content-Length'] = body.bytesize
    headers.each { |k, v| client.print "#{k}: #{v}\r\n" }
    client.print "\r\n"
    client.print body
  end
end

# --- Start ---
# Need digest for password hashing
require 'digest'
AppServer.new(8081).start