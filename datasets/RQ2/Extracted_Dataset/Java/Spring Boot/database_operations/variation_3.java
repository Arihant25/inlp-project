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
</dependencies>
*/

// --- src/main/resources/application.properties ---
/*
spring.datasource.url=jdbc:h2:mem:functionaldb
spring.datasource.driverClassName=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=password
spring.jpa.database-platform=org.hibernate.dialect.H2Dialect
spring.jpa.hibernate.ddl-auto=create-drop
spring.jpa.show-sql=true
*/

// --- src/main/java/com/example/functional/FunctionalApplication.java ---
package com.example.functional;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FunctionalApplication {
    public static void main(String[] args) {
        SpringApplication.run(FunctionalApplication.class, args);
    }
}

// --- src/main/java/com/example/functional/domain/PostStatus.java ---
package com.example.functional.domain;

public enum PostStatus { DRAFT, PUBLISHED }

// --- src/main/java/com/example/functional/domain/Role.java ---
package com.example.functional.domain;

import jakarta.persistence.*;
import java.util.Set;

@Entity
public class Role {
    @Id @GeneratedValue
    private Long id;
    private String name;
    @ManyToMany(mappedBy = "roles")
    private Set<User> users;
    // Getters/Setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}

// --- src/main/java/com/example/functional/domain/User.java ---
package com.example.functional.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Entity
@Table(name = "users")
public class User {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private String email;
    private String passwordHash;
    private boolean isActive;
    @CreationTimestamp
    private Instant createdAt;
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private Set<Post> posts;
    @ManyToMany(fetch = FetchType.EAGER, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"), inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles;
    
    // Getters/Setters
    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public User setEmail(String email) { this.email = email; return this; }
    public User setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; return this; }
    public User setActive(boolean active) { isActive = active; return this; }
    public Set<Post> getPosts() { return posts; }
    public User setRoles(Set<Role> roles) { this.roles = roles; return this; }
    public Set<String> getRoleNames() {
        return this.roles.stream().map(Role::getName).collect(Collectors.toSet());
    }
}

// --- src/main/java/com/example/functional/domain/Post.java ---
package com.example.functional.domain;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
public class Post {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private String title;
    @Lob
    private String content;
    @Enumerated(EnumType.STRING)
    private PostStatus status;
    @ManyToOne(optional = false)
    private User user;
    // Getters/Setters
    public UUID getId() { return id; }
    public Post setUser(User user) { this.user = user; return this; }
    public Post setTitle(String title) { this.title = title; return this; }
    public Post setContent(String content) { this.content = content; return this; }
    public Post setStatus(PostStatus status) { this.status = status; return this; }
}

// --- src/main/java/com/example/functional/repo/UserRepo.java ---
package com.example.functional.repo;

import com.example.functional.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface UserRepo extends JpaRepository<User, UUID> {
    List<User> findAllByIsActive(boolean active);
}

// --- src/main/java/com/example/functional/repo/PostRepo.java ---
package com.example.functional.repo;

import com.example.functional.domain.Post;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface PostRepo extends JpaRepository<Post, UUID> {}

// --- src/main/java/com/example/functional/service/UserAccountService.java ---
package com.example.functional.service;

import com.example.functional.domain.Post;
import com.example.functional.domain.PostStatus;
import com.example.functional.domain.Role;
import com.example.functional.domain.User;
import com.example.functional.repo.PostRepo;
import com.example.functional.repo.UserRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class UserAccountService {

    private final UserRepo userRepo;
    private final PostRepo postRepo;

    public UserAccountService(UserRepo userRepo, PostRepo postRepo) {
        this.userRepo = userRepo;
        this.postRepo = postRepo;
    }

    @Transactional(readOnly = true)
    public Optional<User> findUser(UUID id) {
        return userRepo.findById(id);
    }

    @Transactional
    public User registerUser(String email, String password) {
        Role userRole = new Role();
        userRole.setName("ROLE_USER");

        User newUser = new User()
            .setEmail(email)
            .setPasswordHash(password) // Assume hashed
            .setActive(true)
            .setRoles(Set.of(userRole));
        
        return userRepo.save(newUser);
    }

    @Transactional
    public Optional<User> deactivateUser(UUID id) {
        return userRepo.findById(id)
            .map(user -> user.setActive(false))
            .map(userRepo::save);
    }
    
    @Transactional
    public void deleteUserAndOrphanPosts(UUID id) {
        userRepo.findById(id).ifPresent(userRepo::delete);
    }

    @Transactional(readOnly = true)
    public List<String> getActiveUserEmails() {
        return userRepo.findAllByIsActive(true).stream()
            .map(User::getEmail)
            .collect(Collectors.toList());
    }

    // Transactional rollback demonstration
    @Transactional
    public void createPostForUserAndPublish(UUID userId, Post newPost) {
        findUser(userId)
            .map(addPostToUser(newPost))
            .map(publishPost())
            .map(postRepo::save)
            .orElseThrow(() -> new IllegalStateException("User not found, transaction will be rolled back."));
    }

    private Function<User, Post> addPostToUser(Post post) {
        return user -> {
            if (post.getTitle() == null || post.getTitle().isBlank()) {
                // This exception will trigger a rollback
                throw new IllegalArgumentException("Post title cannot be empty.");
            }
            return post.setUser(user);
        };
    }
    
    private Function<Post, Post> publishPost() {
        return post -> post.setStatus(PostStatus.PUBLISHED);
    }
}