// --- pom.xml ---
/*
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>com.h2database</groupId>
        <artifactId>h2</artifactId>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>
</dependencies>
*/

// --- src/main/resources/application.properties ---
/*
spring.datasource.url=jdbc:h2:mem:pragmaticdb;DB_CLOSE_DELAY=-1
spring.datasource.username=sa
spring.datasource.password=
spring.jpa.hibernate.ddl-auto=validate # Let Flyway manage the schema
spring.flyway.enabled=true
*/

// --- src/main/resources/db/migration/V1__Initial_Schema.sql ---
/*
CREATE TABLE roles (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE
);

CREATE TABLE app_users (
    id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    is_active BOOLEAN NOT NULL,
    created_at TIMESTAMP
);

CREATE TABLE user_roles (
    user_id UUID NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE,
    FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE
);

CREATE TABLE posts (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    title VARCHAR(255) NOT NULL,
    content TEXT,
    status VARCHAR(50) NOT NULL,
    FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE
);

-- Seed initial roles
INSERT INTO roles (name) VALUES ('ROLE_ADMIN'), ('ROLE_USER');
*/

// --- src/main/java/com/example/pragmatic/PragmaticApplication.java ---
package com.example.pragmatic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PragmaticApplication {
    public static void main(String[] args) {
        SpringApplication.run(PragmaticApplication.class, args);
    }
}

// --- src/main/java/com/example/pragmatic/data/PostStatus.java ---
package com.example.pragmatic.data;

public enum PostStatus { DRAFT, PUBLISHED }

// --- src/main/java/com/example/pragmatic/data/Role.java ---
package com.example.pragmatic.data;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import java.util.Set;

@Data
@Entity
@Table(name = "roles")
public class Role {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @ManyToMany(mappedBy = "roles")
    private Set<User> users;
}

// --- src/main/java/com/example/pragmatic/data/User.java ---
package com.example.pragmatic.data;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Data
@Entity
@Table(name = "app_users")
public class User {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private String email;
    private String passwordHash;
    private boolean isActive;
    @CreationTimestamp
    private Instant createdAt;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<Post> posts;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"), inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles;
}

// --- src/main/java/com/example/pragmatic/data/Post.java ---
package com.example.pragmatic.data;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import java.util.UUID;

@Data
@Entity
@Table(name = "posts")
public class Post {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private String title;
    @Lob
    private String content;
    @Enumerated(EnumType.STRING)
    private PostStatus status;

    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;
}

// --- src/main/java/com/example/pragmatic/data/UserRepository.java ---
package com.example.pragmatic.data;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    List<User> findByIsActiveAndRoles_Name(boolean isActive, String roleName);
}

// --- src/main/java/com/example/pragmatic/data/RoleRepository.java ---
package com.example.pragmatic.data;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {
    Optional<Role> findByName(String name);
}

// --- src/main/java/com/example/pragmatic/logic/UserPostManager.java ---
package com.example.pragmatic.logic;

import com.example.pragmatic.data.Role;
import com.example.pragmatic.data.User;
import com.example.pragmatic.data.UserRepository;
import com.example.pragmatic.data.RoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserPostManager {

    private final UserRepository userRepo;
    private final RoleRepository roleRepo;

    // CREATE
    @Transactional
    public User registerNewUser(String email, String password) {
        log.info("Registering new user with email: {}", email);
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(password); // Hashing logic would be here
        user.setActive(true);
        
        Role defaultRole = roleRepo.findByName("ROLE_USER")
            .orElseThrow(() -> new IllegalStateException("Default role ROLE_USER not found."));
        user.setRoles(Set.of(defaultRole));
        
        return userRepo.save(user);
    }

    // READ
    public User findUserById(UUID id) {
        return userRepo.findById(id).orElse(null);
    }

    // READ with filter
    public List<User> findActiveUsersByRole(String roleName) {
        return userRepo.findByIsActiveAndRoles_Name(true, roleName);
    }

    // UPDATE
    @Transactional
    public User updateUser(User user) {
        return userRepo.save(user);
    }

    // DELETE
    @Transactional
    public void deleteUser(UUID id) {
        userRepo.deleteById(id);
    }

    // TRANSACTIONAL ROLLBACK
    @Transactional
    public void bulkActivateUsers(List<UUID> userIds) {
        log.info("Activating {} users.", userIds.size());
        for (UUID id : userIds) {
            User user = userRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("User with ID " + id + " not found. Transaction will roll back."));
            user.setActive(true);
            userRepo.save(user);
        }
        log.info("All users activated successfully.");
    }
}