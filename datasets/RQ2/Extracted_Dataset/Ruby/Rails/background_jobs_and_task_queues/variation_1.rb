<#-- Gemfile -->
# frozen_string_literal: true
source "https://rubygems.org"
gem "rails", "~> 7.0"
gem "pg"
gem "sidekiq"
gem "image_processing"
gem "sidekiq-cron"

#------------------------------------------------------------------------------
#-- config/application.rb -->
require_relative "boot"
require "rails/all"

Bundler.require(*Rails.groups)

module BackgroundJobsApp
  class Application < Rails::Application
    config.load_defaults 7.0
    # Set the queue adapter for Active Job
    config.active_job.queue_adapter = :sidekiq
  end
end

#------------------------------------------------------------------------------
#-- config/initializers/sidekiq.rb -->
# Load periodic jobs from the schedule file
schedule_file = "config/schedule.yml"
if File.exist?(schedule_file) && Sidekiq.server?
  Sidekiq::Cron::Job.load_from_hash YAML.load_file(schedule_file)
end

#------------------------------------------------------------------------------
#-- config/schedule.yml -->
# This file defines periodic jobs for sidekiq-cron
cleanup_job:
  cron: "0 2 * * *" # Run daily at 2:00 AM
  class: "InactiveUserCleanupJob"
  queue: "low_priority"
  description: "Deactivates users who have not logged in for 90 days."

#------------------------------------------------------------------------------
#-- app/models/concerns/uuid_primary_key.rb -->
# Mock concern for UUIDs
module UuidPrimaryKey
  extend ActiveSupport::Concern
  included do
    before_create :set_uuid
    def set_uuid
      self.id ||= SecureRandom.uuid
    end
  end
end

#------------------------------------------------------------------------------
#-- app/models/user.rb -->
class User < ApplicationRecord
  include UuidPrimaryKey
  self.primary_key = :id

  enum role: { USER: 'user', ADMIN: 'admin' }

  has_many :posts

  # Mock method to find inactive users
  def self.find_inactive
    where(is_active: true).where('last_login_at < ?', 90.days.ago)
  end
end

#------------------------------------------------------------------------------
#-- app/models/post.rb -->
class Post < ApplicationRecord
  include UuidPrimaryKey
  self.primary_key = :id

  enum status: { DRAFT: 'draft', PUBLISHED: 'published' }
  enum image_processing_status: { pending: 0, processing: 1, completed: 2, failed: 3 }, _prefix: :image

  belongs_to :user

  # Mock attachment
  def image
    @image ||= ActiveStorage::Blob.new
  end
end

#------------------------------------------------------------------------------
#-- app/mailers/user_mailer.rb -->
class UserMailer < ApplicationMailer
  default from: 'notifications@example.com'

  def welcome_email(user_id)
    @user = User.find(user_id)
    mail(to: @user.email, subject: 'Welcome to Our Awesome Site!')
  end
end

#------------------------------------------------------------------------------
#-- app/jobs/application_job.rb -->
class ApplicationJob < ActiveJob::Base
  # Base job class for shared configurations
end

#------------------------------------------------------------------------------
#-- app/jobs/welcome_email_job.rb -->
# Job for sending a welcome email asynchronously.
class WelcomeEmailJob < ApplicationJob
  queue_as :mailers

  def perform(user_id)
    UserMailer.welcome_email(user_id).deliver_now
  end
end

#------------------------------------------------------------------------------
#-- app/jobs/image_processing_pipeline_job.rb -->
# A multi-step job with retry logic and status tracking.
class ImageProcessingPipelineJob < ApplicationJob
  queue_as :critical

  # Retry 5 times with exponential backoff.
  # The wait time will be roughly 3s, 18s, 83s, 258s, 603s.
  retry_on StandardError, wait: :exponentially_longer, attempts: 5

  def perform(post_id)
    post = Post.find(post_id)
    post.update!(image_processing_status: :processing)

    # Step 1: Resize image
    processed_image = resize(post.image)

    # Step 2: Apply watermark
    watermarked_image = apply_watermark(processed_image)

    # Step 3: Upload to storage
    upload_to_cdn(watermarked_image)

    post.update!(image_processing_status: :completed)
  rescue => e
    post.update!(image_processing_status: :failed)
    # Re-raise the exception to trigger the retry mechanism
    raise e
  end

  private

  def resize(image)
    puts "Resizing image for Post #{image.id}..."
    # Mock image processing logic
    sleep 1
    "resized_#{image.filename}"
  end

  def apply_watermark(image_path)
    puts "Applying watermark to #{image_path}..."
    # Mock image processing logic
    sleep 1
    "watermarked_#{image_path}"
  end

  def upload_to_cdn(image_path)
    puts "Uploading #{image_path} to CDN..."
    # Mock upload logic
    # This is where an error might occur, triggering a retry.
    raise "CDN upload failed: connection timeout" if rand(3) == 0 # Simulate a failure
    sleep 1
    puts "Upload successful."
  end
end

#------------------------------------------------------------------------------
#-- app/jobs/inactive_user_cleanup_job.rb -->
# A periodic job to deactivate users.
class InactiveUserCleanupJob < ApplicationJob
  queue_as :low_priority

  def perform
    puts "Running InactiveUserCleanupJob..."
    inactive_users = User.find_inactive
    inactive_users.each do |user|
      user.update!(is_active: false)
      puts "Deactivated user #{user.id}."
    end
    puts "Cleanup complete. #{inactive_users.count} users deactivated."
  end
end

#------------------------------------------------------------------------------
#-- app/controllers/users_controller.rb -->
# Example of how a job is enqueued from a controller.
class UsersController < ApplicationController
  def create
    @user = User.new(user_params)
    if @user.save
      # Async email sending
      WelcomeEmailJob.perform_later(@user.id)
      redirect_to @user, notice: 'User was successfully created.'
    else
      render :new
    end
  end

  private
  def user_params
    params.require(:user).permit(:email, :password)
  end
end

#------------------------------------------------------------------------------
#-- app/controllers/posts_controller.rb -->
# Example of how a more complex job is enqueued.
class PostsController < ApplicationController
  def create
    @post = current_user.posts.new(post_params)
    if @post.save
      # Start the image processing pipeline in the background
      ImageProcessingPipelineJob.perform_later(@post.id)
      redirect_to @post, notice: 'Post created. Image is being processed.'
    else
      render :new
    end
  end

  private
  def post_params
    params.require(:post).permit(:title, :content, :image)
  end
end