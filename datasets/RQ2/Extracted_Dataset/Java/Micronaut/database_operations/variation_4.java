// Variation 4: Reactive and Functional Approach with R2DBC
// This modern approach uses a non-blocking, reactive stack (Project Reactor, R2DBC).
// It's highly scalable and efficient for I/O-bound operations.
// The code style is more functional, using Monos and Fluxes to compose asynchronous operations.
// Transactions are also managed reactively.

// --- build.gradle dependencies (for context) ---
// implementation("io.micronaut.data:micronaut-data-r2dbc")
// implementation("io.r2dbc:r2dbc-postgresql")
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
    user_id UUID NOT NULL, -- No foreign key in R2DBC schema, managed by app
    title VARCHAR(255) NOT NULL,
    content TEXT,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE users_roles (
    user_id UUID NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id)
);

-- Seed initial roles
INSERT INTO roles(id, name) VALUES (1, 'ADMIN');
INSERT INTO roles(id, name) VALUES (2, 'USER');
*/
// ---------------------------------------------------------

package com.example.dbops.v4.entity;

import io.micronaut.data.annotation.*;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

// Note: R2DBC doesn't support lazy loading or cascading in the same way as JPA.
// Relationships are often managed more explicitly in the application logic.

@MappedEntity("roles")
public record Role(
    @Id Long id,
    String name
) {}

@MappedEntity("users")
public class User {
    @Id @GeneratedValue private UUID id;
    private String email;
    private String passwordHash;
    private boolean isActive = true;
    @DateCreated private Instant createdAt;

    @Relation(value = Relation.Kind.ONE_TO_MANY, mappedBy = "user")
    private Set<Post> posts;

    @Relation(value = Relation.Kind.MANY_TO_MANY)
    @JoinTable(name = "users_roles", joinColumns = @JoinColumn(name = "user_id"), inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles;

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

@MappedEntity("posts")
public class Post {
    public enum Status { DRAFT, PUBLISHED }
    @Id @GeneratedValue private UUID id;
    private String title;
    private String content;
    private Status status;
    private UUID userId; // Foreign key is just a property

    @Relation(Relation.Kind.MANY_TO_ONE)
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
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
}

package com.example.dbops.v4.repository;

import com.example.dbops.v4.entity.Role;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.reactive.ReactiveStreamsCrudRepository;
import reactor.core.publisher.Mono;

@Repository
public interface RoleReactiveRepository extends ReactiveStreamsCrudRepository<Role, Long> {
    Mono<Role> findByName(String name);
}

package com.example.dbops.v4.repository;

import com.example.dbops.v4.entity.User;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.reactive.ReactiveStreamsCrudRepository;
import reactor.core.publisher.Flux;
import java.util.UUID;

@Repository
public interface UserReactiveRepository extends ReactiveStreamsCrudRepository<User, UUID> {
    Flux<User> findByIsActive(boolean isActive);
}

package com.example.dbops.v4.service;

import com.example.dbops.v4.entity.Post;
import com.example.dbops.v4.entity.User;
import com.example.dbops.v4.repository.RoleReactiveRepository;
import com.example.dbops.v4.repository.UserReactiveRepository;
import io.micronaut.transaction.reactive.ReactiveTransactionStatus;
import io.micronaut.transaction.reactive.ReactorReactiveTransactionOperations;
import jakarta.inject.Singleton;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.Set;
import java.util.UUID;

@Singleton
public class ReactiveUserService {

    private final UserReactiveRepository userRepo;
    private final RoleReactiveRepository roleRepo;
    private final ReactorReactiveTransactionOperations<Object> txManager;

    public ReactiveUserService(
        UserReactiveRepository userRepo,
        RoleReactiveRepository roleRepo,
        ReactorReactiveTransactionOperations<Object> txManager
    ) {
        this.userRepo = userRepo;
        this.roleRepo = roleRepo;
        this.txManager = txManager;
    }

    public Mono<User> createUser(String email, String password) {
        return roleRepo.findByName("USER")
            .switchIfEmpty(Mono.error(new IllegalStateException("USER role not found")))
            .flatMap(userRole -> {
                User newUser = new User();
                newUser.setEmail(email);
                newUser.setPasswordHash(password); // Hashing logic
                newUser.setRoles(Set.of(userRole));
                return Mono.from(userRepo.save(newUser));
            });
    }

    public Flux<User> findActiveUsersWithPosts() {
        return userRepo.findByIsActive(true)
            .flatMap(user -> Mono.from(userRepo.findById(user.getId()))); // Re-fetch to get relations
    }

    public Mono<Void> deleteUserAndPostsTransactional(UUID userId) {
        return Mono.from(userRepo.deleteById(userId))
            .then(Mono.error(new RuntimeException("Intentional failure to test rollback")))
            .as(flux -> txManager.withTransaction(status -> flux));
    }

    public Mono<User> createComplexUserWithPosts(String email, String password) {
        return Mono.deferContextual(contextView -> {
            ReactiveTransactionStatus<Object> status = contextView.get(ReactiveTransactionStatus.class);
            
            User user = new User();
            user.setEmail(email);
            user.setPasswordHash(password);

            Post post1 = new Post();
            post1.setTitle("Reactive Post 1");
            post1.setContent("Content 1");
            post1.setStatus(Post.Status.DRAFT);
            post1.setUser(user);

            Post post2 = new Post();
            post2.setTitle("Reactive Post 2");
            post2.setContent("Content 2");
            post2.setStatus(Post.Status.PUBLISHED);
            post2.setUser(user);

            user.setPosts(Set.of(post1, post2));

            return Mono.from(userRepo.save(user));
        }).as(flux -> txManager.withTransaction(status -> flux));
    }
}