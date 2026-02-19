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

module ServiceOrientedApp
  class Application < Rails::Application
    config.load_defaults 7.0
    config.active_job.queue_adapter = :sidekiq
    # Autoload services
    config.autoload_paths += %W(#{config.root}/app/services)
  end
end

#------------------------------------------------------------------------------
#-- config/initializers/sidekiq_cron.rb -->
schedule_file = "config/schedule.yml"
if File.exist?(schedule_file) && Sidekiq.server?
  Sidekiq::Cron::Job.load_from_hash YAML.load_file(schedule_file)
end

#------------------------------------------------------------------------------
#-- config/schedule.yml -->
nightly_maintenance:
  cron: "0 3 * * *"
  class: "Maintenance::PeriodicJob"
  args: ["Maintenance::UserPruner"]
  queue: "maintenance"
  description: "Prunes inactive users."

#------------------------------------------------------------------------------
#-- app/models/concerns/uuid_pk.rb -->
module UuidPk
  extend ActiveSupport::Concern
  included do
    before_create -> { self.id = SecureRandom.uuid if self.id.blank? }
  end
end

#------------------------------------------------------------------------------
#-- app/models/user.rb -->
class User < ApplicationRecord
  include UuidPk
  enum role: { user: 0, admin: 1 }
  has_many :posts
end

#------------------------------------------------------------------------------
#-- app/models/post.rb -->
class Post < ApplicationRecord
  include UuidPk
  enum status: { draft: 0, published: 1 }
  belongs_to :user
  has_one :job_tracker, as: :trackable
end

#------------------------------------------------------------------------------
#-- app/models/job_tracker.rb -->
# A dedicated model for tracking job status, decoupled from domain models.
class JobTracker < ApplicationRecord
  include UuidPk
  belongs_to :trackable, polymorphic: true
  enum status: { queued: 0, running: 1, success: 2, failed: 3 }
end

#------------------------------------------------------------------------------
#-- app/mailers/user_mailer.rb -->
class UserMailer < ApplicationMailer
  def registration_confirmation(user)
    @user = user
    mail(to: @user.email, subject: 'Welcome!')
  end
end

#------------------------------------------------------------------------------
#-- app/jobs/application_job.rb -->
class ApplicationJob < ActiveJob::Base
  # Retry on network errors with a custom backoff strategy
  retry_on Net::OpenTimeout, wait: ->(executions) { executions**4 }, attempts: 5
end

#------------------------------------------------------------------------------
#-- app/jobs/service_runner_job.rb -->
# A generic job that executes a service object.
class ServiceRunnerJob < ApplicationJob
  queue_as :default

  def perform(service_class_name, **args)
    service_class = service_class_name.constantize
    service_class.call(**args)
  end
end

#------------------------------------------------------------------------------
#-- app/jobs/maintenance/periodic_job.rb -->
# A generic job for periodic tasks, also using the service pattern.
module Maintenance
  class PeriodicJob < ApplicationJob
    queue_as :maintenance

    def perform(service_class_name)
      service_class = service_class_name.constantize
      service_class.call
    end
  end
end

#------------------------------------------------------------------------------
#-- app/services/application_service.rb -->
class ApplicationService
  def self.call(**args)
    new(**args).call
  end

  def initialize(**args)
    args.each do |key, value|
      instance_variable_set("@#{key}", value)
    end
  end
end

#------------------------------------------------------------------------------
#-- app/services/users/registration_orchestrator.rb -->
class Users::RegistrationOrchestrator < ApplicationService
  attr_reader :email, :password

  def call
    user = User.create!(email: @email, password_hash: BCrypt::Password.create(@password))
    # Enqueue the email sending service
    ServiceRunnerJob.set(queue: :mailers).perform_later(
      'Users::WelcomeEmailSender',
      user_id: user.id
    )
    user
  end
end

#------------------------------------------------------------------------------
#-- app/services/users/welcome_email_sender.rb -->
class Users::WelcomeEmailSender < ApplicationService
  attr_reader :user_id

  def call
    user = User.find(@user_id)
    UserMailer.registration_confirmation(user).deliver_now
  end
end

#------------------------------------------------------------------------------
#-- app/services/posts/image_pipeline.rb -->
class Posts::ImagePipeline < ApplicationService
  attr_reader :post_id

  def call
    post = Post.find(@post_id)
    tracker = JobTracker.find_or_create_by!(trackable: post)
    tracker.running!

    # Step 1: Resize
    temp_file = ImageProcessor.resize(post.original_image_path)
    # Step 2: Watermark
    final_file = ImageProcessor.watermark(temp_file)
    # Step 3: Upload
    cdn_url = CdnUploader.upload(final_file)

    post.update!(processed_image_url: cdn_url)
    tracker.success!
  rescue => e
    tracker.update!(status: :failed, error_message: e.message)
    raise e # Re-raise to allow ActiveJob to handle retries
  end
end

#------------------------------------------------------------------------------
#-- app/services/maintenance/user_pruner.rb -->
class Maintenance::UserPruner < ApplicationService
  def call
    puts "Executing Maintenance::UserPruner service..."
    User.where('created_at < ? AND is_active = ?', 1.year.ago, true).update_all(is_active: false)
    puts "User pruning complete."
  end
end

#------------------------------------------------------------------------------
#-- lib/image_processor.rb (Mock) -->
module ImageProcessor
  def self.resize(path); "resized_#{path}"; end
  def self.watermark(path); "watermarked_#{path}"; end
end

#------------------------------------------------------------------------------
#-- lib/cdn_uploader.rb (Mock) -->
module CdnUploader
  def self.upload(path)
    # Simulate a network error for retry demonstration
    raise Net::OpenTimeout if rand(3).zero?
    "https://cdn.example.com/#{path}"
  end
end

#------------------------------------------------------------------------------
#-- app/controllers/posts_controller.rb -->
class PostsController < ApplicationController
  def create
    @post = Post.create!(post_params.merge(user: current_user))
    # Enqueue the pipeline service
    ServiceRunnerJob.set(queue: :graphics).perform_later(
      'Posts::ImagePipeline',
      post_id: @post.id
    )
    render json: { id: @post.id, message: "Processing started." }, status: :accepted
  end

  private
  def post_params
    params.require(:post).permit(:title, :content)
  end
end