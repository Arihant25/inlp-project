require 'socket'
require 'json'
require 'securerandom'
require 'time'
require 'uri'

# --- Mock Database ---
# Using a simple Hash as an in-memory key-value store.
USERS_DB = {}
ROLES = ['ADMIN', 'USER'].freeze

# Helper to seed the database
def seed_data
  puts "Seeding initial user data..."
  5.times do |i|
    id = SecureRandom.uuid
    USERS_DB[id] = {
      id: id,
      email: "user#{i + 1}@example.com",
      password_hash: SecureRandom.hex(16),
      role: i.even? ? 'ADMIN' : 'USER',
      is_active: i % 3 != 0,
      created_at: Time.now.utc.iso8601
    }
  end
  puts "#{USERS_DB.size} users seeded."
end

# --- Response Helpers ---
def respond_with(session, status_code, status_message, headers = {}, body = '')
  headers['Content-Type'] ||= 'application/json'
  headers['Content-Length'] = body.bytesize
  
  session.print "HTTP/1.1 #{status_code} #{status_message}\r\n"
  headers.each { |key, value| session.print "#{key}: #{value}\r\n" }
  session.print "\r\n"
  session.print body unless body.empty?
end

def json_response(session, status, data)
  body = data.to_json
  respond_with(session, status, 'OK', {}, body)
end

def error_response(session, status, message)
  body = { error: message }.to_json
  respond_with(session, status, message.gsub(' ', ''), {}, body)
end

def no_content_response(session)
  respond_with(session, 204, 'No Content', { 'Content-Length' => 0 })
end

# --- Main Server Logic ---
def run_server(port)
  server = TCPServer.new(port)
  puts "Procedural server listening on port #{port}"

  loop do
    Thread.start(server.accept) do |session|
      begin
        request_line = session.gets
        next unless request_line

        method, full_path, _http_version = request_line.split
        path, query = full_path.split('?', 2)
        
        headers = {}
        while (line = session.gets) && !line.strip.empty?
          key, value = line.split(':', 2)
          headers[key.strip.downcase] = value.strip
        end

        body = headers['content-length'] ? session.read(headers['content-length'].to_i) : nil
        
        puts "[#{Time.now}] Received: #{method} #{full_path}"

        # --- Routing ---
        case [method, path]
        when ['GET', '/users']
          params = query ? URI.decode_www_form(query).to_h : {}
          
          # Filtering
          filtered_users = USERS_DB.values.select do |user|
            (params['role'] ? user[:role] == params['role'].upcase : true) &&
            (params['is_active'] ? user[:is_active].to_s == params['is_active'] : true)
          end

          # Pagination
          page = params.fetch('page', 1).to_i
          per_page = params.fetch('per_page', 3).to_i
          offset = (page - 1) * per_page
          
          paginated_users = filtered_users.sort_by { |u| u[:created_at] }.reverse.slice(offset, per_page) || []
          
          response_data = {
            data: paginated_users,
            meta: {
              total: filtered_users.size,
              page: page,
              per_page: per_page
            }
          }
          json_response(session, 200, response_data)

        when ['POST', '/users']
          begin
            data = JSON.parse(body)
            raise "Email and password are required" unless data['email'] && data['password']
            
            id = SecureRandom.uuid
            new_user = {
              id: id,
              email: data['email'],
              password_hash: SecureRandom.hex(16), # In real app, hash the password
              role: data['role'] && ROLES.include?(data['role'].upcase) ? data['role'].upcase : 'USER',
              is_active: data.fetch('is_active', true),
              created_at: Time.now.utc.iso8601
            }
            USERS_DB[id] = new_user
            json_response(session, 201, new_user)
          rescue JSON::ParserError, RuntimeError => e
            error_response(session, 400, "Bad Request: #{e.message}")
          end

        when ['GET', %r{/users/(.+)}]
          user_id = path.match(%r{/users/(.+)})[1]
          user = USERS_DB[user_id]
          user ? json_response(session, 200, user) : error_response(session, 404, "Not Found")

        when ['PUT', %r{/users/(.+)}], ['PATCH', %r{/users/(.+)}]
          user_id = path.match(%r{/users/(.+)})[1]
          user = USERS_DB[user_id]
          
          unless user
            error_response(session, 404, "Not Found")
            next
          end

          begin
            data = JSON.parse(body)
            user[:email] = data['email'] if data['email']
            user[:role] = data['role'].upcase if data['role'] && ROLES.include?(data['role'].upcase)
            user[:is_active] = data['is_active'] unless data['is_active'].nil?
            USERS_DB[user_id] = user
            json_response(session, 200, user)
          rescue JSON::ParserError => e
            error_response(session, 400, "Bad Request: #{e.message}")
          end

        when ['DELETE', %r{/users/(.+)}]
          user_id = path.match(%r{/users/(.+)})[1]
          if USERS_DB.delete(user_id)
            no_content_response(session)
          else
            error_response(session, 404, "Not Found")
          end

        else
          error_response(session, 404, "Not Found")
        end
      rescue => e
        puts "Error processing request: #{e.message}\n#{e.backtrace.join("\n")}"
        error_response(session, 500, "Internal Server Error")
      ensure
        session.close
      end
    end
  end
end

# --- Start ---
seed_data
run_server(8080)