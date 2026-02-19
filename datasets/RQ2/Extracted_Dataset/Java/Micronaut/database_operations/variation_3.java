// Variation 3: CQRS-Inspired Approach with DTOs
// This pattern separates commands (writes) from queries (reads).
// Commands use DTOs for input and handle business logic and transactions.
// Queries use DTOs for output and can be optimized for reading, e.g., using custom JPQL queries.
// This improves separation of concerns and can lead to more scalable and maintainable systems.

// --- build.gradle dependencies (for context) ---
// implementation("io.micronaut.data:micronaut-data-hibernate-jpa")
// implementation("io.micronaut.sql:micronaut-jdbc-hikari")
// implementation("org.postgresql:postgresql")
// implementation("io.micronaut.flyway:micronaut-flyway")
// -------------------------------------------------

// --- V1__create_initial_schema.sql (Flyway Migration) ---
/*
CREATE TABLE roles (
    id BIGINT PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE
);

CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE posts (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    title VARCHAR(255) NOT NULL,
    content TEXT,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE users_roles (
    user_id UUID NOT NULL REFERENCES users(id),
    role_id BIGINT NOT NULL REFERENCES roles(id),
    PRIMARY KEY (user_id, role_id)
);

-- Seed initial roles
INSERT INTO roles(id, name) VALUES (1, 'ADMIN');
INSERT INTO roles(id, name) VALUES (2, 'USER');
*/
// ---------------------------------------------------------

package com.example.dbops.v3.data;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "roles")
public class Role {
    @Id private Long id;
    @Column(nullable = false, unique = true) private String name;
    public Long getId() { return id; }
    public String getName() { return name; }
}

@Entity
@Table(name = "users")
public class User {
    @Id @GeneratedValue private UUID id;
    @Column(nullable = false, unique = true) private String email;
    @Column(name = "password_hash", nullable = false) private String passwordHash;
    @Column(name = "is_active", nullable = false) private boolean isActive = true;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true) private Set<Post> posts = new HashSet<>();
    @ManyToMany(fetch = FetchType.EAGER) @JoinTable(name = "users_roles", joinColumns = @JoinColumn(name = "user_id"), inverseJoinColumns = @JoinColumn(name = "role_id")) private Set<Role> roles = new HashSet<>();
    
    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }
    public Instant getCreatedAt() { return createdAt; }
    public Set<Post> getPosts() { return posts; }
    public void setPosts(Set<Post> posts) { this.posts = posts; }
    public Set<Role> getRoles() { return roles; }
    public void setRoles(Set<Role> roles) { this.roles = roles; }
}

@Entity
@Table(name = "posts")
public class Post {
    public enum Status { DRAFT, PUBLISHED }
    @Id @GeneratedValue private UUID id;
    @Column(nullable = false) private String title;
    @Lob private String content;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "user_id", nullable = false) private User user;
    
    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
}

package com.example.dbops.v3.data;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.CrudRepository;
import java.util.Optional;

@Repository
public interface RoleRepository extends CrudRepository<Role, Long> {
    Optional<Role> findByName(String name);
}

package com.example.dbops.v3.dto;

import io.micronaut.core.annotation.Introspected;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Introspected
public record CreateUserCommand(String email, String password) {}

@Introspected
public record AddPostCommand(UUID userId, String title, String content) {}

@Introspected
public record UserView(UUID id, String email, boolean isActive, Instant createdAt, List<String> roles) {}

package com.example.dbops.v3.repo;

import com.example.dbops.v3.data.User;
import com.example.dbops.v3.dto.UserView;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

@Repository
public interface UserRepo extends JpaRepository<User, UUID> {
    @Query("SELECT new com.example.dbops.v3.dto.UserView(u.id, u.email, u.isActive, u.createdAt, r.name) FROM User u JOIN u.roles r")
    List<UserView> listAllAsUserView();
    
    @Query("SELECT new com.example.dbops.v3.dto.UserView(u.id, u.email, u.isActive, u.createdAt, r.name) FROM User u JOIN u.roles r WHERE u.isActive = :active")
    List<UserView> findActiveUsersAsView(boolean active);
}

package com.example.dbops.v3.service;

import com.example.dbops.v3.data.Post;
import com.example.dbops.v3.data.Role;
import com.example.dbops.v3.data.RoleRepository;
import com.example.dbops.v3.data.User;
import com.example.dbops.v3.dto.AddPostCommand;
import com.example.dbops.v3.dto.CreateUserCommand;
import com.example.dbops.v3.dto.UserView;
import com.example.dbops.v3.repo.UserRepo;
import jakarta.inject.Singleton;
import io.micronaut.transaction.annotation.Transactional;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Singleton
public class UserCommandService {
    private final UserRepo userRepo;
    private final RoleRepository roleRepo;

    public UserCommandService(UserRepo userRepo, RoleRepository roleRepo) {
        this.userRepo = userRepo;
        this.roleRepo = roleRepo;
    }

    @Transactional
    public UUID handle(CreateUserCommand command) {
        User user = new User();
        user.setEmail(command.email());
        user.setPasswordHash(command.password()); // Hashing logic here
        Role userRole = roleRepo.findByName("USER").orElseThrow();
        user.setRoles(Set.of(userRole));
        userRepo.save(user);
        return user.getId();
    }

    @Transactional
    public void handle(AddPostCommand command) {
        User user = userRepo.findById(command.userId()).orElseThrow();
        Post post = new Post();
        post.setTitle(command.title());
        post.setContent(command.content());
        post.setStatus(Post.Status.DRAFT);
        post.setUser(user);
        user.getPosts().add(post);
        userRepo.update(user);
    }
}

@Singleton
public class UserQueryService {
    private final UserRepo userRepo;

    public UserQueryService(UserRepo userRepo) {
        this.userRepo = userRepo;
    }

    @Transactional(readOnly = true)
    public List<UserView> getActiveUsers() {
        return userRepo.findActiveUsersAsView(true);
    }
}