# Variation 3: Service Object Pattern
# Each distinct operation is encapsulated in its own class with a `call` method.
# This promotes Single Responsibility Principle and makes the code highly testable and reusable.

require 'securerandom'
require 'csv'
require 'tempfile'
require 'stringio'
require 'time'

# --- Mock Domain and Global State ---
# Using simple hashes for domain objects for simplicity
$DB = {
  users: [],
  posts: [],
  file_storage: {} # key -> binary_data
}

# --- Service Objects for File Operations ---

# 1. Service to parse multipart form data
class MultipartParser
  def self.call(request_io, boundary)
    new(request_io, boundary).call
  end

  def initialize(request_io, boundary)
    @request_io = request_io
    @boundary = "--#{boundary}"
    @parsed_data = { files: {}, fields: {} }
  end

  def call
    body = @request_io.read
    body.split(@boundary).each do |part|
      process_part(part)
    end
    @parsed_data
  end

  private

  def process_part(part)
    return if part.strip.empty? || part.strip == "--"
    
    header_str, content = part.split("\r\n\r\n", 2)
    return unless content

    headers = header_str.scan(/^(.*?):\s*(.*)$/).to_h
    disposition = headers['Content-Disposition']
    return unless disposition

    name = disposition[/name="([^"]+)"/, 1]
    filename = disposition[/filename="([^"]+)"/, 1]

    if filename
      tempfile = Tempfile.new(filename)
      tempfile.binmode
      tempfile.write(content.chomp("\r\n"))
      tempfile.rewind
      @parsed_data[:files][name] = { filename: filename, tempfile: tempfile, content_type: headers['Content-Type'] }
    else
      @parsed_data[:fields][name] = content.strip
    end
  end
end

# 2. Service to import users from a CSV file
class UserCsvImporter
  def self.call(csv_tempfile)
    new(csv_tempfile).call
  end

  def initialize(csv_tempfile)
    @csv_path = csv_tempfile.path
  end

  def call
    new_users = []
    CSV.foreach(@csv_path, headers: true, header_converters: :symbol) do |row|
      user = {
        id: SecureRandom.uuid,
        email: row[:email],
        password_hash: SecureRandom.hex(16),
        role: (row[:role] || 'USER').upcase.to_sym,
        is_active: row[:is_active] == 'true',
        created_at: Time.now.utc.iso8601
      }
      $DB[:users] << user
      new_users << user
    end
    puts "[Importer] Successfully imported #{new_users.count} users."
    new_users
  end
end

# 3. Service to "process" an image
class ImageProcessor
  # In a real app, this might take options like { width: 800, height: 600 }
  def self.call(image_tempfile, associated_id:)
    new(image_tempfile, associated_id).call
  end

  def initialize(image_tempfile, associated_id)
    @image_file = image_tempfile
    @id = associated_id
  end

  def call
    puts "[Processor] Simulating image processing for ID: #{@id}..."
    # Simulation: Copy to a "permanent" storage location (our mock hash)
    storage_key = "uploads/images/#{@id}-#{File.basename(@image_file.path)}"
    $DB[:file_storage][storage_key] = @image_file.read
    puts "[Processor] Stored processed image at '#{storage_key}'."
    storage_key
  end
end

# 4. Service to stream a file for download
class FileStreamer
  def self.call(file_key, output_stream)
    new(file_key, output_stream).call
  end

  def initialize(file_key, output_stream)
    @file_key = file_key
    @output = output_stream
    @chunk_size = 4096
  end

  def call
    file_data = $DB[:file_storage][@file_key]
    unless file_data
      @output.write("HTTP/1.1 404 Not Found\r\n\r\nNot Found")
      return
    end

    headers = [
      "HTTP/1.1 200 OK",
      "Content-Type: application/octet-stream",
      "Content-Length: #{file_data.bytesize}",
      "Content-Disposition: attachment; filename=\"#{File.basename(@file_key)}\""
    ].join("\r\n") + "\r\n\r\n"
    
    @output.write(headers)

    # Stream content
    content_io = StringIO.new(file_data)
    while chunk = content_io.read(@chunk_size)
      @output.write(chunk)
    end
    puts "[Streamer] Finished streaming '#{@file_key}'."
  end
end

# --- Main Execution Block ---
if __FILE__ == $0
  puts "--- DEMO: Service Object Pattern ---"

  # 1. --- Simulate an upload and parse it ---
  puts "\n1. Parsing multipart upload..."
  boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW"
  request_body = <<~BODY
    --#{boundary}\r
    Content-Disposition: form-data; name="user_import_file"; filename="users.csv"\r
    Content-Type: text/csv\r
    \r
    email,role,is_active\r
    eve@example.com,USER,true\r
    frank@example.com,ADMIN,false\r
    \r
    --#{boundary}\r
    Content-Disposition: form-data; name="post_header_image"; filename="header.png"\r
    Content-Type: image/png\r
    \r
    some-fake-png-data-here\r
    --#{boundary}--\r
  BODY
  
  mock_request = StringIO.new(request_body)
  parsed_result = MultipartParser.call(mock_request, boundary)
  puts "Parsed files: #{parsed_result[:files].keys.join(', ')}"

  # 2. --- Use services to process the parsed files ---
  puts "\n2. Processing parsed files with service objects..."
  
  # Import users from the CSV
  csv_file_data = parsed_result[:files]['user_import_file']
  UserCsvImporter.call(csv_file_data[:tempfile]) if csv_file_data
  puts "Current user count: #{$DB[:users].count}"

  # Process the image
  image_file_data = parsed_result[:files]['post_header_image']
  stored_image_key = nil
  if image_file_data
    mock_post_id = SecureRandom.uuid
    stored_image_key = ImageProcessor.call(image_file_data[:tempfile], associated_id: mock_post_id)
  end

  # 3. --- Use a service to download the processed file ---
  puts "\n3. Downloading processed file..."
  mock_response = StringIO.new
  FileStreamer.call(stored_image_key, mock_response) if stored_image_key

  puts "\n--- Simulated HTTP Response ---"
  puts mock_response.string
  puts "-----------------------------"

  # 4. --- Temporary File Management ---
  puts "\n4. Cleaning up tempfiles..."
  parsed_result[:files].each do |_, file|
    file[:tempfile].close!
    puts "Tempfile #{file[:tempfile].path} deleted."
  end
end