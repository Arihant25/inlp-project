# Variation 2: Module-based & Functional Approach
# This implementation uses modules to namespace functionality and avoids heavy class
# hierarchies. Jobs are defined as Procs/Lambdas, making the system highly flexible
# and lightweight. State is managed within module instance variables.

require 'thread'
require 'securerandom'
require 'logger'
require 'time'

# --- Domain Schema ---
User = Struct.new(:id, :email, :password_hash, :role, :is_active, :created_at)
Post = Struct.new(:id, :user_id, :title, :content, :status)

# --- Task Queue System ---
module TaskQueue
  extend self

  # --- State ---
  @task_queue = Queue.new
  @job_registry = {}
  @status_tracker = {}
  @state_mutex = Mutex.new
  @workers = []
  @scheduler_thread = nil
  @is_running = false
  @logger = Logger.new($stdout)

  # --- Job Definitions ---
  def define_job(name, &block)
    @job_registry[name] = block
  end

  # --- Public API ---
  def start(worker_count: 4)
    return if @is_running
    @is_running = true
    @logger.info("Starting TaskQueue with #{worker_count} workers.")
    worker_count.times { |i| @workers << Thread.new { worker_loop(i + 1) } }
    start_scheduler
  end

  def stop
    return unless @is_running
    @logger.info("Stopping TaskQueue...")
    @is_running = false
    @scheduler_thread&.join
    @workers.size.times { @task_queue.push(nil) }
    @workers.each(&:join)
    @workers.clear
    @logger.info("TaskQueue stopped.")
  end

  def schedule(job_name, args, run_at: Time.now, retries: 0)
    job_id = SecureRandom.uuid
    task = {
      id: job_id,
      job_name: job_name,
      args: args,
      run_at: run_at.utc,
      retries: retries,
      max_retries: 5
    }
    @state_mutex.synchronize do
      @status_tracker[job_id] = { status: :enqueued, enqueued_at: Time.now.utc, job_name: job_name }
    end
    @task_queue.push(task)
    @logger.info("Scheduled job '#{job_name}' (ID: #{job_id})")
    job_id
  end

  def get_status(job_id)
    @state_mutex.synchronize { @status_tracker[job_id]&.dup }
  end

  private

  # --- Worker Logic ---
  def worker_loop(worker_id)
    @logger.info("Worker ##{worker_id} started.")
    while @is_running
      task = @task_queue.pop
      break if task.nil?

      process_task(task, worker_id)
    end
    @logger.info("Worker ##{worker_id} stopped.")
  end

  def process_task(task, worker_id)
    job_proc = @job_registry[task[:job_name]]
    unless job_proc
      update_status(task[:id], status: :failed, error: "Job '#{task[:job_name]}' not defined.")
      return
    end

    update_status(task[:id], status: :running, worker_id: worker_id, started_at: Time.now.utc)
    @logger.info("Worker ##{worker_id} processing '#{task[:job_name]}' (ID: #{task[:id]})")

    begin
      job_proc.call(task[:args])
      update_status(task[:id], status: :completed, finished_at: Time.now.utc)
      @logger.info("Worker ##{worker_id} completed '#{task[:job_name]}' (ID: #{task[:id]})")
    rescue => e
      handle_failure(task, e, worker_id)
    end
  end

  def handle_failure(task, error, worker_id)
    if task[:retries] < task[:max_retries]
      new_retries = task[:retries] + 1
      backoff = 2 ** new_retries
      @logger.warn("Worker ##{worker_id} failed '#{task[:job_name]}'. Retrying in #{backoff}s. Error: #{error.message}")
      update_status(task[:id], status: :retrying, retries: new_retries, error: error.message)
      # In this model, the worker sleeps. A more advanced version would use a separate scheduled queue.
      sleep(backoff)
      task[:retries] = new_retries
      @task_queue.push(task)
    else
      @logger.error("Worker ##{worker_id} failed '#{task[:job_name]}' after max retries.")
      update_status(task[:id], status: :failed, finished_at: Time.now.utc, error: error.message)
    end
  end

  # --- Scheduler Logic ---
  def start_scheduler
    @scheduler_thread = Thread.new do
      while @is_running
        # This is a simple periodic scheduler.
        schedule(:system_cleanup, {})
        # Sleep for the interval, accounting for loop time.
        sleep(15)
      end
    end
  end

  # --- Utility ---
  def update_status(job_id, updates)
    @state_mutex.synchronize do
      @status_tracker[job_id].merge!(updates) if @status_tracker[job_id]
    end
  end
end

# --- Job Definitions using the functional API ---

TaskQueue.define_job :send_email do |args|
  puts "--> Sending email to #{args[:recipient]} with subject '#{args[:subject]}'"
  sleep(1)
  # Simulate failure for retry
  if args[:should_fail]
    # This is a bit tricky in a functional approach without external state.
    # We'll use a simple global flag for demonstration.
    $email_fail_count ||= 0
    if $email_fail_count < 2
      $email_fail_count += 1
      raise "SMTP connection timeout"
    end
  end
  puts "--> Email to #{args[:recipient]} sent."
end

TaskQueue.define_job :process_image_pipeline do |args|
  puts "--> Starting image pipeline for post #{args[:post_id]}"
  sleep(0.5); puts "    - Resizing image..."
  sleep(0.5); puts "    - Applying watermark..."
  sleep(0.5); puts "    - Compressing..."
  sleep(0.5); puts "    - Uploading to storage..."
  puts "--> Image pipeline for post #{args[:post_id]} finished."
end

TaskQueue.define_job :system_cleanup do |args|
  puts "--> Running periodic system cleanup..."
  sleep(2)
  puts "--> System cleanup complete."
end


# --- Main Execution ---
if __FILE__ == $0
  puts "--- Variation 2: Module-based & Functional Approach ---"

  # Mock Data
  user = User.new(SecureRandom.uuid, 'test@example.com', 'hash', :USER, true, Time.now)
  post = Post.new(SecureRandom.uuid, user.id, 'My New Post', 'Content here', :PUBLISHED)

  # Start the system
  TaskQueue.start(worker_count: 2)

  # Schedule some jobs
  failing_job_id = TaskQueue.schedule(:send_email, { recipient: user.email, subject: 'Welcome!', should_fail: true })
  TaskQueue.schedule(:process_image_pipeline, { post_id: post.id })
  TaskQueue.schedule(:send_email, { recipient: 'admin@example.com', subject: 'New Post Published' })

  # Let the system run
  puts "Running jobs for 20 seconds..."
  sleep(20)

  # Check job status
  puts "\n--- Job Status Check ---"
  status = TaskQueue.get_status(failing_job_id)
  puts "Status of job #{failing_job_id}: #{status}"
  puts "------------------------\n"

  # Stop the system
  TaskQueue.stop
end