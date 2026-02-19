package com.database.service_mapper;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

// --- Domain Model ---

enum PostStatus { DRAFT, PUBLISHED }

class User {
    UUID id;
    String email;
    String passwordHash;
    boolean isActive;
    Timestamp createdAt;
    List<Role> roles = new ArrayList<>();

    public User(UUID id, String email, String passwordHash, boolean isActive, Timestamp createdAt) {
        this.id = id; this.email = email; this.passwordHash = passwordHash; this.isActive = isActive; this.createdAt = createdAt;
    }
    @Override public String toString() { return "User{id=" + id + ", email='" + email + "', roles=" + roles.stream().map(r -> r.name).collect(Collectors.joining(",")) + "}"; }
}

class Post {
    UUID id;
    UUID userId;
    String title;
    String content;
    PostStatus status;

    public Post(UUID id, UUID userId, String title, String content, PostStatus status) {
        this.id = id; this.userId = userId; this.title = title; this.content = content; this.status = status;
    }
    @Override public String toString() { return "Post{id=" + id + ", title='" + title + "', status=" + status + "}"; }
}

class Role {
    int id;
    String name;
    public Role(int id, String name) { this.id = id; this.name = name; }
    @Override public String toString() { return "Role{id=" + id + ", name='" + name + "'}"; }
}

// --- Data Mappers ---

class UserMapper {
    public User toUser(ResultSet rs) throws SQLException {
        return new User(
            rs.getObject("id", UUID.class),
            rs.getString("email"),
            rs.getString("password_hash"),
            rs.getBoolean("is_active"),
            rs.getTimestamp("created_at")
        );
    }

    public List<User> toUserList(ResultSet rs) throws SQLException {
        List<User> users = new ArrayList<>();
        while (rs.next()) {
            users.add(toUser(rs));
        }
        return users;
    }
}

class PostMapper {
    public Post toPost(ResultSet rs) throws SQLException {
        return new Post(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getString("title"),
            rs.getString("content"),
            PostStatus.valueOf(rs.getString("status"))
        );
    }

    public List<Post> toPostList(ResultSet rs) throws SQLException {
        List<Post> posts = new ArrayList<>();
        while (rs.next()) {
            posts.add(toPost(rs));
        }
        return posts;
    }
}

class RoleMapper {
    public Role toRole(ResultSet rs) throws SQLException {
        return new Role(rs.getInt("id"), rs.getString("name"));
    }
    public List<Role> toRoleList(ResultSet rs) throws SQLException {
        List<Role> roles = new ArrayList<>();
        while(rs.next()) {
            roles.add(toRole(rs));
        }
        return roles;
    }
}

// --- Service Layer ---

class UserService {
    private final Connection _connection;
    private final UserMapper _userMapper = new UserMapper();
    private final RoleMapper _roleMapper = new RoleMapper();

    public UserService(Connection connection) { this._connection = connection; }

    public void createUser(User user) throws SQLException {
        String sql = "INSERT INTO users (id, email, password_hash, is_active, created_at) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = _connection.prepareStatement(sql)) {
            ps.setObject(1, user.id);
            ps.setString(2, user.email);
            ps.setString(3, user.passwordHash);
            ps.setBoolean(4, user.isActive);
            ps.setTimestamp(5, user.createdAt);
            ps.executeUpdate();
        }
    }

    public User findUserById(UUID id) throws SQLException {
        String sql = "SELECT * FROM users WHERE id = ?";
        try (PreparedStatement ps = _connection.prepareStatement(sql)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? _userMapper.toUser(rs) : null;
            }
        }
    }

    public List<User> findUsers(Map<String, Object> filters) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT * FROM users WHERE 1=1");
        List<Object> params = new ArrayList<>();
        filters.forEach((key, value) -> {
            sql.append(" AND ").append(key).append(" = ?");
            params.add(value);
        });
        try (PreparedStatement ps = _connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                return _userMapper.toUserList(rs);
            }
        }
    }

    public void assignRoleToUser(UUID userId, int roleId) throws SQLException {
        String sql = "INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)";
        try (PreparedStatement ps = _connection.prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setInt(2, roleId);
            ps.executeUpdate();
        }
    }

    public List<Role> findRolesForUser(UUID userId) throws SQLException {
        String sql = "SELECT r.id, r.name FROM roles r JOIN user_roles ur ON r.id = ur.role_id WHERE ur.user_id = ?";
        try (PreparedStatement ps = _connection.prepareStatement(sql)) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return _roleMapper.toRoleList(rs);
            }
        }
    }
}

class PostService {
    private final Connection _connection;
    private final PostMapper _postMapper = new PostMapper();

    public PostService(Connection connection) { this._connection = connection; }

    public void createPost(Post post) throws SQLException {
        String sql = "INSERT INTO posts (id, user_id, title, content, status) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = _connection.prepareStatement(sql)) {
            ps.setObject(1, post.id);
            ps.setObject(2, post.userId);
            ps.setString(3, post.title);
            ps.setString(4, post.content);
            ps.setString(5, post.status.name());
            ps.executeUpdate();
        }
    }

    public List<Post> findPostsByAuthor(UUID userId) throws SQLException {
        String sql = "SELECT * FROM posts WHERE user_id = ?";
        try (PreparedStatement ps = _connection.prepareStatement(sql)) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return _postMapper.toPostList(rs);
            }
        }
    }
}

class RoleService {
    private final Connection _connection;
    public RoleService(Connection connection) { this._connection = connection; }
    public void createRole(Role role) throws SQLException {
        String sql = "INSERT INTO roles (id, name) VALUES (?, ?)";
        try (PreparedStatement ps = _connection.prepareStatement(sql)) {
            ps.setInt(1, role.id);
            ps.setString(2, role.name);
            ps.executeUpdate();
        }
    }
}

// --- Migration and Runner ---

public class ServiceMapperDemo {
    private static final String DB_URL = "jdbc:h2:mem:db4;DB_CLOSE_DELAY=-1";

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
            
            UserService userService = new UserService(conn);
            PostService postService = new PostService(conn);
            RoleService roleService = new RoleService(conn);

            // --- Transactional Operations ---
            conn.setAutoCommit(false);
            UUID user1Id = UUID.randomUUID();
            try {
                System.out.println("\n--- CRUD, Relationships, and Transaction Demo ---");
                // Create Roles
                Role adminRole = new Role(1, "ADMIN");
                Role userRole = new Role(2, "USER");
                roleService.createRole(adminRole);
                roleService.createRole(userRole);

                // Create User (C)
                User user1 = new User(user1Id, "admin@example.com", "hash1", true, Timestamp.from(Instant.now()));
                userService.createUser(user1);
                System.out.println("Created user: " + user1.email);

                // Assign Roles (Many-to-Many)
                userService.assignRoleToUser(user1.id, adminRole.id);
                userService.assignRoleToUser(user1.id, userRole.id);
                System.out.println("Assigned roles to user.");

                // Create Posts (One-to-Many)
                Post post1 = new Post(UUID.randomUUID(), user1.id, "First Post", "Content here", PostStatus.PUBLISHED);
                postService.createPost(post1);
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
            User foundUser = userService.findUserById(user1Id);
            if (foundUser != null) {
                foundUser.roles = userService.findRolesForUser(foundUser.id);
                System.out.println("Found user by ID: " + foundUser);
                List<Post> userPosts = postService.findPostsByAuthor(foundUser.id);
                System.out.println("Posts by user: " + userPosts);
            }

            // --- Query with Filter ---
            System.out.println("\n--- Filter Query Demo ---");
            List<User> activeUsers = userService.findUsers(Map.of("is_active", true));
            System.out.println("Found active users: " + activeUsers.size());

            // --- Transaction with Rollback Demo ---
            System.out.println("\n--- Rollback Demo ---");
            UUID user2Id = UUID.randomUUID();
            conn.setAutoCommit(false);
            try {
                User user2 = new User(user2Id, "fail@example.com", "hash2", true, Timestamp.from(Instant.now()));
                userService.createUser(user2);
                System.out.println("Created temporary user: " + user2.email);
                throw new SQLException("Simulating a failure to trigger rollback.");
            } catch (SQLException e) {
                System.err.println(e.getMessage());
                conn.rollback();
                System.out.println("Transaction rolled back.");
            } finally {
                conn.setAutoCommit(true);
            }

            User rolledBackUser = userService.findUserById(user2Id);
            System.out.println("User after rollback: " + (rolledBackUser == null ? "Not found (correct)" : "Found (incorrect)"));

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}