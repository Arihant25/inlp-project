package com.example.cache.v1;

import io.micronaut.cache.annotation.CacheConfig;
import io.micronaut.cache.annotation.CacheInvalidate;
import io.micronaut.cache.annotation.CachePut;
import io.micronaut.cache.annotation.Cacheable;
import jakarta.inject.Singleton;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/*
 * Variation 1: The "Standard" OOP Approach
 *
 * This implementation follows a classic, by-the-book object-oriented design.
 * - Separate services for each domain entity (UserService, PostService).
 * - A mock repository layer to simulate database interaction.
 * - Clear, descriptive naming conventions.
 * - Use of constants for cache names.
 * - Demonstrates the standard @Cacheable, @CachePut, and @CacheInvalidate annotations.
 *
 * --- Micronaut Configuration (place in application.yml) ---
 * micronaut:
 *   caches:
 *     users:
 *       expire-after-write: 10m # Time-based expiration (TTL)
 *       maximum-size: 500      # Triggers LRU-like eviction
 *     posts:
 *       expire-after-write: 5m
 *       maximum-size: 2000
 */

// --- Domain Model ---

enum Role { ADMIN, USER }
enum PostStatus { DRAFT, PUBLISHED }

record User(
    UUID id,
    String email,
    String password_hash,
    Role role,
    boolean is_active,
    Timestamp created_at
) {}

record Post(
    UUID id,
    UUID user_id,
    String title,
    String content,
    PostStatus status
) {}

// --- Mock Data Persistence Layer ---

@Singleton
class UserRepository {
    private final Map<UUID, User> database = new ConcurrentHashMap<>();

    public UserRepository() {
        // Seed data
        UUID userId = UUID.randomUUID();
        database.put(userId, new User(userId, "admin@example.com", "hash123", Role.ADMIN, true, Timestamp.from(Instant.now())));
    }

    public Optional<User> findById(UUID id) {
        System.out.println("DATABASE HIT: Fetching user " + id);
        return Optional.ofNullable(database.get(id));
    }

    public User save(User user) {
        System.out.println("DATABASE HIT: Saving user " + user.id());
        database.put(user.id(), user);
        return user;
    }

    public void deleteById(UUID id) {
        System.out.println("DATABASE HIT: Deleting user " + id);
        database.remove(id);
    }
}

@Singleton
class PostRepository {
    private final Map<UUID, Post> database = new ConcurrentHashMap<>();

    public Optional<Post> findById(UUID id) {
        System.out.println("DATABASE HIT: Fetching post " + id);
        return Optional.ofNullable(database.get(id));
    }

    public Post save(Post post) {
        System.out.println("DATABASE HIT: Saving post " + post.id());
        database.put(post.id(), post);
        return post;
    }

    public void deleteById(UUID id) {
        System.out.println("DATABASE HIT: Deleting post " + id);
        database.remove(id);
    }
}


// --- Service Layer with Caching ---

@Singleton
@CacheConfig(cacheNames = {UserService.USERS_CACHE})
class UserService {
    public static final String USERS_CACHE = "users";
    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Cache-Aside GET: Micronaut checks the 'users' cache for a key matching 'id'.
     * If found, the cached User is returned.
     * If not found, this method is executed, the result is placed in the cache,
     * and then returned.
     */
    @Cacheable
    public Optional<User> getUserById(UUID id) {
        System.out.println("SERVICE LOGIC: Executing getUserById for " + id);
        return userRepository.findById(id);
    }

    /**
     * Cache-Put UPDATE: This method will always execute.
     * The returned User object will be placed in the 'users' cache,
     * overwriting any existing entry with the same key (user.id()).
     */
    @CachePut(parameters = {"user"})
    public User updateUser(User user) {
        System.out.println("SERVICE LOGIC: Executing updateUser for " + user.id());
        return userRepository.save(user);
    }

    /**
     * Cache-Invalidate DELETE: This method will always execute.
     * After execution, Micronaut will remove the entry from the 'users' cache
     * that corresponds to the key 'id'.
     */
    @CacheInvalidate
    public void deleteUser(UUID id) {
        System.out.println("SERVICE LOGIC: Executing deleteUser for " + id);
        userRepository.deleteById(id);
    }
}

@Singleton
@CacheConfig(cacheNames = {"posts"})
class PostService {
    private final PostRepository postRepository;

    public PostService(PostRepository postRepository) {
        this.postRepository = postRepository;
    }

    @Cacheable
    public Optional<Post> getPostById(UUID id) {
        System.out.println("SERVICE LOGIC: Executing getPostById for " + id);
        return postRepository.findById(id);
    }

    @CachePut(parameters = {"post"})
    public Post updatePost(Post post) {
        System.out.println("SERVICE LOGIC: Executing updatePost for " + post.id());
        return postRepository.save(post);
    }

    @CacheInvalidate
    public void deletePost(UUID id) {
        System.out.println("SERVICE LOGIC: Executing deletePost for " + id);
        postRepository.deleteById(id);
    }
}