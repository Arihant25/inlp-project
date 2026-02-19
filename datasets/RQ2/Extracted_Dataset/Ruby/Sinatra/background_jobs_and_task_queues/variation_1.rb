<#
# VARIATION 1: The "Classic" Procedural/Functional Style
# This approach is common in smaller Sinatra apps. Logic is straightforward,
# with workers defined as simple classes and routes directly enqueuing them.
#
# Gemfile for this variation:
# source 'https://rubygems.org'
# gem 'sinatra'
# gem 'sidekiq'
# gem 'sidekiq-cron'
# gem 'puma' # or any other rack server
#>
require 'sinatra'
require 'sidekiq'
require 'sidekiq-cron'
require 'securerandom'
require 'json'
require 'logger'

# --- Configuration ---
# In a real app, this would be in config/initializers/sidekiq.rb
Sidekiq.configure_server do |config|
  config.redis = { url: 'redis://localhost:6379/0' }
  # Load cron jobs from a schedule file
  schedule_file = "config/schedule.yml"
  if File.exist?(schedule_file) && Sidekiq.server?
    Sidekiq::Cron::Job.load_from_hash YAML.load_file(schedule_file)
  end
end

Sidekiq.configure_client do |config|
  config.redis = { url: 'redis://localhost:6379/0' }
end

# --- Mock Domain Models & Database ---
User = Struct.new(:id, :email, :password_hash, :role, :is_active, :created_at)
Post = Struct.new(:id, :user_id, :title, :content, :status)

# In-memory store to simulate a database
DB = {
  users: {},
  posts: {}
}
LOGGER = Logger.new(STDOUT)

# --- Background Job Workers ---

# 1. Async Email Sending
class WelcomeEmailWorker
  include Sidekiq::Worker
  sidekiq_options queue: 'mailers', retry: 3

  def perform(user_id)
    user = DB[:users][user_id]
    if user
      LOGGER.info "--> [WelcomeEmailWorker] Sending welcome email to #{user.email}..."
      sleep 2 # Simulate network latency of an email service
      LOGGER.info "--> [WelcomeEmailWorker] Welcome email sent to #{user.email}."
    else
      LOGGER.warn "--> [WelcomeEmailWorker] User with ID #{user_id} not found."
    end
  end
end

# 2. Image Processing Pipeline & Retry Logic
class ImageProcessingWorker
  include Sidekiq::Worker
  sidekiq_options queue: 'processing', retry: 5 # Exponential backoff is built-in

  def perform(post_id, image_url)
    post = DB[:posts][post_id]
    return LOGGER.warn "--> [ImageProcessingWorker] Post #{post_id} not found." unless post

    LOGGER.info "--> [ImageProcessingWorker] Starting image processing for post '#{post.title}' from #{image_url}."

    # Simulate a transient failure (e.g., network issue, service unavailable)
    if rand(3) == 0 # 33% chance of failure
      LOGGER.error "--> [ImageProcessingWorker] Failed to download image for post #{post_id}. Retrying..."
      raise "Image download failed"
    end

    # Simulate different processing steps
    sleep 1; LOGGER.info "--> [ImageProcessingWorker] Step 1/3: Resizing image for post #{post_id}."
    sleep 1; LOGGER.info "--> [ImageProcessingWorker] Step 2/3: Applying watermark for post #{post_id}."
    sleep 1; LOGGER.info "--> [ImageProcessingWorker] Step 3/3: Generating thumbnails for post #{post_id}."
    LOGGER.info "--> [ImageProcessingWorker] Image processing complete for post #{post_id}."
  end
end

# 3. Scheduled Periodic Task
class InactiveUserCleanupWorker
  include Sidekiq::Worker
  sidekiq_options queue: 'low'

  def perform
    LOGGER.info "--> [InactiveUserCleanupWorker] Running daily cleanup job."
    inactive_users = DB[:users].values.select { |u| !u.is_active }
    LOGGER.info "--> [InactiveUserCleanupWorker] Found #{inactive_users.count} inactive users to prune."
    # In a real app, you would archive or delete them.
    sleep 5 # Simulate work
    LOGGER.info "--> [InactiveUserCleanupWorker] Daily cleanup finished."
  end
end

# --- Sinatra Application ---
# To run:
# 1. Start Redis
# 2. Start Sidekiq: `bundle exec sidekiq -r ./app.rb`
# 3. Start Sinatra: `bundle exec ruby ./app.rb`
# 4. Create a `config/schedule.yml` file with:
#    inactive_user_cleanup:
#      cron: '0 1 * * *' # Every day at 1 AM
#      class: 'InactiveUserCleanupWorker'
#      queue: 'low'

set :port, 4567
set :bind, '0.0.0.0'

# Endpoint to create a user, which triggers a welcome email
post '/users' do
  content_type :json
  email = params[:email]
  return [400, { error: 'Email is required' }.to_json] if email.nil?

  user = User.new(
    SecureRandom.uuid,
    email,
    'hashed_password_placeholder',
    'USER',
    true,
    Time.now
  )
  DB[:users][user.id] = user

  # Enqueue the async email sending job
  job_id = WelcomeEmailWorker.perform_async(user.id)
  LOGGER.info "Enqueued WelcomeEmailWorker for user #{user.id} with JID: #{job_id}"

  status 201
  { id: user.id, email: user.email, welcome_email_job_id: job_id }.to_json
end

# Endpoint to create a post, which triggers image processing
post '/posts' do
  content_type :json
  user_id = params[:user_id]
  title = params[:title]
  image_url = params[:image_url]
  user = DB[:users][user_id]

  return [404, { error: 'User not found' }.to_json] unless user
  return [400, { error: 'Title and image_url are required' }.to_json] if title.nil? || image_url.nil?

  post = Post.new(
    SecureRandom.uuid,
    user_id,
    title,
    'Some content here...',
    'PUBLISHED'
  )
  DB[:posts][post.id] = post

  # Enqueue the async image processing job
  job_id = ImageProcessingWorker.perform_async(post.id, image_url)
  LOGGER.info "Enqueued ImageProcessingWorker for post #{post.id} with JID: #{job_id}"

  status 201
  { id: post.id, title: post.title, image_processing_job_id: job_id }.to_json
end

# 4. Job Status Tracking (Basic)
# Sidekiq Pro/Enterprise or gems like sidekiq-status are needed for API-based status.
# Without them, status is primarily checked via the Sidekiq Web UI.
# This endpoint just demonstrates the concept.
get '/job_status/:jid' do
  content_type :json
  jid = params[:jid]
  # This is a conceptual implementation. A real one would query Redis.
  # For example, with Sidekiq Pro: `Sidekiq::Job.find(jid)`
  # For now, we just acknowledge the request.
  {
    job_id: jid,
    status: 'Check Sidekiq Web UI for real-time status.',
    note: 'API-based status tracking requires Sidekiq Pro or additional gems.'
  }.to_json
end