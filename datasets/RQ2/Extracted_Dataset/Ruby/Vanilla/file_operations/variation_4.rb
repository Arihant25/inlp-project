# Variation 4: Namespaced Module-based Style
# Organizes code into nested modules, providing clear namespaces.
# This is excellent for larger codebases to prevent name collisions and group related logic.

require 'securerandom'
require 'csv'
require 'tempfile'
require 'stringio'
require 'time'

# --- Core Namespace ---
module FileService
  # --- Domain Models & In-Memory DB ---
  module Domain
    User = Struct.new(:id, :email, :password_hash, :role, :is_active, :created_at)
    Post = Struct.new(:id, :user_id, :title, :content, :status)
    
    # Singleton pattern for mock DB
    class Store
      @instance = { users: [], posts: [], files: {} }
      def self.get; @instance; end
    end
  end

  # --- Upload Handling Logic ---
  module Upload
    class Parser
      def initialize(io, boundary)
        @io = io
        @boundary = boundary
      end

      def parse
        data = { files: {}, fields: {} }
        parts = @io.read.split("--#{@boundary}")
        parts.shift # remove first empty part
        parts.pop # remove last part which is just '--'

        parts.each do |part|
          header_str, content = part.strip.split("\r\n\r\n", 2)
          next unless content
          
          name = header_str[/name="([^"]+)"/, 1]
          filename = header_str[/filename="([^"]+)"/, 1]
          
          if filename
            tf = Tempfile.new(filename)
            tf.binmode.write(content)
            tf.rewind
            data[:files][name] = { name: filename, tempfile: tf, type: header_str[/Content-Type:\s*(.*)/, 1] }
          else
            data[:fields][name] = content
          end
        end
        data
      end
    end
  end

  # --- Data Processing Logic ---
  module Process
    class CsvUserBatchImporter
      def self.run(file)
        db = Domain::Store.get
        new_users = []
        CSV.foreach(file.path, headers: true) do |r|
          user = Domain::User.new(
            SecureRandom.uuid, r['email'], SecureRandom.hex,
            r['role']&.upcase || 'USER', r['is_active'] == 'true', Time.now.utc
          )
          db[:users] << user
          new_users << user
        end
        puts "[Process::Csv] Imported #{new_users.length} users."
        new_users
      end
    end

    class ImageManipulator
      # Simulates processing. In reality, this would use an external library.
      def self.resize_and_store(file, post_id)
        puts "[Process::Image] Simulating resize for post #{post_id}."
        db = Domain::Store.get
        key = "images/#{post_id}/#{SecureRandom.uuid}.jpg"
        db[:files][key] = file.read
        puts "[Process::Image] Stored at #{key}."
        key
      end
    end
  end

  # --- Download Logic ---
  module Download
    class Streamer
      CHUNK_SIZE = 2048

      def initialize(key, output_io)
        @key = key
        @out = output_io
        @db = Domain::Store.get
      end

      def stream
        content = @db[:files][@key]
        if content.nil?
          @out.write("HTTP/1.1 404 Not Found\r\n\r\n")
          return
        end

        headers = "HTTP/1.1 200 OK\r\n" \
                  "Content-Type: image/jpeg\r\n" \
                  "Content-Length: #{content.bytesize}\r\n" \
                  "Content-Disposition: attachment; filename=\"#{File.basename(@key)}\"\r\n\r\n"
        @out.write(headers)
        
        source = StringIO.new(content)
        @out.write(source.read(CHUNK_SIZE)) until source.eof?
        puts "[Download::Streamer] Stream complete for #{@key}."
      end
    end
  end
end

# --- Main Execution Block ---
if __FILE__ == $0
  puts "--- DEMO: Namespaced Module-based Style ---"

  # 1. --- Handle Upload ---
  puts "\n1. Parsing upload..."
  boundary = "simple-boundary"
  body_str = <<~BODY
    --#{boundary}\r
    Content-Disposition: form-data; name="csv"; filename="bulk.csv"\r
    Content-Type: text/csv\r
    \r
    email,role,is_active\r
    grace@example.com,USER,true\r
    heidi@example.com,ADMIN,true\r
    \r
    --#{boundary}\r
    Content-Disposition: form-data; name="avatar"; filename="profile.jpg"\r
    Content-Type: image/jpeg\r
    \r
    <binary-jpeg-data>\r
    --#{boundary}--\r
  BODY
  
  parser = FileService::Upload::Parser.new(StringIO.new(body_str), boundary)
  upload_data = parser.parse
  puts "Parsed files: #{upload_data[:files].keys}"

  # 2. --- Process Uploaded Files ---
  puts "\n2. Processing files..."
  
  # Process CSV
  csv_file = upload_data[:files]['csv'][:tempfile]
  FileService::Process::CsvUserBatchImporter.run(csv_file)
  
  # Process Image
  image_file = upload_data[:files]['avatar'][:tempfile]
  mock_post = FileService::Domain::Post.new(SecureRandom.uuid, nil, 'Title', 'Content', 'DRAFT')
  stored_key = FileService::Process::ImageManipulator.resize_and_store(image_file, mock_post.id)

  # 3. --- Handle Download ---
  puts "\n3. Streaming download..."
  response_io = StringIO.new
  streamer = FileService::Download::Streamer.new(stored_key, response_io)
  streamer.stream

  puts "\n--- Simulated HTTP Response ---"
  puts response_io.string
  puts "-----------------------------"

  # 4. --- Tempfile Management ---
  # Tempfiles are automatically cleaned up by GC when `upload_data` goes out of scope.
  # For deterministic cleanup, we can do it manually.
  puts "\n4. Tempfile cleanup..."
  upload_data[:files].values.each do |f|
    f[:tempfile].close!
    puts "Tempfile at #{f[:tempfile].path} is now deleted."
  end
end