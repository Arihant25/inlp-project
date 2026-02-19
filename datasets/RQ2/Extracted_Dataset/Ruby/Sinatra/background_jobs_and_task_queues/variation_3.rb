<#
# VARIATION 3: The "Modular Sinatra App" Style with Advanced Status Tracking
# This approach uses `class MyApp < Sinatra::Base` for better encapsulation,
# making it suitable for larger applications. It introduces `sidekiq-status`
# for robust, API-accessible job status tracking and progress reporting.
#
# Gemfile for this variation:
# source 'https://rubygems.org'
# gem 'sinatra'
# gem 'sidekiq'
# gem 'sidekiq-cron'
# gem 'sidekiq-status'
# gem 'puma'
#>
require 'sinatra/base'
require 'sidekiq'
require 'sidekiq-cron'
require 'sidekiq-status'
require 'securerandom'
require 'json'

# --- Sidekiq Configuration with sidekiq-status ---
Sidekiq.configure_client do |config|
  config.redis = { url: 'redis://localhost:6379/2' }
  Sidekiq::Status.configure_client_middleware config
end

Sidekiq.configure_server do |config|
  config.redis = { url: 'redis://localhost:6379/2' }
  Sidekiq::Status.configure_server_middleware config
  Sidekiq::Status.configure_client_middleware config
end

# --- Mock Data Layer ---
class DataStore
  def self.instance
    @instance ||= new
  end

  attr_accessor :users, :posts

  def initialize
    @users = {}
    @posts = {}
  end

  def find_user(id)
    @users[id]
  end

  def find_post(id)
    @posts[id]
  end
end

# --- Domain Models ---
User = Struct.new(:id, :email, :password_hash, :role, :is_active, :created_at)
Post = Struct.new(:id, :user_id, :title, :content, :status)

# --- Background Workers with Status Tracking ---

# 1. Async Email Sending
class UserMailerJob
  include Sidekiq::Worker
  include Sidekiq::Status::Worker # Enable status tracking
  sidekiq_options retry: 1

  def perform(user_id)
    user = DataStore.instance.find_user(user_id)
    return unless user
    store user_email: user.email # Store custom data in the status hash
    puts "--> [UserMailerJob] Sending welcome email to #{user.email}"
    sleep 3
    puts "--> [UserMailerJob] Email sent."
  end
end

# 2. Image Processing Pipeline with Progress Reporting
class PostProcessingPipelineJob
  include Sidekiq::Worker
  include Sidekiq::Status::Worker # Enable status tracking
  sidekiq_options queue: 'critical'

  def perform(post_id)
    post = DataStore.instance.find_post(post_id)
    return unless post
    total_steps = 4

    at(1, total_steps, "Resizing image...")
    puts "--> [PostProcessingPipelineJob] Resizing image for post #{post_id}"
    sleep 2

    at(2, total_steps, "Applying watermark...")
    puts "--> [PostProcessingPipelineJob] Applying watermark for post #{post_id}"
    sleep 2

    at(3, total_steps, "Generating thumbnails (small, medium, large)...")
    puts "--> [PostProcessingPipelineJob] Generating thumbnails for post #{post_id}"
    sleep 3

    at(4, total_steps, "Deploying to CDN...")
    puts "--> [PostProcessingPipelineJob] Deploying to CDN for post #{post_id}"
    sleep 2

    store final_url: "https://cdn.example.com/#{post_id}/final.jpg"
  end
end

# 3. Periodic Task (using sidekiq-cron)
# Create a `config/schedule.yml` file with:
#   audit_log_rotation:
#     cron: '0 0 * * *' # Daily at midnight
#     class: 'SystemAuditJob'
#     args: ['rotate_logs']
class SystemAuditJob
  include Sidekiq::Worker
  def perform(task)
    puts "--> [SystemAuditJob] Performing scheduled task: #{task}"
  end
end


# --- Modular Sinatra Application ---
class BackgroundJobsApp < Sinatra::Base
  configure do
    set :port, 4569
    set :bind, '0.0.0.0'
    set :db, DataStore.instance
  end

  helpers do
    def find_user_or_halt(user_id)
      user = settings.db.find_user(user_id)
      halt 404, { error: "User #{user_id} not found" }.to_json unless user
      user
    end

    def json(data, status_code = 200)
      content_type :json
      status status_code
      data.to_json
    end
  end

  post '/api/users' do
    user = User.new(SecureRandom.uuid, params[:email], '...', 'USER', true, Time.now)
    settings.db.users[user.id] = user
    job_id = UserMailerJob.perform_async(user.id)
    json({ user_id: user.id, job_id: job_id }, 201)
  end

  post '/api/users/:user_id/posts' do
    user = find_user_or_halt(params[:user_id])
    post = Post.new(SecureRandom.uuid, user.id, params[:title], '...', 'PUBLISHED')
    settings.db.posts[post.id] = post
    job_id = PostProcessingPipelineJob.perform_async(post.id)
    json({ post_id: post.id, job_id: job_id }, 201)
  end

  # 4. Advanced Job Status Tracking Endpoint
  get '/api/jobs/status/:jid' do
    jid = params[:jid]
    status_data = Sidekiq::Status.get_all(jid)
    return json({ error: "Job #{jid} not found" }, 404) if status_data.empty?
    json(status_data)
  end

  # This is necessary to run the app directly
  run! if app_file == $0
end