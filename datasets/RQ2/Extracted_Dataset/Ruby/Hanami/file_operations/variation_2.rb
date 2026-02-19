# Variation 2: The "Functional/Interactor" Developer
# Style: Uses single-purpose, callable objects (Interactors/Operations) with clear
# success/failure paths, often using monads (e.g., Dry::Monads).

require 'hanami'
require 'hanami/action'
require 'dry-container'
require 'dry/monads'
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
  def create_many(users_attrs)
    puts "REPO: Creating #{users_attrs.count} users."
    users_attrs.map { |attrs| User.new(id: SecureRandom.uuid, **attrs) }
  end
end

class MockPostRepo
  def find_by_id(id)
    Post.new(id: id, user_id: SecureRandom.uuid, title: "Monadic Musings", thumbnail_path: nil)
  end
  def set_thumbnail(id, path)
    puts "REPO: Setting thumbnail for post #{id} to #{path}"
  end
end

App.register("repos.users", MockUserRepo.new)
App.register("repos.posts", MockPostRepo.new)

# --- Interactor/Operation Implementation ---

module App::Operations
  class Base
    include Dry::Monads[:result, :do]

    def self.call(*args)
      new.call(*args)
    end
  end

  module Users
    class ImportFromSpreadsheet < Base
      def call(upload_data)
        tempfile = yield validate_upload(upload_data)
        user_attrs = yield parse_csv(tempfile)
        created_users = yield persist_users(user_attrs)

        Success(created_users)
      ensure
        tempfile.close if tempfile
        tempfile.unlink if tempfile
      end

      private

      def validate_upload(upload_data)
        tempfile = upload_data&.dig(:tempfile)
        tempfile ? Success(tempfile) : Failure(:missing_file)
      end

      def parse_csv(tempfile)
        users = CSV.read(tempfile.path, headers: true, header_converters: :symbol).map(&:to_h)
        Success(users)
      rescue CSV::MalformedCSVError => e
        Failure([:csv_parsing_error, e.message])
      end

      def persist_users(user_attrs)
        repo = App["repos.users"]
        users = repo.create_many(user_attrs)
        Success(users)
      end
    end
  end

  module Posts
    class AttachThumbnail < Base
      def call(post_id:, upload_data:)
        tempfile = yield validate_upload(upload_data)
        output_path = yield process_image(post_id, tempfile)
        yield update_post_record(post_id, output_path)

        Success(output_path)
      ensure
        tempfile.close if tempfile
        tempfile.unlink if tempfile
      end

      private

      def validate_upload(upload_data)
        tempfile = upload_data&.dig(:tempfile)
        tempfile ? Success(tempfile) : Failure(:missing_file)
      end

      def process_image(post_id, tempfile)
        path = "tmp/thumbs/#{post_id}.jpg"
        image = MiniMagick::Image.new(tempfile.path)
        image.resize "150x150"
        image.write path
        puts "OPERATION: Processed image to #{path}"
        Success(path)
      rescue MiniMagick::Error => e
        Failure([:image_processing_failed, e.message])
      end

      def update_post_record(post_id, path)
        repo = App["repos.posts"]
        repo.set_thumbnail(post_id, path)
        Success()
      end
    end
    
    class GenerateExport < Base
      def call(post_id:)
        post = yield find_post(post_id)
        file = yield create_temp_export(post)
        Success(file)
      end
      
      private
      
      def find_post(post_id)
        post = App["repos.posts"].find_by_id(post_id)
        post ? Success(post) : Failure(:not_found)
      end
      
      def create_temp_export(post)
        file = Tempfile.new(["export", ".txt"])
        file.write("POST EXPORT\nTitle: #{post.title}")
        file.rewind
        Success(file)
      end
    end
  end
end

# --- Hanami Actions ---

module App::Actions
  module Users
    class BulkCreate < Hanami::Action
      def handle(req, res)
        result = App::Operations::Users::ImportFromSpreadsheet.call(req.params[:user_data])
        
        result.either(
          ->(users) {
            res.status = 201
            res.body = "Created #{users.count} users."
          },
          ->(error_code) {
            res.status = 422
            res.body = "Failed: #{error_code}"
          }
        )
      end
    end
  end

  module Posts
    class AddImage < Hanami::Action
      def handle(req, res)
        result = App::Operations::Posts::AttachThumbnail.call(
          post_id: req.params[:id],
          upload_data: req.params[:image_file]
        )
        
        result.either(
          ->(path) { res.body = "Thumbnail added: #{path}" },
          ->(error) { res.status = 422; res.body = "Error: #{error}" }
        )
      end
    end
    
    class Export < Hanami::Action
      def handle(req, res)
        result = App::Operations::Posts::GenerateExport.call(post_id: req.params[:id])
        
        result.either(
          ->(file) {
            res.headers["Content-Disposition"] = "attachment; filename=\"export.txt\""
            res.headers["Content-Type"] = "text/plain"
            res.body = file.read
          },
          ->(error) {
            res.status = 404
            res.body = "Not found"
          }
        )
      end
    end
  end
end

# --- Demonstration ---
puts "--- VARIATION 2: Functional/Interactor Pattern ---"

# 1. CSV Upload
puts "\n1. Simulating User CSV Import:"
csv_content = "email,role\nuser1@example.com,USER\nuser2@example.com,ADMIN"
temp_csv = Tempfile.new(['data', '.csv']); temp_csv.write(csv_content); temp_csv.rewind
mock_csv_req = OpenStruct.new(params: { user_data: { tempfile: temp_csv } })
mock_res = OpenStruct.new
App::Actions::Users::BulkCreate.new.handle(mock_csv_req, mock_res)
puts "Response: #{mock_res.body}"

# 2. Image Upload and Resize
puts "\n2. Simulating Post Thumbnail Upload:"
temp_img = Tempfile.new(['pic', '.png']);
mock_img_req = OpenStruct.new(params: { id: SecureRandom.uuid, image_file: { tempfile: temp_img } })
# Mock MiniMagick
module MiniMagick; class Image; def initialize(path); end; def resize(size); self; end; def write(path); end; end; end
`mkdir -p tmp/thumbs`
App::Actions::Posts::AddImage.new.handle(mock_img_req, mock_res)
puts "Response: #{mock_res.body}"

# 3. File Download
puts "\n3. Simulating Post Export Download:"
mock_dl_req = OpenStruct.new(params: { id: SecureRandom.uuid })
mock_dl_res = OpenStruct.new(headers: {}, body: nil)
App::Actions::Posts::Export.new.handle(mock_dl_req, mock_dl_res)
puts "Response Headers: #{mock_dl_res.headers}"
puts "Response Body: #{mock_dl_res.body}"
`rm -rf tmp`