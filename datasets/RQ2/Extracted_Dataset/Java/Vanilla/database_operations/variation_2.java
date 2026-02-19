package com.database.repository_uow;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

// --- Domain Entities ---

enum PostStatus { DRAFT, PUBLISHED }

class UserEntity {
    UUID id;
    String email;
    String passwordHash;
    boolean isActive;
    Timestamp createdAt;
    List<RoleEntity> roles = new ArrayList<>();

    public UserEntity(UUID id, String email, String passwordHash, boolean isActive, Timestamp createdAt) {
        this.id = id; this.email = email; this.passwordHash = passwordHash; this.isActive = isActive; this.createdAt = createdAt;
    }
    @Override public String toString() { return "UserEntity{id=" + id + ", email='" + email + "', roles=" + roles.stream().map(r -> r.name).collect(Collectors.joining(",")) + "}"; }
}

class PostEntity {
    UUID id;
    UUID userId;
    String title;
    String content;
    PostStatus status;

    public PostEntity(UUID id, UUID userId, String title, String content, PostStatus status) {
        this.id = id; this.userId = userId; this.title = title; this.content = content; this.status = status;
    }
    @Override public String toString() { return "PostEntity{id=" + id + ", title='" + title + "', status=" + status + "}"; }
}

class RoleEntity {
    int id;
    String name;
    public RoleEntity(int id, String name) { this.id = id; this.name = name; }
    @Override public String toString() { return "RoleEntity{id=" + id + ", name='" + name + "'}"; }
}

// --- Query Builder Utility ---

class QueryBuilder {
    private final StringBuilder query;
    private final List<Object> params = new ArrayList<>();

    public QueryBuilder(String baseQuery) {
        this.query = new StringBuilder(baseQuery);
    }

    public QueryBuilder where(String column, Object value) {
        if (query.toString().toLowerCase().contains(" where ")) {
            query.append(" AND ");
        } else {
            query.append(" WHERE ");
        }
        query.append(column).append(" = ?");
        params.add(value);
        return this;
    }

    public PreparedStatement prepare(Connection conn) throws SQLException {
        PreparedStatement ps = conn.prepareStatement(query.toString());
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
        return ps;
    }
}

// --- Repositories ---

class UserRepository {
    private final Connection connection;
    public UserRepository(Connection conn) { this.connection = conn; }

    public void add(UserEntity user) throws SQLException {
        String sql = "INSERT INTO users (id, email, password_hash, is_active, created_at) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, user.id);
            ps.setString(2, user.email);
            ps.setString(3, user.passwordHash);
            ps.setBoolean(4, user.isActive);
            ps.setTimestamp(5, user.createdAt);
            ps.executeUpdate();
        }
    }

    public UserEntity get(UUID id) throws SQLException {
        QueryBuilder qb = new QueryBuilder("SELECT * FROM users").where("id", id);
        try (PreparedStatement ps = qb.prepare(connection); ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return mapToUser(rs);
        }
        return null;
    }

    public List<UserEntity> find(Map<String, Object> criteria) throws SQLException {
        QueryBuilder qb = new QueryBuilder("SELECT * FROM users");
        criteria.forEach(qb::where);
        List<UserEntity> users = new ArrayList<>();
        try (PreparedStatement ps = qb.prepare(connection); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) users.add(mapToUser(rs));
        }
        return users;
    }

    public void assignRole(UUID userId, int roleId) throws SQLException {
        String sql = "INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setInt(2, roleId);
            ps.executeUpdate();
        }
    }

    public List<RoleEntity> getRoles(UUID userId) throws SQLException {
        String sql = "SELECT r.id, r.name FROM roles r JOIN user_roles ur ON r.id = ur.role_id WHERE ur.user_id = ?";
        List<RoleEntity> roles = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) roles.add(new RoleEntity(rs.getInt("id"), rs.getString("name")));
            }
        }
        return roles;
    }

    private UserEntity mapToUser(ResultSet rs) throws SQLException {
        return new UserEntity(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("password_hash"), rs.getBoolean("is_active"), rs.getTimestamp("created_at"));
    }
}

class PostRepository {
    private final Connection connection;
    public PostRepository(Connection conn) { this.connection = conn; }

    public void add(PostEntity post) throws SQLException {
        String sql = "INSERT INTO posts (id, user_id, title, content, status) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, post.id);
            ps.setObject(2, post.userId);
            ps.setString(3, post.title);
            ps.setString(4, post.content);
            ps.setString(5, post.status.name());
            ps.executeUpdate();
        }
    }

    public List<PostEntity> getByUserId(UUID userId) throws SQLException {
        List<PostEntity> posts = new ArrayList<>();
        QueryBuilder qb = new QueryBuilder("SELECT * FROM posts").where("user_id", userId);
        try (PreparedStatement ps = qb.prepare(connection); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                posts.add(new PostEntity(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("title"), rs.getString("content"), PostStatus.valueOf(rs.getString("status"))));
            }
        }
        return posts;
    }
}

class RoleRepository {
    private final Connection connection;
    public RoleRepository(Connection conn) { this.connection = conn; }

    public void add(RoleEntity role) throws SQLException {
        String sql = "INSERT INTO roles (id, name) VALUES (?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, role.id);
            ps.setString(2, role.name);
            ps.executeUpdate();
        }
    }
}

// --- Unit of Work ---

class UnitOfWork implements AutoCloseable {
    private final Connection connection;
    public final UserRepository users;
    public final PostRepository posts;
    public final RoleRepository roles;

    public UnitOfWork(String dbUrl) throws SQLException {
        this.connection = DriverManager.getConnection(dbUrl);
        this.connection.setAutoCommit(false);
        this.users = new UserRepository(connection);
        this.posts = new PostRepository(connection);
        this.roles = new RoleRepository(connection);
    }

    public void commit() throws SQLException { connection.commit(); }
    public void rollback() throws SQLException { connection.rollback(); }
    @Override public void close() throws SQLException { connection.close(); }
    public Connection getConnection() { return connection; }
}

// --- Migration and Runner ---

public class RepositoryUowDemo {
    private static final String DB_URL = "jdbc:h2:mem:db2;DB_CLOSE_DELAY=-1";

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
        try (Connection setupConn = DriverManager.getConnection(DB_URL)) {
            runMigrations(setupConn);
        } catch (SQLException e) {
            e.printStackTrace();
            return;
        }

        UUID user1Id = UUID.randomUUID();
        try (UnitOfWork uow = new UnitOfWork(DB_URL)) {
            System.out.println("\n--- Creating User, Posts, and Roles in a Transaction ---");
            // Create Roles
            RoleEntity adminRole = new RoleEntity(1, "ADMIN");
            RoleEntity userRole = new RoleEntity(2, "USER");
            uow.roles.add(adminRole);
            uow.roles.add(userRole);

            // Create User (C)
            UserEntity user1 = new UserEntity(user1Id, "admin@example.com", "hash1", true, Timestamp.from(Instant.now()));
            uow.users.add(user1);
            System.out.println("Created user: " + user1.email);

            // Assign Roles (Many-to-Many)
            uow.users.assignRole(user1.id, adminRole.id);
            uow.users.assignRole(user1.id, userRole.id);
            System.out.println("Assigned roles to user.");

            // Create Posts (One-to-Many)
            PostEntity post1 = new PostEntity(UUID.randomUUID(), user1.id, "First Post", "Content here", PostStatus.PUBLISHED);
            uow.posts.add(post1);
            System.out.println("Created post: " + post1.title);

            uow.commit();
            System.out.println("Transaction committed.");
        } catch (SQLException e) {
            System.err.println("Transaction failed: " + e.getMessage());
        }

        // --- Read and Filter Operations in a new Unit of Work ---
        try (UnitOfWork uow = new UnitOfWork(DB_URL)) {
            System.out.println("\n--- Reading Data ---");
            UserEntity foundUser = uow.users.get(user1Id);
            if (foundUser != null) {
                foundUser.roles = uow.users.getRoles(foundUser.id);
                System.out.println("Found user by ID: " + foundUser);
                List<PostEntity> userPosts = uow.posts.getByUserId(foundUser.id);
                System.out.println("Posts by user: " + userPosts);
            }

            System.out.println("\n--- Filtering Users ---");
            List<UserEntity> activeUsers = uow.users.find(Map.of("is_active", true));
            System.out.println("Found active users: " + activeUsers.size());
        } catch (SQLException e) {
            e.printStackTrace();
        }

        // --- Rollback Demo ---
        System.out.println("\n--- Rollback Demo ---");
        UUID user2Id = UUID.randomUUID();
        try (UnitOfWork uow = new UnitOfWork(DB_URL)) {
            UserEntity user2 = new UserEntity(user2Id, "fail@example.com", "hash2", true, Timestamp.from(Instant.now()));
            uow.users.add(user2);
            System.out.println("Created temporary user: " + user2.email);
            throw new RuntimeException("Simulating a business logic failure.");
        } catch (Exception e) {
            System.err.println("Caught exception: " + e.getMessage() + ". UnitOfWork should have rolled back implicitly.");
        }

        try (UnitOfWork uow = new UnitOfWork(DB_URL)) {
            UserEntity rolledBackUser = uow.users.get(user2Id);
            System.out.println("User after rollback: " + (rolledBackUser == null ? "Not found (correct)" : "Found (incorrect)"));
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}