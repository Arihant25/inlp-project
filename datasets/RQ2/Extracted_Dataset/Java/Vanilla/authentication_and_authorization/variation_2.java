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

public class FunctionalStyleAuthSystem {

    // --- Domain Model (Static Nested Classes) ---
    public static class Domain {
        public enum Role { ADMIN, USER }
        public enum PostStatus { DRAFT, PUBLISHED }

        public static class User {
            final UUID id;
            final String email;
            final String passwordHash;
            final Role role;
            final boolean isActive;
            final Timestamp createdAt;

            User(String email, String passwordHash, Role role) {
                this.id = UUID.randomUUID();
                this.email = email;
                this.passwordHash = passwordHash;
                this.role = role;
                this.isActive = true;
                this.createdAt = Timestamp.from(Instant.now());
            }
        }

        public static class Post {
            final UUID id;
            final UUID userId;
            final String title;
            String content;
            PostStatus status;

            Post(UUID userId, String title, String content) {
                this.id = UUID.randomUUID();
                this.userId = userId;
                this.title = title;
                this.content = content;
                this.status = PostStatus.DRAFT;
            }
        }
    }

    // --- In-Memory Data Store ---
    public static final class DataStore {
        private static final Map<String, Domain.User> USERS_BY_EMAIL = new ConcurrentHashMap<>();
        private static final Map<UUID, Domain.Post> POSTS_BY_ID = new ConcurrentHashMap<>();
        private static final Map<String, Domain.User> SESSIONS = new ConcurrentHashMap<>();

        public static void saveUser(Domain.User user) { USERS_BY_EMAIL.put(user.email, user); }
        public static Domain.User findUser(String email) { return USERS_BY_EMAIL.get(email); }
        public static void savePost(Domain.Post post) { POSTS_BY_ID.put(post.id, post); }
        public static Domain.Post findPost(UUID id) { return POSTS_BY_ID.get(id); }
        public static void addSession(String sid, Domain.User user) { SESSIONS.put(sid, user); }
        public static void removeSession(String sid) { SESSIONS.remove(sid); }
    }

    // --- Password Hashing Utility ---
    public static final class Passwords {
        private Passwords() {}
        private static final String ALGO = "PBKDF2WithHmacSHA256";
        private static final int ITERATIONS = 12000;
        private static final int KEY_LEN = 256;

        public static String hash(String password) {
            try {
                SecureRandom sr = new SecureRandom();
                byte[] salt = new byte[16];
                sr.nextBytes(salt);
                PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LEN);
                SecretKeyFactory skf = SecretKeyFactory.getInstance(ALGO);
                byte[] hash = skf.generateSecret(spec).getEncoded();
                return Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(hash);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        public static boolean verify(String password, String stored) {
            try {
                String[] parts = stored.split("\\$");
                byte[] salt = Base64.getDecoder().decode(parts[0]);
                byte[] hash = Base64.getDecoder().decode(parts[1]);
                PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LEN);
                SecretKeyFactory skf = SecretKeyFactory.getInstance(ALGO);
                byte[] testHash = skf.generateSecret(spec).getEncoded();
                return Arrays.equals(hash, testHash);
            } catch (Exception e) { return false; }
        }
    }

    // --- JWT Utility ---
    public static final class JWT {
        private JWT() {}
        private static final String HMAC_ALGO = "HmacSHA256";
        private static final String SECRET_KEY = "another-very-secure-and-long-secret-for-jwt";
        private static final SecretKeySpec SIGNING_KEY = new SecretKeySpec(SECRET_KEY.getBytes(StandardCharsets.UTF_8), HMAC_ALGO);

        public static String create(Domain.User user) {
            String header = "{\"alg\":\"HS256\"}";
            String payload = String.format("{\"sub\":\"%s\",\"role\":\"%s\",\"iat\":%d}",
                user.id, user.role.name(), Instant.now().getEpochSecond());
            String b64Header = Base64.getUrlEncoder().withoutPadding().encodeToString(header.getBytes());
            String b64Payload = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes());
            String signature = sign(b64Header + "." + b64Payload);
            return b64Header + "." + b64Payload + "." + signature;
        }

        public static boolean verify(String token) {
            try {
                String[] parts = token.split("\\.");
                if (parts.length != 3) return false;
                String signature = sign(parts[0] + "." + parts[1]);
                return signature.equals(parts[2]);
            } catch (Exception e) { return false; }
        }

        private static String sign(String data) {
            try {
                Mac mac = Mac.getInstance(HMAC_ALGO);
                mac.init(SIGNING_KEY);
                return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data.getBytes()));
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    // --- RBAC Utility ---
    public static final class RBAC {
        private RBAC() {}
        public static void require(Domain.User user, Domain.Role requiredRole) {
            if (user == null || user.role != requiredRole) {
                throw new IllegalStateException("Access Denied. Required role: " + requiredRole);
            }
        }
    }
    
    // --- OAuth2 Client Utility ---
    public static final class OAuth2 {
        private OAuth2() {}
        private static final String CLIENT_ID = "client_id_789";
        private static final String CLIENT_SECRET = "client_secret_101";

        public static String buildAuthUrl() {
            return "https://oauth.service.com/authorize?client_id=" + CLIENT_ID + "&response_type=code";
        }

        public static String getToken(String code) {
            System.out.println("Simulating POST to token endpoint with code: " + code);
            return "fake_oauth_token_for_" + code;
        }
    }

    // --- Core Authentication Logic ---
    public static final class Auth {
        private Auth() {}

        public static String login(String email, String password) {
            Domain.User user = DataStore.findUser(email);
            if (user != null && user.isActive && Passwords.verify(password, user.passwordHash)) {
                String sessionId = UUID.randomUUID().toString();
                DataStore.addSession(sessionId, user);
                return sessionId;
            }
            return null;
        }

        public static void logout(String sessionId) {
            DataStore.removeSession(sessionId);
        }
    }

    // --- Main Execution ---
    public static void main(String[] args) {
        // 1. Setup
        Domain.User admin = new Domain.User("admin@dev.com", Passwords.hash("adminPass"), Domain.Role.ADMIN);
        Domain.User user = new Domain.User("user@dev.com", Passwords.hash("userPass"), Domain.Role.USER);
        DataStore.saveUser(admin);
        DataStore.saveUser(user);
        System.out.println("--- Setup Complete ---");
        System.out.println("Admin and User created with hashed passwords.");
        System.out.println();

        // 2. Login & Session
        System.out.println("--- Login & Session ---");
        String adminSessionId = Auth.login("admin@dev.com", "adminPass");
        System.out.println("Admin login successful. Session ID: " + (adminSessionId != null));
        String failedLogin = Auth.login("user@dev.com", "wrong");
        System.out.println("User login with wrong password successful: " + (failedLogin != null));
        Auth.logout(adminSessionId);
        System.out.println("Admin logged out.");
        System.out.println();

        // 3. JWT
        System.out.println("--- JWT Generation & Verification ---");
        String adminToken = JWT.create(admin);
        System.out.println("Generated JWT for admin: " + adminToken.substring(0, 40) + "...");
        System.out.println("Is token valid? " + JWT.verify(adminToken));
        System.out.println("Is tampered token valid? " + JWT.verify(adminToken + "x"));
        System.out.println();

        // 4. RBAC
        System.out.println("--- Role-Based Access Control ---");
        try {
            RBAC.require(admin, Domain.Role.ADMIN);
            System.out.println("Admin access check: PASSED");
            Domain.Post post = new Domain.Post(admin.id, "RBAC Test", "Content");
            DataStore.savePost(post);
            System.out.println("Admin created a post.");
        } catch (IllegalStateException e) {
            System.out.println("Admin access check: FAILED (unexpected)");
        }
        try {
            RBAC.require(user, Domain.Role.ADMIN);
            System.out.println("User access check for ADMIN role: PASSED (unexpected)");
        } catch (IllegalStateException e) {
            System.out.println("User access check for ADMIN role: FAILED (expected) -> " + e.getMessage());
        }
        System.out.println();
        
        // 5. OAuth2 Simulation
        System.out.println("--- OAuth2 Simulation ---");
        String authUrl = OAuth2.buildAuthUrl();
        System.out.println("OAuth2 Auth URL: " + authUrl);
        String mockCode = "auth_code_from_provider_12345";
        String token = OAuth2.getToken(mockCode);
        System.out.println("Received OAuth2 Token: " + token);
    }
}