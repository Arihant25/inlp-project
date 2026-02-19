// Variation 3: The "Functional & Reactive" Developer
// Style: Uses Optional for null safety, more functional method signatures.
// Features: Demonstrates caching Optional<T>, programmatic cache access, and complex invalidation with @CacheInvalidateAll.

// --- FILE: src/main/resources/application.properties ---
// quarkus.cache.caffeine."users-optional".expire-after-write=15m
// quarkus.cache.caffeine."users-optional".maximum-size=200
// quarkus.cache.caffeine."active-users-count".expire-after-write=1h

package dev.functional.core.domain;

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

// --- FILE: src/main/java/dev/functional/core/domain/Post.java ---
package dev.functional.core.domain;

import java.util.UUID;

public class Post {
    public enum Status { DRAFT, PUBLISHED }
    public UUID id;
    public UUID user_id;
    public String title;
    public String content;
    public Status status;
}

// --- FILE: src/main/java/dev/functional/core/persistence/UserPersistence.java ---
package dev.functional.core.persistence;

import dev.functional.core.domain.User;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class UserPersistence {
    private final Map<UUID, User> db = new ConcurrentHashMap<>();

    public UserPersistence() {
        UUID id = UUID.fromString("f47ac10b-58cc-4372-a567-0e02b2c3d479");
        User u = new User();
        u.id = id;
        u.email = "functional.dev@example.com";
        u.is_active = true;
        u.role = User.Role.USER;
        u.created_at = Timestamp.from(Instant.now());
        db.put(id, u);
    }

    public Optional<User> findById(UUID id) {
        System.out.println("PERSISTENCE: Looking up user " + id);
        try {
            Thread.sleep(800); // Simulate I/O
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Optional.ofNullable(db.get(id));
    }

    public void save(User user) {
        System.out.println("PERSISTENCE: Saving user " + user.id);
        db.put(user.id, user);
    }
}

// --- FILE: src/main/java/dev/functional/core/application/UserApplicationService.java ---
package dev.functional.core.application;

import dev.functional.core.domain.User;
import dev.functional.core.persistence.UserPersistence;
import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheInvalidate;
import io.quarkus.cache.CacheInvalidateAll;
import io.quarkus.cache.CacheManager;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class UserApplicationService {

    @Inject
    UserPersistence userPersistence;

    @Inject
    CacheManager cacheManager;

    /**
     * Quarkus Cache transparently handles Optional. If the Optional is empty,
     * it will be cached as such, preventing repeated lookups for non-existent entities.
     */
    @CacheResult(cacheName = "users-optional")
    public Optional<User> findUser(UUID id) {
        System.out.println("SERVICE: Executing findUser logic for " + id);
        return userPersistence.findById(id);
    }

    /**
     * This method demonstrates complex invalidation. When a user's active status changes,
     * we must invalidate their specific entry in the 'users-optional' cache.
     * We also invalidate the 'active-users-count' cache, which could be holding a
     * derived value. This is a common pattern for aggregated data.
     */
    @CacheInvalidateAll({
        @CacheInvalidate(cacheName = "users-optional"),
        @CacheInvalidate(cacheName = "active-users-count", all = true)
    })
    public Optional<User> toggleUserActiveStatus(UUID id) {
        System.out.println("SERVICE: Toggling active status for " + id);
        return userPersistence.findById(id).map(user -> {
            user.is_active = !user.is_active;
            userPersistence.save(user);
            return user;
        });
    }

    /**
     * Demonstrates programmatic cache access. Instead of relying on annotations,
     * we can inject the CacheManager to interact with caches directly.
     * This is useful for complex logic that doesn't fit the declarative annotation model.
     */
    public long getActiveUserCount() {
        System.out.println("SERVICE: Calculating active user count");
        Cache cache = cacheManager.getCache("active-users-count").orElseThrow();
        
        // Uni<V> computeIfAbsent(K key, Function<K, V> mappingFunction);
        // The value is computed and cached if it's not already in the cache.
        // Here, we use a static key "count" for the single value.
        return cache.get("count", key -> {
            System.out.println("DB-AGGREGATION: Performing expensive count operation.");
            // In a real app, this would be a slow `SELECT COUNT(*) FROM users WHERE is_active = true`
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            return 500L; // Mocked count
        }).await().indefinitely();
    }
}