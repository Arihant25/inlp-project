# Variation 3: Service-Oriented/Singleton Pattern
# This variation uses modules as singletons to manage state and logic.
# Methods are defined on the module itself (e.g., `Security.hash_pwd`).
# This avoids the overhead of object instantiation for services and is a common
# pattern in Ruby for utility modules or centralized managers.

require 'openssl'
require 'digest'
require 'base64'
require 'json'
require 'securerandom'
require 'time'

# --- Centralized Configuration ---
module AppConfig
  JWT_SECRET = ENV.fetch('JWT_SECRET', 'a_default_secret_key_for_dev')
  JWT_ALGO = 'HS256'
  JWT_EXPIRY_SECONDS = 3600 # 1 hour
end

# --- Data Store Singleton ---
module DataStore
  @users = {}
  @posts = {}

  def self.init
    admin_id = SecureRandom.uuid
    user_id = SecureRandom.uuid

    @users[admin_id] = { id: admin_id, email: 'admin@example.com', password_hash: Security.hash_pwd('p@ssw0rdAdm1n'), role: :ADMIN, is_active: true }
    @users[user_id] = { id: user_id, email: 'user@example.com', password_hash: Security.hash_pwd('p@ssw0rdUser'), role: :USER, is_active: true }
    
    post_id = SecureRandom.uuid
    @posts[post_id] = { id: post_id, user_id: user_id, title: 'My First Post', content: '...', status: :DRAFT }
  end

  def self.find_user_by(field, value)
    @users.values.find { |u| u[field] == value }
  end

  def self.find_post(id)
    @posts[id]
  end
end

# --- Security Singleton ---
module Security
  ITERATIONS = 25000
  DIGEST = 'sha256'

  def self.hash_pwd(password)
    salt = OpenSSL::Random.random_bytes(16)
    digest = OpenSSL::Digest.new(DIGEST)
    hash = OpenSSL::PKCS5.pbkdf2_hmac(password, salt, ITERATIONS, digest.digest_length, digest)
    "pbkdf2_sha256$#{ITERATIONS}$#{Base64.strict_encode64(salt)}$#{Base64.strict_encode64(hash)}"
  end

  def self.verify_pwd(password, stored_hash)
    parts = stored_hash.split('$')
    return false if parts.length != 4
    
    _algo, iterations, salt_b64, hash_b64 = parts
    salt = Base64.strict_decode64(salt_b64)
    stored_hash_bytes = Base64.strict_decode64(hash_b64)
    digest = OpenSSL::Digest.new(DIGEST)
    
    hash_to_check = OpenSSL::PKCS5.pbkdf2_hmac(password, salt, iterations.to_i, digest.digest_length, digest)
    
    OpenSSL.secure_compare(hash_to_check, stored_hash_bytes)
  end

  def self.generate_jwt(user)
    header = { alg: AppConfig::JWT_ALGO, typ: 'JWT' }
    payload = {
      uid: user[:id],
      rol: user[:role],
      exp: Time.now.to_i + AppConfig::JWT_EXPIRY_SECONDS
    }
    
    b64_header = Base64.urlsafe_encode64(header.to_json, padding: false)
    b64_payload = Base64.urlsafe_encode64(payload.to_json, padding: false)
    
    signature_data = "#{b64_header}.#{b64_payload}"
    hmac = OpenSSL::HMAC.digest(DIGEST, AppConfig::JWT_SECRET, signature_data)
    b64_signature = Base64.urlsafe_encode64(hmac, padding: false)
    
    "#{signature_data}.#{b64_signature}"
  end

  def self.decode_jwt(token)
    head_b64, payload_b64, sig_b64 = token.split('.')
    return { error: 'Invalid token structure' } unless sig_b64

    signature_data = "#{head_b64}.#{payload_b64}"
    hmac = OpenSSL::HMAC.digest(DIGEST, AppConfig::JWT_SECRET, signature_data)
    
    return { error: 'Invalid signature' } unless OpenSSL.secure_compare(
      Base64.urlsafe_decode64(sig_b64), hmac
    )

    payload = JSON.parse(Base64.urlsafe_decode64(payload_b64), symbolize_names: true)
    return { error: 'Token expired' } if Time.now.to_i > payload[:exp]

    { payload: payload }
  rescue => e
    { error: "Token decode error: #{e.message}" }
  end
end

# --- Session Manager Singleton ---
module SessionManager
  @active_sessions = {} # session_token -> { user_id: ..., expires_at: ... }

  def self.create(user_id)
    token = SecureRandom.urlsafe_base64(32)
    @active_sessions[token] = { user_id: user_id, expires_at: Time.now + (24 * 3600) }
    token
  end

  def self.validate(token)
    session = @active_sessions[token]
    return nil unless session
    return nil if Time.now > session[:expires_at]
    session[:user_id]
  end
end

# --- Authorization Logic ---
module Authorization
  def self.permit?(user, required_role)
    return false unless user && user[:role]
    # ADMIN has all permissions
    return true if user[:role] == :ADMIN
    user[:role] == required_role
  end
end

# --- OAuth2 Client Simulation ---
module OAuth2Client
  def self.start_auth_dance(provider)
    puts "[OAuth] Initiating flow with #{provider}."
    # Simulate redirect
    "https://#{provider}/oauth/authorize?client_id=app-xyz"
  end

  def self.finish_auth_dance(code)
    puts "[OAuth] Got code #{code}, exchanging for user data."
    # Simulate API call
    { email: 'from.oauth@provider.net', provider_id: 'prov_12345' }
  end
end

# --- Main Execution Block ---
if __FILE__ == $0
  DataStore.init

  puts "--- 1. Login with Password & JWT ---"
  user = DataStore.find_user_by(:email, 'user@example.com')
  if user && Security.verify_pwd('p@ssw0rdUser', user[:password_hash])
    puts "User login successful."
    token = Security.generate_jwt(user)
    puts "JWT generated: #{token.slice(0, 40)}..."

    puts "\n--- 2. Verifying JWT and RBAC ---"
    verification_result = Security.decode_jwt(token)
    if verification_result[:payload]
      puts "JWT is valid. Payload: #{verification_result[:payload]}"
      current_user = DataStore.find_user_by(:id, verification_result[:payload][:uid])
      
      puts "Checking USER-level access: #{Authorization.permit?(current_user, :USER)}"
      puts "Checking ADMIN-level access: #{Authorization.permit?(current_user, :ADMIN)}"
    else
      puts "JWT validation failed: #{verification_result[:error]}"
    end
  else
    puts "User login failed."
  end

  puts "\n--- 3. Admin Session Management ---"
  admin = DataStore.find_user_by(:email, 'admin@example.com')
  if admin && Security.verify_pwd('p@ssw0rdAdm1n', admin[:password_hash])
    session_token = SessionManager.create(admin[:id])
    puts "Admin session created: #{session_token}"
    
    user_id_from_session = SessionManager.validate(session_token)
    if user_id_from_session
      puts "Session is valid for user ID: #{user_id_from_session}"
      admin_from_session = DataStore.find_user_by(:id, user_id_from_session)
      puts "Admin checking ADMIN-level access: #{Authorization.permit?(admin_from_session, :ADMIN)}"
    end
  end

  puts "\n--- 4. OAuth2 Simulation ---"
  OAuth2Client.start_auth_dance('google')
  oauth_user = OAuth2Client.finish_auth_dance('mock_code_abc')
  puts "OAuth user data retrieved: #{oauth_user}"
end