package com.caching.utilstyle;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

// ========= DOMAIN (using modern Java Records) =========
enum UserRole { ADMIN, USER }
record User(UUID id, String email, String password_hash, UserRole role, boolean is_active, Timestamp created_at) {}

enum PostStatus { DRAFT, PUBLISHED }
record Post(UUID id, UUID user_id, String title, String content, PostStatus status) {}

// ========= MOCK DATABASE (Static Utility) =========
final class MockDb {
    private static final Map<UUID, User> users = new ConcurrentHashMap<>();
    private static final Map<UUID, Post> posts = new ConcurrentHashMap<>();

    static {
        User u = new User(UUID.randomUUID(), "admin@dev.com", "h2", UserRole.ADMIN, true, Timestamp.from(Instant.now()));
        Post p = new Post(UUID.randomUUID(), u.id(), "Utility Style", "A post about utility classes.", PostStatus.PUBLISHED);
        users.put(u.id(), u);
        posts.put(p.id(), p);
    }

    public static Optional<User> fetchUser(UUID id) {
        System.out.println("DB>> Fetching user " + id);
        try { Thread.sleep(60); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return Optional.ofNullable(users.get(id));
    }

    public static Optional<Post> fetchPost(UUID id) {
        System.out.println("DB>> Fetching post " + id);
        try { Thread.sleep(60); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return Optional.ofNullable(posts.get(id));
    }

    public static void updatePost(Post p) {
        System.out.println("DB>> Updating post " + p.id());
        try { Thread.sleep(60); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        posts.put(p.id(), p);
    }
    
    public static UUID getAnyPostId() {
        return posts.keySet().iterator().next();
    }
}

// ========= LRU CACHE IMPLEMENTATION =========
class LruCache<K, V> {
    private final int capacity;
    private final Map<K, Node> store;
    private final Node head, tail;

    private class Node {
        K key;
        V val;
        Node prev, next;
        Node(K key, V val) { this.key = key; this.val = val; }
    }

    public LruCache(int capacity) {
        this.capacity = capacity;
        this.store = new ConcurrentHashMap<>(capacity);
        this.head = new Node(null, null);
        this.tail = new Node(null, null);
        head.next = tail;
        tail.prev = head;
    }

    public synchronized V get(K key) {
        Node n = store.get(key);
        if (n == null) return null;
        remove(n);
        insert(n);
        return n.val;
    }

    public synchronized void put(K key, V val) {
        if (store.containsKey(key)) {
            remove(store.get(key));
        }
        if (store.size() == capacity) {
            System.out.println("CACHE>> Evicting LRU item: " + tail.prev.key);
            remove(tail.prev);
        }
        insert(new Node(key, val));
    }

    public synchronized void evict(K key) {
        if (store.containsKey(key)) {
            remove(store.get(key));
        }
    }

    private void remove(Node n) {
        store.remove(n.key);
        n.prev.next = n.next;
        n.next.prev = n.prev;
    }

    private void insert(Node n) {
        store.put(n.key, n);
        Node headNext = head.next;
        head.next = n;
        n.prev = head;
        n.next = headNext;
        headNext.prev = n;
    }
}

// ========= CACHE MANAGER (Static Utility) =========
final class CacheManager {
    private static class TimedEntry<T> {
        final T value;
        final long expiry;
        TimedEntry(T value, long ttlMillis) {
            this.value = value;
            this.expiry = System.currentTimeMillis() + ttlMillis;
        }
        boolean isExpired() {
            return System.currentTimeMillis() > expiry;
        }
    }

    private static final LruCache<UUID, TimedEntry<Post>> postCache = new LruCache<>(5);
    private static final long POST_TTL = TimeUnit.SECONDS.toMillis(5);

    public static Optional<Post> getPost(UUID id) {
        TimedEntry<Post> entry = postCache.get(id);
        if (entry != null) {
            if (entry.isExpired()) {
                System.out.println("CACHE>> Post " + id + " expired. Evicting.");
                postCache.evict(id);
                return Optional.empty();
            }
            return Optional.of(entry.value);
        }
        return Optional.empty();
    }

    public static void setPost(Post post) {
        postCache.put(post.id(), new TimedEntry<>(post, POST_TTL));
    }

    public static void invalidatePost(UUID id) {
        System.out.println("CACHE>> Invalidating post " + id);
        postCache.evict(id);
    }
}

// ========= DATA ACCESS SERVICE (Implements Cache-Aside) =========
final class DataAccessService {
    public static Optional<Post> getPostById(UUID id) {
        // 1. Check cache
        Optional<Post> cached = CacheManager.getPost(id);
        if (cached.isPresent()) {
            System.out.println("SERVICE>> Cache HIT for post " + id);
            return cached;
        }

        // 2. On miss, fetch from DB
        System.out.println("SERVICE>> Cache MISS for post " + id);
        Optional<Post> fromDb = MockDb.fetchPost(id);

        // 3. Populate cache
        fromDb.ifPresent(CacheManager::setPost);
        return fromDb;
    }

    public static void updatePostTitle(UUID id, String newTitle) {
        // In a real app, this would be a more complex transaction
        MockDb.fetchPost(id).ifPresent(p -> {
            Post updatedPost = new Post(p.id(), p.user_id(), newTitle, p.content(), p.status());
            // 1. Update DB
            MockDb.updatePost(updatedPost);
            // 2. Invalidate cache
            CacheManager.invalidatePost(id);
        });
    }
}

// ========= MAIN APPLICATION =========
public class UtilityStyleDemo {
    public static void main(String[] args) throws InterruptedException {
        UUID postId = MockDb.getAnyPostId();
        System.out.println("Operating on Post ID: " + postId);

        System.out.println("\n--- Initial fetch ---");
        DataAccessService.getPostById(postId).ifPresent(p -> System.out.println("Got post: " + p.title()));

        System.out.println("\n--- Second fetch (should hit cache) ---");
        DataAccessService.getPostById(postId).ifPresent(p -> System.out.println("Got post: " + p.title()));

        System.out.println("\n--- Updating post title ---");
        DataAccessService.updatePostTitle(postId, "A New Title!");

        System.out.println("\n--- Fetch after update (should miss, then populate) ---");
        DataAccessService.getPostById(postId).ifPresent(p -> System.out.println("Got post: " + p.title()));

        System.out.println("\n--- Waiting for TTL expiration (5 seconds) ---");
        Thread.sleep(5100);

        System.out.println("\n--- Fetch after expiration (should miss, then populate) ---");
        DataAccessService.getPostById(postId).ifPresent(p -> System.out.println("Got post: " + p.title()));
    }
}