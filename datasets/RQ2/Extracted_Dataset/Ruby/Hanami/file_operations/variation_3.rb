# Variation 3: The "Fat Action" Developer
# Style: Pragmatic approach where logic is kept within the action class itself,
# using private helper methods for organization. Avoids extra layers of abstraction.

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

module App
  class App < Hanami::App
    @_container = Dry::Container.new
    def self.container; @_container; end
    def self.[](key); container[key]; end
    def self.register(key, value); container.register(key, value); end
  end
end

class MockUserRepo
  def add_users(users)
    puts "REPO: Adding #{users.count} users."
    users.map { |u| User.new(id: SecureRandom.uuid, **u) }
  end
end

class MockPostRepo
  def get(id)
    Post.new(id: id, user_id: SecureRandom.uuid, title: "A Simple Post", thumbnail_path: nil)
  end
  def update_image(id, path)
    puts "REPO: Updating image for post #{id} to #{path}"
  end
end

App.register("persistence.user_repo", MockUserRepo.new)
App.register("persistence.post_repo", MockPostRepo.new)

# --- Hanami Actions with In-line Logic ---

module App::Actions::Admin
  class FileUpload < Hanami::Action
    # Dependencies are injected via Hanami's standard DI
    def initialize(user_repo: App["persistence.user_repo"], post_repo: App["persistence.post_repo"], **opts)
      super(**opts)
      @user_repo = user_repo
      @post_repo = post_repo
    end

    def handle(req, res)
      upload = req.params[:upload]
      upload_type = req.params[:type]

      unless upload && upload[:tempfile]
        res.status = 400
        res.body = "File not provided."
        return
      end

      begin
        case upload_type
        when "users_csv"
          count = process_users_csv(upload[:tempfile])
          res.body = "Processed #{count} users from CSV."
        when "post_thumbnail"
          post_id = req.params[:post_id]
          path = process_post_thumbnail(post_id, upload[:tempfile])
          res.body = "Thumbnail for post #{post_id} saved to #{path}."
        else
          res.status = 400
          res.body = "Invalid upload type: #{upload_type}"
        end
      rescue => e
        res.status = 500
        res.body = "An error occurred: #{e.message}"
      ensure
        # Ensure tempfile from upload is always cleaned up
        upload[:tempfile].close
        upload[:tempfile].unlink
      end
    end

    private

    def process_users_csv(tempfile)
      users_data = []
      CSV.foreach(tempfile.path, headers: true) do |row|
        users_data << { email: row['email'], role: row['role'] }
      end
      created = @user_repo.add_users(users_data)
      created.count
    end

    def process_post_thumbnail(post_id, tempfile)
      raise "post_id is required" unless post_id
      
      output_dir = "tmp/public/images"
      `mkdir -p #{output_dir}`
      output_path = File.join(output_dir, "#{post_id}_thumb.jpg")

      image = MiniMagick::Image.new(tempfile.path)
      image.combine_options do |c|
        c.resize "250x250^"
        c.gravity "center"
        c.extent "250x250"
      end
      image.write(output_path)
      
      @post_repo.update_image(post_id, output_path)
      output_path
    end
  end

  class FileDownload < Hanami::Action
    def initialize(post_repo: App["persistence.post_repo"], **opts)
      super(**opts)
      @post_repo = post_repo
    end

    def handle(req, res)
      post = @post_repo.get(req.params[:post_id])
      
      # Generate file content on the fly
      report_content = <<~REPORT
        Post Details Report
        ===================
        ID: #{post.id}
        Title: #{post.title}
        User ID: #{post.user_id}
        Generated: #{Time.now.utc}
      REPORT

      # Use a temporary file to handle streaming
      temp_file = Tempfile.new("report")
      temp_file.write(report_content)
      temp_file.rewind
      
      res.status = 200
      res.headers["Content-Type"] = "application/octet-stream"
      res.headers["Content-Disposition"] = "attachment; filename=\"post_#{post.id}_report.txt\""
      
      # Stream the body. Hanami/Rack will close the file.
      res.body = temp_file
    end
  end
end

# --- Demonstration ---
puts "--- VARIATION 3: Fat Action Pattern ---"

# 1. CSV Upload
puts "\n1. Simulating User CSV Upload:"
csv_content = "email,role\npragmatic.dev@example.com,ADMIN"
temp_csv = Tempfile.new(['users', '.csv']); temp_csv.write(csv_content); temp_csv.rewind
mock_csv_req = OpenStruct.new(params: { type: "users_csv", upload: { tempfile: temp_csv } })
mock_res = OpenStruct.new
App::Actions::Admin::FileUpload.new.handle(mock_csv_req, mock_res)
puts "Response: #{mock_res.body}"

# 2. Image Upload and Resize
puts "\n2. Simulating Post Thumbnail Upload:"
temp_img = Tempfile.new(['thumb', '.png']);
post_uuid = SecureRandom.uuid
mock_img_req = OpenStruct.new(params: { type: "post_thumbnail", post_id: post_uuid, upload: { tempfile: temp_img } })
# Mock MiniMagick
module MiniMagick; class Image; def initialize(path); end; def combine_options; yield(self); end; def resize(s); end; def gravity(g); end; def extent(e); end; def write(path); end; end; end
App::Actions::Admin::FileUpload.new.handle(mock_img_req, mock_res)
puts "Response: #{mock_res.body}"

# 3. File Download with Streaming
puts "\n3. Simulating Post Report Download:"
mock_dl_req = OpenStruct.new(params: { post_id: SecureRandom.uuid })
mock_dl_res = OpenStruct.new(headers: {}, body: nil)
App::Actions::Admin::FileDownload.new.handle(mock_dl_req, mock_dl_res)
puts "Response Headers: #{mock_dl_res.headers}"
puts "Response Body is a Tempfile to be streamed: #{mock_dl_res.body.is_a?(Tempfile)}"
mock_dl_res.body.close! # Manual cleanup for demo

`rm -rf tmp`