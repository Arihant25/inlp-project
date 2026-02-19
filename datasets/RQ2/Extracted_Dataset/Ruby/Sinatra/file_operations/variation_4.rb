# variation_4_app.rb
# To run:
# 1. gem install sinatra puma mini_magick bcrypt
# 2. Create a dummy CSV file named `users.csv`:
#    email,password,role
#    test1@example.com,pass123,USER
#    test2@example.com,pass456,
# 3. Create a dummy image file named `test.jpg`
# 4. ruby variation_4_app.rb
#
# Example curl commands:
# curl -X POST -F "users_csv=@users.csv" http://localhost:4567/api/v1/users/bulk-import
# curl -X POST -F "image_attachment=@test.jpg" http://localhost:4567/api/v1/posts/<post_id>/attachment
# curl http://localhost:4567/api/v1/posts/report.csv -o report.csv
# curl http://localhost:4567/api/v1/data

require 'sinatra/base'
require 'csv'
require 'mini_magick'
require 'securerandom'
require 'bcrypt'
require 'json'
require 'fileutils'

# --- Data Models (using Structs for clarity) ---
User = Struct.new(:id, :email, :password_hash, :role, :is_active, :created_at, keyword_init: true)
Post = Struct.new(:id, :user_id, :title, :content, :status, :image_url, keyword_init: true)

# --- In-Memory Repository (Singleton-like pattern) ---
class Repository
  @users = {}
  @posts = {}

  class << self
    attr_reader :users, :posts

    def seed
      return unless @users.empty?
      admin_pass = BCrypt::Password.create('supersecret')
      admin = User.new(id: SecureRandom.uuid, email: 'admin@example.com', password_hash: admin_pass, role: 'ADMIN', is_active: true, created_at: Time.now)
      @users[admin.id] = admin

      post = Post.new(id: SecureRandom.uuid, user_id: admin.id, title: 'API First', content: 'Content is king.', status: 'PUBLISHED')
      @posts[post.id] = post
    end
  end
end

# --- API Helpers Module ---
module ApiHelpers
  def json_error(message, status_code, details = {})
    status status_code
    { error: { message: message, details: details } }.to_json
  end

  def json_success(data, status_code = 200)
    status status_code
    { data: data }.to_json
  end

  # Mock authentication: In a real app, this would check a JWT or session.
  def authenticate!
    @current_user = Repository.users.values.find { |u| u.role == 'ADMIN' }
    halt 401, json_error('Unauthorized', 401) unless @current_user
  end
end

# --- Main Application (Modern API-focused Style) ---
class ModernApiApp < Sinatra::Base
  helpers ApiHelpers

  configure do
    set :app_file, __FILE__
    set :server, :puma
    set :port, 4567
    set :upload_path, File.expand_path('uploads', settings.root)
    
    FileUtils.mkdir_p(File.join(settings.upload_path, 'images'))
    Repository.seed
  end

  before do
    content_type :json
  end

  # Endpoint 1: Bulk user creation via CSV
  post '/api/v1/users/bulk-import' do
    authenticate!
    
    uploaded_file_payload = params[:users_csv]
    unless uploaded_file_payload && uploaded_file_payload[:tempfile]
      return json_error('`users_csv` multipart form field is required.', 400)
    end

    newly_created_users = []
    begin
      # The uploaded tempfile is managed by Rack and cleaned up post-request.
      temp_file = uploaded_file_payload[:tempfile]
      
      CSV.parse(temp_file.read, headers: true, skip_blanks: true) do |row|
        raise "Missing 'email' column" unless row['email']
        raise "Missing 'password' column" unless row['password']
        
        user = User.new(
          id: SecureRandom.uuid,
          email: row['email'],
          password_hash: BCrypt::Password.create(row['password']),
          role: (row['role'] || 'USER').upcase,
          is_active: true,
          created_at: Time.now
        )
        Repository.users[user.id] = user
        newly_created_users << { id: user.id, email: user.email }
      end
    rescue => e
      return json_error('Failed to process CSV file.', 422, { reason: e.message })
    end

    json_success({ created_count: newly_created_users.size, users: newly_created_users }, 201)
  end

  # Endpoint 2: Attach a processed image to a post
  post '/api/v1/posts/:post_id/attachment' do
    authenticate!
    
    post = Repository.posts[params[:post_id]]
    return json_error('Post not found.', 404) unless post

    image_attachment = params[:image_attachment]
    unless image_attachment && image_attachment[:tempfile]
      return json_error('`image_attachment` multipart form field is required.', 400)
    end

    begin
      image = MiniMagick::Image.new(image_attachment[:tempfile].path)
      image.format('webp') do |c|
        c.resize "1200x800>"
        c.quality 80
      end
      
      image_filename = "#{post.id}.webp"
      destination_path = File.join(settings.upload_path, 'images', image_filename)
      image.write(destination_path)

      post.image_url = "/uploads/images/#{image_filename}"
      json_success({ post_id: post.id, image_url: post.image_url })
    rescue MiniMagick::Error => e
      json_error('Image processing failed.', 500, { reason: e.message })
    end
  end

  # Endpoint 3: Download a report of posts as a streamed CSV
  get '/api/v1/posts/report.csv' do
    authenticate!
    
    content_type 'text/csv'
    headers 'Content-Disposition' => "attachment; filename=\"post-report-#{Time.now.to_i}.csv\""

    stream :keep_open do |out|
      out << ['post_id', 'author_email', 'title', 'status'].to_csv
      Repository.posts.each_value do |post|
        author = Repository.users[post.user_id]
        out << [post.id, author&.email, post.title, post.status].to_csv
      end
    end
  end
  
  # A simple endpoint to view data
  get '/api/v1/data' do
    users_public = Repository.users.values.map { |u| u.to_h.except(:password_hash) }
    posts_public = Repository.posts.values.map(&:to_h)
    json_success({ users: users_public, posts: posts_public })
  end

  run! if app_file == $0
end