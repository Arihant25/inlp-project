# variation_1_app.rb
# To run:
# 1. gem install sinatra mini_magick bcrypt
# 2. Create a dummy CSV file named `users.csv`:
#    email,password,role
#    test1@example.com,pass123,USER
#    test2@example.com,pass456,
# 3. Create a dummy image file named `test.jpg`
# 4. ruby variation_1_app.rb
#
# Example curl commands:
# curl -X POST -F "file=@users.csv" http://localhost:4567/users/import
# curl -X POST -F "image_file=@test.jpg" http://localhost:4567/posts/<post_id>/image
# curl http://localhost:4567/posts/export -o posts_export.csv
# curl http://localhost:4567/data

require 'sinatra'
require 'csv'
require 'mini_magick'
require 'securerandom'
require 'bcrypt'
require 'json'
require 'fileutils'

# --- Configuration and Mock Data (Functional/Global Style) ---
set :port, 4567
set :public_folder, 'public'
# Ensure the directory for uploads exists
FileUtils.mkdir_p('public/uploads/images')

# In-memory database using global variables
$users = []
$posts = []

# Seed data
admin_pass_hash = BCrypt::Password.create('admin123')
$users << { id: SecureRandom.uuid, email: 'admin@example.com', password_hash: admin_pass_hash, role: 'ADMIN', is_active: true, created_at: Time.now }
user_id = $users.first[:id]
$posts << { id: SecureRandom.uuid, user_id: user_id, title: 'First Post', content: 'Hello World!', status: 'PUBLISHED', image_path: nil }

# --- Helpers ---
helpers do
  def json_response(status_code, data)
    status status_code
    content_type :json
    data.to_json
  end

  def find_post(id)
    $posts.find { |p| p[:id] == id }
  end
end

# --- Routes ---

# 1. File Upload & CSV Parsing: Import users from a CSV file
post '/users/import' do
  unless params[:file] && params[:file][:tempfile]
    return json_response(400, { error: 'No file uploaded' })
  end

  # Rack/Sinatra provides the uploaded file as a Tempfile object.
  # It is automatically cleaned up after the request cycle.
  tempfile = params[:file][:tempfile]
  imported_count = 0

  begin
    CSV.foreach(tempfile.path, headers: true) do |row|
      password_hash = BCrypt::Password.create(row['password'])
      new_user = {
        id: SecureRandom.uuid,
        email: row['email'],
        password_hash: password_hash,
        role: row['role']&.upcase || 'USER',
        is_active: true,
        created_at: Time.now
      }
      $users << new_user
      imported_count += 1
    end
    json_response(201, { message: "Successfully imported #{imported_count} users." })
  rescue CSV::MalformedCSVError => e
    json_response(422, { error: "Failed to parse CSV: #{e.message}" })
  end
end

# 2. Image Resizing/Processing: Upload and resize an image for a post
post '/posts/:id/image' do
  post = find_post(params[:id])
  return json_response(404, { error: 'Post not found' }) unless post

  unless params[:image_file] && params[:image_file][:tempfile]
    return json_response(400, { error: 'No image file uploaded' })
  end

  tempfile = params[:image_file][:tempfile]
  filename = params[:image_file][:filename]
  upload_path = File.join(settings.public_folder, 'uploads', 'images')
  
  processed_image_path = File.join(upload_path, "#{post[:id]}-#{SecureRandom.hex(4)}-#{filename}")

  begin
    image = MiniMagick::Image.new(tempfile.path)
    image.resize "800x600>" # Resize if larger, maintain aspect ratio
    image.write processed_image_path

    post[:image_path] = processed_image_path
    json_response(200, { message: 'Image uploaded and resized successfully', path: processed_image_path })
  rescue MiniMagick::Error => e
    json_response(500, { error: "Image processing failed: #{e.message}" })
  end
end

# 3. File Download with Streaming: Stream a CSV export of all posts
get '/posts/export' do
  content_type 'text/csv'
  attachment 'posts.csv'

  stream do |out|
    # Header
    out << CSV.generate_line(['id', 'user_id', 'title', 'status'])
    
    # Body
    $posts.each do |post|
      out << CSV.generate_line([post[:id], post[:user_id], post[:title], post[:status]])
    end
  end
end

# A simple endpoint to view current in-memory data
get '/data' do
  json_response(200, { users: $users, posts: $posts })
end