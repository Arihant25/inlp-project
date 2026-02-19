package com.example.cache.v3;

import io.micronaut.cache.annotation.CacheConfig;
import io.micronaut.cache.annotation.CacheInvalidate;
import io.micronaut.cache.annotation.CachePut;
import io.micronaut.cache.annotation.Cacheable;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/*
 * Variation 3: The "Configuration-Driven" Approach
 *
 * This implementation emphasizes external configuration and dynamic key generation.
 * - Cache names are injected from configuration properties via @Value.
 * - Uses Micronaut's expression language support for dynamic cache keys (e.g., key = "#user.id").
 * - The mock repository simulates network latency with Thread.sleep() to highlight caching benefits.
 * - Demonstrates invalidating multiple caches at once.
 *
 * --- Micronaut Configuration (place in application.yml) ---
 * app:
 *   caches:
 *     user-cache-name: "user-details"
 *     post-cache-name: "post-contents"
 * micronaut:
 *   caches:
 *     user-details:
 *       expire-after-write: 1h
 *       maximum-size: 200
 *     post-contents:
 *       expire-after-write: 20m
 *       maximum-size: 1000
 */

// --- Domain Model ---

enum RoleV3 { ADMIN, USER }
enum PostStatusV3 { DRAFT, PUBLISHED }

record UserV3(
    UUID id,
    String email,
    String password_hash,
    RoleV3 role,
    boolean is_active,
    Timestamp created_at
) {}

record PostV3(
    UUID id,
    UUID user_id,
    String title,
    String content,
    PostStatusV3 status
) {}

// --- Mock Repository with Simulated Latency ---

@Singleton
class SlowDataStore {
    private final Map<UUID, UserV3> users = new ConcurrentHashMap<>();
    private final Map<UUID, PostV3> posts = new ConcurrentHashMap<>();

    public SlowDataStore() {
        UUID userId = UUID.randomUUID();
        users.put(userId, new UserV3(userId, "slow.user@example.com", "hash789", RoleV3.USER, true, Timestamp.from(Instant.now())));
    }

    private void simulateLatency() {
        try {
            TimeUnit.MILLISECONDS.sleep(500); // Simulate 500ms DB call
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public Optional<UserV3> findUserById(UUID id) {
        simulateLatency();
        System.out.println("SLOW DATASOURCE: Querying for user " + id);
        return Optional.ofNullable(users.get(id));
    }

    public UserV3 saveUser(UserV3 user) {
        simulateLatency();
        System.out.println("SLOW DATASOURCE: Writing user " + user.id());
        users.put(user.id(), user);
        return user;
    }

    public void deleteUserAndPosts(UUID userId) {
        simulateLatency();
        System.out.println("SLOW DATASOURCE: Deleting user " + userId + " and their posts.");
        users.remove(userId);
        posts.entrySet().removeIf(entry -> entry.getValue().user_id().equals(userId));
    }
}


// --- Service Layer with Config-driven Caching ---

@Singleton
class UserManagementService {

    private final SlowDataStore dataStore;
    private final String userCacheName;

    public UserManagementService(SlowDataStore dataStore, @Value("${app.caches.user-cache-name}") String userCacheName) {
        this.dataStore = dataStore;
        this.userCacheName = userCacheName;
    }

    /**
     * Cache-Aside GET. The cache name is dynamically injected from application.yml.
     */
    @Cacheable(cacheNames = "${app.caches.user-cache-name}")
    public Optional<UserV3> findById(UUID id) {
        return dataStore.findUserById(id);
    }

    /**
     * Cache-Put UPDATE. Uses expression language to derive the key from the user object's id field.
     */
    @CachePut(cacheNames = "${app.caches.user-cache-name}", key = "#user.id")
    public UserV3 save(UserV3 user) {
        return dataStore.saveUser(user);
    }

    /**
     * Cache-Invalidate DELETE. This invalidates entries in TWO caches at once.
     * This is useful when deleting a user should also clear their related posts from the cache.
     */
    @CacheInvalidate(cacheNames = {"${app.caches.user-cache-name}", "${app.caches.post-cache-name}"}, parameters = "userId")
    public void deleteUserAndAssociatedContent(UUID userId) {
        // In a real scenario, we would need a way to find all post IDs for this user
        // to invalidate them one by one if not using a bulk clear.
        // For this example, we assume invalidating by userId is sufficient for some cache setups
        // or that we are just clearing the user from the user cache.
        // The annotation shows the capability to target multiple caches.
        dataStore.deleteUserAndPosts(userId);
        System.out.println("SERVICE LOGIC: User " + userId + " deleted. Invalidation signal sent for user and post caches.");
    }
}