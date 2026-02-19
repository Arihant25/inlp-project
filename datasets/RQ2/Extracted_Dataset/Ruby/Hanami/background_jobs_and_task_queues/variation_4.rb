# frozen_string_literal: true

# --- BOILERPLATE: MOCKS & FRAMEWORK SETUP ---
# This section simulates a Hanami application environment for the snippet to be self-contained.
require "bundler/inline"

gemfile do
  source "https://rubygems.org"
  gem "hanami", "~> 2.1"
  gem "hanami-queues", "~> 0.2.0"
  gem "sidekiq", "~> 7.0"
  gem "sidekiq-cron", "~> 1.8"
  gem "dry-struct", "~> 1.6"
  gem "dry-types", "~> 1.7"
  gem "securerandom", "~> 0.3"
end

require "hanami"
require "hanami/queues"
require "sidekiq"
require "securerandom"
require "logger"
require "time"

# Mock Hanami Application
module Main
  class App < Hanami::App
    config.queues.adapter = :sidekiq
  end
end

Hanami.app = Main::App

# Mock Domain Entities
module Types
  include Dry.Types()
end

class User < Dry::Struct
  attribute :id, Types::UUID
  attribute :email, Types::String
end

class JobExecution < Dry::Struct
  attribute :id, Types::UUID
  attribute :job_class, Types::String
  attribute :status, Types::String.enum("enqueued", "running", "completed", "failed")
  attribute :context, Types::Hash
  attribute :updated_at, Types::Time
end

# Mock Repositories
class UserRepository
  def self.find(id)
    User.new(id: id, email: "user-#{id}@example.com")
  end
end

class JobExecutionRepository
  @records = {}
  def self.create(attributes)
    id = SecureRandom.uuid
    record = JobExecution.new(attributes.merge(id: id, updated_at: Time.now))
    @records[id] = record
    puts "JOB_REPO: Created JobExecution ##{id} with status '#{record.status}'"
    record
  end

  def self.update(id, attributes)
    return unless @records[id]
    @records[id] = @records[id].new(attributes.merge(updated_at: Time.now))
    puts "JOB_REPO: Updated JobExecution ##{id} to status '#{@records[id].status}'"
  end

  def self.find(id)
    @records[id]
  end
end

# Mock Services
class SystemMailer
  def self.send_welcome_email(user_email)
    puts "MAILER: Sending welcome email to #{user_email}"
  end
end

class ImageProcessingOrchestrator
  def self.run_pipeline(post_id, image_path)
    puts "IMAGE_ORCHESTRATOR: Running pipeline for Post ##{post_id}"
    puts "  -> Step 1: Resize"
    puts "  -> Step 2: Watermark"
    puts "  -> Step 3: Store"
    "s3://final-url"
  end
end

# --- IMPLEMENTATION: The "Enterprise/Over-engineered" Developer ---
# This developer values abstraction, explicit state management, and robust error handling.
# A BaseJob, a JobDispatcher, and a dedicated JobExecution entity are introduced.

module Core
  module Jobs
    # A base class for all jobs to provide common functionality
    class BaseJob < Hanami::Job
      sidekiq_options retry: 3

      def perform(job_execution_id:, **args)
        JobExecutionRepository.update(job_execution_id, status: "running")
        execute(**args)
        JobExecutionRepository.update(job_execution_id, status: "completed")
      rescue => e
        JobExecutionRepository.update(job_execution_id, status: "failed", context: { error: e.message })
        raise e # Re-raise for Sidekiq's retry mechanism
      end

      def execute(**args)
        raise NotImplementedError, "#{self.class.name} must implement the `execute` method."
      end
    end

    # A centralized dispatcher to handle job creation and status tracking
    class Dispatcher
      def self.dispatch(job_class, **args)
        job_execution = JobExecutionRepository.create(
          job_class: job_class.name,
          status: "enqueued",
          context: args
        )
        job_class.perform_async(job_execution_id: job_execution.id, **args)
        job_execution
      end
    end
  end
end

module Main
  module Jobs
    # 1. Async Email Sending
    class SendWelcomeEmailJob < Core::Jobs::BaseJob
      def execute(user_identifier:)
        user = UserRepository.find(user_identifier)
        SystemMailer.send_welcome_email(user.email)
      end
    end

    # 2. Retry Logic (handled by BaseJob and Sidekiq)
    class PublishPostJob < Core::Jobs::BaseJob
      def execute(post_record_id:)
        puts "EXECUTING: PublishPostJob for Post ##{post_record_id}"
        # Simulate a transient error
        raise "Database deadlock detected" if rand > 0.5
        puts "SUCCESS: Post ##{post_record_id} published."
      end
    end

    # 3. Image Processing Pipeline
    class ProcessPostImageJob < Core::Jobs::BaseJob
      def execute(post_id:, image_path:)
        ImageProcessingOrchestrator.run_pipeline(post_id, image_path)
      end
    end

    # 4. Periodic Task
    class CleanupJob < Core::Jobs::BaseJob
      # Configured via sidekiq-cron
      def execute
        puts "EXECUTING: Periodic Cleanup"
      end
    end
  end
end

# --- USAGE & DEMONSTRATION ---
puts "--- Variation 4: Enterprise/Over-engineered ---"
user_id = SecureRandom.uuid
post_id = SecureRandom.uuid

# Use the Dispatcher for all job enqueuing
dispatcher = Core::Jobs::Dispatcher

# 1. Dispatch email job
puts "\n1. Dispatching welcome email job..."
email_job_execution = dispatcher.dispatch(Main::Jobs::SendWelcomeEmailJob, user_identifier: user_id)
puts "  -> Track with JobExecution ID: #{email_job_execution.id}"

# 2. Dispatch a job that might fail and retry
puts "\n2. Dispatching post publishing job..."
publish_job_execution = dispatcher.dispatch(Main::Jobs::PublishPostJob, post_record_id: post_id)
puts "  -> Track with JobExecution ID: #{publish_job_execution.id}"

# 3. Dispatch image processing job
puts "\n3. Dispatching image processing job..."
image_job_execution = dispatcher.dispatch(Main::Jobs::ProcessPostImageJob, post_id: post_id, image_path: "/tmp/photo.tiff")
puts "  -> Track with JobExecution ID: #{image_job_execution.id}"

# 4. Simulate periodic task dispatch
puts "\n4. Simulating periodic cleanup dispatch..."
# In a real scenario, a cron scheduler would call this.
cleanup_job_execution = dispatcher.dispatch(Main::Jobs::CleanupJob)
puts "  -> Track with JobExecution ID: #{cleanup_job_execution.id}"

# 5. Job Status Tracking via the custom repository
puts "\n5. Checking job statuses via JobExecutionRepository..."
retrieved_status = JobExecutionRepository.find(email_job_execution.id)
puts "  -> Status for Email Job (#{email_job_execution.id}): #{retrieved_status.status}"

# Simulate running and completing the email job
puts "\nSimulating job execution..."
Main::Jobs::SendWelcomeEmailJob.new.perform(job_execution_id: email_job_execution.id, user_identifier: user_id)
retrieved_status = JobExecutionRepository.find(email_job_execution.id)
puts "  -> New Status for Email Job (#{email_job_execution.id}): #{retrieved_status.status}"