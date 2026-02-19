# Variation 4: Event-Driven/Observer Pattern
# This implementation uses a central dispatcher to which events are published.
# Listeners (jobs) subscribe to these events and are executed asynchronously in a
# background worker pool when an event they listen for is dispatched. This decouples
# the event source from the job execution.

require 'thread'
require 'securerandom'
require 'logger'
require 'time'

# --- Domain Schema ---
User = Struct.new(:id, :email, :password_hash, :role, :is_active, :created_at)
Post = Struct.new(:id, :user_id, :title, :content, :status)

# --- Event and Job System ---

module BackgroundWorker
  # A generic worker pool and job queue, decoupled from the event system.
  @queue = Queue.new
  @workers = []
  @job_statuses = {}
  @lock = Mutex.new
  @logger = Logger.new($stdout)
  @logger.level = Logger::INFO
  
  def self.start(worker_count: 4)
    @logger.info("Starting BackgroundWorker pool with #{worker_count} workers.")
    worker_count.times do |i|
      @workers << Thread.new { worker_loop(i + 1) }
    end
  end

  def self.stop
    @logger.info("Stopping BackgroundWorker pool.")
    @workers.size.times { @queue.push(nil) }
    @workers.each(&:join)
    @workers.clear
    @logger.info("BackgroundWorker pool stopped.")
  end

  def self.submit_job(job)
    @lock.synchronize do
      @job_statuses[job[:id]] = { status: :enqueued, enqueued_at: Time.now.utc, name: job[:name] }
    end
    @queue.push(job)
  end

  def self.get_status(job_id)
    @lock.synchronize { @job_statuses[job_id]&.dup }
  end

  private

  def self.worker_loop(worker_id)
    loop do
      job = @queue.pop
      break if job.nil?

      update_status(job[:id], { status: :running, worker_id: worker_id })
      @logger.info("Worker ##{worker_id} started job '#{job[:name]}' (#{job[:id]})")
      
      begin
        job[:proc].call(job[:payload])
        update_status(job[:id], { status: :completed, finished_at: Time.now.utc })
        @logger.info("Worker ##{worker_id} finished job '#{job[:name]}'")
      rescue => e
        handle_failure(job, e, worker_id)
      end
    end
  end

  def self.handle_failure(job, error, worker_id)
    job[:retries] += 1
    if job[:retries] <= job[:max_retries]
      backoff = 2 ** job[:retries]
      @logger.warn("Worker ##{worker_id} failed job '#{job[:name]}'. Retrying in #{backoff}s. Error: #{error.message}")
      update_status(job[:id], { status: :retrying, retries: job[:retries], error: error.message })
      sleep(backoff)
      @queue.push(job) # Re-enqueue for another attempt
    else
      @logger.error("Worker ##{worker_id} failed job '#{job[:name]}' after max retries.")
      update_status(job[:id], { status: :failed, error: error.message })
    end
  end

  def self.update_status(job_id, updates)
    @lock.synchronize do
      @job_statuses[job_id]&.merge!(updates)
    end
  end
end

module EventDispatcher
  # The central event bus.
  @listeners = Hash.new { |h, k| h[k] = [] }
  @lock = Mutex.new
  @logger = Logger.new($stdout)
  @logger.level = Logger::INFO

  def self.subscribe(event_name, listener_proc, listener_name)
    @lock.synchronize do
      @listeners[event_name] << { proc: listener_proc, name: listener_name }
      @logger.info("Listener '#{listener_name}' subscribed to event ':#{event_name}'")
    end
  end

  def self.publish(event_name, payload)
    @logger.info("Publishing event ':#{event_name}' with payload: #{payload.keys}")
    listeners_for_event = @lock.synchronize { @listeners[event_name].dup }
    
    listeners_for_event.each do |listener|
      job = {
        id: SecureRandom.uuid,
        name: listener[:name],
        proc: listener[:proc],
        payload: payload,
        retries: 0,
        max_retries: 5
      }
      BackgroundWorker.submit_job(job)
    end
  end
end

# --- Listeners (Jobs) ---

# Listener for sending a welcome email when a user is created
WELCOME_EMAIL_LISTENER = ->(payload) do
  user = payload[:user]
  puts "  -> Sending welcome email to #{user.email}..."
  sleep 1
  # Simulate a transient error
  if payload[:should_fail]
    $fail_count ||= 0
    if $fail_count < 2
      $fail_count += 1
      raise "Email service unavailable"
    end
  end
  puts "  -> Welcome email sent to #{user.email}."
end

# Listener for processing images when a post is published
IMAGE_PIPELINE_LISTENER = ->(payload) do
  post = payload[:post]
  puts "  -> Starting image pipeline for post '#{post.title}'..."
  sleep 2
  puts "  -> Image pipeline for post '#{post.title}' complete."
end

# Listener for sending a notification to admin when a post is published
ADMIN_NOTIFICATION_LISTENER = ->(payload) do
  post = payload[:post]
  puts "  -> Notifying admin about new post: '#{post.title}'..."
  sleep 0.5
  puts "  -> Admin notified."
end

# --- Periodic Task Scheduler ---
class PeriodicScheduler
  def initialize(interval_seconds, event_name, payload)
    @interval = interval_seconds
    @event_name = event_name
    @payload = payload
    @thread = nil
    @running = false
  end

  def start
    @running = true
    @thread = Thread.new do
      while @running
        sleep @interval
        EventDispatcher.publish(@event_name, @payload) if @running
      end
    end
  end

  def stop
    @running = false
    @thread&.join
  end
end

# --- Main Execution ---
if __FILE__ == $0
  puts "--- Variation 4: Event-Driven/Observer Pattern ---"

  # Subscribe listeners to events
  EventDispatcher.subscribe(:user_created, WELCOME_EMAIL_LISTENER, 'SendWelcomeEmail')
  EventDispatcher.subscribe(:post_published, IMAGE_PIPELINE_LISTENER, 'ProcessPostImages')
  EventDispatcher.subscribe(:post_published, ADMIN_NOTIFICATION_LISTENER, 'NotifyAdminOnPublish')
  EventDispatcher.subscribe(:system_tick, ->(p) { puts "  -> Performing system cleanup..." }, 'SystemCleanup')

  # Start the background worker pool
  BackgroundWorker.start(worker_count: 3)

  # Start a periodic task scheduler
  scheduler = PeriodicScheduler.new(15, :system_tick, { time: Time.now })
  scheduler.start

  # --- Simulate application logic that publishes events ---
  puts "\n[APP] A new user has signed up."
  new_user = User.new(SecureRandom.uuid, 'newbie@example.com', 'hash', :USER, true, Time.now)
  # Publish an event that will fail a couple of times
  EventDispatcher.publish(:user_created, { user: new_user, should_fail: true })
  
  sleep 3 # Wait a bit

  puts "\n[APP] A user has published a new post."
  new_post = Post.new(SecureRandom.uuid, new_user.id, 'Events are cool', '...', :PUBLISHED)
  # This single event will trigger two separate background jobs
  EventDispatcher.publish(:post_published, { post: new_post })

  # Let the system run
  puts "\nRunning jobs for 20 seconds..."
  sleep(20)

  # Shutdown
  puts "Shutting down the system..."
  scheduler.stop
  BackgroundWorker.stop
  puts "System shut down gracefully."
end