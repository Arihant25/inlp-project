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
require "sidekiq-cron"
require "dry-struct"
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
  attribute :role, Types::String.enum("ADMIN", "USER")
  attribute :is_active, Types::Bool
  attribute :created_at, Types::Time
end

class Post < Dry::Struct
  attribute :id, Types::UUID
  attribute :user_id, Types::UUID
  attribute :title, Types::String
  attribute :content, Types::String
  attribute :status, Types::String.enum("DRAFT", "PUBLISHED")
end

# Mock Repositories
class UserRepository
  def self.find(id)
    puts "REPO: Finding User ##{id}"
    User.new(id: id, email: "test_#{id}@example.com", role: "USER", is_active: true, created_at: Time.now)
  end

  def self.find_inactive
    puts "REPO: Finding inactive users for cleanup."
    [User.new(id: SecureRandom.uuid, email: "inactive@example.com", role: "USER", is_active: false, created_at: Time.now)]
  end

  def self.delete(id)
    puts "REPO: Deleting User ##{id}"
  end
end

class PostRepository
  def self.find(id)
    puts "REPO: Finding Post ##{id}"
    Post.new(id: id, user_id: SecureRandom.uuid, title: "A Post", content: "...", status: "DRAFT")
  end

  def self.update(id, status:)
    puts "REPO: Updating Post ##{id} to status #{status}"
    true
  end
end

# Mock Services
class UserMailer
  def self.deliver(mailer, user)
    puts "MAILER: Sending '#{mailer}' to #{user.email}"
  end
end

class ImageProcessor
  def self.resize(image_path)
    puts "IMAGE: Resizing #{image_path}"
    "#{image_path}_resized"
  end

  def self.watermark(image_path)
    puts "IMAGE: Watermarking #{image_path}"
    "#{image_path}_watermarked"
  end

  def self.store(image_path, post_id)
    puts "IMAGE: Storing #{image_path} for Post ##{post_id}"
    "s3://bucket/images/#{post_id}/final_image.jpg"
  end
end

# --- IMPLEMENTATION: The "Classic Hanami" Developer ---
# This developer uses standard Hanami::Job for everything, chaining jobs for pipelines,
# and relies on the adapter's built-in features.

module Main
  module Jobs
    # 1. Async Email Sending
    class SendWelcomeEmail < Hanami::Job
      def perform(user_id)
        user = UserRepository.find(user_id)
        UserMailer.deliver(:welcome, user)
      end
    end

    # 2. Retry Logic with Exponential Backoff
    class PublishPost < Hanami::Job
      # Use Sidekiq's built-in retry mechanism
      sidekiq_options retry: 5, backtrace: true

      def perform(post_id, attempt = 0)
        puts "Attempt ##{attempt} to publish Post ##{post_id}"
        # Simulate a transient failure (e.g., database deadlock)
        raise "Database connection error" if attempt < 2

        post = PostRepository.find(post_id)
        PostRepository.update(post.id, status: "PUBLISHED")
        puts "Successfully published Post ##{post_id}"
      end
    end

    # 3. Image Processing Pipeline (chained jobs)
    module ImageProcessing
      class Resize < Hanami::Job
        def perform(post_id, original_image_path)
          resized_path = ImageProcessor.resize(original_image_path)
          # Chain the next job
          Watermark.perform_async(post_id, resized_path)
        end
      end

      class Watermark < Hanami::Job
        def perform(post_id, resized_path)
          watermarked_path = ImageProcessor.watermark(resized_path)
          # Chain the final job
          Store.perform_async(post_id, watermarked_path)
        end
      end

      class Store < Hanami::Job
        def perform(post_id, watermarked_path)
          final_url = ImageProcessor.store(watermarked_path, post_id)
          puts "Image pipeline complete for Post ##{post_id}. Final URL: #{final_url}"
        end
      end
    end

    # 4. Periodic Tasks
    class CleanupInactiveUsers < Hanami::Job
      # This would be configured in `config/sidekiq_cron.yml` like so:
      # my_job:
      #   cron: '0 1 * * *' # Every day at 1 AM
      #   class: 'Main::Jobs::CleanupInactiveUsers'
      #   queue: 'low'
      def perform
        puts "CRON: Running daily inactive user cleanup."
        users_to_delete = UserRepository.find_inactive
        users_to_delete.each do |user|
          UserRepository.delete(user.id)
        end
      end
    end
  end
end

# --- USAGE & DEMONSTRATION ---
puts "--- Variation 1: Classic Hanami ---"
user_id = SecureRandom.uuid
post_id = SecureRandom.uuid

# Enqueue an email job
puts "\n1. Enqueuing welcome email job..."
job_id_email = Main::Jobs::SendWelcomeEmail.perform_async(user_id)
puts "  -> Job ID: #{job_id_email}"

# Enqueue a job that will fail and retry
puts "\n2. Enqueuing post publishing job (will retry)..."
job_id_publish = Main::Jobs::PublishPost.perform_async(post_id)
puts "  -> Job ID: #{job_id_publish}"

# Start the image processing pipeline
puts "\n3. Starting image processing pipeline..."
job_id_image = Main::Jobs::ImageProcessing::Resize.perform_async(post_id, "/tmp/original.jpg")
puts "  -> Initial Job ID: #{job_id_image}"

# Simulate running a periodic task
puts "\n4. Simulating periodic cleanup task..."
Main::Jobs::CleanupInactiveUsers.new.perform

# 5. Job Status Tracking (simulated)
puts "\n5. Checking job statuses (simulated)..."
class MockSidekiqStatus
  def self.[](jid)
    # In a real app, this would query Sidekiq's API.
    # We'll just return a mock status.
    statuses = {
      job_id_email => "complete",
      job_id_publish => "retry",
      job_id_image => "processing"
    }
    statuses.fetch(jid, "unknown")
  end
end

puts "  -> Email Job (#{job_id_email}): #{MockSidekiqStatus[job_id_email]}"
puts "  -> Publish Job (#{job_id_publish}): #{MockSidekiqStatus[job_id_publish]}"
puts "  -> Image Job (#{job_id_image}): #{MockSidekiqStatus[job_id_image]}"

# To run this, you would need a running Sidekiq instance.
# The output above simulates the enqueuing and logging from the jobs.