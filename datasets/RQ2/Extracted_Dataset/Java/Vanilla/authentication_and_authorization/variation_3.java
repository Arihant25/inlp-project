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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// --- Main Application Entry Point ---
public class SecureAuthSystem {

    public static void main(String[] args) {
        // Setup dependencies
        final var userStore = new infra.InMemoryUserStore();
        final var postStore = new infra.InMemoryPostStore();
        final var sessionRegistry = new session.InMemorySessionRegistry();
        final var passwordHasher = new security.hashing.Pbkdf2Hasher();
        final var jwtSigner = new security.jwt.JwtSigner("a-very-long-and-secure-secret-key-for-hmac-sha256");
        final var jwtValidator = new security.jwt.JwtValidator(jwtSigner.getSigningKey());
        final var accessControlManager = new security.access.AccessControlManager();
        final var oauthClient = new oauth.SimpleOAuth2Client("app-id-xyz", "app-secret-xyz");

        // Setup Facades
        final var userAuthFacade = new application.UserAuthFacade(userStore, passwordHasher, jwtSigner, sessionRegistry);
        final var postManagementFacade = new application.PostManagementFacade(postStore, accessControlManager);

        System.out.println("--- System Initialized ---");

        // Create Users
        core.model.User admin = userAuthFacade.registerUser("security.admin@example.com", "ComplexP@ssword1", core.model.Role.ADMIN);
        core.model.User user = userAuthFacade.registerUser("regular.user@example.com", "SimplePassword1", core.model.Role.USER);
        System.out.println("Users registered: " + admin.getEmail() + ", " + user.getEmail());
        System.out.println();

        // Authentication Flow
        System.out.println("--- Authentication Flow ---");
        try {
            application.UserAuthFacade.LoginResult result = userAuthFacade.login("security.admin@example.com", "ComplexP@ssword1");
            System.out.println("Admin login successful.");
            System.out.println("Session ID: " + result.getSessionId());
            System.out.println("JWT valid: " + jwtValidator.isTokenValid(result.getJwtToken()));

            // Test bad login
            userAuthFacade.login("regular.user@example.com", "wrong-password");
        } catch (core.exception.AuthenticationException e) {
            System.out.println("Caught expected exception: " + e.getMessage());
        }
        System.out.println();

        // Authorization (RBAC) Flow
        System.out.println("--- Authorization (RBAC) Flow ---");
        try {
            // Admin action: Should succeed
            postManagementFacade.publishNewPost(admin, "Admin Title", "This is a post by an admin.");
            System.out.println("Admin successfully published a post.");

            // User action: Should fail
            postManagementFacade.publishNewPost(user, "User Title", "This should not be published.");
        } catch (core.exception.AuthorizationException e) {
            System.out.println("Caught expected exception: " + e.getMessage());
        }
        System.out.println();
        
        // JWT Tampering Test
        System.out.println("--- JWT Tampering Test ---");
        try {
            String validToken = jwtSigner.issueToken(admin);
            String tamperedToken = validToken.substring(0, validToken.lastIndexOf('.') + 1) + "tampered";
            jwtValidator.isTokenValid(tamperedToken);
        } catch (core.exception.InvalidTokenException e) {
            System.out.println("Caught expected exception for tampered token: " + e.getMessage());
        }
        System.out.println();

        // OAuth2 Simulation
        System.out.println("--- OAuth2 Simulation ---");
        String authUrl = oauthClient.createAuthorizationRequestUrl("profile email");
        System.out.println("OAuth2 Authorization URL: " + authUrl);
        String mockCode = "mock-provider-code-9876";
        String accessToken = oauthClient.exchangeCodeForAccessToken(mockCode);
        System.out.println("OAuth2 Access Token received: " + accessToken);
    }
}

// --- Core Domain and Exceptions ---
namespace core.model {
    enum Role { ADMIN, USER }
    enum PostStatus { DRAFT, PUBLISHED }

    class User {
        private final UUID id;
        private final String email;
        private final String passwordHash;
        private final Role role;
        private final boolean isActive;
        private final Timestamp createdAt;

        public User(String email, String passwordHash, Role role) {
            this.id = UUID.randomUUID();
            this.email = email;
            this.passwordHash = passwordHash;
            this.role = role;
            this.isActive = true;
            this.createdAt = Timestamp.from(Instant.now());
        }
        public UUID getId() { return id; }
        public String getEmail() { return email; }
        public String getPasswordHash() { return passwordHash; }
        public Role getRole() { return role; }
        public boolean isActive() { return isActive; }
    }

    class Post {
        private final UUID id;
        private final UUID userId;
        private PostStatus status;
        public Post(UUID userId, String title, String content) {
            this.id = UUID.randomUUID();
            this.userId = userId;
            this.status = PostStatus.DRAFT;
        }
        public UUID getId() { return id; }
        public void setStatus(PostStatus status) { this.status = status; }
    }
}

namespace core.exception {
    class AuthenticationException extends RuntimeException {
        public AuthenticationException(String message) { super(message); }
    }
    class AuthorizationException extends RuntimeException {
        public AuthorizationException(String message) { super(message); }
    }
    class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message) { super(message); }
        public InvalidTokenException(String message, Throwable cause) { super(message, cause); }
    }
}

// --- Security Primitives ---
namespace security.hashing {
    interface SecurePasswordHasher {
        String hash(String password);
        boolean verify(String password, String storedHash);
    }

    class Pbkdf2Hasher implements SecurePasswordHasher {
        private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
        private static final int ITERATION_COUNT = 15000;
        private static final int KEY_LENGTH_BITS = 256;
        private static final int SALT_BYTES = 16;

        @Override
        public String hash(String password) {
            try {
                SecureRandom random = new SecureRandom();
                byte[] salt = new byte[SALT_BYTES];
                random.nextBytes(salt);
                PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH_BITS);
                SecretKeyFactory skf = SecretKeyFactory.getInstance(ALGORITHM);
                byte[] hash = skf.generateSecret(spec).getEncoded();
                return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(hash);
            } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
                throw new IllegalStateException("Password hashing failed", e);
            }
        }

        @Override
        public boolean verify(String password, String storedHash) {
            try {
                String[] parts = storedHash.split(":");
                if (parts.length != 2) return false;
                byte[] salt = Base64.getDecoder().decode(parts[0]);
                byte[] hash = Base64.getDecoder().decode(parts[1]);
                PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH_BITS);
                SecretKeyFactory skf = SecretKeyFactory.getInstance(ALGORITHM);
                byte[] testHash = skf.generateSecret(spec).getEncoded();
                return Arrays.equals(hash, testHash);
            } catch (Exception e) {
                return false;
            }
        }
    }
}

namespace security.jwt {
    class JwtSigner {
        private static final String HMAC_ALGO = "HmacSHA256";
        private final SecretKeySpec signingKey;

        public JwtSigner(String secret) {
            this.signingKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO);
        }
        
        public SecretKeySpec getSigningKey() { return signingKey; }

        public String issueToken(core.model.User user) {
            String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
            long expiry = Instant.now().plusSeconds(3600).getEpochSecond();
            String payload = String.format("{\"sub\":\"%s\",\"role\":\"%s\",\"exp\":%d}", user.getId(), user.getRole(), expiry);
            String encodedHeader = Base64.getUrlEncoder().withoutPadding().encodeToString(header.getBytes());
            String encodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes());
            String data = encodedHeader + "." + encodedPayload;
            try {
                Mac mac = Mac.getInstance(HMAC_ALGO);
                mac.init(signingKey);
                byte[] signature = mac.doFinal(data.getBytes());
                return data + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
            } catch (NoSuchAlgorithmException | InvalidKeyException e) {
                throw new IllegalStateException("Could not sign JWT", e);
            }
        }
    }

    class JwtValidator {
        private final SecretKeySpec signingKey;
        public JwtValidator(SecretKeySpec key) { this.signingKey = key; }

        public boolean isTokenValid(String token) {
            try {
                String[] parts = token.split("\\.");
                if (parts.length != 3) throw new core.exception.InvalidTokenException("Invalid JWT format");
                
                Mac mac = Mac.getInstance(signingKey.getAlgorithm());
                mac.init(signingKey);
                byte[] expectedSignature = mac.doFinal((parts[0] + "." + parts[1]).getBytes());
                byte[] providedSignature = Base64.getUrlDecoder().decode(parts[2]);

                if (!Arrays.equals(expectedSignature, providedSignature)) {
                    throw new core.exception.InvalidTokenException("JWT signature does not match");
                }

                String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
                long exp = Long.parseLong(payload.substring(payload.indexOf("\"exp\":") + 6, payload.indexOf("}")));
                if (Instant.now().getEpochSecond() > exp) {
                    throw new core.exception.InvalidTokenException("JWT has expired");
                }
                return true;
            } catch (Exception e) {
                if (e instanceof core.exception.InvalidTokenException) throw e;
                throw new core.exception.InvalidTokenException("JWT validation failed", e);
            }
        }
    }
}

namespace security.access {
    class AccessControlManager {
        public void verifyRole(core.model.User user, core.model.Role requiredRole) {
            if (user.getRole() != requiredRole) {
                throw new core.exception.AuthorizationException("User role " + user.getRole() + " is not authorized. Required: " + requiredRole);
            }
        }
    }
}

// --- Infrastructure and Application Layers ---
namespace infra {
    interface UserStore {
        void save(core.model.User user);
        core.model.User findByEmail(String email);
    }
    class InMemoryUserStore implements UserStore {
        private final Map<String, core.model.User> db = new ConcurrentHashMap<>();
        public void save(core.model.User user) { db.put(user.getEmail(), user); }
        public core.model.User findByEmail(String email) { return db.get(email); }
    }
    interface PostStore { void save(core.model.Post post); }
    class InMemoryPostStore implements PostStore {
        private final Map<UUID, core.model.Post> db = new ConcurrentHashMap<>();
        public void save(core.model.Post post) { db.put(post.getId(), post); }
    }
}

namespace session {
    interface SessionRegistry {
        String newSession(core.model.User user);
    }
    class InMemorySessionRegistry implements SessionRegistry {
        private final Map<String, core.model.User> sessions = new ConcurrentHashMap<>();
        public String newSession(core.model.User user) {
            String sid = UUID.randomUUID().toString();
            sessions.put(sid, user);
            return sid;
        }
    }
}

namespace oauth {
    class SimpleOAuth2Client {
        private final String clientId;
        private final String clientSecret;
        public SimpleOAuth2Client(String id, String secret) { this.clientId = id; this.clientSecret = secret; }
        public String createAuthorizationRequestUrl(String scope) {
            return "https://provider.com/auth?client_id=" + clientId + "&scope=" + scope;
        }
        public String exchangeCodeForAccessToken(String code) {
            // This would be an HTTP call in a real application
            return "at-" + code + "-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }
}

namespace application {
    class UserAuthFacade {
        private final infra.UserStore userStore;
        private final security.hashing.SecurePasswordHasher passwordHasher;
        private final security.jwt.JwtSigner jwtSigner;
        private final session.SessionRegistry sessionRegistry;

        public UserAuthFacade(infra.UserStore us, security.hashing.SecurePasswordHasher ph, security.jwt.JwtSigner js, session.SessionRegistry sr) {
            this.userStore = us; this.passwordHasher = ph; this.jwtSigner = js; this.sessionRegistry = sr;
        }

        public core.model.User registerUser(String email, String password, core.model.Role role) {
            String hash = passwordHasher.hash(password);
            core.model.User user = new core.model.User(email, hash, role);
            userStore.save(user);
            return user;
        }

        public LoginResult login(String email, String password) {
            core.model.User user = userStore.findByEmail(email);
            if (user == null || !user.isActive() || !passwordHasher.verify(password, user.getPasswordHash())) {
                throw new core.exception.AuthenticationException("Invalid email or password");
            }
            String jwt = jwtSigner.issueToken(user);
            String sid = sessionRegistry.newSession(user);
            return new LoginResult(jwt, sid);
        }
        
        public static class LoginResult {
            private final String jwtToken;
            private final String sessionId;
            LoginResult(String jwt, String sid) { this.jwtToken = jwt; this.sessionId = sid; }
            public String getJwtToken() { return jwtToken; }
            public String getSessionId() { return sessionId; }
        }
    }

    class PostManagementFacade {
        private final infra.PostStore postStore;
        private final security.access.AccessControlManager accessManager;

        public PostManagementFacade(infra.PostStore ps, security.access.AccessControlManager am) {
            this.postStore = ps; this.accessManager = am;
        }

        public void publishNewPost(core.model.User author, String title, String content) {
            accessManager.verifyRole(author, core.model.Role.ADMIN);
            core.model.Post post = new core.model.Post(author.getId(), title, content);
            post.setStatus(core.model.PostStatus.PUBLISHED);
            postStore.save(post);
        }
    }
}