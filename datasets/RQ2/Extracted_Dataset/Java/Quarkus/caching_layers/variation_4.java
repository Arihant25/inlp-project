// Variation 4: The "Configuration-Centric" Developer
// Style: Centralizes cache management, uses multiple named caches for the same entity, and relies on application.properties for behavior.
// Features: Multiple named caches, centralized invalidation service, and detailed configuration.

// --- FILE: src/main/resources/application.properties ---
// # Define multiple caches with different characteristics
// # A short-lived, smaller cache for users looked up by ID (most common)
// quarkus.cache.caffeine."users-by-id".expire-after-write=5m
// quarkus.cache.caffeine."users-by-id".maximum-size=1000
//
// # A longer-lived, larger cache for users looked up by email (less common, more stable)
// quarkus.cache.caffeine."users-by-email".expire-after-write=1h
// quarkus.cache.caffeine."users-by-email".maximum-size=2000
//
// # A cache for posts
// quarkus.cache.caffeine."posts-by-id".expire-after-write=30m
// quarkus.cache.caffeine."posts-by-id".maximum-size=5000

package com.configcentric.app.model;

import java.sql.Timestamp;
import java.util.UUID;

public class User {
    public enum Role { ADMIN, USER }
    public UUID id;
    public String email;
    public String password_hash;
    public Role role;
    public boolean is_active;
    public Timestamp created_at;
}

// --- FILE: src/main/java/com/configcentric/app/model/Post.java ---
package com.configcentric.app.model;

import java.util.UUID;

public class Post {
    public enum Status { DRAFT, PUBLISHED }
    public UUID id;
    public UUID user_id;
    public String title;
    public String content;
    public Status status;
}

// --- FILE: src/main/java/com/configcentric/app/repository/UserRepo.java ---
package com.configcentric.app.repository;

import com.configcentric.app.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class UserRepo {
    private final Map<UUID, User> userTable = new ConcurrentHashMap<>();
    private final Map<String, UUID> emailIndex = new ConcurrentHashMap<>();

    public UserRepo() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.id = userId;
        user.email = "test.user@config.com";
        user.created_at = Timestamp.from(Instant.now());
        userTable.put(userId, user);
        emailIndex.put(user.email, userId);
    }

    public User findById(UUID id) {
        System.out.println("REPO: Finding user by ID: " + id);
        simulateLatency();
        return userTable.get(id);
    }

    public User findByEmail(String email) {
        System.out.println("REPO: Finding user by email: " + email);
        simulateLatency();
        UUID id = emailIndex.get(email);
        return id != null ? userTable.get(id) : null;
    }

    public User save(User user) {
        System.out.println("REPO: Saving user: " + user.id);
        userTable.put(user.id, user);
        emailIndex.put(user.email, user.id);
        return user;
    }

    private void simulateLatency() {
        try { Thread.sleep(750); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}

// --- FILE: src/main/java/com/configcentric/app/cache/CacheInvalidationService.java ---
package com.configcentric.app.cache;

import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheName;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;

@ApplicationScoped
public class CacheInvalidationService {

    @Inject
    @CacheName("users-by-id")
    Cache userByIdCache;

    @Inject
    @CacheName("users-by-email")
    Cache userByEmailCache;

    /**
     * Centralized logic to invalidate all caches related to a single user.
     * This is crucial when an entity is cached using multiple different keys.
     * We must evict the entry from the 'users-by-id' cache and the 'users-by-email' cache.
     */
    public void invalidateUserCaches(UUID userId, String userEmail) {
        System.out.println("CACHE-SVC: Invalidating caches for user " + userId + " (" + userEmail + ")");
        userByIdCache.invalidate(userId).await().indefinitely();
        userByEmailCache.invalidate(userEmail).await().indefinitely();
    }
}

// --- FILE: src/main/java/com/configcentric/app/service/UserService.java ---
package com.configcentric.app.service;

import com.configcentric.app.cache.CacheInvalidationService;
import com.configcentric.app.model.User;
import com.configcentric.app.repository.UserRepo;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;

@ApplicationScoped
public class UserService {

    @Inject
    UserRepo userRepo;

    @Inject
    CacheInvalidationService cacheInvalidator;

    @CacheResult(cacheName = "users-by-id")
    public User findById(UUID id) {
        System.out.println("SERVICE: Calling repo to find user by ID");
        return userRepo.findById(id);
    }

    @CacheResult(cacheName = "users-by-email")
    public User findByEmail(String email) {
        System.out.println("SERVICE: Calling repo to find user by email");
        return userRepo.findByEmail(email);
    }

    public User updateUser(UUID userId, String newEmail) {
        System.out.println("SERVICE: Updating user " + userId);
        User user = userRepo.findById(userId);
        if (user != null) {
            String oldEmail = user.email;
            user.email = newEmail;
            User updatedUser = userRepo.save(user);
            
            // Delegate invalidation to the centralized service
            cacheInvalidator.invalidateUserCaches(userId, oldEmail);
            
            return updatedUser;
        }
        return null;
    }
}