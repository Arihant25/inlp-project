package variation3;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Variation 3: Functional Style with a Router
 * This approach uses a more modern, functional style. A `Router` class maps HTTP methods
 * and regex-based path patterns to handler functions (lambdas). This decouples routing
 * logic from the handlers themselves, making the code more declarative.
 */
public class FunctionalApiServer {

    // --- Data Store & Domain Model ---
    private static final Map<UUID, UserDTO> userDatabase = new ConcurrentHashMap<>();
    enum Role { ADMIN, USER }
    static class UserDTO {
        UUID id; String email; String passwordHash; Role role; boolean isActive; Timestamp createdAt;
        public UserDTO(String email, String password, Role role, boolean isActive) {
            this.id = UUID.randomUUID(); this.email = email; this.passwordHash = "hash(" + password + ")";
            this.role = role; this.isActive = isActive; this.createdAt = Timestamp.from(Instant.now());
        }
    }

    public static void main(String[] args) throws IOException {
        // Seed data
        UserDTO u1 = new UserDTO("admin.func@example.com", "p1", Role.ADMIN, true);
        UserDTO u2 = new UserDTO("user.func@example.com", "p2", Role.USER, false);
        userDatabase.put(u1.id, u1);
        userDatabase.put(u2.id, u2);

        Router router = new Router();
        registerRoutes(router);

        HttpServer server = HttpServer.create(new InetSocketAddress(8003), 0);
        server.createContext("/", router);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(10));
        server.start();
        System.out.println("Server 3 (Functional Router) started on port 8003");
    }

    private static void registerRoutes(Router router) {
        // POST /users
        router.post("/users", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> params = JsonHelper.parseBody(body);
            UserDTO newUser = new UserDTO(
                params.get("email"),
                params.get("password"),
                Role.valueOf(params.getOrDefault("role", "USER").toUpperCase()),
                Boolean.parseBoolean(params.getOrDefault("is_active", "true"))
            );
            userDatabase.put(newUser.id, newUser);
            Response.json(exchange, 201, JsonHelper.fromUser(newUser));
        });

        // GET /users
        router.get("/users", exchange -> {
            Map<String, String> query = QueryHelper.parse(exchange.getRequestURI().getQuery());
            long page = Long.parseLong(query.getOrDefault("page", "1"));
            long limit = Long.parseLong(query.getOrDefault("limit", "10"));

            List<UserDTO> result = userDatabase.values().stream()
                .filter(u -> query.get("role") == null || u.role.name().equalsIgnoreCase(query.get("role")))
                .filter(u -> query.get("email") == null || u.email.contains(query.get("email")))
                .skip((page - 1) * limit)
                .limit(limit)
                .collect(Collectors.toList());
            
            Response.json(exchange, 200, JsonHelper.fromUserList(result));
        });

        // GET /users/{id}
        router.get("/users/([0-9a-fA-F\\-]+)", (exchange, params) -> {
            UUID userId = UUID.fromString(params.get(0));
            UserDTO user = userDatabase.get(userId);
            if (user != null) {
                Response.json(exchange, 200, JsonHelper.fromUser(user));
            } else {
                Response.error(exchange, 404, "User not found");
            }
        });

        // PUT /users/{id}
        router.put("/users/([0-9a-fA-F\\-]+)", (exchange, params) -> {
            UUID userId = UUID.fromString(params.get(0));
            UserDTO user = userDatabase.get(userId);
            if (user == null) {
                Response.error(exchange, 404, "User not found");
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> updates = JsonHelper.parseBody(body);
            if (updates.containsKey("email")) user.email = updates.get("email");
            if (updates.containsKey("role")) user.role = Role.valueOf(updates.get("role").toUpperCase());
            Response.json(exchange, 200, JsonHelper.fromUser(user));
        });

        // DELETE /users/{id}
        router.delete("/users/([0-9a-fA-F\\-]+)", (exchange, params) -> {
            UUID userId = UUID.fromString(params.get(0));
            if (userDatabase.remove(userId) != null) {
                Response.noContent(exchange);
            } else {
                Response.error(exchange, 404, "User not found");
            }
        });
    }
}

// --- ROUTER IMPLEMENTATION ---
@FunctionalInterface
interface RouteHandler {
    void handle(HttpExchange exchange, List<String> urlParams) throws IOException;
}

class Router implements HttpHandler {
    private final List<Route> routes = new ArrayList<>();

    public void get(String pathRegex, RouteHandler handler) { addRoute("GET", pathRegex, handler); }
    public void post(String pathRegex, RouteHandler handler) { addRoute("POST", pathRegex, handler); }
    public void put(String pathRegex, RouteHandler handler) { addRoute("PUT", pathRegex, handler); }
    public void delete(String pathRegex, RouteHandler handler) { addRoute("DELETE", pathRegex, handler); }

    private void addRoute(String method, String pathRegex, RouteHandler handler) {
        routes.add(new Route(method, Pattern.compile("^" + pathRegex + "$"), handler));
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        for (Route route : routes) {
            Matcher matcher = route.pathPattern.matcher(path);
            if (method.equals(route.method) && matcher.matches()) {
                List<String> params = new ArrayList<>();
                for (int i = 1; i <= matcher.groupCount(); i++) {
                    params.add(matcher.group(i));
                }
                route.handler.handle(exchange, params);
                return;
            }
        }
        Response.error(exchange, 404, "Endpoint not found");
    }

    private static class Route {
        final String method;
        final Pattern pathPattern;
        final RouteHandler handler;
        Route(String method, Pattern pattern, RouteHandler handler) {
            this.method = method; this.pathPattern = pattern; this.handler = handler;
        }
    }
}

// --- HELPERS ---
class Response {
    public static void send(HttpExchange ex, int code, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }
    public static void json(HttpExchange ex, int code, String json) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json");
        send(ex, code, json);
    }
    public static void error(HttpExchange ex, int code, String message) throws IOException {
        json(ex, code, String.format("{\"error\":\"%s\"}", message));
    }
    public static void noContent(HttpExchange ex) throws IOException {
        ex.sendResponseHeaders(204, -1);
    }
}

class JsonHelper {
    public static String fromUser(FunctionalApiServer.UserDTO user) {
        return String.format("{\"id\":\"%s\",\"email\":\"%s\",\"role\":\"%s\",\"is_active\":%b,\"created_at\":\"%s\"}",
            user.id, user.email, user.role, user.isActive, user.createdAt.toInstant());
    }
    public static String fromUserList(List<FunctionalApiServer.UserDTO> users) {
        return users.stream().map(JsonHelper::fromUser).collect(Collectors.joining(",", "[", "]"));
    }
    public static Map<String, String> parseBody(String body) {
        return Arrays.stream(body.replaceAll("[{}\"]", "").split(","))
            .map(entry -> entry.split(":", 2))
            .filter(parts -> parts.length == 2)
            .collect(Collectors.toMap(parts -> parts[0].trim(), parts -> parts[1].trim()));
    }
}

class QueryHelper {
    public static Map<String, String> parse(String query) {
        if (query == null) return Collections.emptyMap();
        return Arrays.stream(query.split("&"))
            .map(param -> param.split("=", 2))
            .collect(Collectors.toMap(p -> p[0], p -> p.length > 1 ? p[1] : ""));
    }
}