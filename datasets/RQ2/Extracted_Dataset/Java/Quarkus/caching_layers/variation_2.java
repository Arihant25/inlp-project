// Variation 2: The "Pragmatic / Annotation-Driven" Developer
// Style: Concise, annotation-heavy, uses Lombok to reduce boilerplate.
// Features: Caches a List, uses @CacheKey for composite keys, and demonstrates lock-timeout for stampede protection.

// --- FILE: src/main/resources/application.properties ---
// quarkus.cache.caffeine."posts-by-user".expire-after-write=5m
// quarkus.cache.caffeine."posts-by-user".maximum-size=500
// quarkus.cache.caffeine."posts-by-user".lock-timeout=2s // Protect against cache stampede

package com.pragmatic.blog.model;

import java.sql.Timestamp;
import java.util.UUID;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Data @AllArgsConstructor @NoArgsConstructor
public class User {
    public enum Role { ADMIN, USER }
    private UUID id;
    private String email;
    private String password_hash;
    private Role role;
    private boolean is_active;
    private Timestamp created_at;
}

// --- FILE: src/main/java/com/pragmatic/blog/model/Post.java ---
package com.pragmatic.blog.model;

import java.util.UUID;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Data @AllArgsConstructor @NoArgsConstructor
public class Post {
    public enum Status { DRAFT, PUBLISHED }
    private UUID id;
    private UUID user_id;
    private String title;
    private String content;
    private Status status;
}

// --- FILE: src/main/java/com/pragmatic/blog/data/PostDataAccessor.java ---
package com.pragmatic.blog.data;

import com.pragmatic.blog.model.Post;
import io.quarkus.cache.CacheInvalidate;
import io.quarkus.cache.CacheKey;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class PostDataAccessor {

    private final Map<UUID, Post> postDb = new ConcurrentHashMap<>();

    public PostDataAccessor() {
        UUID userId = UUID.randomUUID();
        UUID postId1 = UUID.randomUUID();
        UUID postId2 = UUID.randomUUID();
        postDb.put(postId1, new Post(postId1, userId, "Quarkus Intro", "Content...", Post.Status.PUBLISHED));
        postDb.put(postId2, new Post(postId2, userId, "Caching in Quarkus", "More content...", Post.Status.PUBLISHED));
    }

    /**
     * Caches a list of posts. The cache key is a composite of both userId and status.
     * The @CacheKey annotations specify which parameters to use for the key.
     * `lock-timeout` prevents multiple threads from calling the slow DB method simultaneously
     * for the same key if the cache entry is missing (cache stampede).
     */
    @CacheResult(cacheName = "posts-by-user")
    public List<Post> findPostsByUserAndStatus(@CacheKey UUID userId, @CacheKey Post.Status status) {
        System.out.println("DB-ACCESS: Finding posts for user " + userId + " with status " + status);
        try {
            Thread.sleep(1500); // Simulate slow query
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return postDb.values().stream()
                .filter(p -> p.getUser_id().equals(userId) && p.getStatus() == status)
                .collect(Collectors.toList());
    }

    /**
     * When a new post is created, we must invalidate the cache for that user's posts.
     * Since the cache key depends on status, we must invalidate the specific entry
     * for the status of the newly created post.
     */
    @CacheInvalidate(cacheName = "posts-by-user")
    public Post createPost(@CacheKey UUID userId, String title, String content, @CacheKey Post.Status status) {
        System.out.println("DB-ACCESS: Creating new post for user " + userId);
        UUID newId = UUID.randomUUID();
        Post newPost = new Post(newId, userId, title, content, status);
        postDb.put(newId, newPost);
        return newPost;
    }
    
    /**
     * A simple get by ID, not cached in this example to contrast with the list-based cache.
     */
    public Post findById(UUID postId) {
        return postDb.get(postId);
    }
}