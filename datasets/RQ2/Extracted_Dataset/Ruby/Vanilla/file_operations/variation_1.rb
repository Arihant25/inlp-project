# Variation 1: Procedural/Functional Style
# A collection of methods within a module to handle file operations.
# This style is straightforward and suitable for scripts or smaller applications.

require 'securerandom'
require 'csv'
require 'tempfile'
require 'stringio'
require 'time'

# --- Domain Schema and Mock Database ---

# Using Structs to represent our domain models
User = Struct.new(:id, :email, :password_hash, :role, :is_active, :created_at)
Post = Struct.new(:id, :user_id, :title, :content, :status)

# In-memory store to act as a database
$users = []
$posts = []
$file_storage = {} # Mock file storage (path -> content)

# --- Module for File Operations ---

module FileUtilsHandler
  # Parses a multipart/form-data byte stream.
  #
  # @param request_io [IO] The IO stream of the request body.
  # @param boundary [String] The boundary string from the Content-Type header.
  # @return [Hash] A hash of form fields, where file fields are represented by Tempfile objects.
  def self.parse_multipart_form_data(request_io, boundary)
    parts = request_io.read.split("--#{boundary}")
    form_data = {}

    parts.each do |part|
      next if part.strip.empty? || part.strip == "--"

      headers_str, content = part.split("\r\n\r\n", 2)
      next unless content

      headers = {}
      headers_str.strip.split("\r\n").each do |line|
        key, value = line.split(":", 2)
        headers[key.strip.downcase] = value.strip
      end

      disposition = headers['content-disposition']
      next unless disposition

      if disposition =~ /name="([^"]+)"/
        name = $1
        if disposition =~ /filename="([^"]+)"/
          filename = $1
          tempfile = Tempfile.new(filename)
          tempfile.binmode
          # The content has a trailing \r\n that needs to be removed
          tempfile.write(content[0..-3]) 
          tempfile.rewind
          form_data[name] = { filename: filename, tempfile: tempfile, content_type: headers['content-type'] }
        else
          form_data[name] = content.strip
        end
      end
    end
    form_data
  end

  # Processes a CSV file to bulk-import users.
  #
  # @param csv_tempfile [Tempfile] The temporary file object containing user data.
  # @return [Array<User>] An array of newly created User objects.
  def self.import_users_from_csv(csv_tempfile)
    new_users = []
    CSV.foreach(csv_tempfile.path, headers: true, header_converters: :symbol) do |row|
      user = User.new(
        SecureRandom.uuid,
        row[:email],
        SecureRandom.hex(16), # mock password hash
        (row[:role] || 'USER').upcase,
        row[:is_active] == 'true',
        Time.now.utc.iso8601
      )
      $users << user
      new_users << user
    end
    new_users
  end

  # Simulates resizing an image.
  # In a real application, this would use an external library (e.g., RMagick)
  # or shell out to a command-line tool (e.g., ImageMagick's `convert`).
  #
  # @param source_tempfile [Tempfile] The uploaded image file.
  # @param post_id [String] The ID of the post to associate the image with.
  # @return [String] The path/key of the "processed" stored image.
  def self.process_post_image(source_tempfile, post_id)
    puts "[INFO] Simulating image processing for Post #{post_id}..."
    # Simulate processing by just copying the content to our mock storage.
    storage_key = "images/#{post_id}/processed_image.jpg"
    $file_storage[storage_key] = source_tempfile.read
    puts "[INFO] Image processed and stored at '#{storage_key}'."
    storage_key
  end

  # Streams a file to a client.
  #
  # @param file_key [String] The key of the file in our mock storage.
  # @param output_io [IO] The IO stream to write the response to.
  def self.stream_file_download(file_key, output_io)
    file_content = $file_storage[file_key]
    unless file_content
      output_io.write("HTTP/1.1 404 Not Found\r\n")
      output_io.write("Content-Type: text/plain\r\n")
      output_io.write("\r\n")
      output_io.write("File not found.")
      return
    end

    # Write HTTP headers for download
    output_io.write("HTTP/1.1 200 OK\r\n")
    output_io.write("Content-Type: application/octet-stream\r\n")
    output_io.write("Content-Length: #{file_content.bytesize}\r\n")
    output_io.write("Content-Disposition: attachment; filename=\"#{File.basename(file_key)}\"\r\n")
    output_io.write("\r\n")

    # Stream the file in chunks
    chunk_size = 1024
    content_io = StringIO.new(file_content)
    while (chunk = content_io.read(chunk_size))
      output_io.write(chunk)
    end
    puts "[INFO] Finished streaming file '#{file_key}'."
  end
end

# --- Main Execution Block ---

if __FILE__ == $0
  puts "--- DEMO: Procedural File Operations ---"

  # 1. --- Simulate a file upload request ---
  puts "\n1. Handling file uploads..."
  boundary = "AaB03x"
  csv_content = "email,role,is_active\nalice@example.com,ADMIN,true\nbob@example.com,USER,false"
  image_content = "dummy-image-bytes" # Fake image data

  request_body = <<~MULTIPART_BODY
    --#{boundary}\r
    Content-Disposition: form-data; name="user_csv"; filename="users.csv"\r
    Content-Type: text/csv\r
    \r
    #{csv_content}\r
    --#{boundary}\r
    Content-Disposition: form-data; name="post_image"; filename="photo.jpg"\r
    Content-Type: image/jpeg\r
    \r
    #{image_content}\r
    --#{boundary}--\r
  MULTIPART_BODY

  mock_request_io = StringIO.new(request_body)
  parsed_data = FileUtilsHandler.parse_multipart_form_data(mock_request_io, boundary)
  puts "Parsed form data fields: #{parsed_data.keys.join(', ')}"

  # 2. --- Process the uploaded CSV ---
  puts "\n2. Processing uploaded CSV..."
  csv_file = parsed_data['user_csv'][:tempfile]
  newly_imported_users = FileUtilsHandler.import_users_from_csv(csv_file)
  puts "Imported #{newly_imported_users.size} users. Total users: #{$users.size}."
  puts "First new user: #{$users.first.email}"

  # 3. --- Process the uploaded image ---
  puts "\n3. Processing uploaded image..."
  image_file = parsed_data['post_image'][:tempfile]
  mock_post = Post.new(SecureRandom.uuid, $users.first.id, "My Post", "Content", "DRAFT")
  $posts << mock_post
  stored_image_path = FileUtilsHandler.process_post_image(image_file, mock_post.id)

  # 4. --- Simulate a file download request ---
  puts "\n4. Handling file download..."
  mock_response_io = StringIO.new
  FileUtilsHandler.stream_file_download(stored_image_path, mock_response_io)
  
  puts "\n--- Simulated HTTP Response ---"
  puts mock_response_io.string
  puts "-----------------------------"

  # 5. --- Temporary File Management ---
  # The Tempfile objects created during parsing are automatically cleaned up
  # by the garbage collector when they are no longer referenced.
  puts "\n5. Temp files are automatically managed and will be cleaned up."
  puts "CSV Tempfile path: #{csv_file.path}"
  puts "Image Tempfile path: #{image_file.path}"
  csv_file.close! # Explicitly delete
  image_file.close! # Explicitly delete
  puts "Tempfiles closed and deleted."
end