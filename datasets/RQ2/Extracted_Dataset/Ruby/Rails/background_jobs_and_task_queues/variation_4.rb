<#-- Gemfile -->
# frozen_string_literal: true
source "https://rubygems.org"
gem "rails", "~> 7.0"
gem "sqlite3"
gem "sidekiq"
gem "image_processing"

#------------------------------------------------------------------------------
#-- config/application.rb -->
require_relative "boot"
require "rails/all"

Bundler.require(*Rails.groups)

module MinimalistApp
  class Application < Rails::Application
    config.load_defaults 7.0
    config.active_job.queue_adapter = :sidekiq
  end
end

#------------------------------------------------------------------------------
#-- app/models/user.rb -->
class User < ApplicationRecord
  enum role: { USER: 'user', ADMIN: 'admin' }
  has_many :posts
  before_create { self.id ||= SecureRandom.uuid }

  after_create :dispatch_welcome_email

  private

  # Use the most direct way to send async mail
  def dispatch_welcome_email
    UserMailer.with(user: self).welcome_email.deliver_later
  end
end

#------------------------------------------------------------------------------
#-- app/models/post.rb -->
class Post < ApplicationRecord
  enum status: { DRAFT: 'draft', PUBLISHED: 'published' }
  belongs_to :user
  before_create { self.id ||= SecureRandom.uuid }

  # Using a model callback to kick off the first job in the chain.
  # This keeps the controller extremely thin.
  after_commit :schedule_image_processing, on: :create

  private

  def schedule_image_processing
    # Enqueue the first job of the pipeline
    Image::ResizeJob.perform_later(self.id, { width: 1024, height: 768 })
  end
end

#------------------------------------------------------------------------------
#-- app/mailers/user_mailer.rb -->
class UserMailer < ApplicationMailer
  def welcome_email
    @user = params[:user]
    mail(to: @user.email, subject: 'Welcome!')
  end
end

#------------------------------------------------------------------------------
#-- app/jobs/application_job.rb -->
class ApplicationJob < ActiveJob::Base
  # Minimalist approach: Rely on Sidekiq's default retry mechanism.
  # Sidekiq retries 25 times over ~21 days with exponential backoff by default.
  # No custom retry logic is needed for most cases.
  # Status tracking is handled by observing the Sidekiq Web UI and logs,
  # not by polluting domain models with status fields.
end

#------------------------------------------------------------------------------
#-- app/jobs/image/resize_job.rb -->
# First job in the image processing chain.
module Image
  class ResizeJob < ApplicationJob
    queue_as :image_processing

    def perform(post_id, dimensions)
      post = Post.find(post_id)
      puts "Resizing image for Post #{post.id} to #{dimensions[:width]}x#{dimensions[:height]}..."
      # original_path = download_image(post.image_url)
      # resized_path = ImageProcessing::MiniMagick.source(original_path).resize_to_limit!(dimensions[:width], dimensions[:height])
      resized_path = "/tmp/resized_#{post.id}.jpg" # Mock
      puts "Resize complete: #{resized_path}"

      # Enqueue the next job in the chain
      Image::WatermarkJob.perform_later(post.id, resized_path)
    end
  end
end

#------------------------------------------------------------------------------
#-- app/jobs/image/watermark_job.rb -->
# Second job in the chain.
module Image
  class WatermarkJob < ApplicationJob
    queue_as :image_processing

    def perform(post_id, source_image_path)
      post = Post.find(post_id)
      puts "Watermarking image for Post #{post.id} from #{source_image_path}..."
      # watermarked_path = ImageProcessing::MiniMagick.source(source_image_path).composite("watermark.png", gravity: "south_east").call
      watermarked_path = "/tmp/watermarked_#{post.id}.jpg" # Mock
      puts "Watermark complete: #{watermarked_path}"

      # Enqueue the final job in the chain
      Image::UploadJob.perform_later(post.id, watermarked_path)
    end
  end
end

#------------------------------------------------------------------------------
#-- app/jobs/image/upload_job.rb -->
# Final job in the chain.
module Image
  class UploadJob < ApplicationJob
    queue_as :uploads

    def perform(post_id, final_image_path)
      post = Post.find(post_id)
      puts "Uploading image for Post #{post.id} from #{final_image_path}..."
      # cdn_url = S3Service.upload(file: final_image_path, key: "posts/#{post.id}.jpg")
      cdn_url = "https://s3.amazonaws.com/bucket/posts/#{post.id}.jpg" # Mock
      post.update!(processed_image_url: cdn_url)
      puts "Upload complete. URL: #{cdn_url}"
      # File.delete(final_image_path) # Cleanup
    end
  end
end

#------------------------------------------------------------------------------
#-- lib/tasks/maintenance.rake -->
# A simple Rake task for periodic jobs, to be triggered by system cron.
namespace :maintenance do
  desc "Deactivates user accounts that have been inactive for over 180 days"
  task prune_inactive_users: :environment do
    puts "Starting inactive user pruning..."
    threshold = 180.days.ago
    inactive_users = User.where("is_active = ? AND last_login_at < ?", true, threshold)
    count = inactive_users.update_all(is_active: false)
    puts "Pruning complete. Deactivated #{count} user(s)."
  end
end
# To run this from cron:
# 0 5 * * * /bin/bash -l -c 'cd /path/to/app && RAILS_ENV=production bundle exec rake maintenance:prune_inactive_users'

#------------------------------------------------------------------------------
#-- app/controllers/users_controller.rb -->
class UsersController < ApplicationController
  # The model's after_create callback handles the welcome email,
  # so the controller is very simple.
  def create
    user = User.new(email: params[:email], password_hash: '...')
    if user.save
      render json: { id: user.id }, status: :created
    else
      render json: user.errors, status: :unprocessable_entity
    end
  end
end

#------------------------------------------------------------------------------
#-- app/controllers/posts_controller.rb -->
class PostsController < ApplicationController
  # The model's after_commit callback handles enqueueing the image jobs.
  def create
    post = current_user.posts.new(title: params[:title], content: params[:content])
    if post.save
      # No job logic here. The model handles it.
      render json: { id: post.id, message: "Post created and processing initiated." }, status: :accepted
    else
      render json: post.errors, status: :unprocessable_entity
    end
  end
end