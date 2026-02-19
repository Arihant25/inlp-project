package variation1;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Variation 1: Monolithic & Procedural Style
 * A single-class approach where all logic (server setup, routing, handling, data access)
 * is contained within one file. This is simple for small projects but lacks separation of concerns.
 * Routing is handled by a large if-else block directly inside the handler.
 */
public class MonolithicApiServer {

    // 1. In-memory "database"
    private static final Map<UUID, User> userStore = new ConcurrentHashMap<>();

    // 2. Domain Model
    enum Role { ADMIN, USER }
    static class User {
        UUID id;
        String email;
        String password_hash;
        Role role;
        boolean is_active;
        Timestamp created_at;

        User(String email, String password, Role role, boolean isActive) {
            this.id = UUID.randomUUID();
            this.email = email;
            this.password_hash = "hashed_" + password; // Dummy hashing
            this.role = role;
            this.is_active = isActive;
            this.created_at = Timestamp.from(Instant.now());
        }

        // Manual JSON serialization
        public String toJson() {
            return String.format(
                "{\"id\":\"%s\",\"email\":\"%s\",\"role\":\"%s\",\"is_active\":%b,\"created_at\":\"%s\"}",
                id, email, role, is_active, created_at.toInstant().toString()
            );
        }
    }

    // 3. Main Server Entry Point
    public static void main(String[] args) throws IOException {
        // Pre-populate data
        User admin = new User("admin@example.com", "pass1", Role.ADMIN, true);
        User user1 = new User("user1@example.com", "pass2", Role.USER, true);
        User user2 = new User("user2@example.com", "pass3", Role.USER, false);
        userStore.put(admin.id, admin);
        userStore.put(user1.id, user1);
        userStore.put(user2.id, user2);

        HttpServer server = HttpServer.create(new InetSocketAddress(8001), 0);
        // A single context handles all user-related paths
        server.createContext("/users", new UserHandler());
        server.createContext("/users/", new UserHandler()); // Handle trailing slash
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        System.out.println("Server 1 (Monolithic) started on port 8001");
    }

    // 4. The All-in-One Handler
    static class UserHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String[] segments = path.split("/");

            try {
                // Route: /users
                if (segments.length == 2 && segments[1].equals("users")) {
                    if ("GET".equals(method)) {
                        handleListUsers(exchange);
                    } else if ("POST".equals(method)) {
                        handleCreateUser(exchange);
                    } else {
                        sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                    }
                // Route: /users/{id}
                } else if (segments.length == 3 && segments[1].equals("users")) {
                    UUID userId;
                    try {
                        userId = UUID.fromString(segments[2]);
                    } catch (IllegalArgumentException e) {
                        sendResponse(exchange, 400, "{\"error\":\"Invalid UUID format\"}");
                        return;
                    }

                    switch (method) {
                        case "GET":
                            handleGetUserById(exchange, userId);
                            break;
                        case "PUT":
                        case "PATCH":
                            handleUpdateUser(exchange, userId);
                            break;
                        case "DELETE":
                            handleDeleteUser(exchange, userId);
                            break;
                        default:
                            sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                            break;
                    }
                } else {
                    sendResponse(exchange, 404, "{\"error\":\"Not Found\"}");
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "{\"error\":\"Internal Server Error\"}");
            }
        }

        private void handleListUsers(HttpExchange exchange) throws IOException {
            Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            int page = Integer.parseInt(params.getOrDefault("page", "1"));
            int limit = Integer.parseInt(params.getOrDefault("limit", "10"));

            List<User> filteredUsers = userStore.values().stream()
                .filter(user -> params.get("email") == null || user.email.contains(params.get("email")))
                .filter(user -> params.get("role") == null || user.role.name().equalsIgnoreCase(params.get("role")))
                .filter(user -> params.get("is_active") == null || user.is_active == Boolean.parseBoolean(params.get("is_active")))
                .collect(Collectors.toList());

            int total = filteredUsers.size();
            int offset = (page - 1) * limit;

            List<User> paginatedUsers = filteredUsers.stream()
                .skip(offset)
                .limit(limit)
                .collect(Collectors.toList());

            String userListJson = paginatedUsers.stream()
                .map(User::toJson)
                .collect(Collectors.joining(", ", "[", "]"));

            String response = String.format(
                "{\"total\":%d,\"page\":%d,\"limit\":%d,\"data\":%s}",
                total, page, limit, userListJson
            );
            sendResponse(exchange, 200, response);
        }

        private void handleCreateUser(HttpExchange exchange) throws IOException {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> bodyMap = parseJsonBody(requestBody);

            if (!bodyMap.containsKey("email") || !bodyMap.containsKey("password")) {
                sendResponse(exchange, 400, "{\"error\":\"Email and password are required\"}");
                return;
            }

            User newUser = new User(
                bodyMap.get("email"),
                bodyMap.get("password"),
                Role.valueOf(bodyMap.getOrDefault("role", "USER").toUpperCase()),
                Boolean.parseBoolean(bodyMap.getOrDefault("is_active", "true"))
            );
            userStore.put(newUser.id, newUser);
            sendResponse(exchange, 201, newUser.toJson());
        }

        private void handleGetUserById(HttpExchange exchange, UUID userId) throws IOException {
            User user = userStore.get(userId);
            if (user != null) {
                sendResponse(exchange, 200, user.toJson());
            } else {
                sendResponse(exchange, 404, "{\"error\":\"User not found\"}");
            }
        }

        private void handleUpdateUser(HttpExchange exchange, UUID userId) throws IOException {
            User user = userStore.get(userId);
            if (user == null) {
                sendResponse(exchange, 404, "{\"error\":\"User not found\"}");
                return;
            }

            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> bodyMap = parseJsonBody(requestBody);

            if (bodyMap.containsKey("email")) user.email = bodyMap.get("email");
            if (bodyMap.containsKey("role")) user.role = Role.valueOf(bodyMap.get("role").toUpperCase());
            if (bodyMap.containsKey("is_active")) user.is_active = Boolean.parseBoolean(bodyMap.get("is_active"));

            userStore.put(userId, user);
            sendResponse(exchange, 200, user.toJson());
        }

        private void handleDeleteUser(HttpExchange exchange, UUID userId) throws IOException {
            User removedUser = userStore.remove(userId);
            if (removedUser != null) {
                sendResponse(exchange, 204, "");
            } else {
                sendResponse(exchange, 404, "{\"error\":\"User not found\"}");
            }
        }
    }

    // 5. Utility Methods
    private static void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, response.getBytes(StandardCharsets.UTF_8).length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static Map<String, String> parseQuery(String query) {
        if (query == null || query.isEmpty()) {
            return java.util.Collections.emptyMap();
        }
        return java.util.Arrays.stream(query.split("&"))
            .map(s -> s.split("=", 2))
            .collect(Collectors.toMap(a -> a[0], a -> a.length > 1 ? a[1] : ""));
    }

    private static Map<String, String> parseJsonBody(String body) {
        // Extremely basic JSON parser for flat objects
        return java.util.Arrays.stream(body.replace("{", "").replace("}", "").split(","))
            .map(s -> s.split(":", 2))
            .filter(a -> a.length == 2)
            .collect(Collectors.toMap(
                a -> a[0].trim().replace("\"", ""),
                a -> a[1].trim().replace("\"", "")
            ));
    }
}