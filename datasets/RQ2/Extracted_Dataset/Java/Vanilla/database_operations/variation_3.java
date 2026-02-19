package com.database.functional_style;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

// --- Data Transfer Objects (DTOs) ---

class UserDTO {
    final UUID id;
    final String email;
    final String passwordHash;
    final boolean isActive;
    final Timestamp createdAt;
    List<RoleDTO> roles = new ArrayList<>();

    UserDTO(UUID id, String email, String passwordHash, boolean isActive, Timestamp createdAt) {
        this.id = id; this.email = email; this.passwordHash = passwordHash; this.isActive = isActive; this.createdAt = createdAt;
    }
    @Override public String toString() { return "UserDTO{id=" + id + ", email='" + email + "', roles=" + roles.stream().map(r -> r.name).collect(Collectors.joining(",")) + "}"; }
}

class PostDTO {
    final UUID id;
    final UUID userId;
    final String title;
    final String content;
    final String status;

    PostDTO(UUID id, UUID userId, String title, String content, String status) {
        this.id = id; this.userId = userId; this.title = title; this.content = content; this.status = status;
    }
    @Override public String toString() { return "PostDTO{id=" + id + ", title='" + title + "', status=" + status + "}"; }
}

class RoleDTO {
    final int id;
    final String name;
    RoleDTO(int id, String name) { this.id = id; this.name = name; }
    @Override public String toString() { return "RoleDTO{id=" + id + ", name='" + name + "'}"; }
}

// --- Static Database Operations Utility Class ---

final class DbOperations {
    private DbOperations() {} // Prevent instantiation

    // --- Generic Query Executor ---
    private static <T> List<T> query(Connection c, String sql, Function<ResultSet, T> mapper, Object... params) throws SQLException {
        List<T> results = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapper.apply(rs));
                }
            }
        }
        return results;
    }

    // --- Generic Update Executor ---
    private static int update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            return ps.executeUpdate();
        }
    }

    // --- Mappers ---
    private static final Function<ResultSet, UserDTO> userMapper = rs -> {
        try {
            return new UserDTO(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("password_hash"), rs.getBoolean("is_active"), rs.getTimestamp("created_at"));
        } catch (SQLException e) { throw new RuntimeException(e); }
    };
    private static final Function<ResultSet, PostDTO> postMapper = rs -> {
        try {
            return new PostDTO(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("title"), rs.getString("content"), rs.getString("status"));
        } catch (SQLException e) { throw new RuntimeException(e); }
    };
    private static final Function<ResultSet, RoleDTO> roleMapper = rs -> {
        try {
            return new RoleDTO(rs.getInt("id"), rs.getString("name"));
        } catch (SQLException e) { throw new RuntimeException(e); }
    };

    // --- User Operations ---
    public static void insertUser(Connection c, UserDTO user) throws SQLException {
        update(c, "INSERT INTO users (id, email, password_hash, is_active, created_at) VALUES (?, ?, ?, ?, ?)", user.id, user.email, user.passwordHash, user.isActive, user.createdAt);
    }
    public static UserDTO fetchUser(Connection c, UUID id) throws SQLException {
        return query(c, "SELECT * FROM users WHERE id = ?", userMapper, id).stream().findFirst().orElse(null);
    }
    public static void deleteUser(Connection c, UUID id) throws SQLException {
        update(c, "DELETE FROM users WHERE id = ?", id);
    }
    public static List<UserDTO> findUsersByFilter(Connection c, Map<String, Object> filters) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT * FROM users WHERE 1=1");
        List<Object> params = new ArrayList<>();
        filters.forEach((key, value) -> {
            sql.append(" AND ").append(key).append(" = ?");
            params.add(value);
        });
        return query(c, sql.toString(), userMapper, params.toArray());
    }

    // --- Post Operations ---
    public static void insertPost(Connection c, PostDTO post) throws SQLException {
        update(c, "INSERT INTO posts (id, user_id, title, content, status) VALUES (?, ?, ?, ?, ?)", post.id, post.userId, post.title, post.content, post.status);
    }
    public static List<PostDTO> fetchPostsForUser(Connection c, UUID userId) throws SQLException {
        return query(c, "SELECT * FROM posts WHERE user_id = ?", postMapper, userId);
    }

    // --- Role Operations ---
    public static void insertRole(Connection c, RoleDTO role) throws SQLException {
        update(c, "INSERT INTO roles (id, name) VALUES (?, ?)", role.id, role.name);
    }
    public static void linkUserToRole(Connection c, UUID userId, int roleId) throws SQLException {
        update(c, "INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)", userId, roleId);
    }
    public static List<RoleDTO> fetchRolesForUser(Connection c, UUID userId) throws SQLException {
        String sql = "SELECT r.id, r.name FROM roles r JOIN user_roles ur ON r.id = ur.role_id WHERE ur.user_id = ?";
        return query(c, sql, roleMapper, userId);
    }
}

// --- Migration and Runner ---
public class FunctionalStyleDemo {
    private static final String DB_URL = "jdbc:h2:mem:db3;DB_CLOSE_DELAY=-1";

    public static void runMigrations(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS schema_version (version INT PRIMARY KEY, applied_on TIMESTAMP NOT NULL)");
            ResultSet rs = stmt.executeQuery("SELECT MAX(version) FROM schema_version");
            int currentVersion = rs.next() ? rs.getInt(1) : 0;

            if (currentVersion < 1) {
                System.out.println("Applying migration 1...");
                stmt.execute("CREATE TABLE users (id UUID PRIMARY KEY, email VARCHAR(255) UNIQUE NOT NULL, password_hash VARCHAR(255) NOT NULL, is_active BOOLEAN DEFAULT TRUE, created_at TIMESTAMP NOT NULL)");
                stmt.execute("CREATE TABLE posts (id UUID PRIMARY KEY, user_id UUID NOT NULL, title VARCHAR(255) NOT NULL, content TEXT, status VARCHAR(50) NOT NULL, FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE)");
                stmt.execute("CREATE TABLE roles (id INT PRIMARY KEY, name VARCHAR(50) UNIQUE NOT NULL)");
                stmt.execute("CREATE TABLE user_roles (user_id UUID NOT NULL, role_id INT NOT NULL, PRIMARY KEY (user_id, role_id), FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE, FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE)");
                stmt.execute("INSERT INTO schema_version (version, applied_on) VALUES (1, CURRENT_TIMESTAMP)");
                System.out.println("Migration 1 applied.");
            }
        }
    }

    public static void main(String[] args) {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            runMigrations(conn);

            // --- Transactional Operations ---
            conn.setAutoCommit(false);
            UUID user1Id = UUID.randomUUID();
            try {
                System.out.println("\n--- CRUD, Relationships, and Transaction Demo ---");
                // Create Roles
                RoleDTO adminRole = new RoleDTO(1, "ADMIN");
                RoleDTO userRole = new RoleDTO(2, "USER");
                DbOperations.insertRole(conn, adminRole);
                DbOperations.insertRole(conn, userRole);

                // Create User (C)
                UserDTO user1 = new UserDTO(user1Id, "admin@example.com", "hash1", true, Timestamp.from(Instant.now()));
                DbOperations.insertUser(conn, user1);
                System.out.println("Created user: " + user1.email);

                // Assign Roles (Many-to-Many)
                DbOperations.linkUserToRole(conn, user1.id, adminRole.id);
                DbOperations.linkUserToRole(conn, user1.id, userRole.id);
                System.out.println("Assigned roles to user.");

                // Create Posts (One-to-Many)
                PostDTO post1 = new PostDTO(UUID.randomUUID(), user1.id, "First Post", "Content here", "PUBLISHED");
                DbOperations.insertPost(conn, post1);
                System.out.println("Created post: " + post1.title);

                conn.commit();
                System.out.println("Transaction committed.");
            } catch (SQLException e) {
                System.err.println("Transaction failed, rolling back.");
                conn.rollback();
                e.printStackTrace();
            } finally {
                conn.setAutoCommit(true);
            }

            // --- Read Operations ---
            System.out.println("\n--- Read Operations Demo ---");
            UserDTO foundUser = DbOperations.fetchUser(conn, user1Id);
            if (foundUser != null) {
                foundUser.roles = DbOperations.fetchRolesForUser(conn, foundUser.id);
                System.out.println("Found user by ID: " + foundUser);
                List<PostDTO> userPosts = DbOperations.fetchPostsForUser(conn, foundUser.id);
                System.out.println("Posts by user: " + userPosts);
            }

            // --- Query with Filter ---
            System.out.println("\n--- Filter Query Demo ---");
            List<UserDTO> activeUsers = DbOperations.findUsersByFilter(conn, Map.of("is_active", true));
            System.out.println("Found active users: " + activeUsers.size());

            // --- Transaction with Rollback Demo ---
            System.out.println("\n--- Rollback Demo ---");
            UUID user2Id = UUID.randomUUID();
            conn.setAutoCommit(false);
            try {
                UserDTO user2 = new UserDTO(user2Id, "fail@example.com", "hash2", true, Timestamp.from(Instant.now()));
                DbOperations.insertUser(conn, user2);
                System.out.println("Created temporary user: " + user2.email);
                if (true) throw new SQLException("Simulating a failure to trigger rollback.");
                conn.commit();
            } catch (SQLException e) {
                System.err.println(e.getMessage());
                conn.rollback();
                System.out.println("Transaction rolled back.");
            } finally {
                conn.setAutoCommit(true);
            }

            UserDTO rolledBackUser = DbOperations.fetchUser(conn, user2Id);
            System.out.println("User after rollback: " + (rolledBackUser == null ? "Not found (correct)" : "Found (incorrect)"));

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}