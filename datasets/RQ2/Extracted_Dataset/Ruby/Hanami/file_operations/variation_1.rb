# Variation 1: The "Service Object" Developer
# Style: Classic OOP, thin actions, logic encapsulated in dedicated service classes.
# Dependencies are explicitly injected into services.

require 'hanami'
require 'hanami/action'
require 'dry-container'
require 'csv'
require 'mini_magick'
require 'securerandom'
require 'tempfile'
require 'ostruct'

# --- Mock Domain and Application Setup ---
User = Struct.new(:id, :email, :role, keyword_init: true)
Post = Struct.new(:id, :user_id, :title, :thumbnail_path, keyword_init: true)

# Mock Hanami application and container for dependency injection
module App
  class App < Hanami::App
    @_container = Dry::Container.new
    def self.container; @_container; end
    def self.[](key); container[key]; end
    def self.register(key, value); container.register(key, value); end
  end
end

class MockUserRepo
  def bulk_create(users)
    puts "REPO: Bulk creating #{users.count} users."
    users.each { |u| puts "  - #{u[:email]} (#{u[:role]})" }
    users.map { |u| User.new(id: SecureRandom.uuid, **u) }
  end
end

class MockPostRepo
  def find(id)
    Post.new(id: id, user_id: SecureRandom.uuid, title: "A Great Post", thumbnail_path: nil)
  end
  def update(id, data)
    puts "REPO: Updating post #{id} with thumbnail: #{data[:thumbnail_path]}"
  end
end

App.register("repositories.user_repo", MockUserRepo.new)
App.register("repositories.post_repo", MockPostRepo.new)

# --- Service Objects Implementation ---

# Service to handle user import from a CSV file
class UserImportService
  attr_reader :user_repo

  def initialize(user_repo: App["repositories.user_repo"])
    @user_repo = user_repo
  end

  def call(uploaded_file)
    tempfile = uploaded_file[:tempfile]
    return { success: false, errors: ["No file provided"] } unless tempfile

    users_to_create = []
    CSV.foreach(tempfile.path, headers: true, header_converters: :symbol) do |row|
      users_to_create << { email: row[:email], role: row[:role].upcase }
    end

    created_users = user_repo.bulk_create(users_to_create)
    { success: true, count: created_users.count }
  ensure
    tempfile.close
    tempfile.unlink
  end
end

# Service to process and resize an image
class ImageProcessingService
  attr_reader :post_repo

  def initialize(post_repo: App["repositories.post_repo"])
    @post_repo = post_repo
  end

  def create_thumbnail(post_id:, uploaded_file:)
    tempfile = uploaded_file[:tempfile]
    output_path = "tmp/thumbnails/#{post_id}-#{SecureRandom.hex(4)}.jpg"

    image = MiniMagick::Image.new(tempfile.path)
    image.resize "200x200"
    image.format "jpg"
    image.write output_path

    puts "SERVICE: Resized image and saved to #{output_path}"
    post_repo.update(post_id, thumbnail_path: output_path)
    { success: true, path: output_path }
  ensure
    tempfile.close
    tempfile.unlink
  end
end

# Service to generate a file for download
class ReportGenerationService
  def call(post)
    temp_report = Tempfile.new(["report-#{post.id}", ".txt"])
    temp_report.write("--- Post Report ---\n")
    temp_report.write("ID: #{post.id}\n")
    temp_report.write("Title: #{post.title}\n")
    temp_report.write("Generated at: #{Time.now}\n")
    temp_report.rewind
    temp_report
  end
end

# --- Hanami Actions ---

module App::Actions::Users
  class Import < Hanami::Action
    def handle(req, res)
      import_service = UserImportService.new
      result = import_service.call(req.params[:users_file])

      if result[:success]
        res.status = 201
        res.body = "Successfully imported #{result[:count]} users."
      else
        res.status = 422
        res.body = "Import failed: #{result[:errors].join(', ')}"
      end
    end
  end
end

module App::Actions::Posts
  class UploadThumbnail < Hanami::Action
    def handle(req, res)
      processing_service = ImageProcessingService.new
      result = processing_service.create_thumbnail(
        post_id: req.params[:id],
        uploaded_file: req.params[:thumbnail]
      )
      res.body = "Thumbnail updated. Path: #{result[:path]}"
    end
  end

  class DownloadReport < Hanami::Action
    def handle(req, res)
      post_repo = App["repositories.post_repo"]
      report_service = ReportGenerationService.new
      
      post = post_repo.find(req.params[:id])
      report_file = report_service.call(post)

      res.headers.merge!(
        "Content-Type" => "text/plain",
        "Content-Disposition" => "attachment; filename=\"report-#{post.id}.txt\""
      )
      
      # Stream the file
      res.body = Rack::Files::File.new(report_file.path).to_a
    ensure
      # Rack will handle closing, but good practice to ensure cleanup
      report_file.close if report_file
      report_file.unlink if report_file
    end
  end
end

# --- Demonstration ---
puts "--- VARIATION 1: Service Object Pattern ---"

# 1. CSV Upload
puts "\n1. Simulating User CSV Import:"
csv_content = "email,role\njane.doe@example.com,USER\njohn.smith@example.com,ADMIN"
temp_csv = Tempfile.new(['users', '.csv']); temp_csv.write(csv_content); temp_csv.rewind
mock_csv_req = OpenStruct.new(params: { users_file: { tempfile: temp_csv } })
mock_res = OpenStruct.new
App::Actions::Users::Import.new.handle(mock_csv_req, mock_res)
puts "Response: #{mock_res.body}"

# 2. Image Upload and Resize
puts "\n2. Simulating Post Thumbnail Upload:"
temp_img = Tempfile.new(['avatar', '.png']); # Create a dummy file
mock_img_req = OpenStruct.new(params: { id: SecureRandom.uuid, thumbnail: { tempfile: temp_img } })
# Mock MiniMagick to avoid actual image processing library dependency
module MiniMagick; class Image; def initialize(path); end; def resize(size); self; end; def format(fmt); self; end; def write(path); end; end; end
`mkdir -p tmp/thumbnails`
App::Actions::Posts::UploadThumbnail.new.handle(mock_img_req, mock_res)
puts "Response: #{mock_res.body}"

# 3. File Download with Streaming
puts "\n3. Simulating Post Report Download:"
mock_dl_req = OpenStruct.new(params: { id: SecureRandom.uuid })
mock_dl_res = OpenStruct.new(headers: {}, body: nil)
App::Actions::Posts::DownloadReport.new.handle(mock_dl_req, mock_dl_res)
puts "Response Headers: #{mock_dl_res.headers}"
puts "Response Body (streaming): #{mock_dl_res.body.class}" # Shows it's a streaming body
`rm -rf tmp`