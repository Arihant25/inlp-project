import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class MiddlewareChainOOP {

    // --- Domain Schema ---
    enum UserRole { ADMIN, USER }
    enum PostStatus { DRAFT, PUBLISHED }

    static class User {
        UUID id; String email; String password_hash; UserRole role; boolean is_active; Instant created_at;
        public User(String email, String password_hash, UserRole role) {
            this.id = UUID.randomUUID(); this.email = email; this.password_hash = password_hash;
            this.role = role; this.is_active = true; this.created_at = Instant.now();
        }
        @Override public String toString() {
            return String.format("{\"id\":\"%s\",\"email\":\"%s\",\"role\":\"%s\",\"is_active\":%b}", id, email, role, is_active);
        }
    }

    static class Post {
        UUID id; UUID user_id; String title; String content; PostStatus status;
        public Post(UUID user_id, String title, String content) {
            this.id = UUID.randomUUID(); this.user_id = user_id; this.title = title;
            this.content = content; this.status = PostStatus.DRAFT;
        }
        @Override public String toString() {
            return String.format("{\"id\":\"%s\",\"user_id\":\"%s\",\"title\":\"%s\",\"status\":\"%s\"}", id, user_id, title, status);
        }
    }

    // --- HTTP Abstraction ---
    static class Request {
        String method; String path; Map<String, String> headers; String body;
        public Request(String method, String path, String clientIp) {
            this.method = method; this.path = path;
            this.headers = new ConcurrentHashMap<>();
            this.headers.put("X-Client-IP", clientIp);
        }
    }

    static class Response {
        int statusCode = 200; Map<String, String> headers = new ConcurrentHashMap<>(); String body = "";
    }

    // --- Middleware Pattern (Chain of Responsibility) ---
    abstract static class Middleware {
        private Middleware next;

        public static Middleware link(Middleware first, Middleware... chain) {
            Middleware head = first;
            for (Middleware nextInChain : chain) {
                head.next = nextInChain;
                head = nextInChain;
            }
            return first;
        }

        public abstract void handle(Request request, Response response);

        protected void handleNext(Request request, Response response) {
            if (next != null) {
                next.handle(request, response);
            }
        }
    }

    // --- Concrete Middleware Implementations ---
    static class ErrorHandlingMiddleware extends Middleware {
        @Override
        public void handle(Request request, Response response) {
            try {
                handleNext(request, response);
            } catch (Exception e) {
                System.err.println("ERROR: " + e.getMessage());
                response.statusCode = 500;
                response.body = String.format("{\"error\":\"Internal Server Error: %s\"}", e.getMessage());
                response.headers.put("Content-Type", "application/json");
            }
        }
    }

    static class LoggingMiddleware extends Middleware {
        @Override
        public void handle(Request request, Response response) {
            long startTime = System.currentTimeMillis();
            System.out.printf("Request Start: %s %s from IP %s\n", request.method, request.path, request.headers.get("X-Client-IP"));
            handleNext(request, response);
            long duration = System.currentTimeMillis() - startTime;
            System.out.printf("Request End: %s %s -> %d (%dms)\n", request.method, request.path, response.statusCode, duration);
        }
    }

    static class CorsMiddleware extends Middleware {
        @Override
        public void handle(Request request, Response response) {
            response.headers.put("Access-Control-Allow-Origin", "*");
            response.headers.put("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            response.headers.put("Access-Control-Allow-Headers", "Content-Type, Authorization");

            if ("OPTIONS".equals(request.method)) {
                response.statusCode = 204;
                response.body = "";
                return; // End chain for OPTIONS pre-flight
            }
            handleNext(request, response);
        }
    }

    static class RateLimitingMiddleware extends Middleware {
        private final int maxRequests;
        private final long timeWindowSeconds;
        private final Map<String, AtomicInteger> requestCounts = new ConcurrentHashMap<>();
        private final Map<String, Long> windowStartTimes = new ConcurrentHashMap<>();

        public RateLimitingMiddleware(int maxRequests, long timeWindowSeconds) {
            this.maxRequests = maxRequests;
            this.timeWindowSeconds = timeWindowSeconds;
        }

        @Override
        public void handle(Request request, Response response) {
            String clientIp = request.headers.getOrDefault("X-Client-IP", "unknown");
            long now = System.currentTimeMillis();

            long windowStart = windowStartTimes.computeIfAbsent(clientIp, k -> now);
            if (now - windowStart > timeWindowSeconds * 1000) {
                windowStartTimes.put(clientIp, now);
                requestCounts.put(clientIp, new AtomicInteger(0));
            }

            int count = requestCounts.computeIfAbsent(clientIp, k -> new AtomicInteger(0)).incrementAndGet();

            if (count > maxRequests) {
                response.statusCode = 429;
                response.body = "{\"error\":\"Too Many Requests\"}";
                response.headers.put("Content-Type", "application/json");
                return;
            }
            handleNext(request, response);
        }
    }

    // --- Decorator for Transformation ---
    static class ResponseTransformationMiddleware extends Middleware {
        // This middleware uses a decorator on the response object
        static class TransformedResponse extends Response {
            private final Response originalResponse;
            TransformedResponse(Response original) { this.originalResponse = original; }

            @Override public String toString() {
                // Decorate the final body
                String transformedBody = String.format(
                    "{\"data\":%s,\"meta\":{\"timestamp\":\"%s\",\"version\":\"v1\"}}",
                    originalResponse.body, Instant.now().toString()
                );
                originalResponse.body = transformedBody;
                originalResponse.headers.put("Content-Type", "application/json");
                return originalResponse.toString(); // Not used, but for completeness
            }
        }

        @Override
        public void handle(Request request, Response response) {
            TransformedResponse transformedResponse = new TransformedResponse(response);
            handleNext(request, transformedResponse);
            transformedResponse.toString(); // Trigger the transformation
        }
    }

    // --- Final Business Logic Handler ---
    static class ApiHandler extends Middleware {
        private final Map<UUID, User> userStore = new ConcurrentHashMap<>();

        public ApiHandler() {
            User admin = new User("admin@example.com", "hash1", UserRole.ADMIN);
            userStore.put(admin.id, admin);
        }

        @Override
        public void handle(Request request, Response response) {
            if ("/users".equals(request.path) && "GET".equals(request.method)) {
                response.statusCode = 200;
                response.body = userStore.values().toString();
            } else {
                response.statusCode = 404;
                response.body = "{\"error\":\"Not Found\"}";
            }
        }
    }

    // --- Main Execution ---
    public static void main(String[] args) {
        // 1. Build the middleware chain
        Middleware chain = Middleware.link(
            new ErrorHandlingMiddleware(),
            new LoggingMiddleware(),
            new CorsMiddleware(),
            new RateLimitingMiddleware(5, 60), // 5 requests per 60 seconds
            new ResponseTransformationMiddleware(),
            new ApiHandler()
        );

        // 2. Simulate a successful request
        System.out.println("--- Simulating a successful request ---");
        Request req1 = new Request("GET", "/users", "192.168.1.1");
        Response res1 = new Response();
        chain.handle(req1, res1);
        System.out.println("Final Response Status: " + res1.statusCode);
        System.out.println("Final Response Headers: " + res1.headers);
        System.out.println("Final Response Body: " + res1.body);
        System.out.println("\n");

        // 3. Simulate a rate-limited request
        System.out.println("--- Simulating rate limiting ---");
        for (int i = 0; i < 6; i++) {
            Request req = new Request("GET", "/users", "10.0.0.2");
            Response res = new Response();
            System.out.print("Request " + (i + 1) + ": ");
            chain.handle(req, res);
            System.out.println("Status " + res.statusCode);
        }
        
        // 4. Simulate a not found request
        System.out.println("\n--- Simulating a 404 Not Found request ---");
        Request req2 = new Request("GET", "/posts", "192.168.1.1");
        Response res2 = new Response();
        chain.handle(req2, res2);
        System.out.println("Final Response Status: " + res2.statusCode);
        System.out.println("Final Response Body: " + res2.body);
    }
}