import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

public class FunctionalMiddlewareComposition {

    // --- Domain Schema ---
    enum UserRole { ADMIN, USER }
    enum PostStatus { DRAFT, PUBLISHED }

    static class User {
        UUID id = UUID.randomUUID();
        String email; String password_hash; UserRole role;
        boolean is_active = true; Timestamp created_at = Timestamp.from(Instant.now());
        public User(String email, String password_hash, UserRole role) {
            this.email = email; this.password_hash = password_hash; this.role = role;
        }
        public String toJson() {
            return String.format("{\"id\":\"%s\",\"email\":\"%s\",\"role\":\"%s\"}", id, email, role);
        }
    }

    static class Post {
        UUID id = UUID.randomUUID();
        UUID user_id; String title; String content; PostStatus status = PostStatus.DRAFT;
        public Post(UUID user_id, String title, String content) {
            this.user_id = user_id; this.title = title; this.content = content;
        }
        public String toJson() {
            return String.format("{\"id\":\"%s\",\"user_id\":\"%s\",\"title\":\"%s\"}", id, user_id, title);
        }
    }

    // --- HTTP Abstraction ---
    static class HttpRequest {
        final String method; final String path; final Map<String, String> headers; String body;
        HttpRequest(String method, String path) {
            this.method = method; this.path = path; this.headers = new ConcurrentHashMap<>();
        }
    }

    static class HttpResponse {
        int status = 200; Map<String, String> headers = new ConcurrentHashMap<>(); String body = "";
    }

    // --- Middleware Pattern (Functional Composition) ---
    @FunctionalInterface
    interface HttpHandler {
        HttpResponse handle(HttpRequest request);
    }

    @FunctionalInterface
    interface Middleware extends Function<HttpHandler, HttpHandler> {}

    // --- Concrete Middleware Implementations (as static methods) ---
    static Middleware loggingMiddleware() {
        return next -> request -> {
            System.out.println(String.format("[Log] Request received: %s %s", request.method, request.path));
            HttpResponse response = next.handle(request);
            System.out.println(String.format("[Log] Responding with status: %d", response.status));
            return response;
        };
    }

    static Middleware corsMiddleware() {
        return next -> request -> {
            HttpResponse response = next.handle(request);
            response.headers.put("Access-Control-Allow-Origin", "*");
            response.headers.put("Access-Control-Allow-Methods", "GET, POST");
            if ("OPTIONS".equals(request.method)) {
                response.status = 204;
                response.body = "";
            }
            return response;
        };
    }

    static class RateLimiter {
        private final Map<String, Long> accessTimes = new ConcurrentHashMap<>();
        private final int limit;
        private final long periodMillis;

        RateLimiter(int limit, long periodSeconds) {
            this.limit = limit;
            this.periodMillis = periodSeconds * 1000;
        }

        Middleware createMiddleware() {
            return next -> request -> {
                String clientIp = request.headers.getOrDefault("Client-IP", "127.0.0.1");
                long now = System.currentTimeMillis();
                
                // Simple token bucket would be better, but this is a simple time-based check
                long lastAccess = accessTimes.getOrDefault(clientIp, 0L);
                if (now - lastAccess < periodMillis / limit) {
                    HttpResponse response = new HttpResponse();
                    response.status = 429;
                    response.body = "{\"error\":\"Rate limit exceeded\"}";
                    response.headers.put("Content-Type", "application/json");
                    return response;
                }
                accessTimes.put(clientIp, now);
                return next.handle(request);
            };
        }
    }

    static Middleware errorHandlingMiddleware() {
        return next -> request -> {
            try {
                return next.handle(request);
            } catch (ResourceNotFoundException e) {
                HttpResponse response = new HttpResponse();
                response.status = 404;
                response.body = String.format("{\"error\":\"%s\"}", e.getMessage());
                return response;
            } catch (Exception e) {
                HttpResponse response = new HttpResponse();
                response.status = 500;
                response.body = "{\"error\":\"An unexpected error occurred\"}";
                return response;
            }
        };
    }

    // --- Decorator for Transformation ---
    static Middleware responseTransformationMiddleware() {
        return next -> request -> {
            HttpResponse response = next.handle(request);
            // Decorate the response body
            if (response.body != null && !response.body.isEmpty() && response.status >= 200 && response.status < 300) {
                response.body = String.format(
                    "{\"payload\":%s,\"request_id\":\"%s\"}",
                    response.body, UUID.randomUUID().toString()
                );
                response.headers.put("Content-Type", "application/json");
            }
            return response;
        };
    }

    static class ResourceNotFoundException extends RuntimeException {
        public ResourceNotFoundException(String message) { super(message); }
    }

    // --- Final Business Logic Handler ---
    static class UserApi {
        private static final Map<UUID, User> userDatabase = new ConcurrentHashMap<>();
        static {
            User user = new User("test@user.com", "hashed_pw", UserRole.USER);
            userDatabase.put(user.id, user);
        }

        public static HttpHandler getUsersHandler() {
            return request -> {
                if (!"/api/users".equals(request.path)) {
                    throw new ResourceNotFoundException("Endpoint not found: " + request.path);
                }
                HttpResponse response = new HttpResponse();
                String usersJson = userDatabase.values().stream().map(User::toJson).collect(Collectors.joining(","));
                response.body = "[" + usersJson + "]";
                return response;
            };
        }
    }

    // --- Main Execution ---
    public static void main(String[] args) {
        // 1. Create middleware instances
        RateLimiter rateLimiter = new RateLimiter(1, 2); // 1 request per 2 seconds
        
        // 2. Compose the handler by wrapping it with middleware
        HttpHandler handler = UserApi.getUsersHandler();
        
        HttpHandler composedHandler = errorHandlingMiddleware()
            .andThen(loggingMiddleware())
            .andThen(corsMiddleware())
            .andThen(rateLimiter.createMiddleware())
            .andThen(responseTransformationMiddleware())
            .apply(handler);

        // 3. Simulate a valid request
        System.out.println("--- Simulating Valid Request ---");
        HttpRequest req1 = new HttpRequest("GET", "/api/users");
        req1.headers.put("Client-IP", "192.168.0.1");
        HttpResponse res1 = composedHandler.handle(req1);
        System.out.println("Response: " + res1.status + " / Body: " + res1.body);
        System.out.println("Headers: " + res1.headers);
        System.out.println();

        // 4. Simulate a rate-limited request
        System.out.println("--- Simulating Rate-Limited Request ---");
        HttpResponse res2 = composedHandler.handle(req1);
        System.out.println("Response: " + res2.status + " / Body: " + res2.body);
        System.out.println();
        
        // 5. Simulate a not found request
        System.out.println("--- Simulating Not Found Request ---");
        HttpRequest req3 = new HttpRequest("GET", "/api/posts");
        req3.headers.put("Client-IP", "192.168.0.1");
        HttpResponse res3 = composedHandler.handle(req3);
        System.out.println("Response: " + res3.status + " / Body: " + res3.body);
    }
}