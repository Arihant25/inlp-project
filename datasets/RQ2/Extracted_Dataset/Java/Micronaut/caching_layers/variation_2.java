package com.example.cache.v2;

import io.micronaut.cache.annotation.CacheConfig;
import io.micronaut.cache.annotation.CacheInvalidate;
import io.micronaut.cache.annotation.Cacheable;
import jakarta.inject.Singleton;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/*
 * Variation 2: The "Functional & Concise" Approach
 *
 * This version uses a more modern, concise style.
 * - A single, unified service handles both Users and Posts.
 * - The mock "database" is an internal map within the service for brevity.
 * - Methods return Optional to handle "not found" cases functionally.
 * - Demonstrates @CacheInvalidate(all = true) for a "nuke the cache" strategy.
 * - Uses shorter, but still meaningful, variable names.
 *
 * --- Micronaut Configuration (place in application.yml) ---
 * micronaut:
 *   caches:
 *     entities:
 *       expire-after-access: 15m # Time-based expiration (TTL after last access)
 *       maximum-size: 1000       # Triggers LRU-like eviction
 */

// --- Domain Model ---

enum RoleV2 { ADMIN, USER }
enum PostStatusV2 { DRAFT, PUBLISHED }

record UserV2(
    UUID id,
    String email,
    String password_hash,
    RoleV2 role,
    boolean is_active,
    Timestamp created_at
) {}

record PostV2(
    UUID id,
    UUID user_id,
    String title,
    String content,
    PostStatusV2 status
) {}


// --- Unified Service Layer with Caching ---

@Singleton
@CacheConfig(cacheNames = {"entities"})
public class ContentService {

    private final Map<UUID, UserV2> userStore = new ConcurrentHashMap<>();
    private final Map<UUID, PostV2> postStore = new ConcurrentHashMap<>();

    public ContentService() {
        // Seed data
        UUID usrId = UUID.randomUUID();
        UserV2 initialUser = new UserV2(usrId, "user@example.com", "hash456", RoleV2.USER, true, Timestamp.from(Instant.now()));
        PostV2 initialPost = new PostV2(UUID.randomUUID(), usrId, "First Post", "Content here", PostStatusV2.PUBLISHED);
        userStore.put(usrId, initialUser);
        postStore.put(initialPost.id(), initialPost);
    }

    /**
     * Cache-Aside GET for User. The key is generated from the 'id' parameter.
     * The result is cached in the 'entities' cache.
     */
    @Cacheable(value = "entities", parameters = "id")
    public Optional<UserV2> findUser(UUID id) {
        System.out.println("DB READ: User " + id);
        return Optional.ofNullable(userStore.get(id));
    }

    /**
     * Cache-Aside GET for Post.
     */
    @Cacheable(value = "entities", parameters = "id")
    public Optional<PostV2> findPost(UUID id) {
        System.out.println("DB READ: Post " + id);
        return Optional.ofNullable(postStore.get(id));
    }

    /**
     * This method updates a user and invalidates its specific cache entry.
     * Note: @CachePut would also work well here. This is just a different way.
     */
    @CacheInvalidate(parameters = "user.id")
    public UserV2 saveUser(UserV2 user) {
        System.out.println("DB WRITE: User " + user.id());
        userStore.put(user.id(), user);
        return user;
    }

    /**
     * This method updates a post and invalidates its specific cache entry.
     */
    @CacheInvalidate(parameters = "post.id")
    public PostV2 savePost(PostV2 post) {
        System.out.println("DB WRITE: Post " + post.id());
        postStore.put(post.id(), post);
        return post;
    }

    /**
     * Cache Invalidation (All): This method will execute and then completely
     * clear all entries from the 'entities' cache. Useful for bulk updates
     * or system resets.
     */
    @CacheInvalidate(all = true)
    public void resetAllContent() {
        System.out.println("SERVICE LOGIC: Resetting all content. Cache will be cleared.");
        // In a real app, you might re-seed data or perform other operations here.
        userStore.clear();
        postStore.clear();
    }
}