package com.example.cache.v4;

import io.micronaut.cache.Cache;
import io.micronaut.cache.CacheManager;
import io.micronaut.cache.SyncCache;
import jakarta.inject.Singleton;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/*
 * Variation 4: The "Explicit Cache Manager" Approach
 *
 * This variation demonstrates programmatic cache interaction, forgoing annotations for direct control.
 * - The Micronaut CacheManager is injected directly into the service.
 * - The service logic manually implements the cache-aside pattern.
 * - This approach is useful for complex caching logic that doesn't fit the declarative annotation model.
 * - The mock data source is a ConcurrentHashMap to emphasize thread-safety.
 *
 * --- Micronaut Configuration (place in application.yml) ---
 * micronaut:
 *   caches:
 *     manual-user-cache:
 *       expire-after-write: 5m
 *       maximum-size: 100
 *     manual-post-cache:
 *       expire-after-write: 15m
 *       maximum-size: 500
 */

// --- Domain Model ---

enum RoleV4 { ADMIN, USER }
enum PostStatusV4 { DRAFT, PUBLISHED }

record UserV4(
    UUID id,
    String email,
    String password_hash,
    RoleV4 role,
    boolean is_active,
    Timestamp created_at
) {}

record PostV4(
    UUID id,
    UUID user_id,
    String title,
    String content,
    PostStatusV4 status
) {}

// --- Service with Programmatic Caching ---

@Singleton
public class DataService {

    private final Map<UUID, UserV4> userDb = new ConcurrentHashMap<>();
    private final Map<UUID, PostV4> postDb = new ConcurrentHashMap<>();

    private final SyncCache<UserV4> userCache;
    private final SyncCache<PostV4> postCache;

    public DataService(CacheManager cacheManager) {
        this.userCache = cacheManager.getCache("manual-user-cache");
        this.postCache = cacheManager.getCache("manual-post-cache");

        // Seed data
        UUID userId = UUID.randomUUID();
        userDb.put(userId, new UserV4(userId, "direct.user@example.com", "hashABC", RoleV4.ADMIN, true, Timestamp.from(Instant.now())));
    }

    /**
     * Manual Cache-Aside GET: The logic is explicitly written out.
     * 1. Check the cache for the key.
     * 2. If present, return the cached value.
     * 3. If absent, call the data source (the lambda).
     * 4. The result of the data source call is put into the cache and returned.
     */
    public Optional<UserV4> getUser(UUID id) {
        // The get() method with a Supplier implements cache-aside atomically.
        return userCache.get(id, () -> {
            System.out.println("CACHE MISS / DB HIT: Fetching user " + id);
            return Optional.ofNullable(userDb.get(id));
        });
    }

    public Optional<PostV4> getPost(UUID id) {
        return postCache.get(id, () -> {
            System.out.println("CACHE MISS / DB HIT: Fetching post " + id);
            return Optional.ofNullable(postDb.get(id));
        });
    }

    /**
     * Manual Cache Update/Set: The logic explicitly saves to the DB
     * and then explicitly puts the new value into the cache, overwriting any old value.
     */
    public UserV4 saveUser(UserV4 user) {
        System.out.println("DB WRITE: Saving user " + user.id());
        userDb.put(user.id(), user);

        System.out.println("CACHE WRITE: Putting user " + user.id() + " into cache.");
        userCache.put(user.id(), user);

        return user;
    }

    /**
     * Manual Cache Invalidation: The logic explicitly deletes from the DB
     * and then explicitly invalidates the corresponding key in the cache.
     */
    public void deleteUser(UUID id) {
        System.out.println("DB DELETE: Deleting user " + id);
        userDb.remove(id);

        System.out.println("CACHE INVALIDATE: Removing user " + id + " from cache.");
        userCache.invalidate(id);
    }
}