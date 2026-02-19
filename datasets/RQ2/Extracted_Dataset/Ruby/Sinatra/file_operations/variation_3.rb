# variation_3_app.rb
# To run:
# 1. gem install sinatra mini_magick bcrypt
# 2. Create a dummy CSV file named `users.csv`:
#    email,password,role
#    test1@example.com,pass123,USER
#    test2@example.com,pass456,
# 3. Create a dummy image file named `test.jpg`
# 4. ruby variation_3_app.rb
#
# Example curl commands:
# curl -X POST -F "csv_file=@users.csv" http://localhost:4567/users/import
# curl -X POST -F "image=@test.jpg" http://localhost:4567/posts/<post_id>/image
# curl http://localhost:4567/posts/export -o posts_export.csv
# curl http://localhost:4567/data

require 'sinatra/base'
require 'csv'
require 'mini_magick'
require 'securerandom'
require 'bcrypt'
require 'json'
require 'fileutils'
require 'tempfile'

# --- Mock Data Store (Repository Pattern) ---
class DataStore
  attr_accessor :users, :posts

  def initialize
    @users = []
    @posts = []
    seed
  end

  def find_post(id)
    @posts.find { |p| p[:id] == id }
  end

  private

  def seed
    admin_pass_hash = BCrypt::Password.create('admin123')
    admin = { id: SecureRandom.uuid, email: 'admin@example.com', password_hash: admin_pass_hash, role: 'ADMIN', is_active: true, created_at: Time.now }
    @users << admin
    @posts << { id: SecureRandom.uuid, user_id: admin[:id], title: 'First Post', content: 'Hello World!', status: 'PUBLISHED', image_path: nil }
  end
end

# --- Service Layer (Service-Oriented Design) ---

class UserImportService
  def initialize(data_store)
    @data_store = data_store
  end

  def call(tempfile)
    return { success: false, error: 'File is empty', count: 0 } unless tempfile && File.size?(tempfile.path)
    
    imported_users = []
    CSV.foreach(tempfile.path, headers: true) do |row|
      user = {
        id: SecureRandom.uuid,
        email: row.fetch('email'),
        password_hash: BCrypt::Password.create(row.fetch('password')),
        role: row.fetch('role', 'USER').upcase,
        is_active: true,
        created_at: Time.now
      }
      @data_store.users << user
      imported_users << user
    end
    { success: true, count: imported_users.count }
  rescue KeyError => e
    { success: false, error: "CSV missing required header: #{e.key}", count: 0 }
  rescue => e
    { success: false, error: e.message, count: 0 }
  end
end

class ImageProcessingService
  def initialize(upload_dir)
    @upload_dir = upload_dir
    FileUtils.mkdir_p(@upload_dir)
  end

  def call(tempfile, basename)
    # This service demonstrates explicit temporary file management for multi-step processing.
    processed_file = Tempfile.new(['processed-', '.jpg'])
    begin
      image = MiniMagick::Image.new(tempfile.path)
      image.combine_options do |c|
        c.resize "500x500"
        c.quality "85"
        c.interlace "plane"
      end
      image.write(processed_file.path)

      final_path = File.join(@upload_dir, "#{basename}-#{SecureRandom.hex(6)}.jpg")
      FileUtils.mv(processed_file.path, final_path)
      
      { success: true, path: final_path }
    rescue MiniMagick::Error => e
      { success: false, error: "Image processing failed: #{e.message}" }
    ensure
      processed_file.close
      processed_file.unlink # Explicitly delete the tempfile
    end
  end
end

class PostExportService
  def initialize(data_store)
    @data_store = data_store
  end

  def generate_csv_stream(stream_out)
    stream_out << CSV.generate_line(['ID', 'Title', 'Status', 'User Email'])
    @data_store.posts.each do |post|
      user = @data_store.users.find { |u| u[:id] == post[:user_id] }
      stream_out << CSV.generate_line([post[:id], post[:title], post[:status], user ? user[:email] : 'N/A'])
    end
  end
end

# --- Sinatra Application Layer (Thin Controller) ---

class ServiceOrientedApp < Sinatra::Base
  set :data_store, DataStore.new
  set :user_importer, UserImportService.new(settings.data_store)
  set :image_processor, ImageProcessingService.new('public/uploads/images')
  set :post_exporter, PostExportService.new(settings.data_store)

  post '/users/import' do
    content_type :json
    file_param = params[:csv_file]
    unless file_param && file_param[:tempfile]
      status 400
      return { error: 'Parameter `csv_file` is missing.' }.to_json
    end

    result = settings.user_importer.call(file_param[:tempfile])

    if result[:success]
      status 201
      { message: "Imported #{result[:count]} users." }.to_json
    else
      status 422
      { error: result[:error] }.to_json
    end
  end

  post '/posts/:id/image' do
    content_type :json
    post = settings.data_store.find_post(params[:id])
    return status 404 unless post

    file_param = params[:image]
    unless file_param && file_param[:tempfile]
      status 400
      return { error: 'Parameter `image` is missing.' }.to_json
    end

    result = settings.image_processor.call(file_param[:tempfile], post[:id])

    if result[:success]
      post[:image_path] = result[:path]
      status 200
      { message: 'Image updated.', path: result[:path] }.to_json
    else
      status 500
      { error: result[:error] }.to_json
    end
  end

  get '/posts/export' do
    content_type 'text/csv'
    attachment 'posts_export.csv'

    stream do |out|
      settings.post_exporter.generate_csv_stream(out)
    end
  end
  
  get '/data' do
    content_type :json
    { users: settings.data_store.users, posts: settings.data_store.posts }.to_json
  end

  run! if app_file == $0
end