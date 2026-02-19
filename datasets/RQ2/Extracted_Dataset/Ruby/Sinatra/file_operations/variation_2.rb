# variation_2_app.rb
# To run:
# 1. gem install sinatra mini_magick bcrypt
# 2. Create a dummy CSV file named `users.csv`:
#    email,password,role
#    test1@example.com,pass123,USER
#    test2@example.com,pass456,
# 3. Create a dummy image file named `test.jpg`
# 4. ruby variation_2_app.rb
#
# Example curl commands:
# curl -X POST -F "user_data=@users.csv" http://localhost:4567/users/import
# curl -X POST -F "post_image=@test.jpg" http://localhost:4567/posts/<post_id>/image
# curl http://localhost:4567/posts/export -o posts_export.csv
# curl http://localhost:4567/data

require 'sinatra/base'
require 'csv'
require 'mini_magick'
require 'securerandom'
require 'bcrypt'
require 'json'
require 'fileutils'

class FileOpsApp < Sinatra::Base
  # --- Configuration (OOP/Modular Style) ---
  configure do
    set :public_folder, 'public'
    FileUtils.mkdir_p(File.join(settings.public_folder, 'uploads', 'images'))
  end

  # --- Mock Database ---
  before do
    @users ||= begin
      admin_pass_hash = BCrypt::Password.create('admin123')
      [{ id: SecureRandom.uuid, email: 'admin@example.com', password_hash: admin_pass_hash, role: 'ADMIN', is_active: true, created_at: Time.now }]
    end
    @posts ||= begin
      user_id = @users.first[:id]
      [{ id: SecureRandom.uuid, user_id: user_id, title: 'First Post', content: 'Hello World!', status: 'PUBLISHED', image_path: nil }]
    end
  end

  # --- Helpers ---
  helpers do
    def find_post_by_id(id)
      @posts.find { |p| p[:id] == id }
    end

    def halt_with_json(status_code, body)
      content_type :json
      halt status_code, body.to_json
    end
  end

  # --- Routes ---

  # 1. CSV User Import
  post '/users/import' do
    upload = params[:user_data]
    unless upload && upload[:tempfile]
      halt_with_json(400, { error: 'File `user_data` is required.' })
    end

    imported_users = []
    begin
      # The tempfile from params is automatically managed by Rack.
      CSV.foreach(upload[:tempfile], headers: true, header_converters: :symbol) do |row|
        new_user = {
          id: SecureRandom.uuid,
          email: row[:email],
          password_hash: BCrypt::Password.create(row[:password]),
          role: row[:role]&.upcase || 'USER',
          is_active: true,
          created_at: Time.now
        }
        @users << new_user
        imported_users << { id: new_user[:id], email: new_user[:email] }
      end
    rescue => e
      halt_with_json(422, { error: 'Invalid CSV format.', details: e.message })
    end

    halt_with_json(201, { message: "Imported #{imported_users.count} users.", users: imported_users })
  end

  # 2. Post Image Upload and Processing
  post '/posts/:id/image' do
    post_record = find_post_by_id(params[:id])
    halt_with_json(404, { error: "Post with id #{params[:id]} not found." }) unless post_record

    upload = params[:post_image]
    unless upload && upload[:tempfile]
      halt_with_json(400, { error: 'File `post_image` is required.' })
    end

    begin
      image = MiniMagick::Image.open(upload[:tempfile].path)
      image.strip # Remove EXIF data
      image.resize '1024x768^' # Fill dimensions, then crop
      image.gravity 'center'
      image.extent '1024x768'
      
      output_dir = File.join(settings.public_folder, 'uploads', 'images')
      output_filename = "#{post_record[:id]}_#{Time.now.to_i}.jpg"
      output_path = File.join(output_dir, output_filename)
      
      image.format 'jpg'
      image.write(output_path)

      post_record[:image_path] = "/uploads/images/#{output_filename}"
      halt_with_json(200, { message: 'Image processed successfully.', path: post_record[:image_path] })
    rescue MiniMagick::Invalid => e
      halt_with_json(415, { error: 'Unsupported image type.', details: e.message })
    end
  end

  # 3. Streamed CSV Post Export
  get '/posts/export' do
    headers(
      'Content-Type' => 'text/csv',
      'Content-Disposition' => "attachment; filename=\"posts_#{Time.now.strftime('%Y%m%d')}.csv\""
    )

    stream do |out|
      out << %w[id user_id title status].to_csv
      @posts.each do |post|
        out << [post[:id], post[:user_id], post[:title], post[:status]].to_csv
      end
    end
  end
  
  # A simple endpoint to view data
  get '/data' do
    content_type :json
    { users: @users, posts: @posts }.to_json
  end

  # To run this app with `ruby variation_2_app.rb`
  run! if app_file == $0
end