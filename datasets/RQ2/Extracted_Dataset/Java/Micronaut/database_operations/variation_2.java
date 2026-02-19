// Variation 2: Specification Pattern for Dynamic Queries
// This approach is ideal for building complex, dynamic queries, such as for advanced search filters.
// It uses the JPA Criteria API through Micronaut Data's JpaSpecificationExecutor.
// The structure is still layered, but the service constructs a Specification object based on filter criteria.

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

package com.example.dbops.v2.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "roles")
public class RoleEntity {
    @Id
    private Long id;
    @Column(nullable = false, unique = true)
    private String name;
    
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}

@Entity
@Table(name = "users")
public class UserEntity {
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
    private Set<PostEntity> posts = new HashSet<>();

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "users_roles", joinColumns = @JoinColumn(name = "user_id"), inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<RoleEntity> roles = new HashSet<>();

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
    public Set<PostEntity> getPosts() { return posts; }
    public void setPosts(Set<PostEntity> posts) { this.posts = posts; }
    public Set<RoleEntity> getRoles() { return roles; }
    public void setRoles(Set<RoleEntity> roles) { this.roles = roles; }
}

@Entity
@Table(name = "posts")
public class PostEntity {
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
    private UserEntity user;
    
    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public UserEntity getUser() { return user; }
    public void setUser(UserEntity user) { this.user = user; }
}

package com.example.dbops.v2.persistence;

import com.example.dbops.v2.domain.UserEntity;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jpa.repository.JpaRepository;
import io.micronaut.data.jpa.repository.spec.JpaSpecificationExecutor;

import java.util.UUID;

@Repository
public interface UserRepo extends JpaRepository<UserEntity, UUID>, JpaSpecificationExecutor<UserEntity> {
}

package com.example.dbops.v2.persistence;

import com.example.dbops.v2.domain.RoleEntity;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.CrudRepository;
import java.util.Optional;

@Repository
public interface RoleRepo extends CrudRepository<RoleEntity, Long> {
    Optional<RoleEntity> findByName(String name);
}

package com.example.dbops.v2.persistence;

import com.example.dbops.v2.domain.UserEntity;
import io.micronaut.data.jpa.repository.spec.Specification;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class UserSpecifications {
    public static Specification<UserEntity> findByCriteria(Map<String, Object> filters) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (filters.containsKey("emailContains")) {
                predicates.add(criteriaBuilder.like(root.get("email"), "%" + filters.get("emailContains") + "%"));
            }
            if (filters.containsKey("isActive")) {
                predicates.add(criteriaBuilder.equal(root.get("isActive"), filters.get("isActive")));
            }
            if (filters.containsKey("role")) {
                predicates.add(criteriaBuilder.equal(root.join("roles").get("name"), filters.get("role")));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }
}

package com.example.dbops.v2.service;

import com.example.dbops.v2.domain.PostEntity;
import com.example.dbops.v2.domain.RoleEntity;
import com.example.dbops.v2.domain.UserEntity;
import com.example.dbops.v2.persistence.RoleRepo;
import com.example.dbops.v2.persistence.UserRepo;
import com.example.dbops.v2.persistence.UserSpecifications;
import jakarta.inject.Singleton;
import io.micronaut.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Singleton
public class UserManagementService {

    private final UserRepo userRepo;
    private final RoleRepo roleRepo;

    public UserManagementService(UserRepo userRepo, RoleRepo roleRepo) {
        this.userRepo = userRepo;
        this.roleRepo = roleRepo;
    }

    @Transactional
    public UserEntity registerUser(String email, String password) {
        UserEntity user = new UserEntity();
        user.setEmail(email);
        user.setPasswordHash(password); // Hashing omitted for brevity
        
        RoleEntity userRole = roleRepo.findByName("USER").orElseThrow(() -> new IllegalStateException("USER role not found"));
        user.setRoles(Set.of(userRole));
        
        return userRepo.save(user);
    }

    @Transactional
    public UserEntity addPostToUser(UUID userId, String title, String content) {
        UserEntity user = userRepo.findById(userId).orElseThrow(() -> new RuntimeException("User not found"));
        
        PostEntity post = new PostEntity();
        post.setTitle(title);
        post.setContent(content);
        post.setStatus(PostEntity.Status.PUBLISHED);
        post.setUser(user);
        
        user.getPosts().add(post);
        return userRepo.update(user);
    }

    @Transactional(readOnly = true)
    public List<UserEntity> searchUsers(Map<String, Object> filters) {
        return userRepo.findAll(UserSpecifications.findByCriteria(filters));
    }

    @Transactional
    public void deleteUser(UUID userId) {
        userRepo.deleteById(userId);
    }
}