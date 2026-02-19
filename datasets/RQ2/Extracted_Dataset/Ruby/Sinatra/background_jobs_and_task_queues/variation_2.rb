<#
# VARIATION 2: The "OOP/Service Object" Style
# This approach uses Service Objects to encapsulate business logic, keeping the
# Sinatra routes thin and clean. It promotes better separation of concerns.
#
# Gemfile for this variation:
# source 'https://rubygems.org'
# gem 'sinatra'
# gem 'sidekiq'
# gem 'sidekiq-cron'
# gem 'puma'
#>
require 'sinatra'
require 'sidekiq'
require 'sidekiq-cron'
require 'securerandom'
require 'json'
require 'logger'

# --- Configuration ---
Sidekiq.configure_server do |config|
  config.redis = { url: 'redis://localhost:6379/1' } # Use a different DB
end

Sidekiq.configure_client do |config|
  config.redis = { url: 'redis://localhost:6379/1' }
end

# --- Domain Models & Mock Persistence ---
module Models
  User = Struct.new(:id, :email, :password_hash, :role, :is_active, :created_at)
  Post = Struct.new(:id, :user_id, :title, :content, :status)
end

class Database
  @data = { users: {}, posts: {} }
  def self.users; @data[:users]; end
  def self.posts; @data[:posts]; end
end

# --- Background Workers ---
module Workers
  # 1. Async Email Sending
  class NotificationWorker
    include Sidekiq::Worker
    sidekiq_options queue: 'notifications', retry: 5

    def perform(user_id, type)
      user = Database.users[user_id]
      return unless user
      puts "--> [NotificationWorker] Preparing '#{type}' email for #{user.email}."
      sleep 2 # Simulate email API call
      puts "--> [NotificationWorker] Sent '#{type}' email to #{user.email}."
    end
  end

  # 2. Image Processing Pipeline
  class AssetPipelineWorker
    include Sidekiq::Worker
    sidekiq_options queue: 'media', retry: 3, backtrace: true

    def perform(post_id, asset_url)
      post = Database.posts[post_id]
      return unless post
      puts "--> [AssetPipelineWorker] Processing asset #{asset_url} for post #{post_id}"
      # Simulate a failure that should be retried
      if rand(2) == 0
        puts "--> [AssetPipelineWorker] ERROR: Asset processor service is down. Retrying..."
        raise "AssetProcessorUnavailable"
      end
      sleep 4 # Simulate heavy work
      puts "--> [AssetPipelineWorker] Finished processing asset for post #{post_id}"
    end
  end

  # 3. Periodic Task
  class MaintenanceWorker
    include Sidekiq::Worker
    def perform(task_name)
      puts "--> [MaintenanceWorker] Performing scheduled task: #{task_name}"
      case task_name
      when 'purge_drafts'
        drafts = Database.posts.values.select { |p| p.status == 'DRAFT' }
        puts "--> [MaintenanceWorker] Found #{drafts.count} drafts to purge."
      end
      puts "--> [MaintenanceWorker] Finished task: #{task_name}"
    end
  end
end

# --- Service Objects ---
module Services
  class UserRegistration
    def initialize(email:)
      @email = email
    end

    def execute
      new_user = Models::User.new(
        SecureRandom.uuid, @email, '...hash...', 'USER', true, Time.now
      )
      Database.users[new_user.id] = new_user

      # Enqueue background job from within the service
      job_id = Workers::NotificationWorker.perform_async(new_user.id, 'user_welcome')

      { user: new_user, job_id: job_id }
    end
  end

  class PostPublisher
    def initialize(user_id:, title:, image_url:)
      @user_id = user_id
      @title = title
      @image_url = image_url
    end

    def execute
      return { error: 'User not found' } unless Database.users[@user_id]

      new_post = Models::Post.new(
        SecureRandom.uuid, @user_id, @title, 'Content...', 'PUBLISHED'
      )
      Database.posts[new_post.id] = new_post

      # Enqueue background job
      job_id = Workers::AssetPipelineWorker.perform_async(new_post.id, @image_url)

      { post: new_post, job_id: job_id }
    end
  end
end

# --- Sinatra Application ---
# To run:
# 1. Start Redis
# 2. Start Sidekiq: `bundle exec sidekiq -r ./app.rb`
# 3. Start Sinatra: `bundle exec ruby ./app.rb`
# 4. Manually enqueue a periodic job via `irb` or another script:
#    `require './app.rb'; Workers::MaintenanceWorker.perform_async('purge_drafts')`

set :port, 4568

helpers do
  def json_response(code, data)
    status code
    content_type :json
    data.to_json
  end
end

# Routes are thin, delegating all logic to service objects
post '/v2/users' do
  result = Services::UserRegistration.new(email: params[:email]).execute
  user_data = { id: result[:user].id, email: result[:user].email }
  json_response(201, { user: user_data, job_id: result[:job_id] })
end

post '/v2/posts' do
  result = Services::PostPublisher.new(
    user_id: params[:user_id],
    title: params[:title],
    image_url: params[:image_url]
  ).execute

  if result[:error]
    json_response(404, { error: result[:error] })
  else
    post_data = { id: result[:post].id, title: result[:post].title }
    json_response(201, { post: post_data, job_id: result[:job_id] })
  end
end

# 4. Job Status Tracking
# This variation also relies on the Sidekiq Web UI.
# The JID is returned, which is the key to finding the job in the UI.
get '/v2/jobs/:jid' do
  json_response(200, {
    job_id: params[:jid],
    status_info: "Please check the Sidekiq Web UI for job status."
  })
end