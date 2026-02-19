package variation2;

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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Variation 2: Classic OOP with Service & Repository Layers
 * This approach separates concerns into distinct layers:
 * - ApiServer: Entry point, server configuration.
 * - UserHandler (Presentation): Handles HTTP request/response, delegates to the service.
 * - UserService (Business Logic): Contains validation and business rules.
 * - UserRepository (Data Access): Manages the in-memory data store.
 * - Domain models and a JSON utility are also separated.
 */
public class LayeredApiServer {

    public static void main(String[] args) throws IOException {
        // Dependency Injection (manual)
        UserRepository userRepository = new UserRepository();
        UserService userService = new UserService(userRepository);
        UserHandler userHandler = new UserHandler(userService);

        HttpServer server = HttpServer.create(new InetSocketAddress(8002), 0);
        server.createContext("/users", userHandler);
        server.createContext("/users/", userHandler);
        server.setExecutor(null); // Use default executor
        server.start();
        System.out.println("Server 2 (Layered) started on port 8002");
    }
}

// --- DOMAIN MODEL ---
enum UserRole { ADMIN, USER }

class User {
    final UUID id;
    String email;
    String passwordHash;
    UserRole role;
    boolean isActive;
    final Timestamp createdAt;

    User(UUID id, String email, String passwordHash, UserRole role, boolean isActive, Timestamp createdAt) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.isActive = isActive;
        this.createdAt = createdAt;
    }
}

// --- PRESENTATION LAYER ---
class UserHandler implements HttpHandler {
    private final UserService userService;

    public UserHandler(UserService userService) {
        this.userService = userService;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String[] segments = path.split("/");

            if (path.equals("/users") || path.equals("/users/")) {
                if ("GET".equals(method)) {
                    listUsers(exchange);
                } else if ("POST".equals(method)) {
                    createUser(exchange);
                } else {
                    sendJsonResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                }
            } else if (segments.length == 3 && segments[1].equals("users")) {
                UUID userId = UUID.fromString(segments[2]);
                switch (method) {
                    case "GET":
                        getUser(exchange, userId);
                        break;
                    case "PUT":
                    case "PATCH":
                        updateUser(exchange, userId);
                        break;
                    case "DELETE":
                        deleteUser(exchange, userId);
                        break;
                    default:
                        sendJsonResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                        break;
                }
            } else {
                sendJsonResponse(exchange, 404, "{\"error\":\"Not Found\"}");
            }
        } catch (IllegalArgumentException e) {
            sendJsonResponse(exchange, 400, "{\"error\":\"Invalid UUID format\"}");
        } catch (NoSuchElementException e) {
            sendJsonResponse(exchange, 404, "{\"error\":\"" + e.getMessage() + "\"}");
        } catch (Exception e) {
            e.printStackTrace();
            sendJsonResponse(exchange, 500, "{\"error\":\"Internal Server Error\"}");
        }
    }

    private void listUsers(HttpExchange exchange) throws IOException {
        Map<String, String> params = QueryParser.parse(exchange.getRequestURI().getQuery());
        List<User> users = userService.findUsers(params);
        sendJsonResponse(exchange, 200, JsonUtil.toJson(users));
    }

    private void createUser(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> data = JsonUtil.parse(body);
        User createdUser = userService.createUser(data);
        sendJsonResponse(exchange, 201, JsonUtil.toJson(createdUser));
    }

    private void getUser(HttpExchange exchange, UUID userId) throws IOException {
        User user = userService.getUserById(userId);
        sendJsonResponse(exchange, 200, JsonUtil.toJson(user));
    }

    private void updateUser(HttpExchange exchange, UUID userId) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> data = JsonUtil.parse(body);
        User updatedUser = userService.updateUser(userId, data);
        sendJsonResponse(exchange, 200, JsonUtil.toJson(updatedUser));
    }

    private void deleteUser(HttpExchange exchange, UUID userId) throws IOException {
        userService.deleteUser(userId);
        exchange.sendResponseHeaders(204, -1); // No content
    }

    private void sendJsonResponse(HttpExchange exchange, int code, String json) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        byte[] responseBytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }
}

// --- SERVICE LAYER ---
class UserService {
    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User getUserById(UUID id) {
        return userRepository.findById(id)
            .orElseThrow(() -> new NoSuchElementException("User not found"));
    }

    public List<User> findUsers(Map<String, String> filters) {
        int page = Integer.parseInt(filters.getOrDefault("page", "1"));
        int limit = Integer.parseInt(filters.getOrDefault("limit", "10"));
        return userRepository.findAll(filters, (page - 1) * limit, limit);
    }

    public User createUser(Map<String, String> data) {
        if (!data.containsKey("email") || !data.containsKey("password")) {
            throw new IllegalArgumentException("Email and password are required");
        }
        User user = new User(
            UUID.randomUUID(),
            data.get("email"),
            "hashed:" + data.get("password"),
            UserRole.valueOf(data.getOrDefault("role", "USER").toUpperCase()),
            Boolean.parseBoolean(data.getOrDefault("is_active", "true")),
            Timestamp.from(Instant.now())
        );
        return userRepository.save(user);
    }

    public User updateUser(UUID id, Map<String, String> data) {
        User user = getUserById(id);
        data.forEach((key, value) -> {
            switch (key) {
                case "email": user.email = value; break;
                case "role": user.role = UserRole.valueOf(value.toUpperCase()); break;
                case "is_active": user.isActive = Boolean.parseBoolean(value); break;
            }
        });
        return userRepository.save(user);
    }

    public void deleteUser(UUID id) {
        if (!userRepository.deleteById(id)) {
            throw new NoSuchElementException("User not found for deletion");
        }
    }
}

// --- DATA ACCESS LAYER ---
class UserRepository {
    private final Map<UUID, User> userTable = new ConcurrentHashMap<>();

    public UserRepository() {
        // Seed data
        User u1 = new User(UUID.randomUUID(), "admin.layered@example.com", "hash1", UserRole.ADMIN, true, Timestamp.from(Instant.now()));
        User u2 = new User(UUID.randomUUID(), "user.layered@example.com", "hash2", UserRole.USER, true, Timestamp.from(Instant.now()));
        userTable.put(u1.id, u1);
        userTable.put(u2.id, u2);
    }

    public Optional<User> findById(UUID id) {
        return Optional.ofNullable(userTable.get(id));
    }

    public List<User> findAll(Map<String, String> filters, int offset, int limit) {
        return userTable.values().stream()
            .filter(u -> filters.get("email") == null || u.email.contains(filters.get("email")))
            .filter(u -> filters.get("role") == null || u.role.name().equalsIgnoreCase(filters.get("role")))
            .filter(u -> filters.get("is_active") == null || u.isActive == Boolean.parseBoolean(filters.get("is_active")))
            .sorted(Comparator.comparing(u -> u.createdAt))
            .skip(offset)
            .limit(limit)
            .collect(Collectors.toList());
    }

    public User save(User user) {
        userTable.put(user.id, user);
        return user;
    }

    public boolean deleteById(UUID id) {
        return userTable.remove(id) != null;
    }
}

// --- UTILITIES ---
class JsonUtil {
    public static String toJson(User user) {
        return String.format(
            "{\"id\":\"%s\",\"email\":\"%s\",\"role\":\"%s\",\"is_active\":%b,\"created_at\":\"%s\"}",
            user.id, user.email, user.role, user.isActive, user.createdAt.toInstant()
        );
    }

    public static String toJson(List<User> users) {
        return users.stream().map(JsonUtil::toJson).collect(Collectors.joining(", ", "[", "]"));
    }


    public static Map<String, String> parse(String json) {
        Map<String, String> map = new HashMap<>();
        String content = json.trim();
        if (content.startsWith("{") && content.endsWith("}")) {
            content = content.substring(1, content.length() - 1);
            for (String pair : content.split(",")) {
                String[] keyValue = pair.split(":", 2);
                if (keyValue.length == 2) {
                    String key = keyValue[0].trim().replace("\"", "");
                    String value = keyValue[1].trim().replace("\"", "");
                    map.put(key, value);
                }
            }
        }
        return map;
    }
}

class QueryParser {
    public static Map<String, String> parse(String query) {
        if (query == null || query.isEmpty()) return Collections.emptyMap();
        Map<String, String> params = new HashMap<>();
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length > 1) {
                params.put(pair[0], pair[1]);
            } else {
                params.put(pair[0], "");
            }
        }
        return params;
    }
}