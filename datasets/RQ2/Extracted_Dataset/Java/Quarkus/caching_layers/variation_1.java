// Variation 1: The "Standard Enterprise" Developer
// Style: Classic, verbose, object-oriented, clear separation of concerns.
// Features: Demonstrates basic @CacheResult, @CacheInvalidate, and @CacheInvalidateAll.

// --- FILE: src/main/resources/application.properties ---
// quarkus.cache.caffeine."user-cache".expire-after-write=10m
// quarkus.cache.caffeine."user-cache".initial-capacity=10
// quarkus.cache.caffeine."user-cache".maximum-size=100

package com.enterprise.acme.model;

import java.sql.Timestamp;
import java.util.UUID;

public class User {
    private UUID id;
    private String email;
    private String passwordHash;
    private Role role;
    private boolean isActive;
    private Timestamp createdAt;

    public enum Role { ADMIN, USER }

    // Constructors, Getters, and Setters
    public User(UUID id, String email, String passwordHash, Role role, boolean isActive, Timestamp createdAt) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.isActive = isActive;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }
    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }
}

// --- FILE: src/main/java/com/enterprise/acme/model/Post.java ---
package com.enterprise.acme.model;

import java.util.UUID;

public class Post {
    private UUID id;
    private UUID userId;
    private String title;
    private String content;
    private Status status;

    public enum Status { DRAFT, PUBLISHED }

    // Constructors, Getters, and Setters
    public Post(UUID id, UUID userId, String title, String content, Status status) {
        this.id = id;
        this.userId = userId;
        this.title = title;
        this.content = content;
        this.status = status;
    }
    
    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public Status getStatus() { return status; }
}

// --- FILE: src/main/java/com/enterprise/acme/repository/UserRepository.java ---
package com.enterprise.acme.repository;

import com.enterprise.acme.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class UserRepository {

    private final Map<UUID, User> userDatabase = new ConcurrentHashMap<>();

    public UserRepository() {
        // Mock data
        UUID userId = UUID.randomUUID();
        userDatabase.put(userId, new User(userId, "admin@acme.com", "hash123", User.Role.ADMIN, true, Timestamp.from(Instant.now())));
    }

    // Simulates a slow database query
    public User findUserById(UUID userId) {
        try {
            System.out.println("DATABASE HIT: Querying for user with ID: " + userId);
            Thread.sleep(1000); // Simulate network latency
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return userDatabase.get(userId);
    }

    public User saveUser(User user) {
        System.out.println("DATABASE HIT: Saving user with ID: " + user.getId());
        if (user.getId() == null) {
            user.setId(UUID.randomUUID());
        }
        userDatabase.put(user.getId(), user);
        return user;
    }

    public void deleteUser(UUID userId) {
        System.out.println("DATABASE HIT: Deleting user with ID: " + userId);
        userDatabase.remove(userId);
    }
}

// --- FILE: src/main/java/com/enterprise/acme/service/UserService.java ---
package com.enterprise.acme.service;

import com.enterprise.acme.model.User;
import com.enterprise.acme.repository.UserRepository;
import io.quarkus.cache.CacheInvalidate;
import io.quarkus.cache.CacheInvalidateAll;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;

@ApplicationScoped
public class UserService {

    @Inject
    UserRepository userRepository;

    /**
     * Implements Cache-Aside pattern.
     * If a user is found in 'user-cache' with the key 'userId', it's returned immediately.
     * Otherwise, the method executes, calls the slow repository, and the result is
     * placed in the cache before being returned.
     */
    @CacheResult(cacheName = "user-cache")
    public User getUserById(UUID userId) {
        System.out.println("SERVICE: Fetching user by ID: " + userId);
        return userRepository.findUserById(userId);
    }

    /**
     * When a user is updated, we must invalidate their specific entry in the cache
     * to avoid serving stale data. The 'userId' parameter is used as the cache key.
     */
    @CacheInvalidate(cacheName = "user-cache")
    public User updateUserEmail(UUID userId, String newEmail) {
        System.out.println("SERVICE: Updating email for user: " + userId);
        User userToUpdate = userRepository.findUserById(userId);
        if (userToUpdate != null) {
            userToUpdate.setEmail(newEmail);
            return userRepository.saveUser(userToUpdate);
        }
        return null;
    }

    /**
     * When a user is deleted, their entry must be removed from the cache.
     */
    @CacheInvalidate(cacheName = "user-cache")
    public void deleteUser(UUID userId) {
        System.out.println("SERVICE: Deleting user: " + userId);
        userRepository.deleteUser(userId);
    }

    /**
     * A more drastic operation that invalidates the entire cache.
     * Useful for bulk updates or when a system-wide change affects all users.
     */
    @CacheInvalidateAll(cacheName = "user-cache")
    public void deactivateAllUsers() {
        System.out.println("SERVICE: Deactivating all users and clearing cache.");
        // In a real app, this would iterate and update all users in the DB.
    }
}