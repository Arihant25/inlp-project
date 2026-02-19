# Variation 2: Classic Object-Oriented Style
# A central `FileProcessor` class encapsulates all related logic.
# This approach promotes better organization and state management than a purely procedural one.

require 'securerandom'
require 'csv'
require 'tempfile'
require 'stringio'
require 'time'

# --- Domain Model & Mock Data Store ---
module Domain
  User = Struct.new(:id, :email, :password_hash, :role, :is_active, :created_at)
  Post = Struct.new(:id, :user_id, :title, :content, :status)

  class Database
    attr_accessor :users, :posts, :files
    def initialize
      @users = []
      @posts = []
      @files = {} # path -> content
    end
    def self.instance
      @instance ||= new
    end
  end
end

# --- Main File Processor Class ---
class FileProcessor
  attr_reader :uploaded_files, :form_fields

  def initialize
    @uploaded_files = {}
    @form_fields = {}
    @db = Domain::Database.instance
  end

  # Public method to handle an entire upload request
  def handle_upload(request_io, boundary)
    parse_request(request_io, boundary)
    process_uploads
  end

  # Public method to handle a download request
  def stream_download(file_key, output_io)
    file_content = @db.files[file_key]
    if file_content
      write_download_headers(output_io, file_key, file_content.bytesize)
      stream_content(output_io, file_content)
    else
      write_not_found(output_io)
    end
  end

  private

  # --- Upload Handling Logic ---

  def parse_request(request_io, boundary)
    raw_body = request_io.read
    parts = raw_body.split("--#{boundary}")

    parts.each do |part|
      next if part.strip.empty? || part.strip == "--"
      process_part(part)
    end
  end

  def process_part(part)
    header_section, content_section = part.split("\r\n\r\n", 2)
    return unless content_section

    headers = parse_headers(header_section)
    disposition = headers['content-disposition']
    return unless disposition && disposition =~ /name="([^"]+)"/
    
    field_name = $1
    if disposition =~ /filename="([^"]+)"/
      filename = $1
      tempfile = create_tempfile(filename, content_section)
      @uploaded_files[field_name] = { filename: filename, tempfile: tempfile, content_type: headers['content-type'] }
    else
      @form_fields[field_name] = content_section.strip
    end
  end

  def parse_headers(header_section)
    header_section.strip.split("\r\n").each_with_object({}) do |line, hash|
      key, value = line.split(":", 2)
      hash[key.strip.downcase] = value.strip
    end
  end
  
  def create_tempfile(filename, content)
    tempfile = Tempfile.new(filename)
    tempfile.binmode
    # Multipart content has a trailing CRLF before the next boundary
    tempfile.write(content[0..-3])
    tempfile.rewind
    tempfile
  end

  def process_uploads
    results = {}
    @uploaded_files.each do |field_name, file_data|
      case file_data[:content_type]
      when 'text/csv'
        results[:csv_import] = import_users(file_data[:tempfile])
      when 'image/jpeg', 'image/png'
        # In a real app, we'd get the post_id from form_fields
        mock_post_id = SecureRandom.uuid
        results[:image_processing] = process_image(file_data[:tempfile], mock_post_id)
      end
    end
    results
  end

  def import_users(csv_file)
    imported_count = 0
    CSV.foreach(csv_file.path, headers: true) do |row|
      user = Domain::User.new(
        SecureRandom.uuid,
        row['email'],
        SecureRandom.hex(16),
        (row['role'] || 'USER').upcase.to_sym,
        row['is_active'] == 'true',
        Time.now.utc
      )
      @db.users << user
      imported_count += 1
    end
    puts "[INFO] Imported #{imported_count} users from CSV."
    imported_count
  end

  def process_image(image_file, post_id)
    puts "[INFO] Simulating image resize for post #{post_id}."
    # This is a simulation. Real implementation requires external tools.
    storage_key = "processed/#{post_id}/#{File.basename(image_file.path)}"
    @db.files[storage_key] = image_file.read
    puts "[INFO] Image stored at key: #{storage_key}"
    storage_key
  end

  # --- Download Handling Logic ---

  def write_download_headers(io, filename, content_length)
    io.write("HTTP/1.1 200 OK\r\n")
    io.write("Content-Type: application/octet-stream\r\n")
    io.write("Content-Length: #{content_length}\r\n")
    io.write("Content-Disposition: attachment; filename=\"#{File.basename(filename)}\"\r\n")
    io.write("\r\n")
  end

  def stream_content(io, content)
    content_stream = StringIO.new(content)
    IO.copy_stream(content_stream, io)
    puts "[INFO] Finished streaming content."
  end

  def write_not_found(io)
    io.write("HTTP/1.1 404 Not Found\r\n\r\nFile Not Found")
  end
end

# --- Main Execution Block ---
if __FILE__ == $0
  puts "--- DEMO: Classic OOP File Operations ---"

  # 1. --- Simulate Upload ---
  puts "\n1. Handling upload request..."
  boundary = "boundary-1234"
  request_body_str = <<~MULTIPART
    --#{boundary}\r
    Content-Disposition: form-data; name="csv_data"; filename="new_users.csv"\r
    Content-Type: text/csv\r
    \r
    email,role,is_active\r
    charlie@example.com,USER,true\r
    diana@example.com,ADMIN,true\r
    \r
    --#{boundary}\r
    Content-Disposition: form-data; name="post_attachment"; filename="header.jpg"\r
    Content-Type: image/jpeg\r
    \r
    fake-jpeg-binary-data\r
    --#{boundary}--\r
  MULTIPART

  request_io = StringIO.new(request_body_str)
  processor = FileProcessor.new
  upload_results = processor.handle_upload(request_io, boundary)
  puts "Upload processing results: #{upload_results}"
  
  # Verify state
  db = Domain::Database.instance
  puts "Total users in DB: #{db.users.size}"
  puts "Total files in storage: #{db.files.size}"

  # 2. --- Simulate Download ---
  puts "\n2. Handling download request..."
  image_key = upload_results[:image_processing]
  response_io = StringIO.new
  processor.stream_download(image_key, response_io)

  puts "\n--- Simulated HTTP Response ---"
  puts response_io.string
  puts "-----------------------------"

  # 3. --- Temporary File Management ---
  # Tempfiles are created during parsing and are held by the `FileProcessor` instance.
  # They are cleaned up by the GC once the instance is out of scope.
  # We can also explicitly close them.
  puts "\n3. Cleaning up temporary files..."
  processor.uploaded_files.each do |_, file_data|
    file_data[:tempfile].close!
    puts "Closed and deleted tempfile: #{file_data[:tempfile].path}"
  end
end