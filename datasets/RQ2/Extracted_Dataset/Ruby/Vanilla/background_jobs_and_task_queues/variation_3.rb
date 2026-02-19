# Variation 3: Actor-like Model
# This implementation simulates a simple actor model. Each "Actor" is a thread
# with its own message queue (mailbox). This is useful for isolating state and
# behavior, especially for different types of jobs. A central `JobRouter`
# dispatches jobs to the appropriate actor.

require 'thread'
require 'securerandom'
require 'logger'
require 'time'

# --- Domain Schema ---
User = Struct.new(:id, :email, :password_hash, :role, :is_active, :created_at)
Post = Struct.new(:id, :user_id, :title, :content, :status)

# --- Actor System Components ---

# Base module for actor-like behavior
module Actor
  def self.included(base)
    base.extend(ClassMethods)
  end

  module ClassMethods
    def spawn(*args)
      new(*args).tap(&:run)
    end
  end

  attr_reader :mailbox

  def initialize
    @mailbox = Queue.new
    @thread = nil
    @logger = Logger.new($stdout)
    @logger.formatter = proc { |severity, datetime, progname, msg| "[#{self.class.name}] #{msg}\n" }
  end

  def post(message)
    @mailbox.push(message)
  end

  def run
    @thread = Thread.new do
      loop do
        message = @mailbox.pop
        break if message == :shutdown
        handle_message(message)
      end
    end
  end

  def shutdown
    post(:shutdown)
    @thread&.join
  end

  def handle_message(message)
    raise NotImplementedError, "Actors must implement 'handle_message'"
  end
end

# Actor for sending emails
class EmailActor
  include Actor

  def handle_message(job)
    @logger.info "Processing email to #{job[:payload][:to]}"
    sleep 1 # Simulate network I/O
    
    # Retry logic is handled inside the actor
    retries = job[:retries]
    begin
      if job[:payload][:should_fail] && retries < 2
        raise "SMTP Error"
      end
      @logger.info "Email to #{job[:payload][:to]} sent successfully."
      JobRouter.update_status(job[:id], { status: :completed })
    rescue => e
      retries += 1
      if retries <= 5
        backoff = 2**retries
        @logger.warn "Email failed. Retrying in #{backoff}s. (Attempt #{retries})"
        JobRouter.update_status(job[:id], { status: :retrying, retries: retries, error: e.message })
        sleep backoff
        job[:retries] = retries
        post(job) # Post back to self for retry
      else
        @logger.error "Email failed after max retries."
        JobRouter.update_status(job[:id], { status: :failed, error: e.message })
      end
    end
  end
end

# Actor for image processing
class ImageProcessingActor
  include Actor

  def handle_message(job)
    @logger.info "Starting image pipeline for post #{job[:payload][:post_id]}"
    JobRouter.update_status(job[:id], { status: :running })
    sleep 0.5; @logger.info "  - Resizing..."
    sleep 0.5; @logger.info "  - Watermarking..."
    sleep 0.5; @logger.info "  - Uploading..."
    @logger.info "Image pipeline complete."
    JobRouter.update_status(job[:id], { status: :completed })
  end
end

# Actor for periodic system tasks
class SchedulerActor
  include Actor

  def run
    @thread = Thread.new do
      loop do
        # Instead of a mailbox, this actor's job is time-based
        JobRouter.dispatch(:system_task, { task: :cleanup })
        # Check for shutdown message without blocking
        begin
          msg = @mailbox.pop(true)
          break if msg == :shutdown
        rescue ThreadError
          # Queue is empty, continue
        end
        sleep 15
      end
    end
  end

  def handle_message(job)
    # This actor doesn't process jobs from the router, but could
    @logger.info "Running system task: #{job[:payload][:task]}"
    sleep 2
    @logger.info "System task #{job[:payload][:task]} complete."
    JobRouter.update_status(job[:id], { status: :completed })
  end
end

# Central dispatcher that routes jobs to the correct actor
module JobRouter
  @actors = {}
  @job_statuses = {}
  @lock = Mutex.new
  @logger = Logger.new($stdout)

  def self.register(job_type, actor)
    @actors[job_type] = actor
  end

  def self.dispatch(job_type, payload)
    actor = @actors[job_type]
    unless actor
      @logger.error("No actor registered for job type: #{job_type}")
      return
    end

    job_id = SecureRandom.uuid
    job = { id: job_id, type: job_type, payload: payload, retries: 0 }

    @lock.synchronize do
      @job_statuses[job_id] = { status: :dispatched, dispatched_at: Time.now.utc }
    end
    
    actor.post(job)
    @logger.info("Dispatched job #{job_id} of type '#{job_type}'")
    job_id
  end

  def self.update_status(job_id, updates)
    @lock.synchronize do
      @job_statuses[job_id]&.merge!(updates)
    end
  end

  def self.get_status(job_id)
    @lock.synchronize { @job_statuses[job_id]&.dup }
  end

  def self.shutdown_all
    @logger.info("Shutting down all actors...")
    @actors.values.each(&:shutdown)
    @logger.info("All actors shut down.")
  end
end

# --- Main Execution ---
if __FILE__ == $0
  puts "--- Variation 3: Actor-like Model ---"

  # Mock Data
  user = User.new(SecureRandom.uuid, 'test@example.com', 'hash', :USER, true, Time.now)
  post = Post.new(SecureRandom.uuid, user.id, 'My New Post', 'Content here', :PUBLISHED)

  # Spawn actors and register them with the router
  email_actor = EmailActor.spawn
  image_actor = ImageProcessingActor.spawn
  scheduler_actor = SchedulerActor.spawn
  # The scheduler actor can also handle dispatched jobs
  JobRouter.register(:system_task, scheduler_actor)
  JobRouter.register(:send_email, email_actor)
  JobRouter.register(:process_image, image_actor)
  
  # Dispatch jobs
  failing_job_id = JobRouter.dispatch(:send_email, { to: user.email, subject: 'Welcome', should_fail: true })
  JobRouter.dispatch(:process_image, { post_id: post.id })
  JobRouter.dispatch(:send_email, { to: 'admin@example.com', subject: 'New Post' })

  # Let the system run
  puts "Running actors for 20 seconds..."
  sleep(20)

  # Check job status
  puts "\n--- Job Status Check ---"
  status = JobRouter.get_status(failing_job_id)
  puts "Status of job #{failing_job_id}: #{status}"
  puts "------------------------\n"

  # Shutdown
  JobRouter.shutdown_all
end