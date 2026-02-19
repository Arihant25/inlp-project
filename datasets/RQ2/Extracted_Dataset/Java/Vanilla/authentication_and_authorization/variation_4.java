import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class ModernJavaAuthSystem {

    // --- Domain Model (Using Records) ---
    enum Role { ADMIN, USER }
    enum PostStatus { DRAFT, PUBLISHED }

    record User(UUID id, String email, String passwordHash, Role role, boolean isActive, Timestamp createdAt) {
        public User(String email, String passwordHash, Role role) {
            this(UUID.randomUUID(), email, passwordHash, role, true, Timestamp.from(Instant.now()));
        }
    }

    record Post(UUID id, UUID userId, String title, String content, PostStatus status) {
        public Post(UUID userId, String title, String content) {
            this(UUID.randomUUID(), userId, title, content, PostStatus.DRAFT);
        }
        public Post asPublished() {
            return new Post(this.id, this.userId, this.title, this.content, PostStatus.PUBLISHED);
        }
    }

    // --- Data Transfer Objects (Using Records) ---
    record LoginCredentials(String email, String password) {}
    record AuthResponse(String jwt, String sessionId) {}
    record OAuth2Config(String clientId, String clientSecret, String redirectUri) {}

    // --- Infrastructure Layer ---
    static class InMemoryStore {
        private final Map<UUID, User> usersById = new ConcurrentHashMap<>();
        private final Map<String, User> usersByEmail = new ConcurrentHashMap<>();
        private final Map<UUID, Post> posts = new ConcurrentHashMap<>();
        private final Map<String, User> sessions = new ConcurrentHashMap<>();

        public void saveUser(User user) {
            usersById.put(user.id(), user);
            usersByEmail.put(user.email(), user);
        }
        public Optional<User> findUserByEmail(String email) {
            return Optional.ofNullable(usersByEmail.get(email));
        }
        public void savePost(Post post) {
            posts.put(post.id(), post);
        }
        public String createSession(User user) {
            String sid = UUID.randomUUID().toString();
            sessions.put(sid, user);
            return sid;
        }
        public void endSession(String sessionId) {
            sessions.remove(sessionId);
        }
    }

    // --- Security Utilities ---
    static class PasswordManager {
        private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";
        private static final int SALT_LENGTH = 16;
        private static final int ITERATIONS = 20000;
        private static final int KEY_LENGTH = 256;

        public static String hash(String password) {
            try {
                SecureRandom random = new SecureRandom();
                byte[] salt = new byte[SALT_LENGTH];
                random.nextBytes(salt);
                PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH);
                SecretKeyFactory skf = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM);
                byte[] hash = skf.generateSecret(spec).getEncoded();
                return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(hash);
            } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
                throw new IllegalStateException("Error during password hashing.", e);
            }
        }

        public static boolean verify(String password, String storedHash) {
            try {
                String[] parts = storedHash.split(":");
                byte[] salt = Base64.getDecoder().decode(parts[0]);
                byte[] hash = Base64.getDecoder().decode(parts[1]);
                PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH);
                SecretKeyFactory skf = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM);
                byte[] testHash = skf.generateSecret(spec).getEncoded();
                return Arrays.equals(hash, testHash);
            } catch (Exception e) {
                return false;
            }
        }
    }

    static class JwtHandler {
        private final SecretKeySpec secretKey;
        private static final String HMAC_ALGO = "HmacSHA256";

        public JwtHandler(String secret) {
            this.secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO);
        }

        public String generate(User user) {
            var header = Map.of("alg", "HS256", "typ", "JWT");
            var payload = Map.of(
                "sub", user.id().toString(),
                "role", user.role().name(),
                "iat", Instant.now().getEpochSecond()
            );
            String encodedHeader = toBase64Url(toJson(header));
            String encodedPayload = toBase64Url(toJson(payload));
            String signature = sign(encodedHeader + "." + encodedPayload);
            return encodedHeader + "." + encodedPayload + "." + signature;
        }

        public boolean validate(String token) {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return false;
            String expectedSignature = sign(parts[0] + "." + parts[1]);
            return expectedSignature.equals(parts[2]);
        }

        private String sign(String data) {
            try {
                Mac mac = Mac.getInstance(HMAC_ALGO);
                mac.init(secretKey);
                byte[] signatureBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
                return toBase64Url(signatureBytes);
            } catch (NoSuchAlgorithmException | InvalidKeyException e) {
                throw new IllegalStateException("JWT signing failed", e);
            }
        }

        // Simple JSON-like string builder (no external libs)
        private String toJson(Map<String, Object> map) {
            return map.entrySet().stream()
                .map(e -> "\"" + e.getKey() + "\":" + (e.getValue() instanceof String ? "\"" + e.getValue() + "\"" : e.getValue()))
                .collect(Collectors.joining(",", "{", "}"));
        }
        private String toBase64Url(String data) {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(data.getBytes(StandardCharsets.UTF_8));
        }
        private String toBase64Url(byte[] data) {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
        }
    }

    // --- Service Layer ---
    static class AuthService {
        private final InMemoryStore store;
        private final JwtHandler jwtHandler;

        public AuthService(InMemoryStore store, JwtHandler jwtHandler) {
            this.store = store;
            this.jwtHandler = jwtHandler;
        }

        public User register(String email, String password, Role role) {
            if (store.findUserByEmail(email).isPresent()) {
                throw new IllegalArgumentException("Email already in use.");
            }
            var user = new User(email, PasswordManager.hash(password), role);
            store.saveUser(user);
            return user;
        }

        public Optional<AuthResponse> login(LoginCredentials credentials) {
            return store.findUserByEmail(credentials.email())
                .filter(User::isActive)
                .filter(user -> PasswordManager.verify(credentials.password(), user.passwordHash()))
                .map(user -> {
                    String jwt = jwtHandler.generate(user);
                    String sessionId = store.createSession(user);
                    return new AuthResponse(jwt, sessionId);
                });
        }
    }

    static class PostService {
        private final InMemoryStore store;
        public PostService(InMemoryStore store) { this.store = store; }

        public Post create(User author, String title, String content) {
            var post = new Post(author.id(), title, content);
            store.savePost(post);
            return post;
        }

        public void publish(User user, Post post) {
            RoleEnforcer.requireRole(user, Role.ADMIN);
            store.savePost(post.asPublished());
        }
    }

    // --- Access Control ---
    static class RoleEnforcer {
        public static void requireRole(User user, Role required) {
            if (user.role() != required) {
                throw new SecurityException("Access denied. Required role: " + required);
            }
        }
    }
    
    // --- OAuth2 Client ---
    static class OAuth2Handler {
        private final OAuth2Config config;
        public OAuth2Handler(OAuth2Config config) { this.config = config; }
        
        public String getAuthorizationUri(String state) {
            return "https://oauth.example.com/auth?client_id=" + config.clientId() + "&redirect_uri=" + config.redirectUri() + "&state=" + state;
        }
        
        public String exchangeCode(String code) {
            System.out.println("Simulating token exchange with code: " + code);
            return "simulated_access_token_" + UUID.randomUUID();
        }
    }

    // --- Main Application ---
    public static void main(String[] args) {
        // 1. Initialization
        var store = new InMemoryStore();
        var jwtHandler = new JwtHandler("my-super-awesome-and-long-secret-for-the-app");
        var authService = new AuthService(store, jwtHandler);
        var postService = new PostService(store);
        var oauthConfig = new OAuth2Config("client-id-modern", "client-secret-modern", "http://localhost/callback");
        var oauthHandler = new OAuth2Handler(oauthConfig);

        // 2. User Registration
        System.out.println("--- User Registration ---");
        User admin = authService.register("admin@modern.java", "AdminPass1!", Role.ADMIN);
        User user = authService.register("user@modern.java", "UserPass1!", Role.USER);
        System.out.println("Registered: " + admin.email() + " and " + user.email());
        System.out.println();

        // 3. Authentication
        System.out.println("--- Authentication ---");
        var adminLogin = new LoginCredentials("admin@modern.java", "AdminPass1!");
        authService.login(adminLogin).ifPresentOrElse(
            response -> System.out.println("Admin login successful. JWT starts with: " + response.jwt().substring(0, 20)),
            () -> System.out.println("Admin login failed.")
        );
        var badLogin = new LoginCredentials("user@modern.java", "wrong-pass");
        authService.login(badLogin).ifPresentOrElse(
            response -> System.out.println("User login succeeded unexpectedly."),
            () -> System.out.println("User login failed as expected.")
        );
        System.out.println();

        // 4. JWT Validation
        System.out.println("--- JWT Validation ---");
        String adminJwt = authService.login(adminLogin).get().jwt();
        System.out.println("Admin JWT is valid: " + jwtHandler.validate(adminJwt));
        System.out.println("Tampered JWT is valid: " + jwtHandler.validate(adminJwt.replace('.', 'x')));
        System.out.println();

        // 5. RBAC
        System.out.println("--- Role-Based Access Control ---");
        Post draftPost = postService.create(admin, "A New Post", "Some content.");
        try {
            postService.publish(admin, draftPost);
            System.out.println("Admin successfully published a post.");
        } catch (SecurityException e) {
            System.out.println("Admin failed to publish (unexpected).");
        }
        try {
            postService.publish(user, draftPost);
            System.out.println("User successfully published a post (unexpected).");
        } catch (SecurityException e) {
            System.out.println("User failed to publish as expected: " + e.getMessage());
        }
        System.out.println();
        
        // 6. OAuth2 Flow
        System.out.println("--- OAuth2 Flow ---");
        String state = UUID.randomUUID().toString();
        String authUri = oauthHandler.getAuthorizationUri(state);
        System.out.println("1. Redirect to: " + authUri);
        String mockCode = "code-from-provider";
        String token = oauthHandler.exchangeCode(mockCode);
        System.out.println("2. Exchanged code for token: " + token);
    }
}