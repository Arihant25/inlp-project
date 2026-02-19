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

# Mock Hanami Application
module Main
  class App < Hanami::App
    config.queues.adapter = :sidekiq
  end
end

Hanami.app = Main::App

# Mock Data Layer (functional style)
module Data
  module Users
    def self.get(id)
      puts "DB: Get User #{id}"
      { id: id, email: "user-#{id}@example.com", active: true }
    end

    def self.find_and_delete_inactive
      puts "DB: Deleting inactive users."
      [{ id: SecureRandom.uuid }]
    end
  end

  module Posts
    def self.update_status(id, status)
      puts "DB: Update Post #{id} -> #{status}"
      { id: id, status: status }
    end
  end
end

# Mock Services (functional style)
module Services
  module Mailer
    def self.send(template, recipient_email)
      puts "MAIL: Sending '#{template}' to #{recipient_email}"
    end
  end

  module ImgProc
    RESIZE = ->(data) { puts "IMG: Resizing #{data[:path]}"; data.merge(path: "#{data[:path]}-sm") }
    WATERMARK = ->(data) { puts "IMG: Watermarking #{data[:path]}"; data.merge(path: "#{data[:path]}-wm") }
    STORE = ->(data) { puts "IMG: Storing #{data[:path]} for #{data[:post_id]}"; data.merge(url: "s3://...") }
  end
end

# --- IMPLEMENTATION: The "Functional/Procedural" Developer ---
# This developer avoids classes where modules and functions suffice. Jobs are simple
# entry points that call a series of functions. Data is passed around as primitive hashes.

module BackgroundTasks
  # 1. Async Email Sending
  class SendWelcomeEmail < Hanami::Job
    def perform(user_data)
      user = Data::Users.get(user_data['id'])
      Services::Mailer.send(:welcome, user[:email])
    end
  end

  # 2. Retry Logic
  class SetPostPublished < Hanami::Job
    sidekiq_options retry: 5

    def perform(post_id)
      # Simulate a network blip
      raise "Connection failed" if (rand(10) > 5)

      Data::Posts.update_status(post_id, "PUBLISHED")
      puts "TASK: Post #{post_id} is now published."
    end
  end

  # 3. Image Processing Pipeline (functional composition)
  class ProcessPostImage < Hanami::Job
    PIPELINE_STEPS = {
      'resize' => Services::ImgProc::RESIZE,
      'watermark' => Services::ImgProc::WATERMARK,
      'store' => Services::ImgProc::STORE
    }.freeze

    def perform(data)
      # data = { 'post_id' => '...', 'image_path' => '...', 'steps' => ['resize', 'watermark', 'store'] }
      initial_state = { post_id: data['post_id'], path: data['image_path'] }

      final_state = data['steps'].reduce(initial_state) do |current_data, step_name|
        PIPELINE_STEPS[step_name].call(current_data)
      end

      puts "TASK: Image pipeline complete. Final URL: #{final_state[:url]}"
    end
  end

  # 4. Periodic Task
  class PurgeInactiveAccounts < Hanami::Job
    # Configured via sidekiq-cron to run periodically.
    def perform
      puts "TASK: Starting nightly purge of inactive accounts."
      Data::Users.find_and_delete_inactive
      puts "TASK: Purge complete."
    end
  end
end

# --- USAGE & DEMONSTRATION ---
puts "--- Variation 3: Functional/Procedural ---"
user_id = SecureRandom.uuid
post_id = SecureRandom.uuid

# 1. Enqueue email task
puts "\n1. Enqueuing welcome email task..."
# Pass data as a simple hash
job_id_email = BackgroundTasks::SendWelcomeEmail.perform_async({ 'id' => user_id })
puts "  -> Job ID: #{job_id_email}"

# 2. Enqueue retryable task
puts "\n2. Enqueuing post publish task..."
job_id_publish = BackgroundTasks::SetPostPublished.perform_async(post_id)
puts "  -> Job ID: #{job_id_publish}"

# 3. Enqueue functional pipeline task
puts "\n3. Enqueuing image processing pipeline..."
image_data = {
  'post_id' => post_id,
  'image_path' => '/tmp/source.jpg',
  'steps' => ['resize', 'watermark', 'store']
}
job_id_image = BackgroundTasks::ProcessPostImage.perform_async(image_data)
puts "  -> Job ID: #{job_id_image}"

# 4. Simulate periodic task
puts "\n4. Simulating periodic account purge..."
BackgroundTasks::PurgeInactiveAccounts.new.perform

# 5. Job Status Tracking
# The mechanism is identical (querying Sidekiq), but the developer might prefer a
# simple script over a dedicated class for it.
puts "\n5. Checking job statuses..."
require 'sidekiq/api'
queue = Sidekiq::Queue.new
puts "  -> Jobs currently in 'default' queue: #{queue.size}"
# For specific job status, one would still need a JID and a tool like sidekiq-status.