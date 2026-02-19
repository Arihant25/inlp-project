// Variation 1: Classic Service/Repository Layer
// This approach uses a standard, layered architecture.
// Controller -> Service -> Repository.
// It's straightforward, easy to understand, and very common in enterprise applications.
// Querying is done via Micronaut Data's declared queries (method name conventions).

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

package com.example.dbops.v1.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "roles")
public class Role {
    @Id
    private Long id;
    @Column(nullable = false, unique = true)
    private String name;

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}

@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue
    private UUID id;
    @Column(nullable = false, unique = true)
    private String email;
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;
    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<Post> posts = new HashSet<>();

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "users_roles",
        joinColumns = @JoinColumn(name = "user_id"),
        inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    private Set<Role> roles = new HashSet<>();
    
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
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Set<Post> getPosts() { return posts; }
    public void setPosts(Set<Post> posts) { this.posts = posts; }
    public Set<Role> getRoles() { return roles; }
    public void setRoles(Set<Role> roles) { this.roles = roles; }
}

@Entity
@Table(name = "posts")
public class Post {
    public enum Status { DRAFT, PUBLISHED }

    @Id
    @GeneratedValue
    private UUID id;
    @Column(nullable = false)
    private String title;
    @Lob
    private String content;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

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

package com.example.dbops.v1.repository;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.CrudRepository;
import com.example.dbops.v1.model.User;
import java.util.List;
import java.util.UUID;

@Repository
public interface UserRepository extends CrudRepository<User, UUID> {
    List<User> findByIsActive(boolean isActive);
    long countByIsActive(boolean isActive);
}

package com.example.dbops.v1.repository;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.CrudRepository;
import com.example.dbops.v1.model.Post;
import com.example.dbops.v1.model.Post.Status;
import java.util.List;
import java.util.UUID;

@Repository
public interface PostRepository extends CrudRepository<Post, UUID> {
    List<Post> findByUserIdAndStatus(UUID userId, Status status);
}

package com.example.dbops.v1.repository;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.CrudRepository;
import com.example.dbops.v1.model.Role;
import java.util.Optional;

@Repository
public interface RoleRepository extends CrudRepository<Role, Long> {
    Optional<Role> findByName(String name);
}

package com.example.dbops.v1.service;

import com.example.dbops.v1.model.Post;
import com.example.dbops.v1.model.Role;
import com.example.dbops.v1.model.User;
import com.example.dbops.v1.repository.PostRepository;
import com.example.dbops.v1.repository.RoleRepository;
import com.example.dbops.v1.repository.UserRepository;
import jakarta.inject.Singleton;
import io.micronaut.transaction.annotation.Transactional;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Singleton
public class UserService {

    private final UserRepository userRepository;
    private final PostRepository postRepository;
    private final RoleRepository roleRepository;

    public UserService(UserRepository userRepository, PostRepository postRepository, RoleRepository roleRepository) {
        this.userRepository = userRepository;
        this.postRepository = postRepository;
        this.roleRepository = roleRepository;
    }

    @Transactional
    public User createUserWithPost(String email, String password, String postTitle, String postContent) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(password); // In a real app, hash this
        
        Role userRole = roleRepository.findByName("USER").orElseThrow(() -> new IllegalStateException("USER role not found"));
        user.setRoles(Set.of(userRole));

        Post post = new Post();
        post.setTitle(postTitle);
        post.setContent(postContent);
        post.setStatus(Post.Status.DRAFT);
        post.setUser(user);

        user.getPosts().add(post);

        return userRepository.save(user);
    }

    @Transactional
    public void deleteUserAndRollbackOnError(UUID userId) {
        userRepository.deleteById(userId);
        // Simulate an error that would cause a rollback
        if (true) { // This condition is always true to demonstrate the rollback
            throw new RuntimeException("Simulating error after delete to trigger rollback!");
        }
    }

    @Transactional(readOnly = true)
    public List<User> findActiveUsers() {
        return userRepository.findByIsActive(true);
    }

    @Transactional
    public User updateUserEmail(UUID userId, String newEmail) {
        User user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
        user.setEmail(newEmail);
        return userRepository.update(user);
    }
}