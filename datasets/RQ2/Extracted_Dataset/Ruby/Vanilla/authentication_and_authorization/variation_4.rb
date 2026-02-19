# Variation 4: Policy-Based/Concern-Driven Design
# This advanced pattern separates authorization logic into dedicated "Policy"
# classes. Instead of checking roles in controllers or services, we ask a policy
# object if an action is allowed (e.g., `PostPolicy.new(user, post).update?`).
# This makes authorization rules explicit, reusable, and easy to test.

require 'openssl'
require 'digest'
require 'base64'
require 'json'
require 'securerandom'
require 'time'

# --- Core Concerns (Modules) ---
module CryptoConcern
  # A module for cryptographic primitives
  module Password
    ITERATIONS = 20_000
    DIGEST = OpenSSL::Digest::SHA256.new

    def self.create_hash(password)
      salt = OpenSSL::Random.random_bytes(16)
      hash = OpenSSL::PKCS5.pbkdf2_hmac(password, salt, ITERATIONS, DIGEST.digest_length, DIGEST)
      "#{salt.unpack1('H*')}.#{hash.unpack1('H*')}"
    end

    def self.verify(password, stored_hash)
      salt_hex, hash_hex = stored_hash.split('.')
      return false unless salt_hex && hash_hex
      salt = [salt_hex].pack('H*')
      
      test_hash = OpenSSL::PKCS5.pbkdf2_hmac(password, salt, ITERATIONS, DIGEST.digest_length, DIGEST)
      
      OpenSSL.secure_compare(test_hash.unpack1('H*'), hash_hex)
    end
  end

  module JsonWebToken
    SECRET = 'change_this_secret_in_production'

    def self.issue(payload)
      header = { typ: 'JWT', alg: 'HS256' }.to_json
      payload[:exp] ||= Time.now.to_i + 3600 # Default 1 hour expiry
      
      encoded_header = Base64.urlsafe_encode64(header, padding: false)
      encoded_payload = Base64.urlsafe_encode64(payload.to_json, padding: false)
      
      signature_base = "#{encoded_header}.#{encoded_payload}"
      signature = OpenSSL::HMAC.digest('SHA256', SECRET, signature_base)
      encoded_signature = Base64.urlsafe_encode64(signature, padding: false)
      
      "#{signature_base}.#{encoded_signature}"
    end

    def self.verify(token)
      head_b64, payload_b64, sig_b64 = token.split('.')
      return { error: :invalid_format } unless head_b64 && payload_b64 && sig_b64

      signature_base = "#{head_b64}.#{payload_b64}"
      expected_sig = OpenSSL::HMAC.digest('SHA256', SECRET, signature_base)
      
      unless OpenSSL.secure_compare(Base64.urlsafe_decode64(sig_b64), expected_sig)
        return { error: :invalid_signature }
      end

      payload = JSON.parse(Base64.urlsafe_decode64(payload_b64), symbolize_names: true)
      return { error: :expired } if Time.now.to_i > payload[:exp]

      { payload: payload }
    rescue
      { error: :decode_error }
    end
  end
end

# --- Data Models and Repository ---
class User
  attr_reader :id, :email, :password_hash, :role, :is_active

  def initialize(id:, email:, password_hash:, role:, is_active:)
    @id, @email, @password_hash, @role, @is_active = id, email, password_hash, role.to_sym, is_active
  end

  def admin?
    @role == :ADMIN
  end
end

class Post
  attr_reader :id, :user_id, :title, :status

  def initialize(id:, user_id:, title:, status:)
    @id, @user_id, @title, @status = id, user_id, title, status.to_sym
  end
end

class Repository
  @users = {}
  @posts = {}

  def self.seed
    admin = User.new(id: SecureRandom.uuid, email: 'admin@example.com', password_hash: CryptoConcern::Password.create_hash('secure_admin'), role: 'ADMIN', is_active: true)
    user = User.new(id: SecureRandom.uuid, email: 'user@example.com', password_hash: CryptoConcern::Password.create_hash('secure_user'), role: 'USER', is_active: true)
    @users[admin.id] = admin
    @users[user.id] = user

    post = Post.new(id: SecureRandom.uuid, user_id: user.id, title: 'A Post', status: 'DRAFT')
    @posts[post.id] = post
  end

  def self.find_user_by_email(email)
    @users.values.find { |u| u.email == email }
  end
  
  def self.find_post(id)
    @posts[id]
  end
end

# --- Authentication Service ---
class Authenticator
  def self.authenticate(email, password)
    user = Repository.find_user_by_email(email)
    return nil unless user&.is_active
    
    if CryptoConcern::Password.verify(password, user.password_hash)
      CryptoConcern::JsonWebToken.issue({ user_id: user.id, role: user.role })
    else
      nil
    end
  end
end

# --- Authorization Policies ---
class ApplicationPolicy
  attr_reader :user, :record

  def initialize(user, record)
    @user = user
    @record = record
  end
end

class PostPolicy < ApplicationPolicy
  def update?
    # An admin can update any post. A user can only update their own.
    user.admin? || record.user_id == user.id
  end

  def destroy?
    # Only admins can destroy posts.
    user.admin?
  end
end

# --- Session Management (simplified for demonstration) ---
class Session
  @store = {} # In-memory store: token -> user_id

  def self.create(user)
    token = SecureRandom.hex
    @store[token] = user.id
    token
  end

  def self.find_user_id(token)
    @store[token]
  end
end

# --- OAuth2 Client Simulation ---
class OAuth2Handler
  def self.request_authorization(provider)
    puts "[OAuth] Requesting authorization from #{provider}..."
    # Returns a URL for the user to visit
    "https://#{provider}.com/auth?client_id=our_app_id"
  end

  def self.process_callback(code)
    puts "[OAuth] Processing callback with code: #{code}"
    # Simulates exchanging the code for user info
    { email: 'new_user@oauth.com', provider_id: SecureRandom.uuid }
  end
end

# --- Main Execution Block ---
if __FILE__ == $0
  Repository.seed

  puts "--- 1. Authentication ---"
  puts "Logging in as user..."
  user_token = Authenticator.authenticate('user@example.com', 'secure_user')
  puts user_token ? "User login successful." : "User login failed."

  puts "\nLogging in as admin..."
  admin_token = Authenticator.authenticate('admin@example.com', 'secure_admin')
  puts admin_token ? "Admin login successful." : "Admin login failed."

  puts "\n--- 2. Authorization with Policies ---"
  user_data = CryptoConcern::JsonWebToken.verify(user_token)[:payload]
  admin_data = CryptoConcern::JsonWebToken.verify(admin_token)[:payload]

  current_user = Repository.find_user_by_email('user@example.com')
  current_admin = Repository.find_user_by_email('admin@example.com')
  post = Repository.find_post(Repository.instance_variable_get(:@posts).keys.first)

  puts "Post belongs to user: #{post.user_id == current_user.id}"

  # Check policies
  user_post_policy = PostPolicy.new(current_user, post)
  admin_post_policy = PostPolicy.new(current_admin, post)

  puts "Can user update this post? #{user_post_policy.update?}"
  puts "Can user destroy this post? #{user_post_policy.destroy?}"
  puts "Can admin update this post? #{admin_post_policy.update?}"
  puts "Can admin destroy this post? #{admin_post_policy.destroy?}"

  puts "\n--- 3. Session Management ---"
  session_token = Session.create(current_admin)
  puts "Created session for admin: #{session_token}"
  found_user_id = Session.find_user_id(session_token)
  puts "Validated session, found user ID: #{found_user_id}"

  puts "\n--- 4. OAuth2 Simulation ---"
  OAuth2Handler.request_authorization('github')
  user_info = OAuth2Handler.process_callback('mock_oauth_code')
  puts "OAuth flow completed. User info: #{user_info}"
end