package com.example.caching.classic_oop;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

// ========= DOMAIN =========
class User {
    enum Role { ADMIN, USER }
    private final UUID id;
    private final String email;
    private String passwordHash;
    private Role role;
    private boolean isActive;
    private final Timestamp createdAt;

    public User(UUID id, String email, String passwordHash, Role role, boolean isActive) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.isActive = isActive;
        this.createdAt = Timestamp.from(Instant.now());
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public boolean isActive() { return isActive; }
    public Timestamp getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        return "User{id=" + id + ", email='" + email + "'}";
    }
}

class Post {
    enum Status { DRAFT, PUBLISHED }
    private final UUID id;
    private final UUID userId;
    private String title;
    private String content;
    private Status status;

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
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    @Override
    public String toString() {
        return "Post{id=" + id + ", title='" + title + "'}";
    }
}

// ========= DATA SOURCE (Mock Database) =========
class MockDatabase {
    private static final Map<UUID, User> userTable = new HashMap<>();
    private static final Map<UUID, Post> postTable = new HashMap<>();

    static {
        User adminUser = new User(UUID.randomUUID(), "admin@example.com", "hash1", User.Role.ADMIN, true);
        userTable.put(adminUser.getId(), adminUser);
        Post firstPost = new Post(UUID.randomUUID(), adminUser.getId(), "Hello World", "This is the first post.", Post.Status.PUBLISHED);
        postTable.put(firstPost.getId(), firstPost);
    }

    public static Optional<User> findUserById(UUID id) {
        System.out.println("[DATABASE] Querying for User with ID: " + id);
        try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } // Simulate latency
        return Optional.ofNullable(userTable.get(id));
    }

    public static Optional<Post> findPostById(UUID id) {
        System.out.println("[DATABASE] Querying for Post with ID: " + id);
        try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } // Simulate latency
        return Optional.ofNullable(postTable.get(id));
    }

    public static void savePost(Post post) {
        System.out.println("[DATABASE] Saving Post with ID: " + post.getId());
        try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } // Simulate latency
        postTable.put(post.getId(), post);
    }
}

// ========= CACHE IMPLEMENTATION =========
interface Cache<K, V> {
    Optional<V> get(K key);
    void set(K key, V value);
    void delete(K key);
    void cleanUp();
}

class LruCache<K, V> implements Cache<K, V> {
    private static class CacheNode<K, V> {
        K key;
        V value;
        long expiryTime;
        CacheNode<K, V> prev;
        CacheNode<K, V> next;
    }

    private final Map<K, CacheNode<K, V>> cacheMap;
    private final int capacity;
    private final long timeToLiveMillis;
    private final CacheNode<K, V> head;
    private final CacheNode<K, V> tail;

    public LruCache(int capacity, long timeToLive, TimeUnit timeUnit) {
        this.capacity = capacity;
        this.timeToLiveMillis = timeUnit.toMillis(timeToLive);
        this.cacheMap = new HashMap<>();
        this.head = new CacheNode<>();
        this.tail = new CacheNode<>();
        head.next = tail;
        tail.prev = head;
    }

    @Override
    public synchronized Optional<V> get(K key) {
        CacheNode<K, V> node = cacheMap.get(key);
        if (node == null) {
            return Optional.empty();
        }

        if (System.currentTimeMillis() > node.expiryTime) {
            removeNode(node);
            cacheMap.remove(node.key);
            System.out.println("[CACHE] Expired entry found and removed for key: " + key);
            return Optional.empty();
        }

        moveToHead(node);
        return Optional.of(node.value);
    }

    @Override
    public synchronized void set(K key, V value) {
        CacheNode<K, V> node = cacheMap.get(key);
        if (node != null) {
            node.value = value;
            node.expiryTime = System.currentTimeMillis() + timeToLiveMillis;
            moveToHead(node);
        } else {
            if (cacheMap.size() >= capacity) {
                CacheNode<K, V> tailNode = popTail();
                cacheMap.remove(tailNode.key);
                System.out.println("[CACHE] Capacity reached. Evicted LRU item with key: " + tailNode.key);
            }
            CacheNode<K, V> newNode = new CacheNode<>();
            newNode.key = key;
            newNode.value = value;
            newNode.expiryTime = System.currentTimeMillis() + timeToLiveMillis;
            cacheMap.put(key, newNode);
            addNode(newNode);
        }
    }

    @Override
    public synchronized void delete(K key) {
        CacheNode<K, V> node = cacheMap.get(key);
        if (node != null) {
            removeNode(node);
            cacheMap.remove(key);
            System.out.println("[CACHE] Deleted entry for key: " + key);
        }
    }

    @Override
    public synchronized void cleanUp() {
        // This is a simple cleanup, a real implementation might use a background thread
        long now = System.currentTimeMillis();
        cacheMap.entrySet().removeIf(entry -> {
            if (now > entry.getValue().expiryTime) {
                removeNode(entry.getValue());
                return true;
            }
            return false;
        });
    }

    private void addNode(CacheNode<K, V> node) {
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
    }

    private void removeNode(CacheNode<K, V> node) {
        CacheNode<K, V> prev = node.prev;
        CacheNode<K, V> next = node.next;
        prev.next = next;
        next.prev = prev;
    }

    private void moveToHead(CacheNode<K, V> node) {
        removeNode(node);
        addNode(node);
    }

    private CacheNode<K, V> popTail() {
        CacheNode<K, V> res = tail.prev;
        removeNode(res);
        return res;
    }
}

// ========= REPOSITORY (Implements Cache-Aside) =========
class PostRepository {
    private final Cache<UUID, Post> postCache;

    public PostRepository(Cache<UUID, Post> postCache) {
        this.postCache = postCache;
    }

    public Optional<Post> findById(UUID id) {
        // 1. Try to get from cache
        Optional<Post> cachedPost = postCache.get(id);
        if (cachedPost.isPresent()) {
            System.out.println("[REPOSITORY] Cache HIT for Post ID: " + id);
            return cachedPost;
        }

        // 2. On cache miss, get from database
        System.out.println("[REPOSITORY] Cache MISS for Post ID: " + id);
        Optional<Post> dbPost = MockDatabase.findPostById(id);

        // 3. Store in cache and return
        dbPost.ifPresent(post -> postCache.set(id, post));
        return dbPost;
    }

    public void save(Post post) {
        // 1. Update database first
        MockDatabase.savePost(post);
        // 2. Invalidate cache
        postCache.delete(post.getId());
        System.out.println("[REPOSITORY] Invalidated cache for Post ID: " + post.getId());
    }
}

// ========= MAIN APPLICATION =========
public class ClassicOopDemo {
    public static void main(String[] args) {
        // Initialize cache and repository
        Cache<UUID, Post> postCache = new LruCache<>(10, 5, TimeUnit.SECONDS);
        PostRepository postRepository = new PostRepository(postCache);

        // Get the ID of the post we know exists in the mock DB
        UUID existingPostId = MockDatabase.postTable.values().iterator().next().getId();

        System.out.println("--- First Read ---");
        postRepository.findById(existingPostId).ifPresent(p -> System.out.println("Found Post: " + p.getTitle()));

        System.out.println("\n--- Second Read (should be a cache hit) ---");
        postRepository.findById(existingPostId).ifPresent(p -> System.out.println("Found Post: " + p.getTitle()));

        System.out.println("\n--- Update Post ---");
        Post postToUpdate = postRepository.findById(existingPostId).get();
        postToUpdate.setTitle("Hello World [Updated]");
        postRepository.save(postToUpdate);

        System.out.println("\n--- Third Read (should be a cache miss after invalidation) ---");
        postRepository.findById(existingPostId).ifPresent(p -> System.out.println("Found Post: " + p.getTitle()));

        System.out.println("\n--- Fourth Read (should be a cache hit again) ---");
        postRepository.findById(existingPostId).ifPresent(p -> System.out.println("Found Post: " + p.getTitle()));

        System.out.println("\n--- Waiting for cache to expire ---");
        try {
            Thread.sleep(6000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("\n--- Fifth Read (should be a cache miss after expiration) ---");
        postRepository.findById(existingPostId).ifPresent(p -> System.out.println("Found Post: " + p.getTitle()));
    }
}