# Variation 4: The "Concern/Mixin" Developer
# Style: Extracts reusable logic into modules (Concerns) and includes them in actions.
# This pattern is popular in the Rails community and often used for cross-cutting concerns.

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
  def create_from_hashes(user_hashes)
    puts "REPO: Creating #{user_hashes.count} users via mixin."
    user_hashes.map { |h| User.new(id: SecureRandom.uuid, **h) }
  end
end

class MockPostRepo
  def find_post(id)
    Post.new(id: id, user_id: SecureRandom.uuid, title: "Modular Methods", thumbnail_path: nil)
  end
  def save_thumbnail_path(id, path)
    puts "REPO: Saving thumbnail path for post #{id}: #{path}"
  end
end

App.register("repos.user_repo", MockUserRepo.new)
App.register("repos.post_repo", MockPostRepo.new)

# --- Concerns/Mixins Implementation ---

module App::Actions::Concerns
  module FileParsing
    private
    def parse_user_data_from_csv(tempfile)
      # Assumes @user_repo is available in the including class
      user_hashes = CSV.new(tempfile, headers: true, header_converters: :symbol).map(&:to_h)
      @user_repo.create_from_hashes(user_hashes)
    end
  end

  module ImageManipulation
    private
    def generate_post_thumbnail(post_id, tempfile)
      # Assumes @post_repo is available
      output_path = "tmp/generated/#{post_id}-thumb.jpg"
      
      image = MiniMagick::Image.new(tempfile.path)
      image.resize "100x100"
      image.write(output_path)
      
      @post_repo.save_thumbnail_path(post_id, output_path)
      output_path
    end
  end

  module FileStreaming
    private
    def stream_text_file(res, filename, content)
      res.headers.merge!(
        "Content-Type" => "text/plain",
        "Content-Disposition" => "attachment; filename=\"#{filename}\""
      )
      # For simple content, streaming a simple array of strings is fine.
      res.body = [content]
    end
  end
end

# --- Hanami Actions using Concerns ---

module App::Actions::Users
  class Upload < Hanami::Action
    include App::Actions::Concerns::FileParsing

    def initialize(user_repo: App["repos.user_repo"], **opts)
      super(**opts)
      @user_repo = user_repo
    end

    def handle(req, res)
      uploaded_file = req.params[:csv_file]
      
      if uploaded_file && uploaded_file[:tempfile]
        begin
          created_users = parse_user_data_from_csv(uploaded_file[:tempfile])
          res.body = "User upload complete. #{created_users.count} users created."
        ensure
          uploaded_file[:tempfile].close
          uploaded_file[:tempfile].unlink
        end
      else
        res.status = 400
        res.body = "No CSV file provided."
      end
    end
  end
end

module App::Actions::Posts
  class AttachImage < Hanami::Action
    include App::Actions::Concerns::ImageManipulation

    def initialize(post_repo: App["repos.post_repo"], **opts)
      super(**opts)
      @post_repo = post_repo
    end

    def handle(req, res)
      post_id = req.params[:post_id]
      image_file = req.params[:image]
      
      path = generate_post_thumbnail(post_id, image_file[:tempfile])
      res.body = "Image attached. Path: #{path}"
    ensure
      image_file[:tempfile].close
      image_file[:tempfile].unlink
    end
  end

  class Download < Hanami::Action
    include App::Actions::Concerns::FileStreaming

    def initialize(post_repo: App["repos.post_repo"], **opts)
      super(**opts)
      @post_repo = post_repo
    end

    def handle(req, res)
      post = @post_repo.find_post(req.params[:id])
      report_content = "Report for Post: #{post.title} (ID: #{post.id})"
      
      stream_text_file(res, "post_#{post.id}.txt", report_content)
    end
  end
end

# --- Demonstration ---
puts "--- VARIATION 4: Concern/Mixin Pattern ---"

# 1. CSV Upload
puts "\n1. Simulating User CSV Upload:"
csv_content = "email,role\nmixin.lover@example.com,USER"
temp_csv = Tempfile.new(['users', '.csv']); temp_csv.write(csv_content); temp_csv.rewind
mock_csv_req = OpenStruct.new(params: { csv_file: { tempfile: temp_csv } })
mock_res = OpenStruct.new
App::Actions::Users::Upload.new.handle(mock_csv_req, mock_res)
puts "Response: #{mock_res.body}"

# 2. Image Upload and Resize
puts "\n2. Simulating Post Image Attachment:"
temp_img = Tempfile.new(['post_img', '.png']);
post_uuid = SecureRandom.uuid
mock_img_req = OpenStruct.new(params: { post_id: post_uuid, image: { tempfile: temp_img } })
# Mock MiniMagick
module MiniMagick; class Image; def initialize(path); end; def resize(s); end; def write(path); end; end; end
`mkdir -p tmp/generated`
App::Actions::Posts::AttachImage.new.handle(mock_img_req, mock_res)
puts "Response: #{mock_res.body}"

# 3. File Download with Streaming
puts "\n3. Simulating Post Data Download:"
mock_dl_req = OpenStruct.new(params: { id: SecureRandom.uuid })
mock_dl_res = OpenStruct.new(headers: {}, body: nil)
App::Actions::Posts::Download.new.handle(mock_dl_req, mock_dl_res)
puts "Response Headers: #{mock_dl_res.headers}"
puts "Response Body: #{mock_dl_res.body.join}"
`rm -rf tmp`