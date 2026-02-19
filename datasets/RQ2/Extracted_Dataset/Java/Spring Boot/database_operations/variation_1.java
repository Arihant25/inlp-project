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
spring.datasource.url=jdbc:h2:mem:classicdb
spring.datasource.driverClassName=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=password
spring.jpa.database-platform=org.hibernate.dialect.H2Dialect
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true
*/

// --- src/main/java/com/example/classic/ClassicApplication.java ---
package com.example.classic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ClassicApplication {
    public static void main(String[] args) {
        SpringApplication.run(ClassicApplication.class, args);
    }
}

// --- src/main/java/com/example/classic/model/PostStatus.java ---
package com.example.classic.model;

public enum PostStatus {
    DRAFT,
    PUBLISHED
}

// --- src/main/java/com/example/classic/model/Role.java ---
package com.example.classic.model;

import jakarta.persistence.*;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "roles")
public class Role {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @ManyToMany(mappedBy = "roles")
    private Set<User> users;
    
    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Set<User> getUsers() { return users; }
    public void setUsers(Set<User> users) { this.users = users; }
}

// --- src/main/java/com/example/classic/model/User.java ---
package com.example.classic.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "app_users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private boolean isActive;

    @CreationTimestamp
    private Instant createdAt;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private Set<Post> posts;

    @ManyToMany(fetch = FetchType.EAGER, cascade = CascadeType.PERSIST)
    @JoinTable(
        name = "user_roles",
        joinColumns = @JoinColumn(name = "user_id"),
        inverseJoinColumns = @JoinColumn(name = "role_id")
    )
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

// --- src/main/java/com/example/classic/model/Post.java ---
package com.example.classic.model;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "posts")
public class Post {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String title;

    @Lob
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PostStatus status;

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
    public PostStatus getStatus() { return status; }
    public void setStatus(PostStatus status) { this.status = status; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
}

// --- src/main/java/com/example/classic/repository/UserRepository.java ---
package com.example.classic.repository;

import com.example.classic.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    // Example of a derived query
    List<User> findByIsActive(boolean isActive);

    // Example of a custom JPQL query
    @Query("SELECT u FROM User u JOIN u.roles r WHERE r.name = :roleName")
    List<User> findByRoleName(@Param("roleName") String roleName);
}

// --- src/main/java/com/example/classic/repository/PostRepository.java ---
package com.example.classic.repository;

import com.example.classic.model.Post;
import com.example.classic.model.PostStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface PostRepository extends JpaRepository<Post, UUID> {
    List<Post> findByUserIdAndStatus(UUID userId, PostStatus status);
}

// --- src/main/java/com/example/classic/service/UserService.java ---
package com.example.classic.service;

import com.example.classic.model.User;
import java.util.List;
import java.util.UUID;

public interface UserService {
    User createUser(String email, String password);
    User getUserById(UUID id);
    List<User> getAllActiveUsers();
    User updateUserEmail(UUID id, String newEmail);
    void deleteUser(UUID id);
    void createUsersWithTransactionRollback(User user1, User user2) throws Exception;
}

// --- src/main/java/com/example/classic/service/UserServiceImpl.java ---
package com.example.classic.service;

import com.example.classic.model.Role;
import com.example.classic.model.User;
import com.example.classic.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;

    public UserServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional
    public User createUser(String email, String password) {
        User newUser = new User();
        newUser.setEmail(email);
        newUser.setPasswordHash("hashed_" + password); // In real app, use BCrypt
        newUser.setActive(true);
        
        Role userRole = new Role();
        userRole.setName("ROLE_USER");
        Set<Role> roles = new HashSet<>();
        roles.add(userRole);
        newUser.setRoles(roles);

        return userRepository.save(newUser);
    }

    @Override
    @Transactional(readOnly = true)
    public User getUserById(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("User not found with id: " + id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<User> getAllActiveUsers() {
        return userRepository.findByIsActive(true);
    }

    @Override
    @Transactional
    public User updateUserEmail(UUID id, String newEmail) {
        User userToUpdate = getUserById(id);
        userToUpdate.setEmail(newEmail);
        return userRepository.save(userToUpdate);
    }

    @Override
    @Transactional
    public void deleteUser(UUID id) {
        if (!userRepository.existsById(id)) {
            throw new EntityNotFoundException("User not found with id: " + id);
        }
        userRepository.deleteById(id);
    }

    @Override
    @Transactional
    public void createUsersWithTransactionRollback(User user1, User user2) throws Exception {
        userRepository.save(user1);
        if (user2.getEmail().contains("invalid")) {
            throw new IllegalArgumentException("Invalid email detected, transaction will be rolled back.");
        }
        userRepository.save(user2);
    }
}