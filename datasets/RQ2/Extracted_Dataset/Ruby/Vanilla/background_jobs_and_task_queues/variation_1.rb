# Variation 1: Classic Object-Oriented Programming (OOP) Approach
# This implementation uses classes to model each component of the system:
# Job, Worker, QueueManager, and Scheduler. It's a traditional, well-structured
# design that emphasizes separation of concerns.

require 'thread'
require 'securerandom'
require 'logger'
require 'time'

# --- Domain Schema ---
User = Struct.new(:id, :email, :password_hash, :role, :is_active, :created_at)
Post = Struct.new(:id, :user_id, :title, :content, :status)

# --- Job System Components ---

# Base class for all background jobs
class BaseJob
  attr_reader :id, :params, :retries, :max_retries, :status

  def initialize(params = {})
    @id = SecureRandom.uuid
    @params = params
    @retries = 0
    @max_retries = 5
    @status = :pending
  end

  def execute
    @status = :running
    begin
      perform
      @status = :completed
      log(:info, "Job completed successfully.")
    rescue => e
      @status = :failed
      log(:error, "Job failed: #{e.message}. Retries left: #{@max_retries - @retries}.")
      raise # Re-raise to be caught by the worker for retry logic
    end
  end

  def perform
    raise NotImplementedError, "#{self.class} has not implemented method 'perform'"
  end

  def increment_retries
    @retries += 1
  end

  def can_retry?
    @retries < @max_retries
  end

  private

  def log(level, message)
    JobQueueManager.instance.logger.send(level, "Job<#{self.class.name}##{@id}>: #{message}")
  end
end

# --- Specific Job Implementations ---

class AsyncEmailJob < BaseJob
  def perform
    log(:info, "Sending email to #{params[:recipient]} with subject '#{params[:subject]}'")
    # Simulate network latency
    sleep(1)
    # Simulate a transient failure for retry demonstration
    if @retries < 2 && params[:should_fail]
      raise "SMTP server connection failed"
    end
    log(:info, "Email sent successfully.")
  end
end

class ImageProcessingPipelineJob < BaseJob
  def perform
    log(:info, "Starting image processing pipeline for post #{params[:post_id]}")
    sleep(0.5); log(:info, "Step 1: Resizing image to 1024x768.")
    sleep(0.5); log(:info, "Step 2: Applying watermark.")
    sleep(0.5); log(:info, "Step 3: Compressing image.")
    sleep(0.5); log(:info, "Step 4: Uploading to CDN.")
    log(:info, "Image processing pipeline finished.")
  end
end

class SystemCleanupJob < BaseJob
  def perform
    log(:info, "Running periodic system cleanup. Deleting old log files and temporary data.")
    sleep(2)
    log(:info, "System cleanup complete.")
  end
end


# Manages the queue, job statuses, and worker pool
class JobQueueManager
  include Singleton

  attr_reader :logger

  def initialize
    @queue = Queue.new
    @job_statuses = {}
    @lock = Mutex.new
    @workers = []
    @logger = Logger.new($stdout)
    @logger.level = Logger::INFO
  end

  def enqueue(job)
    @lock.synchronize do
      @job_statuses[job.id] = { status: :enqueued, class: job.class.name, enqueued_at: Time.now.utc }
    end
    @queue.push(job)
    logger.info("Enqueued Job<#{job.class.name}##{job.id}>")
  end

  def get_job_status(job_id)
    @lock.synchronize { @job_statuses[job_id]&.dup }
  end

  def start(worker_count: 4)
    worker_count.times do |i|
      worker = Worker.new(@queue, @job_statuses, @lock, i + 1)
      @workers << Thread.new { worker.run }
    end
    logger.info("#{worker_count} workers started.")
  end

  def stop
    # Signal workers to stop by pushing nil for each worker
    @workers.size.times { @queue.push(nil) }
    @workers.each(&:join)
    logger.info("All workers have been stopped.")
  end

  def update_job_status(job_id, status_update)
     @lock.synchronize do
      @job_statuses[job_id].merge!(status_update) if @job_statuses[job_id]
    end
  end
end

# A worker that pulls jobs from the queue and executes them
class Worker
  def initialize(queue, job_statuses, lock, id)
    @queue = queue
    @job_statuses = job_statuses
    @lock = lock
    @id = id
    @logger = Logger.new($stdout)
    @logger.level = Logger::INFO
  end

  def run
    @logger.info("Worker ##{@id} starting.")
    loop do
      job = @queue.pop
      break if job.nil? # Shutdown signal

      log(:info, "Picked up job #{job.class.name}##{job.id}")
      update_status(job.id, { status: :running, worker_id: @id, started_at: Time.now.utc })

      begin
        job.execute
        update_status(job.id, { status: :completed, finished_at: Time.now.utc })
      rescue => e
        if job.can_retry?
          job.increment_retries
          backoff_time = 2 ** job.retries
          log(:warn, "Job failed. Retrying in #{backoff_time} seconds.")
          update_status(job.id, { status: :retrying, retries: job.retries, error: e.message })
          sleep(backoff_time) # In a real system, this would re-enqueue to a separate retry queue
          @queue.push(job)
        else
          log(:error, "Job failed after max retries.")
          update_status(job.id, { status: :failed, finished_at: Time.now.utc, error: e.message })
        end
      end
    end
    @logger.info("Worker ##{@id} shutting down.")
  end

  private

  def log(level, message)
    @logger.send(level, "Worker ##{@id}: #{message}")
  end

  def update_status(job_id, status_update)
    @lock.synchronize do
      @job_statuses[job_id].merge!(status_update) if @job_statuses[job_id]
    end
  end
end

# Schedules periodic tasks
class Scheduler
  def initialize(queue_manager)
    @queue_manager = queue_manager
    @thread = nil
    @running = false
  end

  def start
    @running = true
    @thread = Thread.new do
      last_run_time = Time.now
      while @running
        if Time.now - last_run_time >= 15 # Run every 15 seconds
          @queue_manager.enqueue(SystemCleanupJob.new)
          last_run_time = Time.now
        end
        sleep(1)
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
  puts "--- Variation 1: Classic OOP Approach ---"

  # Mock Data
  user = User.new(SecureRandom.uuid, 'test@example.com', 'hash', :USER, true, Time.now)
  post = Post.new(SecureRandom.uuid, user.id, 'My New Post', 'Content here', :PUBLISHED)

  # Initialize and start the system
  manager = JobQueueManager.instance
  manager.start(worker_count: 2)

  scheduler = Scheduler.new(manager)
  scheduler.start

  # Enqueue some jobs
  email_job_1 = AsyncEmailJob.new({ recipient: user.email, subject: 'Welcome!', should_fail: true })
  manager.enqueue(email_job_1)
  manager.enqueue(ImageProcessingPipelineJob.new({ post_id: post.id }))
  manager.enqueue(AsyncEmailJob.new({ recipient: 'admin@example.com', subject: 'New Post Published' }))

  # Let the system run for a while
  puts "Running jobs for 20 seconds..."
  sleep(20)

  # Check job status
  puts "\n--- Job Status Check ---"
  status = manager.get_job_status(email_job_1.id)
  puts "Status of job #{email_job_1.id}: #{status}"
  puts "------------------------\n"

  # Shutdown
  puts "Shutting down the system..."
  scheduler.stop
  manager.stop
  puts "System shut down gracefully."
end