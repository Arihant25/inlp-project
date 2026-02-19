<#
# VARIATION 4: The "Concise & Modern Microservice" Style
# This version is structured as a compact microservice. It uses Sidekiq's Batch API
# for a more robust processing pipeline, where multiple jobs can be grouped and a
# callback can be triggered upon completion. Syntax is concise and modern.
#
# Gemfile for this variation:
# source 'https://rubygems.org'
# gem 'sinatra'
# gem 'sidekiq' # Sidekiq Pro/Enterprise needed for Batch API, but we can mock the concept
# gem 'puma'
#>
require 'sinatra'
require 'sidekiq'
require 'securerandom'
require 'json'
require 'logger'

# --- Configuration ---
$redis_conn = { url: 'redis://localhost:6379/3' }
Sidekiq.configure_server { |c| c.redis = $redis_conn }
Sidekiq.configure_client { |c| c.redis = $redis_conn }
$logger = Logger.new(STDOUT)

# --- Mock Data & Models ---
# Using simple hashes for a microservice feel
$DB = { users: {}, posts: {} }
User = ->(email) { { id: SecureRandom.uuid, email: email, role: 'USER', created_at: Time.now.utc } }
Post = ->(uid, title) { { id: SecureRandom.uuid, user_id: uid, title: title, status: 'DRAFT' } }

# --- Worker Definitions ---

# 1. Async Email Sending
class EmailDispatchWorker
  include Sidekiq::Worker
  def perform(uid, template)
    user = $DB[:users][uid]
    $logger.info "DISPATCH: Sending '#{template}' to #{user[:email]}"
    sleep 1
    $logger.info "DISPATCHED: '#{template}' to #{user[:email]}"
  end
end

# 2. Image Processing Pipeline using Batches
# NOTE: Sidekiq::Batch is a Pro/Enterprise feature. This code demonstrates the pattern.
# If not using Pro, this could be implemented with a coordinator worker.
class ImageResizeWorker
  include Sidekiq::Worker
  def perform(pid, size)
    $logger.info "RESIZE: Resizing image for post #{pid} to #{size}."
    sleep(rand(1..3))
    # Simulate a retryable failure
    raise "ImageMagick crashed" if size == '1024x1024' && rand(2) == 0
    $logger.info "RESIZED: Post #{pid} to #{size}."
  end
end

class WatermarkWorker
  include Sidekiq::Worker
  def perform(pid)
    $logger.info "WATERMARK: Applying watermark to post #{pid}."
    sleep 2
    $logger.info "WATERMARKED: Post #{pid}."
  end
end

class PipelineCompletionWorker
  include Sidekiq::Worker
  def on_success(status, options)
    pid = options['post_id']
    $DB[:posts][pid][:status] = 'PUBLISHED'
    $logger.info "PIPELINE COMPLETE: Post #{pid} is now PUBLISHED."
    # Trigger another job, e.g., notify subscribers
    EmailDispatchWorker.perform_async($DB[:posts][pid][:user_id], 'post_published')
  end

  def on_failure(status, options)
    pid = options['post_id']
    $DB[:posts][pid][:status] = 'FAILED_PROCESSING'
    $logger.error "PIPELINE FAILED: Post #{pid} processing failed."
  end
end

# 3. Periodic Task (Manual Enqueue for Simplicity)
# In a real app, use sidekiq-cron. Here we show programmatic enqueue.
class SystemHealthWorker
  include Sidekiq::Worker
  sidekiq_options queue: 'monitoring'
  def perform
    queue_stats = Sidekiq::Stats.new
    $logger.info "HEALTH CHECK: #{queue_stats.queues.to_json}"
  end
end
# To schedule: Sidekiq.set_schedule('health_check', { 'class' => 'SystemHealthWorker', 'every' => '1h' })
# (Requires sidekiq-scheduler or similar)

# --- Sinatra Microservice ---
set :port, 4570

# Endpoint to create a user
post '/u' do
  user = User.call(params[:email])
  $DB[:users][user[:id]] = user
  EmailDispatchWorker.perform_async(user[:id], 'new_user_welcome')
  [201, { id: user[:id] }.to_json]
end

# Endpoint to publish a post, triggering a batch job
post '/p' do
  uid, title = params.values_at('uid', 'title')
  halt 404, 'User not found' unless $DB[:users][uid]

  post = Post.call(uid, title)
  $DB[:posts][post[:id]] = post
  pid = post[:id]

  # This is the core of the batch pattern
  # batch = Sidekiq::Batch.new # <-- This is a Pro feature
  # batch.description = "Processing images for Post #{pid}"
  # batch.on(:success, PipelineCompletionWorker, 'post_id' => pid)
  # batch.on(:failure, PipelineCompletionWorker, 'post_id' => pid)
  # batch.jobs do
  #   # Enqueue jobs that are part of the batch
  #   ['200x200', '800x600', '1024x1024'].each do |size|
  #     ImageResizeWorker.perform_async(pid, size)
  #   end
  #   WatermarkWorker.perform_async(pid)
  # end

  # --- Mocking the batch call for open-source Sidekiq ---
  $logger.info "Simulating batch enqueue for Post #{pid}"
  ['200x200', '800x600', '1024x1024'].each do |size|
    ImageResizeWorker.perform_async(pid, size)
  end
  WatermarkWorker.perform_async(pid)
  # Manually trigger completion after a delay to simulate
  PipelineCompletionWorker.perform_in(20.seconds, 'post_id' => pid)
  # --- End Mock ---

  [202, { msg: "Post #{pid} is processing.", post: post }.to_json]
end

# 4. Job Status Tracking via Sidekiq API
get '/q-stats' do
  stats = Sidekiq::Stats.new
  workers = Sidekiq::Workers.new
  {
    processed: stats.processed,
    failed: stats.failed,
    queues: stats.queues,
    active_workers: workers.map { |process_id, thread_id, work| work }
  }.to_json
end