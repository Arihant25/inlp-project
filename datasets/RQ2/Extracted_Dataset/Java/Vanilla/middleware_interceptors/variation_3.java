import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PipelineIteratorMiddleware {

    // --- Domain Schema ---
    enum UserRole { ADMIN, USER }
    enum PostStatus { DRAFT, PUBLISHED }

    static class User {
        UUID id; String email; String password_hash; UserRole role; boolean is_active; Instant created_at;
        public User(String email) {
            this.id = UUID.randomUUID(); this.email = email; this.password_hash = "secret";
            this.role = UserRole.USER; this.is_active = true; this.created_at = Instant.now();
        }
        public String asJson() {
            return String.format("{\"id\":\"%s\",\"email\":\"%s\"}", id, email);
        }
    }

    static class Post {
        UUID id; UUID user_id; String title; String content; PostStatus status;
        public Post(UUID user_id, String title) {
            this.id = UUID.randomUUID(); this.user_id = user_id; this.title = title;
            this.content = "Lorem ipsum..."; this.status = PostStatus.DRAFT;
        }
        public String asJson() {
            return String.format("{\"id\":\"%s\",\"title\":\"%s\"}", id, title);
        }
    }

    // --- HTTP Abstraction ---
    static class RequestContext {
        final String method; final String path; final Map<String, String> headers;
        int status_code = 200; String response_body = ""; Map<String, String> response_headers = new HashMap<>();
        RequestContext(String method, String path) {
            this.method = method; this.path = path; this.headers = new HashMap<>();
        }
    }

    // --- Middleware Pattern (Iterator-based Pipeline) ---
    @FunctionalInterface
    interface Middleware {
        void handle(RequestContext context, Pipeline next);
    }

    static class Pipeline {
        private final List<Middleware> middlewares;
        private int index = 0;

        Pipeline(List<Middleware> middlewares) {
            this.middlewares = middlewares;
        }

        public void next(RequestContext context) {
            if (index < middlewares.size()) {
                middlewares.get(index++).handle(context, this);
            }
        }
    }

    // --- Concrete Middleware Implementations ---
    static class LoggerMiddleware implements Middleware {
        @Override
        public void handle(RequestContext context, Pipeline pipeline) {
            System.out.printf("-> IN: %s %s\n", context.method, context.path);
            pipeline.next(context);
            System.out.printf("<- OUT: %d\n", context.status_code);
        }
    }

    static class CorsMiddleware implements Middleware {
        @Override
        public void handle(RequestContext context, Pipeline pipeline) {
            context.response_headers.put("Access-Control-Allow-Origin", "*");
            if ("OPTIONS".equals(context.method)) {
                context.status_code = 204;
                // Stop the pipeline
                return;
            }
            pipeline.next(context);
        }
    }

    static class RateLimiterMiddleware implements Middleware {
        private final Map<String, Long> last_request_time = new ConcurrentHashMap<>();
        private final long min_interval_ms;

        public RateLimiterMiddleware(long min_interval_ms) {
            this.min_interval_ms = min_interval_ms;
        }

        @Override
        public void handle(RequestContext context, Pipeline pipeline) {
            String client_ip = context.headers.getOrDefault("Remote-Addr", "localhost");
            long now = System.currentTimeMillis();
            long last_req = last_request_time.getOrDefault(client_ip, 0L);

            if (now - last_req < min_interval_ms) {
                context.status_code = 429;
                context.response_body = "{\"error\":\"Rate limit hit\"}";
                return; // Stop pipeline
            }
            last_request_time.put(client_ip, now);
            pipeline.next(context);
        }
    }

    static class ErrorHandlerMiddleware implements Middleware {
        @Override
        public void handle(RequestContext context, Pipeline pipeline) {
            try {
                pipeline.next(context);
            } catch (Exception e) {
                System.err.println("Caught exception in pipeline: " + e.getMessage());
                context.status_code = 500;
                context.response_body = "{\"error\":\"Server fault\"}";
            }
        }
    }
    
    // --- Decorator for Transformation ---
    // In this pattern, transformation is just another middleware in the list
    static class JsonResponseTransformerMiddleware implements Middleware {
        @Override
        public void handle(RequestContext context, Pipeline pipeline) {
            pipeline.next(context); // Let the API handler run first
            
            // Now, decorate the response
            if (context.status_code == 200 && !context.response_body.isEmpty()) {
                context.response_body = String.format(
                    "{\"status\":\"success\",\"data\":%s}",
                    context.response_body
                );
                context.response_headers.put("Content-Type", "application/json");
            }
        }
    }

    // --- Final Business Logic Handler (as a Middleware) ---
    static class ApiRouter implements Middleware {
        private final Map<String, User> user_db = new ConcurrentHashMap<>();
        public ApiRouter() {
            user_db.put("user1", new User("user1@domain.com"));
        }

        @Override
        public void handle(RequestContext context, Pipeline pipeline) {
            if ("/users".equals(context.path)) {
                context.response_body = "[" + user_db.values().stream().map(User::asJson).reduce((a, b) -> a + "," + b).orElse("") + "]";
                context.status_code = 200;
            } else {
                context.status_code = 404;
                context.response_body = "{\"error\":\"Route not found\"}";
            }
            // This is the last in the chain, so it doesn't call pipeline.next()
        }
    }

    // --- Main Execution ---
    public static void main(String[] args) {
        // 1. Create a list of middleware. Order matters.
        List<Middleware> middleware_stack = new ArrayList<>();
        middleware_stack.add(new ErrorHandlerMiddleware());
        middleware_stack.add(new LoggerMiddleware());
        middleware_stack.add(new CorsMiddleware());
        middleware_stack.add(new RateLimiterMiddleware(1000)); // 1s interval
        middleware_stack.add(new JsonResponseTransformerMiddleware()); // Transformation happens after handler
        middleware_stack.add(new ApiRouter()); // The actual endpoint logic

        // 2. Simulate a request
        System.out.println("--- Simulating a valid request ---");
        RequestContext ctx1 = new RequestContext("GET", "/users");
        ctx1.headers.put("Remote-Addr", "1.2.3.4");
        
        Pipeline pipeline1 = new Pipeline(new ArrayList<>(middleware_stack));
        pipeline1.next(ctx1);
        
        System.out.println("Final Response: " + ctx1.status_code);
        System.out.println("Body: " + ctx1.response_body);
        System.out.println("Headers: " + ctx1.response_headers);
        System.out.println();

        // 3. Simulate a rate-limited request
        System.out.println("--- Simulating a rate-limited request ---");
        RequestContext ctx2 = new RequestContext("GET", "/users");
        ctx2.headers.put("Remote-Addr", "5.6.7.8");
        
        Pipeline pipeline2 = new Pipeline(new ArrayList<>(middleware_stack));
        pipeline2.next(ctx2); // First request from this IP is OK
        System.out.println("First call status: " + ctx2.status_code);

        RequestContext ctx3 = new RequestContext("GET", "/users");
        ctx3.headers.put("Remote-Addr", "5.6.7.8");
        Pipeline pipeline3 = new Pipeline(new ArrayList<>(middleware_stack));
        pipeline3.next(ctx3); // Second immediate request should be blocked
        System.out.println("Second call status: " + ctx3.status_code);
        System.out.println("Body: " + ctx3.response_body);
    }
}