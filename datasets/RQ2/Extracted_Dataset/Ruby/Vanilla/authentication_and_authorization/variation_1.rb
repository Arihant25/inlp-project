# Variation 1: Procedural/Functional Style
# This approach uses modules to group related functions, promoting a clear
# separation of concerns without heavy reliance on object instantiation.
# It's straightforward and easy to follow for developers who prefer a functional paradigm.

require 'openssl'
require 'digest'
require 'base64'
require 'json'
require 'securerandom'
require 'time'

# --- Constants and Configuration ---
JWT_SECRET = 'a_very_secret_key_for_hmac_sha256'
JWT_ALGO = 'HS256'
PASSWORD_SALT_BYTES = 16
PASSWORD_HASH_ITERATIONS = 20000

# --- Mock Database ---
module MockDB
  USERS = {}
  POSTS = {}

  def self.seed
    admin_id = SecureRandom.uuid
    user_id = SecureRandom.uuid

    admin_salt = OpenSSL::Random.random_bytes(PASSWORD_SALT_BYTES)
    admin_hash = PasswordUtils.hash_password('admin123', admin_salt)

    user_salt = OpenSSL::Random.random_bytes(PASSWORD_SALT_BYTES)
    user_hash = PasswordUtils.hash_password('user123', user_salt)

    USERS[admin_id] = {
      id: admin_id,
      email: 'admin@example.com',
      password_hash: "#{Base64.strict_encode64(admin_salt)}:#{admin_hash}",
      role: 'ADMIN',
      is_active: true,
      created_at: Time.now.utc
    }
    USERS[user_id] = {
      id: user_id,
      email: 'user@example.com',
      password_hash: "#{Base64.strict_encode64(user_salt)}:#{user_hash}",
      role: 'USER',
      is_active: true,
      created_at: Time.now.utc
    }

    POSTS[SecureRandom.uuid] = {
      id: SecureRandom.uuid,
      user_id: user_id,
      title: 'User Post',
      content: 'This is a post by a regular user.',
      status: 'PUBLISHED'
    }
    POSTS[SecureRandom.uuid] = {
      id: SecureRandom.uuid,
      user_id: admin_id,
      title: 'Admin Post',
      content: 'This is a post by an admin.',
      status: 'PUBLISHED'
    }
  end

  def self.find_user_by_email(email)
    USERS.values.find { |u| u[:email] == email }
  end
end

# --- Password Hashing Utilities ---
module PasswordUtils
  def self.hash_password(password, salt)
    digest = OpenSSL::Digest.new('sha256')
    hashed = OpenSSL::PKCS5.pbkdf2_hmac(password, salt, PASSWORD_HASH_ITERATIONS, digest.digest_length, digest)
    hashed.unpack1('H*')
  end

  def self.verify_password(password, stored_hash_with_salt)
    salt_b64, stored_hash = stored_hash_with_salt.split(':')
    return false unless salt_b64 && stored_hash

    salt = Base64.strict_decode64(salt_b64)
    hash_to_check = hash_password(password, salt)
    
    # Constant-time comparison
    OpenSSL.secure_compare(hash_to_check, stored_hash)
  end
end

# --- JWT Generation and Validation ---
module JwtHandler
  def self.urlsafe_encode64(data)
    Base64.urlsafe_encode64(data, padding: false)
  end

  def self.urlsafe_decode64(encoded_data)
    Base64.urlsafe_decode64(encoded_data)
  end

  def self.generate_token(user_id, user_role)
    header = { alg: JWT_ALGO, typ: 'JWT' }
    payload = {
      sub: user_id,
      role: user_role,
      iat: Time.now.to_i,
      exp: Time.now.to_i + 3600 # Expires in 1 hour
    }

    encoded_header = urlsafe_encode64(JSON.generate(header))
    encoded_payload = urlsafe_encode64(JSON.generate(payload))
    
    signature_input = "#{encoded_header}.#{encoded_payload}"
    signature = OpenSSL::HMAC.digest('sha256', JWT_SECRET, signature_input)
    encoded_signature = urlsafe_encode64(signature)

    "#{signature_input}.#{encoded_signature}"
  end

  def self.validate_token(token)
    return nil unless token
    parts = token.split('.')
    return nil if parts.length != 3

    encoded_header, encoded_payload, encoded_signature = parts
    
    begin
      signature_input = "#{encoded_header}.#{encoded_payload}"
      expected_signature = OpenSSL::HMAC.digest('sha256', JWT_SECRET, signature_input)
      decoded_signature = urlsafe_decode64(encoded_signature)

      return nil unless OpenSSL.secure_compare(expected_signature, decoded_signature)

      payload = JSON.parse(urlsafe_decode64(encoded_payload), symbolize_names: true)
      return nil if Time.now.to_i > payload[:exp]

      payload
    rescue
      nil
    end
  end
end

# --- Session and Authorization ---
module SessionManager
  @sessions = {}

  def self.create_session(user)
    session_id = SecureRandom.hex(16)
    @sessions[session_id] = { user_id: user[:id], role: user[:role], created_at: Time.now }
    session_id
  end

  def self.get_session(session_id)
    @sessions[session_id]
  end
end

module AccessControl
  def self.authorize(context, required_role)
    user_role = context[:role]
    
    # Simple hierarchy: ADMIN can do anything a USER can do.
    return true if user_role == 'ADMIN'
    return true if user_role == required_role
    
    false
  end
end

# --- OAuth2 Client Simulation ---
module OAuthClient
  def self.initiate_flow(provider_url)
    puts "[OAuth] Redirecting to #{provider_url} for authorization..."
    # In a real app, this would be an HTTP redirect.
    "#{provider_url}?client_id=our_app&redirect_uri=http://localhost/callback"
  end

  def self.handle_callback(code)
    puts "[OAuth] Received authorization code: #{code}. Exchanging for a token..."
    # Simulate token exchange
    {
      access_token: "mock_oauth_token_for_#{code}",
      user_info: { email: 'oauth_user@provider.com', name: 'OAuth User' }
    }
  end
end

# --- Main Execution Block ---
if __FILE__ == $0
  MockDB.seed

  puts "--- 1. User Login and JWT Generation ---"
  user_to_login = MockDB.find_user_by_email('user@example.com')
  is_valid = PasswordUtils.verify_password('user123', user_to_login[:password_hash])
  
  if is_valid
    puts "Login successful for #{user_to_login[:email]}."
    jwt = JwtHandler.generate_token(user_to_login[:id], user_to_login[:role])
    puts "Generated JWT: #{jwt[0..30]}..."
    
    # --- 2. JWT Validation and RBAC ---
    puts "\n--- 2. Validating JWT and Performing Role-Based Access Control ---"
    decoded_payload = JwtHandler.validate_token(jwt)
    if decoded_payload
      puts "JWT is valid. Payload: #{decoded_payload}"
      
      puts "Attempting to access a USER resource..."
      if AccessControl.authorize(decoded_payload, 'USER')
        puts "Access GRANTED."
      else
        puts "Access DENIED."
      end

      puts "Attempting to access an ADMIN resource..."
      if AccessControl.authorize(decoded_payload, 'ADMIN')
        puts "Access GRANTED."
      else
        puts "Access DENIED."
      end
    else
      puts "JWT is invalid."
    end
  else
    puts "Login failed."
  end

  puts "\n--- 3. Admin Login and Session Management ---"
  admin_to_login = MockDB.find_user_by_email('admin@example.com')
  is_admin_valid = PasswordUtils.verify_password('admin123', admin_to_login[:password_hash])
  if is_admin_valid
    session_id = SessionManager.create_session(admin_to_login)
    puts "Admin login successful. Session created: #{session_id}"
    session_data = SessionManager.get_session(session_id)
    puts "Retrieved session data: #{session_data}"

    puts "Admin attempting to access an ADMIN resource..."
    if AccessControl.authorize(session_data, 'ADMIN')
      puts "Access GRANTED."
    else
      puts "Access DENIED."
    end
  end

  puts "\n--- 4. OAuth2 Client Flow Simulation ---"
  OAuthClient.initiate_flow("https://oauth.provider.com/auth")
  token_response = OAuthClient.handle_callback("mock_auth_code_12345")
  puts "OAuth flow complete. Received token and user info: #{token_response}"
end