<ruby>
# frozen_string_literal: true

# Variation 2: The "Service Object" Approach
# This developer extracts business logic into dedicated, single-purpose classes (Service Objects).
# This promotes SRP, improves testability, and keeps controllers lean and focused on HTTP concerns.

require 'action_controller/railtie'
require 'active_record'
require 'active_storage/engine'
require 'csv'
require 'roo'
require 'tempfile'
require 'securerandom'

# --- Boilerplate: Minimal Rails Application for Self-Contained Execution ---
class FileOpsApp < Rails::Application
  config.root = __dir__
  config.hosts << "example.org"
  config.eager_load = false
  config.autoload_paths += ["#{config.root}/app/services"]
  config.active_storage.service = :local
  config.active_storage.service_configurations = {
    local: {
      service: "Disk",
      root: Dir.mktmpdir("active_storage")
    }
  }
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

# --- Service Objects ---
class BaseService
  def self.call(*args, &block)
    new(*args, &block).call
  end
end

class PostImportService < BaseService
  def initialize(uploaded_file, user)
    @file = uploaded_file
    @user = user
    @result = { created_count: 0, failed_rows: [] }
  end

  def call
    return { error: 'File is missing' } if @file.blank?

    Tempfile.create(['import', @file.original_filename]) do |tempfile|
      tempfile.binmode
      tempfile.write(@file.read)
      tempfile.rewind
      process_spreadsheet(tempfile.path)
    end
    @result
  rescue => e
    { error: "Processing failed: #{e.message}" }
  end

  private

  def process_spreadsheet(file_path)
    spreadsheet = Roo::Spreadsheet.open(file_path)
    header = spreadsheet.row(1).map(&:downcase)

    (2..spreadsheet.last_row).each do |i|
      row_data = Hash[[header, spreadsheet.row(i)].transpose]
      create_post_from_row(row_data, i)
    end
  end

  def create_post_from_row(row_data, row_num)
    post = @user.posts.build(
      title: row_data['title'],
      content: row_data['content'],
      status: row_data['status']&.downcase || 'DRAFT'
    )
    if post.save
      @result[:created_count] += 1
    else
      @result[:failed_rows] << { row: row_num, errors: post.errors.full_messages }
    end
  end
end

class AvatarManagementService < BaseService
  def initialize(user, avatar_file)
    @user = user
    @avatar_file = avatar_file
  end

  def call
    return { success: false, error: 'No avatar file provided' } if @avatar_file.blank?

    @user.avatar.attach(@avatar_file)

    if @user.avatar.attached?
      { success: true }
    else
      { success: false, error: 'Avatar failed to attach' }
    end
  end
end

class PostReportGenerator
  def self.generate_csv_stream
    Enumerator.new do |yielder|
      yielder << CSV.generate_line(['ID', 'Title', 'Status', 'Author Email', 'Created At'])
      Post.includes(:user).find_each do |post|
        yielder << CSV.generate_line([
          post.id,
          post.title,
          post.status,
          post.user.email,
          post.created_at.to_s
        ])
      end
    end
  end
end

# --- Controller Implementation ---
class FileOperationsController < ActionController::Base
  before_action :authenticate_admin!, only: [:upload_posts, :download_posts_report]
  before_action :set_user!, only: [:upload_avatar]

  def upload_avatar
    result = AvatarManagementService.call(@user, params[:avatar])
    if result[:success]
      render json: {
        message: 'Avatar uploaded successfully',
        avatar_url: url_for(@user.avatar),
        thumbnail_url: url_for(@user.avatar.variant(resize_to_limit: [200, 200]))
      }, status: :ok
    else
      render json: { error: result[:error] }, status: :unprocessable_entity
    end
  end

  def upload_posts
    result = PostImportService.call(params[:file], @current_user)
    if result[:error]
      render json: { error: result[:error] }, status: :unprocessable_entity
    else
      render json: {
        message: "Import complete.",
        posts_created: result[:created_count],
        failed_rows: result[:failed_rows]
      }, status: :created
    end
  end

  def download_posts_report
    headers = {
      "Content-Type" => "text/csv",
      "Content-Disposition" => "attachment; filename=\"posts_report_#{Time.now.to_i}.csv\""
    }
    response.headers.merge!(headers)
    self.response_body = PostReportGenerator.generate_csv_stream
  end

  private

  def authenticate_admin!
    @current_user = User.find_or_create_by!(email: 'admin@example.com', role: 'ADMIN') { |u| u.password_hash = '...' }
    head :unauthorized unless @current_user.ADMIN?
  end

  def set_user!
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