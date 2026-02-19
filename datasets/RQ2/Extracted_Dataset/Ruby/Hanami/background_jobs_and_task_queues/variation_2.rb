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
require "sidekiq/api"
require "securerandom"
require "logger"

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

class Post < Dry::Struct
  attribute :id, Types::UUID
  attribute :user_id, Types::UUID
end

# Mock Repositories
class UserRepo
  def self.find(uid)
    puts "REPO: Finding User ##{uid}"
    User.new(id: uid, email: "test_#{uid}@example.com")
  end

  def self.delete_inactive!
    puts "REPO: Deleting all inactive users."
    2 # deleted count
  end
end

class PostRepo
  def self.find(pid)
    puts "REPO: Finding Post ##{pid}"
    Post.new(id: pid, user_id: SecureRandom.uuid)
  end

  def self.set_status(pid, status)
    puts "REPO: Setting Post ##{pid} status to '#{status}'"
    true
  end
end

# Mock Services
class Notifier
  def self.send_welcome_email(user)
    puts "NOTIFIER: Sending welcome email to #{user.email}"
  end
end

class ImagePipelineService
  def self.process(image_path:, post_id:)
    puts "IMAGE_PIPELINE: Starting for Post ##{post_id}"
    puts "  -> Resizing #{image_path}"
    puts "  -> Watermarking..."
    puts "  -> Storing..."
    "s3://bucket/final.jpg"
  end
end

# --- IMPLEMENTATION: The "Service Object" Developer ---
# This developer prefers to keep jobs as thin wrappers around dedicated service objects
# or interactors, separating queuing concerns from business logic.

module Main
  # Service Objects / Interactors
  module Operations
    class SendWelcomeNotification
      def call(user_id:)
        user = UserRepo.find(user_id)
        Notifier.send_welcome_email(user)
      end
    end

    class PublishPost
      MAX_ATTEMPTS = 3

      def call(post_id:)
        post = PostRepo.find(post_id)
        # Custom retry logic can live here if needed,
        # but we'll still rely on Sidekiq's for this example.
        PostRepo.set_status(post.id, "PUBLISHED")
        puts "SERVICE: Post ##{post.id} published successfully."
      end
    end

    class CleanupUsers
      def call
        deleted_count = UserRepo.delete_inactive!
        puts "SERVICE: Cleaned up #{deleted_count} inactive users."
      end
    end
  end

  # Thin Job Wrappers
  module Jobs
    class WelcomeEmailJob < Hanami::Job
      def perform(uid)
        Operations::SendWelcomeNotification.new.call(user_id: uid)
      end
    end

    class PostPublishJob < Hanami::Job
      sidekiq_options retry: 5

      def perform(pid)
        # Simulate failure for demonstration
        @attempt ||= 0
        @attempt += 1
        raise "Service unavailable" if @attempt < 3

        Operations::PublishPost.new.call(post_id: pid)
      end
    end

    class ImageProcessingJob < Hanami::Job
      def perform(pid, image_path)
        ImagePipelineService.process(image_path: image_path, post_id: pid)
      end
    end

    class PeriodicCleanupJob < Hanami::Job
      # Configuration in sidekiq_cron.yml would point to this class.
      def perform
        Operations::CleanupUsers.new.call
      end
    end
  end
end

# --- USAGE & DEMONSTRATION ---
puts "--- Variation 2: Service Object ---"
uid = SecureRandom.uuid
pid = SecureRandom.uuid

# 1. Async email sending
puts "\n1. Enqueuing welcome email job..."
job_id_email = Main::Jobs::WelcomeEmailJob.perform_async(uid)
puts "  -> Job ID: #{job_id_email}"

# 2. Retry logic
puts "\n2. Enqueuing post publishing job..."
job_id_publish = Main::Jobs::PostPublishJob.perform_async(pid)
puts "  -> Job ID: #{job_id_publish}"

# 3. Image processing pipeline (single job, complex service)
puts "\n3. Enqueuing image processing job..."
job_id_image = Main::Jobs::ImageProcessingJob.perform_async(pid, "/tmp/photo.png")
puts "  -> Job ID: #{job_id_image}"

# 4. Periodic task
puts "\n4. Simulating periodic cleanup task..."
Main::Jobs::PeriodicCleanupJob.new.perform

# 5. Job Status Tracking
# Status tracking remains the same, by querying the queue backend.
# The service objects themselves might add more detailed logging.
puts "\n5. Checking job statuses (via Sidekiq API)..."
# In a real app, you'd use Sidekiq::Status or similar.
puts "  -> Status for #{job_id_email} can be checked via Sidekiq UI or API."