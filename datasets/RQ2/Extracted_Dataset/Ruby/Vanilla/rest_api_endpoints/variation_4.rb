require 'socket'
require 'json'
require 'securerandom'
require 'time'
require 'uri'
require 'singleton'

# --- Core Application Namespace ---
module WebAPI
  # --- Models ---
  module Models
    User = Struct.new(:id, :email, :password_hash, :role, :is_active, :created_at, keyword_init: true) do
      def serialize
        to_h.slice(:id, :email, :role, :is_active, :created_at)
      end
    end
  end

  # --- Data Store ---
  module Store
    class UserStore
      include Singleton

      attr_reader :records

      def initialize
        @records = {}
        populate_with_defaults
      end

      def find(id)
        @records[id]
      end

      def all
        @records.values
      end

      def save(user_model)
        @records[user_model.id] = user_model
      end

      def destroy(id)
        @records.delete(id)
      end

      private

      def populate_with_defaults
        puts "Seeding initial user data..."
        5.times do |i|
          id = SecureRandom.uuid
          @records[id] = Models::User.new(
            id: id,
            email: "user#{i + 1}@example.com",
            password_hash: SecureRandom.hex(16),
            role: i.even? ? 'ADMIN' : 'USER',
            is_active: i % 3 != 0,
            created_at: Time.now.utc.iso8601
          )
        end
        puts "#{@records.size} users seeded."
      end
    end
  end

  # --- HTTP Handlers ---
  module Handlers
    class Users
      def self.call(env)
        req = Request.new(env)
        
        # Regex-based routing
        if req.path =~ %r{^/users/([\w-]+)$}
          id = $1
          case req.method
          when 'GET'    then show(id)
          when 'PUT', 'PATCH' then update(id, req)
          when 'DELETE' then destroy(id)
          else not_found
          end
        elsif req.path == '/users'
          case req.method
          when 'GET'  then index(req)
          when 'POST' then create(req)
          else not_found
          end
        else
          not_found
        end
      end

      private

      def self.index(req)
        store = Store::UserStore.instance
        
        # Filtering
        users = store.all.select do |u|
          (req.params['role'] ? u.role == req.params['role'].upcase : true) &&
          (req.params['is_active'] ? u.is_active.to_s == req.params['is_active'] : true)
        end
        
        # Pagination
        page = req.params.fetch('page', '1').to_i
        per_page = req.params.fetch('per_page', '3').to_i
        offset = (page - 1) * per_page
        
        paginated = users.sort_by(&:created_at).reverse.slice(offset, per_page) || []
        
        payload = {
          data: paginated.map(&:serialize),
          meta: { total: users.size, page: page, per_page: per_page }
        }
        json_response(200, payload)
      end

      def self.show(id)
        user = Store::UserStore.instance.find(id)
        user ? json_response(200, user.serialize) : not_found
      end

      def self.create(req)
        data = req.json_body
        new_user = Models::User.new(
          id: SecureRandom.uuid,
          email: data.fetch('email'),
          password_hash: SecureRandom.hex(16),
          role: ['ADMIN', 'USER'].include?(data['role']&.upcase) ? data['role'].upcase : 'USER',
          is_active: data.fetch('is_active', true),
          created_at: Time.now.utc.iso8601
        )
        Store::UserStore.instance.save(new_user)
        json_response(201, new_user.serialize)
      rescue KeyError, JSON::ParserError => e
        json_response(400, { error: "Bad Request: #{e.message}" })
      end

      def self.update(id, req)
        user = Store::UserStore.instance.find(id)
        return not_found unless user

        data = req.json_body
        user.email = data['email'] if data.key?('email')
        user.role = data['role'].upcase if data.key?('role') && ['ADMIN', 'USER'].include?(data['role'].upcase)
        user.is_active = data['is_active'] if data.key?('is_active')
        
        Store::UserStore.instance.save(user)
        json_response(200, user.serialize)
      rescue JSON::ParserError => e
        json_response(400, { error: "Bad Request: #{e.message}" })
      end

      def self.destroy(id)
        user = Store::UserStore.instance.destroy(id)
        user ? [204, {}, ['']] : not_found
      end

      # --- Response Helpers ---
      def self.json_response(status, body)
        [status, { 'Content-Type' => 'application/json' }, [body.to_json]]
      end

      def self.not_found
        json_response(404, { error: 'Not Found' })
      end
    end
  end

  # --- Server Infrastructure ---
  class Server
    Request = Struct.new(:env) do
      def method; env['REQUEST_METHOD']; end
      def path; env['PATH_INFO']; end
      def params; env['QUERY_PARAMS']; end
      def body; env['BODY']; end
      def json_body; JSON.parse(body); end
    end

    def initialize(port)
      @tcp_server = TCPServer.new(port)
      puts "Modular server listening on port #{port}"
    end

    def start
      loop do
        Thread.start(@tcp_server.accept) do |socket|
          begin
            env = parse_request(socket)
            status, headers, body = Handlers::Users.call(env)
            send_response(socket, status, headers, body)
          rescue => e
            puts "Error: #{e.class} - #{e.message}\n#{e.backtrace.join("\n")}"
            send_response(socket, 500, {}, [{error: "Internal Server Error"}.to_json])
          ensure
            socket.close
          end
        end
      end
    end

    private

    def parse_request(socket)
      method, full_path, _ = socket.gets.split
      path, query = full_path.split('?', 2)
      params = query ? URI.decode_www_form(query).to_h : {}
      
      headers = {}
      while (line = socket.gets) && !line.strip.empty?
        key, value = line.split(':', 2)
        headers[key.strip.downcase] = value.strip
      end
      
      body = headers['content-length'] ? socket.read(headers['content-length'].to_i) : ''
      
      {
        'REQUEST_METHOD' => method,
        'PATH_INFO' => path,
        'QUERY_PARAMS' => params,
        'BODY' => body
      }
    end

    def send_response(socket, status, headers, body_parts)
      body = body_parts.join
      headers['Content-Length'] = body.bytesize.to_s
      
      socket.print "HTTP/1.1 #{status}\r\n"
      headers.each { |k, v| socket.print "#{k}: #{v}\r\n" }
      socket.print "\r\n"
      socket.print body
    end
  end
end

# --- Entrypoint ---
WebAPI::Server.new(8083).start