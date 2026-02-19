# Variation 2: Classic Object-Oriented (OOP) Style
# This variation models the system using classes and objects. Each component
# (User, AuthService, JwtService) is a distinct class with its own responsibilities.
# This approach is highly structured, testable, and familiar to developers
# from object-oriented backgrounds.

require 'openssl'
require 'digest'
require 'base64'
require 'json'
require 'securerandom'
require 'time'

# --- Domain Models ---
User = Struct.new(:id, :email, :password_hash, :role, :is_active, :created_at)
Post = Struct.new(:id, :user_id, :title, :content, :status)

# --- In-Memory Data Store ---
class InMemoryStore
  attr_reader :users, :posts

  def initialize
    @users = {}
    @posts = {}
  end

  def find_user_by_email(email)
    @users.values.find { |u| u.email == email }
  end

  def find_user(id)
    @users[id]
  end
end

# --- Cryptography Service ---
class CryptoService
  ITERATIONS = 20000
  SALT_BYTES = 16
  DIGEST = OpenSSL::Digest.new('sha256')

  def hash_password(password)
    salt = OpenSSL::Random.random_bytes(SALT_BYTES)
    hash = OpenSSL::PKCS5.pbkdf2_hmac(password, salt, ITERATIONS, DIGEST.digest_length, DIGEST)
    "#{Base64.strict_encode64(salt)}:$#{hash.unpack1('H*')}"
  end

  def verify_password(password, full_hash)
    salt_b64, stored_hash_with_prefix = full_hash.split(':')
    return false unless salt_b64 && stored_hash_with_prefix
    
    stored_hash = stored_hash_with_prefix.delete_prefix('$')
    salt = Base64.strict_decode64(salt_b64)
    
    hash_to_check = OpenSSL::PKCS5.pbkdf2_hmac(password, salt, ITERATIONS, DIGEST.digest_length, DIGEST)
    
    OpenSSL.secure_compare(hash_to_check.unpack1('H*'), stored_hash)
  end
end

# --- JWT Service ---
class JwtService
  def initialize(secret_key, algorithm = 'HS256')
    @secret_key = secret_key
    @algorithm = algorithm
  end

  def encode(payload)
    header = { alg: @algorithm, typ: 'JWT' }
    
    encoded_header = urlsafe_b64_encode(JSON.generate(header))
    encoded_payload = urlsafe_b64_encode(JSON.generate(payload))
    
    signature_base = "#{encoded_header}.#{encoded_payload}"
    signature = OpenSSL::HMAC.digest('sha256', @secret_key, signature_base)
    encoded_signature = urlsafe_b64_encode(signature)
    
    "#{signature_base}.#{encoded_signature}"
  end

  def decode(token)
    header_segment, payload_segment, crypto_segment = token.split('.')
    return nil unless header_segment && payload_segment && crypto_segment

    signature_base = "#{header_segment}.#{payload_segment}"
    expected_signature = OpenSSL::HMAC.digest('sha256', @secret_key, signature_base)
    decoded_signature = urlsafe_b64_decode(crypto_segment)

    return nil unless OpenSSL.secure_compare(expected_signature, decoded_signature)

    payload = JSON.parse(urlsafe_b64_decode(payload_segment), symbolize_names: true)
    return nil if Time.now.to_i > payload[:exp]
    
    payload
  rescue
    nil
  end

  private

  def urlsafe_b64_encode(str)
    Base64.urlsafe_encode64(str, padding: false)
  end

  def urlsafe_b64_decode(str)
    Base64.urlsafe_decode64(str)
  end
end

# --- Authentication Service ---
class AuthService
  def initialize(db, crypto_service, jwt_service)
    @db = db
    @crypto = crypto_service
    @jwt = jwt_service
  end

  def login(email, password)
    user = @db.find_user_by_email(email)
    return nil unless user && user.is_active
    
    if @crypto.verify_password(password, user.password_hash)
      payload = { sub: user.id, role: user.role, iat: Time.now.to_i, exp: Time.now.to_i + 3600 }
      @jwt.encode(payload)
    else
      nil
    end
  end
end

# --- Authorization Service ---
class AuthorizationService
  def self.can?(user, action, resource)
    # In a real app, this would be more complex, checking resource ownership etc.
    case action
    when :delete_post
      user.role == 'ADMIN'
    when :publish_post
      user.role == 'ADMIN' || (user.role == 'USER' && resource.user_id == user.id)
    else
      false
    end
  end
end

# --- Session Management ---
class SessionManager
  def initialize
    @sessions = {} # session_id -> { user_id: ..., created_at: ... }
  end

  def start_session(user_id)
    session_id = SecureRandom.hex(20)
    @sessions[session_id] = { user_id: user_id, created_at: Time.now }
    session_id
  end

  def get_user_id_from_session(session_id)
    session = @sessions[session_id]
    # In a real app, check for session expiry
    session ? session[:user_id] : nil
  end
end

# --- OAuth2 Client Simulation ---
class MockOAuth2Client
  def initialize(client_id, client_secret)
    @client_id = client_id
    @client_secret = client_secret
  end

  def get_authorization_url(redirect_uri)
    "https://some-provider.com/auth?response_type=code&client_id=#{@client_id}&redirect_uri=#{redirect_uri}"
  end

  def exchange_code_for_token(code)
    puts "[OAuth] Exchanging code '#{code}' for an access token..."
    # This would be an HTTP POST request in a real scenario
    {
      access_token: SecureRandom.hex(32),
      token_type: 'Bearer',
      expires_in: 3600,
      user_profile: { email: 'oauth.user@example.com', id: 'provider-123' }
    }
  end
end

# --- Main Execution Block ---
if __FILE__ == $0
  # --- Setup ---
  db = InMemoryStore.new
  crypto = CryptoService.new
  jwt_service = JwtService.new('my_super_secret_for_jwt')
  auth_service = AuthService.new(db, crypto, jwt_service)
  session_manager = SessionManager.new

  # --- Seeding Data ---
  admin_id = SecureRandom.uuid
  user_id = SecureRandom.uuid
  db.users[admin_id] = User.new(admin_id, 'admin@example.com', crypto.hash_password('adminpass'), 'ADMIN', true, Time.now)
  db.users[user_id] = User.new(user_id, 'user@example.com', crypto.hash_password('userpass'), 'USER', true, Time.now)
  
  post_id = SecureRandom.uuid
  db.posts[post_id] = Post.new(post_id, user_id, 'A User Post', 'Content here', 'DRAFT')

  # --- 1. Authentication Flow ---
  puts "--- 1. Authentication Flow ---"
  puts "Attempting login for user@example.com..."
  token = auth_service.login('user@example.com', 'userpass')
  if token
    puts "Login successful. Token received: #{token.slice(0, 30)}..."
  else
    puts "Login failed."
  end

  # --- 2. Authorization Flow ---
  puts "\n--- 2. Authorization Flow ---"
  if token
    payload = jwt_service.decode(token)
    current_user = db.find_user(payload[:sub])
    post_to_manage = db.posts.values.first

    puts "User '#{current_user.email}' (role: #{current_user.role}) trying to publish their own post."
    can_publish = AuthorizationService.can?(current_user, :publish_post, post_to_manage)
    puts "Result: #{can_publish ? 'ALLOWED' : 'DENIED'}"

    puts "User '#{current_user.email}' (role: #{current_user.role}) trying to delete a post."
    can_delete = AuthorizationService.can?(current_user, :delete_post, post_to_manage)
    puts "Result: #{can_delete ? 'ALLOWED' : 'DENIED'}"
  end

  # --- 3. Session Management Flow ---
  puts "\n--- 3. Session Management Flow ---"
  admin_user = db.find_user_by_email('admin@example.com')
  session_id = session_manager.start_session(admin_user.id)
  puts "Admin session started. Session ID: #{session_id}"
  retrieved_user_id = session_manager.get_user_id_from_session(session_id)
  puts "User ID from session: #{retrieved_user_id}"
  
  # --- 4. OAuth2 Flow ---
  puts "\n--- 4. OAuth2 Flow Simulation ---"
  oauth_client = MockOAuth2Client.new('client123', 'secret456')
  auth_url = oauth_client.get_authorization_url('http://localhost/callback')
  puts "Please visit: #{auth_url}"
  # Simulate user authorizing and provider sending back a code
  mock_code = 'authorization_code_from_provider'
  token_data = oauth_client.exchange_code_for_token(mock_code)
  puts "OAuth token received: #{token_data.inspect}"
end