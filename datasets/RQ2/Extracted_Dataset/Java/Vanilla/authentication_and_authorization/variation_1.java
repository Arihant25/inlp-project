import javax.crypto.Mac;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// --- Main Application Class ---
public class ClassicOOPAuthSystem {

    public static void main(String[] args) {
        // 1. Setup
        UserRepository userRepository = new InMemoryUserRepository();
        PasswordUtils passwordUtils = new PasswordUtils();
        UserService userService = new UserService(userRepository, passwordUtils);
        JwtService jwtService = new JwtService("a-very-secure-secret-key-that-is-long-enough");
        SessionManager sessionManager = new SessionManager();
        AuthService authService = new AuthService(userService, jwtService, sessionManager);
        PostRepository postRepository = new InMemoryPostRepository();
        PostService postService = new PostService(postRepository);
        OAuth2Client oAuth2Client = new OAuth2Client("client_id_123", "client_secret_456", "https://example.com/callback");

        // Create mock users
        User adminUser = userService.createUser("admin@example.com", "AdminPassword123", Role.ADMIN);
        User regularUser = userService.createUser("user@example.com", "UserPassword123", Role.USER);
        System.out.println("--- Users Created ---");
        System.out.println("Admin: " + adminUser.getEmail());
        System.out.println("User: " + regularUser.getEmail());
        System.out.println();

        // 2. Authentication
        System.out.println("--- Authentication ---");
        // Successful login
        AuthService.AuthResponse adminAuth = authService.login("admin@example.com", "AdminPassword123");
        System.out.println("Admin login successful. Session ID: " + adminAuth.getSessionId());
        // Failed login
        try {
            authService.login("user@example.com", "WrongPassword");
        } catch (IllegalArgumentException e) {
            System.out.println("User login failed as expected: " + e.getMessage());
        }
        System.out.println();

        // 3. JWT Validation
        System.out.println("--- JWT Validation ---");
        String adminToken = adminAuth.getJwtToken();
        System.out.println("Admin JWT: " + adminToken.substring(0, 30) + "...");
        boolean isValid = jwtService.validateToken(adminToken, adminUser);
        System.out.println("Is admin token valid? " + isValid);
        String tamperedToken = adminToken.substring(0, adminToken.length() - 5) + "abcde";
        System.out.println("Is tampered token valid? " + !jwtService.validateToken(tamperedToken, adminUser));
        System.out.println();

        // 4. Session Management
        System.out.println("--- Session Management ---");
        User sessionUser = sessionManager.getUserBySessionId(adminAuth.getSessionId());
        System.out.println("User retrieved from session: " + (sessionUser != null ? sessionUser.getEmail() : "null"));
        authService.logout(adminAuth.getSessionId());
        System.out.println("Admin logged out.");
        sessionUser = sessionManager.getUserBySessionId(adminAuth.getSessionId());
        System.out.println("User retrieved after logout: " + (sessionUser != null ? sessionUser.getEmail() : "null"));
        System.out.println();

        // 5. Role-Based Access Control (RBAC)
        System.out.println("--- Role-Based Access Control ---");
        AuthService.AuthResponse userAuth = authService.login("user@example.com", "UserPassword123");
        User retrievedAdmin = userService.findByEmail("admin@example.com");
        User retrievedUser = userService.findByEmail("user@example.com");

        // Admin tries to publish a post (should succeed)
        try {
            AuthorizationHandler.authorize(retrievedAdmin, Role.ADMIN);
            Post newPost = postService.createPost(retrievedAdmin.getId(), "Admin Post", "Content by admin.");
            postService.publishPost(newPost.getId());
            System.out.println("Admin successfully created and published a post.");
        } catch (SecurityException e) {
            System.out.println("Admin action failed unexpectedly: " + e.getMessage());
        }

        // User tries to publish a post (should fail)
        try {
            AuthorizationHandler.authorize(retrievedUser, Role.ADMIN);
            System.out.println("User action succeeded unexpectedly.");
        } catch (SecurityException e) {
            System.out.println("User action failed as expected: " + e.getMessage());
        }
        System.out.println();
        
        // 6. OAuth2 Client Simulation
        System.out.println("--- OAuth2 Client Simulation ---");
        String authUrl = oAuth2Client.getAuthorizationUrl("read write");
        System.out.println("1. Redirect user to: " + authUrl);
        // Simulate user authenticating and getting a code
        String authCode = "mock_auth_code_from_provider";
        System.out.println("2. User authenticates, provider returns code: " + authCode);
        String accessToken = oAuth2Client.exchangeCodeForToken(authCode);
        System.out.println("3. Exchanged code for access token: " + accessToken);
    }
}

// --- Domain Model ---
enum Role { ADMIN, USER }
enum PostStatus { DRAFT, PUBLISHED }

class User {
    private UUID id;
    private String email;
    private String passwordHash;
    private Role role;
    private boolean isActive;
    private Timestamp createdAt;

    public User(String email, String passwordHash, Role role) {
        this.id = UUID.randomUUID();
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.isActive = true;
        this.createdAt = Timestamp.from(Instant.now());
    }

    // Getters
    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public boolean isActive() { return isActive; }
}

class Post {
    private UUID id;
    private UUID userId;
    private String title;
    private String content;
    private PostStatus status;

    public Post(UUID userId, String title, String content) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.title = title;
        this.content = content;
        this.status = PostStatus.DRAFT;
    }
    
    // Getters and Setters
    public UUID getId() { return id; }
    public void setStatus(PostStatus status) { this.status = status; }
}

// --- Repositories (Data Layer) ---
interface UserRepository {
    User save(User user);
    User findByEmail(String email);
}

class InMemoryUserRepository implements UserRepository {
    private final Map<String, User> userStore = new ConcurrentHashMap<>();

    @Override
    public User save(User user) {
        userStore.put(user.getEmail(), user);
        return user;
    }

    @Override
    public User findByEmail(String email) {
        return userStore.get(email);
    }
}

interface PostRepository {
    Post save(Post post);
    Post findById(UUID id);
}

class InMemoryPostRepository implements PostRepository {
    private final Map<UUID, Post> postStore = new ConcurrentHashMap<>();

    @Override
    public Post save(Post post) {
        postStore.put(post.getId(), post);
        return post;
    }

    @Override
    public Post findById(UUID id) {
        return postStore.get(id);
    }
}

// --- Services (Business Logic) ---
class UserService {
    private final UserRepository userRepository;
    private final PasswordUtils passwordUtils;

    public UserService(UserRepository userRepository, PasswordUtils passwordUtils) {
        this.userRepository = userRepository;
        this.passwordUtils = passwordUtils;
    }

    public User createUser(String email, String password, Role role) {
        if (userRepository.findByEmail(email) != null) {
            throw new IllegalArgumentException("User with this email already exists.");
        }
        String hashedPassword = passwordUtils.hashPassword(password);
        User newUser = new User(email, hashedPassword, role);
        return userRepository.save(newUser);
    }

    public User findByEmail(String email) {
        return userRepository.findByEmail(email);
    }
}

class AuthService {
    private final UserService userService;
    private final JwtService jwtService;
    private final SessionManager sessionManager;
    private final PasswordUtils passwordUtils = new PasswordUtils();

    public AuthService(UserService userService, JwtService jwtService, SessionManager sessionManager) {
        this.userService = userService;
        this.jwtService = jwtService;
        this.sessionManager = sessionManager;
    }

    public AuthResponse login(String email, String password) {
        User user = userService.findByEmail(email);
        if (user == null || !user.isActive() || !passwordUtils.verifyPassword(password, user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid credentials or user inactive.");
        }
        String jwtToken = jwtService.generateToken(user);
        String sessionId = sessionManager.createSession(user);
        return new AuthResponse(jwtToken, sessionId);
    }

    public void logout(String sessionId) {
        sessionManager.invalidateSession(sessionId);
    }
    
    static class AuthResponse {
        private final String jwtToken;
        private final String sessionId;
        public AuthResponse(String jwtToken, String sessionId) { this.jwtToken = jwtToken; this.sessionId = sessionId; }
        public String getJwtToken() { return jwtToken; }
        public String getSessionId() { return sessionId; }
    }
}

class PostService {
    private final PostRepository postRepository;

    public PostService(PostRepository postRepository) {
        this.postRepository = postRepository;
    }

    public Post createPost(UUID userId, String title, String content) {
        Post post = new Post(userId, title, content);
        return postRepository.save(post);
    }

    public void publishPost(UUID postId) {
        Post post = postRepository.findById(postId);
        if (post != null) {
            post.setStatus(PostStatus.PUBLISHED);
            postRepository.save(post);
        }
    }
}

// --- Security & Utils ---
class PasswordUtils {
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 10000;
    private static final int KEY_LENGTH = 256;
    private static final int SALT_SIZE = 16;

    public String hashPassword(String password) {
        try {
            SecureRandom random = new SecureRandom();
            byte[] salt = new byte[SALT_SIZE];
            random.nextBytes(salt);

            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH);
            SecretKeyFactory skf = SecretKeyFactory.getInstance(ALGORITHM);
            byte[] hash = skf.generateSecret(spec).getEncoded();

            return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("Error hashing password", e);
        }
    }

    public boolean verifyPassword(String password, String storedHash) {
        try {
            String[] parts = storedHash.split(":");
            byte[] salt = Base64.getDecoder().decode(parts[0]);
            byte[] hash = Base64.getDecoder().decode(parts[1]);

            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH);
            SecretKeyFactory skf = SecretKeyFactory.getInstance(ALGORITHM);
            byte[] testHash = skf.generateSecret(spec).getEncoded();

            return Arrays.equals(hash, testHash);
        } catch (Exception e) {
            return false;
        }
    }
}

class JwtService {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private final SecretKeySpec signingKey;

    public JwtService(String secret) {
        this.signingKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    }

    public String generateToken(User user) {
        String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        long expirationTime = Instant.now().getEpochSecond() + 3600; // 1 hour
        String payload = String.format("{\"sub\":\"%s\",\"role\":\"%s\",\"exp\":%d}",
                user.getId().toString(), user.getRole().name(), expirationTime);

        String encodedHeader = Base64.getUrlEncoder().withoutPadding().encodeToString(header.getBytes(StandardCharsets.UTF_8));
        String encodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));

        String signature = createSignature(encodedHeader + "." + encodedPayload);
        return encodedHeader + "." + encodedPayload + "." + signature;
    }

    public boolean validateToken(String token, User user) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return false;

            String signature = createSignature(parts[0] + "." + parts[1]);
            if (!signature.equals(parts[2])) return false;

            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            if (!payloadJson.contains("\"sub\":\"" + user.getId().toString() + "\"")) return false;

            long exp = Long.parseLong(payloadJson.split("\"exp\":")[1].split("}")[0]);
            return exp > Instant.now().getEpochSecond();
        } catch (Exception e) {
            return false;
        }
    }

    private String createSignature(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(signingKey);
            byte[] signatureBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signatureBytes);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("Failed to create JWT signature", e);
        }
    }
}

class SessionManager {
    private final Map<String, User> activeSessions = new ConcurrentHashMap<>();

    public String createSession(User user) {
        String sessionId = UUID.randomUUID().toString();
        activeSessions.put(sessionId, user);
        return sessionId;
    }

    public User getUserBySessionId(String sessionId) {
        return activeSessions.get(sessionId);
    }

    public void invalidateSession(String sessionId) {
        activeSessions.remove(sessionId);
    }
}

class AuthorizationHandler {
    public static void authorize(User user, Role requiredRole) {
        if (user == null || user.getRole() != requiredRole) {
            throw new SecurityException("Authorization failed. Required role: " + requiredRole);
        }
    }
}

class OAuth2Client {
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;

    public OAuth2Client(String clientId, String clientSecret, String redirectUri) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
    }

    public String getAuthorizationUrl(String scope) {
        return String.format("https://oauth.provider.com/auth?response_type=code&client_id=%s&redirect_uri=%s&scope=%s",
                clientId, redirectUri, scope.replace(" ", "+"));
    }

    public String exchangeCodeForToken(String code) {
        // In a real app, this would be an HTTP POST request to the token endpoint.
        System.out.printf("Simulating token exchange for code '%s' with client_id '%s'\n", code, clientId);
        // Mocked response from the provider
        return "mock_access_token_" + UUID.randomUUID().toString();
    }
}