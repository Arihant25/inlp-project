package variation4;

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
import java.util.stream.Collectors;

/**
 * Variation 4: Command Pattern
 * This design uses the Command pattern to encapsulate each API action into a separate object.
 * - A `CommandDispatcher` acts as a front controller, parsing the request and creating a specific command.
 * - An `HttpCommand` interface defines the contract for all command objects.
 * - Concrete classes like `GetUserCommand`, `CreateUserCommand`, etc., implement the logic for one action.
 * This pattern promotes high cohesion and is very extensible.
 */
public class CommandPatternApiServer {

    public static void main(String[] args) throws IOException {
        // The data source is a singleton-like static class, accessible by all commands.
        UserData.initialize();

        HttpServer server = HttpServer.create(new InetSocketAddress(8004), 0);
        server.createContext("/", new CommandDispatcher());
        server.setExecutor(java.util.concurrent.Executors.newWorkStealingPool());
        server.start();
        System.out.println("Server 4 (Command Pattern) started on port 8004");
    }
}

// --- DOMAIN MODEL ---
enum UserRole { ADMIN, USER }
class User {
    UUID id; String email; String passwordHash; UserRole role; boolean isActive; Timestamp createdAt;
    public User(String email, String password) {
        this.id = UUID.randomUUID(); this.email = email; this.passwordHash = Base64.getEncoder().encodeToString(password.getBytes());
        this.role = UserRole.USER; this.isActive = true; this.createdAt = Timestamp.from(Instant.now());
    }
    public String asJson() {
        return String.format("{\"id\":\"%s\",\"email\":\"%s\",\"role\":\"%s\",\"is_active\":%b,\"created_at\":\"%s\"}",
            id, email, role, isActive, createdAt.toInstant());
    }
}

// --- DATA SOURCE ---
class UserData {
    private static final Map<UUID, User> users = new ConcurrentHashMap<>();
    public static void initialize() {
        User u = new User("admin.cmd@example.com", "secret");
        u.role = UserRole.ADMIN;
        users.put(u.id, u);
    }
    public static Optional<User> find(UUID id) { return Optional.ofNullable(users.get(id)); }
    public static Collection<User> findAll() { return users.values(); }
    public static void save(User user) { users.put(user.id, user); }
    public static boolean delete(UUID id) { return users.remove(id) != null; }
}

// --- COMMAND INTERFACE ---
interface HttpCommand {
    void execute(HttpExchange exchange) throws IOException;
}

// --- COMMAND DISPATCHER (FRONT CONTROLLER) ---
class CommandDispatcher implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            HttpCommand command = resolveCommand(exchange);
            command.execute(exchange);
        } catch (Exception e) {
            e.printStackTrace();
            String errorJson = String.format("{\"error\":\"Internal error: %s\"}", e.getMessage());
            sendResponse(exchange, 500, errorJson);
        }
    }

    private HttpCommand resolveCommand(HttpExchange exchange) {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String[] parts = path.split("/");

        if (parts.length > 1 && parts[1].equals("users")) {
            if (parts.length == 2) { // /users
                if ("GET".equals(method)) return new ListUsersCommand(exchange);
                if ("POST".equals(method)) return new CreateUserCommand(exchange);
            } else if (parts.length == 3) { // /users/{id}
                try {
                    UUID id = UUID.fromString(parts[2]);
                    if ("GET".equals(method)) return new GetUserCommand(id);
                    if ("PUT".equals(method) || "PATCH".equals(method)) return new UpdateUserCommand(id, exchange);
                    if ("DELETE".equals(method)) return new DeleteUserCommand(id);
                } catch (IllegalArgumentException e) {
                    return new BadRequestCommand("Invalid UUID");
                }
            }
        }
        return new NotFoundCommand();
    }

    private void sendResponse(HttpExchange ex, int code, String body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, bytes.length > 0 ? bytes.length : -1);
        if (bytes.length > 0) {
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        }
    }
}

// --- CONCRETE COMMANDS ---
abstract class BaseCommand {
    protected void sendJsonResponse(HttpExchange ex, int code, String json) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }
    protected void sendError(HttpExchange ex, int code, String message) throws IOException {
        sendJsonResponse(ex, code, String.format("{\"error\":\"%s\"}", message));
    }
    protected Map<String, String> parseJson(String body) {
        Map<String, String> map = new HashMap<>();
        String content = body.trim().substring(1, body.length() - 1);
        for (String pair : content.split(",")) {
            String[] kv = pair.split(":", 2);
            if (kv.length == 2) map.put(kv[0].trim().replace("\"", ""), kv[1].trim().replace("\"", ""));
        }
        return map;
    }
}

class ListUsersCommand extends BaseCommand implements HttpCommand {
    private final Map<String, String> queryParams;
    public ListUsersCommand(HttpExchange exchange) {
        String query = exchange.getRequestURI().getQuery();
        this.queryParams = (query == null) ? Collections.emptyMap() :
            Arrays.stream(query.split("&")).map(s -> s.split("=")).collect(Collectors.toMap(a -> a[0], a -> a.length > 1 ? a[1] : ""));
    }
    @Override
    public void execute(HttpExchange exchange) throws IOException {
        List<User> users = UserData.findAll().stream()
            .filter(u -> queryParams.get("is_active") == null || u.isActive == Boolean.parseBoolean(queryParams.get("is_active")))
            .collect(Collectors.toList());
        String json = users.stream().map(User::asJson).collect(Collectors.joining(",", "[", "]"));
        sendJsonResponse(exchange, 200, json);
    }
}

class CreateUserCommand extends BaseCommand implements HttpCommand {
    private final HttpExchange exchange;
    public CreateUserCommand(HttpExchange exchange) { this.exchange = exchange; }
    @Override
    public void execute(HttpExchange exchange) throws IOException {
        String body = new String(this.exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> data = parseJson(body);
        if (!data.containsKey("email") || !data.containsKey("password")) {
            sendError(exchange, 400, "Email and password required");
            return;
        }
        User user = new User(data.get("email"), data.get("password"));
        UserData.save(user);
        sendJsonResponse(exchange, 201, user.asJson());
    }
}

class GetUserCommand extends BaseCommand implements HttpCommand {
    private final UUID userId;
    public GetUserCommand(UUID userId) { this.userId = userId; }
    @Override
    public void execute(HttpExchange exchange) throws IOException {
        UserData.find(userId)
            .ifPresentOrElse(
                user -> {
                    try { sendJsonResponse(exchange, 200, user.asJson()); } catch (IOException e) { throw new RuntimeException(e); }
                },
                () -> {
                    try { sendError(exchange, 404, "User not found"); } catch (IOException e) { throw new RuntimeException(e); }
                }
            );
    }
}

class UpdateUserCommand extends BaseCommand implements HttpCommand {
    private final UUID userId;
    private final HttpExchange exchange;
    public UpdateUserCommand(UUID userId, HttpExchange exchange) { this.userId = userId; this.exchange = exchange; }
    @Override
    public void execute(HttpExchange exchange) throws IOException {
        Optional<User> userOpt = UserData.find(userId);
        if (userOpt.isEmpty()) {
            sendError(exchange, 404, "User not found");
            return;
        }
        User user = userOpt.get();
        String body = new String(this.exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> data = parseJson(body);
        if (data.containsKey("email")) user.email = data.get("email");
        if (data.containsKey("role")) user.role = UserRole.valueOf(data.get("role").toUpperCase());
        UserData.save(user);
        sendJsonResponse(exchange, 200, user.asJson());
    }
}

class DeleteUserCommand extends BaseCommand implements HttpCommand {
    private final UUID userId;
    public DeleteUserCommand(UUID userId) { this.userId = userId; }
    @Override
    public void execute(HttpExchange exchange) throws IOException {
        if (UserData.delete(userId)) {
            exchange.sendResponseHeaders(204, -1);
        } else {
            sendError(exchange, 404, "User not found");
        }
    }
}

class NotFoundCommand extends BaseCommand implements HttpCommand {
    @Override
    public void execute(HttpExchange exchange) throws IOException {
        sendError(exchange, 404, "Not Found");
    }
}

class BadRequestCommand extends BaseCommand implements HttpCommand {
    private final String message;
    public BadRequestCommand(String message) { this.message = message; }
    @Override
    public void execute(HttpExchange exchange) throws IOException {
        sendError(exchange, 400, message);
    }
}