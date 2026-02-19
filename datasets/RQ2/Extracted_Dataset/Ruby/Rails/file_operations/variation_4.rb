<ruby>
# frozen_string_literal: true

# Variation 4: The "Background Job" Approach
# This developer is focused on performance and user experience. Long-running tasks like
# parsing large files are offloaded to a background job queue (Active Job). The controller
# quickly accepts the file, enqueues a job, and returns an immediate response to the user.

require 'action_controller/railtie'
require 'active_record'
require 'active_storage/engine'
require 'active_job'
require 'csv'
require 'roo'
require 'tempfile'
require 'securerandom'

# --- Boilerplate: Minimal Rails Application for Self-Contained Execution ---
class FileOpsApp < Rails::Application
  config.root = __dir__
  config.hosts << "example.org"
  config.eager_load = false
  config.autoload_paths += ["#{config.root}/app/jobs"]
  config.active_storage.service = :local
  config.active_storage.service_configurations = {
    local: {
      service: "Disk",
      root: Dir.mktmpdir("active_storage")
    }
  }
  config.active_job.queue_adapter = :inline # Use :inline for synchronous execution in this example
  config.secret_key_base = SecureRandom.hex(64)
  config.logger = Logger.new(nil)
  Rails.application.initialize!
end

# --- Boilerplate: Database and Schema ---
ActiveRecord::Base.establish_connection(adapter: "sqlite3", database: ":memory:")
ActiveRecord::Base.connection.create_table :users, id: :uuid do |t|
  t.string :email, null: false
  t.string :password_hash, null: false
  t.integer :role, default: 1
  t.boolean :is_active, default: true
  t.timestamps
end

ActiveRecord::Base.connection.create_table :posts, id: :uuid do |t|
  t.uuid :user_id, null: false
  t.string :title
  t.text :content
  t.integer :status, default: 0
  t.timestamps
end

ActiveRecord::Schema.define do
  create_table :active_storage_blobs do |t|
    t.string   :key,          null: false
    t.string   :filename,     null: false
    t.string   :content_type
    t.text     :metadata
    t.string   :service_name, null: false
    t.bigint   :byte_size,    null: false
    t.string   :checksum
    t.datetime :created_at,   null: false
    t.index [ :key ], unique: true
  end

  create_table :active_storage_attachments do |t|
    t.string     :name,     null: false
    t.references :record,   null: false, polymorphic: true, index: false, type: :uuid
    t.references :blob,     null: false
    t.datetime :created_at, null: false
    t.index [ :record_type, :record_id, :name, :blob_id ], name: "index_active_storage_attachments_uniqueness", unique: true
    t.foreign_key :active_storage_blobs, column: :blob_id
  end

  create_table :active_storage_variant_records do |t|
    t.belongs_to :blob, null: false, index: false
    t.string :variation_digest, null: false
    t.index [ :blob_id, :variation_digest ], name: "index_active_storage_variant_records_uniqueness", unique: true
    t.foreign_key :active_storage_blobs, column: :blob_id
  end
end

# --- Domain Models ---
class User < ActiveRecord::Base
  has_many :posts
  has_one_attached :avatar
  enum role: { ADMIN: 0, USER: 1 }
end

class Post < ActiveRecord::Base
  belongs_to :user
  enum status: { DRAFT: 0, PUBLISHED: 1 }
end

# --- Background Job ---
class PostImportJob < ActiveJob::Base
  queue_as :default

  def perform(user_id, file_blob_id)
    user = User.find(user_id)
    blob = ActiveStorage::Blob.find(blob_id)
    
    # Download the file from storage to a temporary file for processing
    blob.open do |tempfile|
      spreadsheet = Roo::Spreadsheet.open(tempfile.path)
      header = spreadsheet.row(1)

      (2..spreadsheet.last_row).each do |i|
        row_data = Hash[[header, spreadsheet.row(i)].transpose]
        user.posts.create(
          title: row_data['title'],
          content: row_data['content'],
          status: row_data['status']&.downcase || 'DRAFT'
        )
      end
    end
    # In a real app, you might send a notification (e.g., email) to the user upon completion.
    # blob.purge # Optionally clean up the uploaded file after processing
  end
end

# --- Controller Implementation ---
class FileOperationsController < ActionController::Base
  before_action :authenticate_admin!, only: [:upload_posts, :download_posts_report]
  before_action :get_user, only: [:upload_avatar]

  # POST /users/:user_id/avatar
  # Active Storage automatically handles image processing in the background.
  def upload_avatar
    avatar_file = params[:avatar]
    return render json: { error: 'No avatar file provided' }, status: :bad_request if avatar_file.blank?

    @user.avatar.attach(avatar_file)

    if @user.avatar.attached?
      render json: {
        message: 'Avatar upload accepted. Processing will occur in the background.',
        avatar_url: url_for(@user.avatar)
      }, status: :ok
    else
      render json: { error: 'Avatar failed to attach' }, status: :unprocessable_entity
    end
  end

  # POST /posts/bulk_upload
  # Enqueues a background job to process the file, providing an immediate response.
  def upload_posts
    file = params[:file]
    return render json: { error: 'No file provided' }, status: :bad_request if file.blank?

    # Attach the file to the user model temporarily for the job to access it.
    # This uses Active Storage to handle the upload to a cloud service (like S3).
    blob = ActiveStorage::Blob.create_and_upload!(
      io: file.open,
      filename: file.original_filename,
      content_type: file.content_type
    )

    PostImportJob.perform_later(@current_user.id, blob.id)

    render json: { message: "File accepted. Posts are being imported in the background." }, status: :accepted
  end

  # GET /posts/report
  # Streaming is synchronous and remains in the controller.
  def download_posts_report
    headers = {
      "Content-Type" => "text/csv",
      "Content-Disposition" => "attachment; filename=\"posts_report_#{Time.now.to_i}.csv\""
    }
    response.headers.merge!(headers)

    self.response_body = Enumerator.new do |yielder|
      yielder << CSV.generate_line(['ID', 'Title', 'Status', 'Author Email', 'Created At'])
      Post.includes(:user).find_each do |post|
        yielder << CSV.generate_line([post.id, post.title, post.status, post.user.email, post.created_at.to_s])
      end
    end
  end

  private

  def authenticate_admin!
    @current_user = User.find_or_create_by!(email: 'admin@example.com', role: 'ADMIN') { |u| u.password_hash = '...' }
    head :unauthorized unless @current_user.ADMIN?
  end

  def get_user
    @user = User.find(params[:user_id])
  end
end

# --- Routes ---
Rails.application.routes.draw do
  post '/users/:user_id/avatar', to: 'file_operations#upload_avatar'
  post '/posts/bulk_upload', to: 'file_operations#upload_posts'
  get '/posts/report', to: 'file_operations#download_posts_report'
end
</ruby>