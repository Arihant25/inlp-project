import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public class PureDecoratorMiddleware {

    // --- Domain Schema ---
    enum UserRole { ADMIN, USER }
    enum PostStatus { DRAFT, PUBLISHED }

    static class User {
        UUID id; String email; String password_hash; UserRole role; boolean is_active; Instant created_at;
        public User(String email, UserRole role) {
            this.id = UUID.randomUUID(); this.email = email; this.password_hash = "hashed";
            this.role = role; this.is_active = true; this.created_at = Instant.now();
        }
        public String toJson() { return String.format("{\"id\":\"%s\",\"email\":\"%s\"}", id, email); }
    }

    static class Post {
        UUID id; UUID user_id; String title; String content; PostStatus status;
        public Post(UUID userId, String title) {
            this.id = UUID.randomUUID(); this.user_id = userId; this.title = title;
            this.content = "Content"; this.status = PostStatus.DRAFT;
        }
        public String toJson() { return String.format("{\"id\":\"%s\",\"title\":\"%s\"}", id, title); }
    }

    // --- HTTP Abstraction ---
    static class Req {
        final String method; final String path; final Map<String, String> headers = new ConcurrentHashMap<>();
        Req(String method, String path) { this.method = method; this.path = path; }
    }

    static class Res {
        int status = 200; String body = ""; Map<String, String> headers = new ConcurrentHashMap<>();
    }

    // --- Middleware Pattern (Pure Decorator) ---
    @FunctionalInterface
    interface Handler {
        Res serve(Req req);
    }

    // --- Base Handler (Business Logic) ---
    static class ApiHandler implements Handler {
        private static final User user = new User("api@test.com", UserRole.ADMIN);
        @Override
        public Res serve(Req req) {
            Res res = new Res();
            if ("/user/profile".equals(req.path)) {
                res.body = user.toJson();
                res.headers.put("Content-Type", "application/json");
            } else {
                res.status = 404;
                res.body = "{\"error\":\"not found\"}";
            }
            return res;
        }
    }

    // --- Decorator Implementations ---
    abstract static class HandlerDecorator implements Handler {
        protected final Handler wrapped;
        HandlerDecorator(Handler wrapped) { this.wrapped = wrapped; }
    }

    static class ErrorDecorator extends HandlerDecorator {
        ErrorDecorator(Handler wrapped) { super(wrapped); }
        @Override
        public Res serve(Req req) {
            try {
                return wrapped.serve(req);
            } catch (Exception e) {
                System.err.println("FATAL ERROR: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                Res res = new Res();
                res.status = 500;
                res.body = "{\"error\":\"A server error occurred\"}";
                return res;
            }
        }
    }

    static class LoggingDecorator extends HandlerDecorator {
        LoggingDecorator(Handler wrapped) { super(wrapped); }
        @Override
        public Res serve(Req req) {
            System.out.printf(">> %s %s\n", req.method, req.path);
            Res res = wrapped.serve(req);
            System.out.printf("<< %d\n", res.status);
            return res;
        }
    }

    static class CorsDecorator extends HandlerDecorator {
        CorsDecorator(Handler wrapped) { super(wrapped); }
        @Override
        public Res serve(Req req) {
            if ("OPTIONS".equalsIgnoreCase(req.method)) {
                Res res = new Res();
                res.status = 204;
                res.headers.put("Access-Control-Allow-Origin", "*");
                res.headers.put("Access-Control-Allow-Methods", "GET, POST");
                return res;
            }
            Res res = wrapped.serve(req);
            res.headers.put("Access-Control-Allow-Origin", "*");
            return res;
        }
    }

    static class RateLimitDecorator extends HandlerDecorator {
        private final Map<String, LongAdder> counts = new ConcurrentHashMap<>();
        private final int max_reqs_per_minute;

        RateLimitDecorator(Handler wrapped, int max_reqs_per_minute) {
            super(wrapped);
            this.max_reqs_per_minute = max_reqs_per_minute;
            // In a real app, a background thread would clear old entries
        }

        @Override
        public Res serve(Req req) {
            String ip = req.headers.getOrDefault("X-Forwarded-For", "127.0.0.1");
            LongAdder adder = counts.computeIfAbsent(ip, k -> new LongAdder());
            adder.increment();

            if (adder.sum() > max_reqs_per_minute) {
                Res res = new Res();
                res.status = 429;
                res.body = "{\"error\":\"Too many requests\"}";
                return res;
            }
            return wrapped.serve(req);
        }
    }

    // --- Response Transformation Decorator ---
    // This decorator wraps the response object itself to modify its final state.
    static class TransformDecorator extends HandlerDecorator {
        TransformDecorator(Handler wrapped) { super(wrapped); }

        private static class WrappedRes extends Res {
            @Override
            public String toString() {
                // This method is a hook to perform the final transformation
                if (status >= 200 && status < 300 && body != null && !body.isEmpty()) {
                    this.body = String.format("{\"response\":%s,\"server_time\":\"%s\"}", body, Instant.now());
                    this.headers.put("Content-Type", "application/json");
                }
                return super.toString();
            }
        }

        @Override
        public Res serve(Req req) {
            // Create a decorated response object
            Res decoratedRes = new WrappedRes();
            
            // The actual handler logic will populate our decorated object
            Handler tempHandler = r -> {
                Res originalRes = wrapped.serve(r);
                decoratedRes.status = originalRes.status;
                decoratedRes.body = originalRes.body;
                decoratedRes.headers.putAll(originalRes.headers);
                return decoratedRes;
            };
            
            Res finalRes = tempHandler.serve(req);
            finalRes.toString(); // Trigger the transformation logic in WrappedRes
            return finalRes;
        }
    }

    // --- Main Execution ---
    public static void main(String[] args) {
        // 1. Compose the final handler by nesting decorators.
        // The execution order is from the outside in.
        Handler handler =
            new ErrorDecorator(
            new LoggingDecorator(
            new CorsDecorator(
            new RateLimitDecorator(
            new TransformDecorator(
                new ApiHandler()
            ), 10) // 10 reqs/min
            )));

        // 2. Simulate a successful request
        System.out.println("--- Simulating a successful request ---");
        Req req1 = new Req("GET", "/user/profile");
        req1.headers.put("X-Forwarded-For", "10.10.10.1");
        Res res1 = handler.serve(req1);
        System.out.println("Status: " + res1.status);
        System.out.println("Headers: " + res1.headers);
        System.out.println("Body: " + res1.body);
        System.out.println();

        // 3. Simulate a not found request
        System.out.println("--- Simulating a 404 Not Found request ---");
        Req req2 = new Req("GET", "/posts");
        req2.headers.put("X-Forwarded-For", "10.10.10.1");
        Res res2 = handler.serve(req2);
        System.out.println("Status: " + res2.status);
        System.out.println("Body: " + res2.body);
        System.out.println();

        // 4. Simulate an OPTIONS request
        System.out.println("--- Simulating an OPTIONS pre-flight request ---");
        Req req3 = new Req("OPTIONS", "/user/profile");
        Res res3 = handler.serve(req3);
        System.out.println("Status: " + res3.status);
        System.out.println("Headers: " + res3.headers);
        System.out.println("Body: " + res3.body);
    }
}