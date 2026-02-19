<#-- Gemfile -->
# frozen_string_literal: true
source "https://rubygems.org"
gem "rails", "~> 7.0"
gem "pg"
gem "sidekiq"
gem "sidekiq-cron"
gem "image_processing"

#------------------------------------------------------------------------------
#-- config/application.rb -->
require_relative "boot"
require "rails/all"

Bundler.require(*Rails.groups)

module ConcernDrivenApp
  class Application < Rails::Application
    config.load_defaults 7.0
    config.active_job.queue_adapter = :sidekiq
  end
end

#------------------------------------------------------------------------------
#-- config/initializers/sidekiq.rb -->
schedule_file = "config/schedule.yml"
if File.exist?(schedule_file) && Sidekiq.server?
  Sidekiq::Cron::Job.load_from_hash YAML.load_file(schedule_file)
end

#------------------------------------------------------------------------------
#-- config/schedule.yml -->
nightly_user_audit:
  cron: "0 4 * * *"
  class: "UserAuditJob"
  queue: "low"
  description: "Audits user accounts for inactivity."

#------------------------------------------------------------------------------
#-- app/models/user.rb -->
class User < ApplicationRecord
  enum role: { USER: 'user', ADMIN: 'admin' }
  has_many :posts
  before_create -> { self.id = SecureRandom.uuid unless id }
end

#------------------------------------------------------------------------------
#-- app/models/post.rb -->
class Post < ApplicationRecord
  enum status: { DRAFT: 'draft', PUBLISHED: 'published' }
  belongs_to :user
  before_create -> { self.id = SecureRandom.uuid unless id }

  # Field for job status tracking
  attr_accessor :processing_job_id
  attr_accessor :processing_status
end

#------------------------------------------------------------------------------
#-- app/mailers/notification_mailer.rb -->
class NotificationMailer < ApplicationMailer
  def welcome(user_id)
    @user = User.find(user_id)
    mail(to: @user.email, subject: 'Welcome Aboard!')
  end
end

#------------------------------------------------------------------------------
#-- app/jobs/concerns/job_status_tracking.rb -->
# A concern to automatically track job status on a related model.
module JobStatusTracking
  extend ActiveSupport::Concern

  included do
    before_perform :mark_as_running
    after_perform :mark_as_completed
    rescue_from(Exception) { |exception| mark_as_failed(exception) }
  end

  private

  def find_trackable_model(args)
    # Convention: The first argument is assumed to be the ID of the trackable model.
    # The model class is inferred from the job name (e.g., PostImageJob -> Post).
    model_class = self.class.name.remove("Job").constantize
    model_class.find(args.first)
  end

  def mark_as_running(*args)
    model = find_trackable_model(args)
    model.update_columns(processing_job_id: self.job_id, processing_status: 'running')
  end

  def mark_as_completed(*args)
    model = find_trackable_model(args)
    model.update_columns(processing_status: 'completed')
  end

  def mark_as_failed(exception, *args)
    model = find_trackable_model(args)
    model.update_columns(processing_status: 'failed')
    # Re-raise to let the queue handle retries
    raise exception
  end
end

#------------------------------------------------------------------------------
#-- app/jobs/concerns/exponential_backoff_retry.rb -->
# A concern for a standardized retry strategy.
module ExponentialBackoffRetry
  extend ActiveSupport::Concern

  included do
    # Retry on most common transient errors
    retry_on ActiveStorage::FileNotFoundError,
             Net::ReadTimeout,
             wait: :exponentially_longer,
             attempts: 8
  end
end

#------------------------------------------------------------------------------
#-- app/jobs/application_job.rb -->
class ApplicationJob < ActiveJob::Base
  # Base class can include common concerns if desired
end

#------------------------------------------------------------------------------
#-- app/jobs/user_notification_job.rb -->
class UserNotificationJob < ApplicationJob
  queue_as :notifications

  def perform(user_id, notification_type)
    case notification_type.to_sym
    when :welcome
      NotificationMailer.welcome(user_id).deliver_now
    else
      raise "Unknown notification type: #{notification_type}"
    end
  end
end

#------------------------------------------------------------------------------
#-- app/jobs/post_image_job.rb -->
# This job processes a post's image and includes our concerns.
class PostImageJob < ApplicationJob
  include JobStatusTracking
  include ExponentialBackoffRetry

  queue_as :high_priority

  def perform(post_id)
    post = Post.find(post_id)
    image = post.image_attachment # Assumes Active Storage

    # Pipeline steps
    resized = process_step(image, :resize, 800, 600)
    watermarked = process_step(resized, :watermark, 'logo.png')
    
    puts "Image processing complete for Post #{post_id}"
  end

  private

  def process_step(image, step, *args)
    puts "Performing step '#{step}'..."
    # Mock processing
    sleep 0.5
    # Simulate a transient failure
    raise Net::ReadTimeout, "S3 connection failed" if rand(5).zero?
    "processed_#{step}_image"
  end
end

#------------------------------------------------------------------------------
#-- app/jobs/user_audit_job.rb -->
# Periodic job scheduled via sidekiq-cron
class UserAuditJob < ApplicationJob
  queue_as :low

  def perform
    puts "Starting nightly user audit..."
    User.where(is_active: true, role: 'USER')
        .where('last_seen_at < ?', 6.months.ago)
        .find_each do |user|
      puts "Deactivating user #{user.email}"
      user.update(is_active: false)
    end
    puts "User audit finished."
  end
end

#------------------------------------------------------------------------------
#-- app/controllers/users_controller.rb -->
class UsersController < ApplicationController
  def create
    @user = User.create!(user_params)
    # Enqueue a notification job
    UserNotificationJob.perform_later(@user.id, :welcome)
    render json: @user, status: :created
  end

  private
  def user_params; params.require(:user).permit(:email); end
end

#------------------------------------------------------------------------------
#-- app/controllers/posts_controller.rb -->
class PostsController < ApplicationController
  def create
    @post = current_user.posts.create!(post_params)
    # Enqueue the image processing job
    PostImageJob.perform_later(@post.id)
    render json: { post_id: @post.id, status: "Image processing scheduled." }, status: :accepted
  end

  private
  def post_params; params.require(:post).permit(:title, :content); end
end