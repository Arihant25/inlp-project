package com.caching.concurrent;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

public class ConcurrentAccessDemo {

    // ========= DOMAIN ENTITIES =========
    enum UserRole { ADMIN, USER }
    static class User {
        final UUID id; final String email; final String passwordHash; final UserRole role; final boolean isActive; final Timestamp createdAt;
        User(UUID id, String email, String passwordHash, UserRole role) {
            this.id = id; this.email = email; this.passwordHash = passwordHash; this.role = role;
            this.isActive = true; this.createdAt = Timestamp.from(Instant.now());
        }
        public UUID getId() { return id; }
        @Override public String toString() { return "User{id=" + id + "}"; }
    }

    enum PostStatus { DRAFT, PUBLISHED }
    static class Post {
        final UUID id; final UUID userId; String title; String content; PostStatus status;
        Post(UUID id, UUID userId, String title, String content) {
            this.id = id; this.userId = userId; this.title = title; this.content = content; this.status = PostStatus.PUBLISHED;
        }
        public UUID getId() { return id; }
        @Override public String toString() { return "Post{id=" + id + ", title='" + title + "'}"; }
    }

    // ========= MOCK DATA STORE =========
    static class DataStore {
        private static final Map<UUID, Post> posts = new ConcurrentHashMap<>();
        static {
            UUID userId = UUID.randomUUID();
            for (int i = 0; i < 5; i++) {
                UUID postId = UUID.randomUUID();
                posts.put(postId, new Post(postId, userId, "Post " + i, "Content " + i));
            }
        }
        public static Optional<Post> findPost(UUID id) {
            System.out.printf("[DB-READ] Thread %d fetching post %s\n", Thread.currentThread().getId(), id.toString().substring(0, 8));
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return Optional.ofNullable(posts.get(id));
        }
        public static void savePost(Post post) {
            System.out.printf("[DB-WRITE] Thread %d saving post %s\n", Thread.currentThread().getId(), post.id.toString().substring(0, 8));
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            posts.put(post.id, post);
        }
    }

    // ========= THREAD-SAFE LRU CACHE =========
    static class ThreadSafeLruCache<K, V> {
        private final int capacity;
        private final long ttlMillis;
        private final ConcurrentHashMap<K, CacheNode<K, V>> cacheMap;
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        private final CacheNode<K, V> head, tail;

        private static class CacheNode<K, V> {
            K key; V value; long expires; CacheNode<K, V> prev, next;
        }

        public ThreadSafeLruCache(int capacity, long ttl, TimeUnit unit) {
            this.capacity = capacity;
            this.ttlMillis = unit.toMillis(ttl);
            this.cacheMap = new ConcurrentHashMap<>(capacity);
            this.head = new CacheNode<>();
            this.tail = new CacheNode<>();
            head.next = tail;
            tail.prev = head;
        }

        public Optional<V> get(K key) {
            CacheNode<K, V> node = cacheMap.get(key);
            if (node == null) return Optional.empty();

            if (System.currentTimeMillis() > node.expires) {
                // Let a write operation handle the removal
                return Optional.empty();
            }

            lock.writeLock().lock(); // Need write lock to modify the linked list
            try {
                moveToHead(node);
            } finally {
                lock.writeLock().unlock();
            }
            return Optional.of(node.value);
        }

        public void put(K key, V value) {
            lock.writeLock().lock();
            try {
                CacheNode<K, V> node = cacheMap.get(key);
                if (node != null) {
                    node.value = value;
                    node.expires = System.currentTimeMillis() + ttlMillis;
                    moveToHead(node);
                } else {
                    if (cacheMap.size() >= capacity) {
                        CacheNode<K, V> tailNode = popTail();
                        cacheMap.remove(tailNode.key);
                        System.out.printf("[CACHE-EVICT] Thread %d evicted key %s\n", Thread.currentThread().getId(), tailNode.key.toString().substring(0, 8));
                    }
                    CacheNode<K, V> newNode = new CacheNode<>();
                    newNode.key = key;
                    newNode.value = value;
                    newNode.expires = System.currentTimeMillis() + ttlMillis;
                    cacheMap.put(key, newNode);
                    addToHead(newNode);
                }
            } finally {
                lock.writeLock().unlock();
            }
        }

        public void delete(K key) {
            lock.writeLock().lock();
            try {
                CacheNode<K, V> node = cacheMap.remove(key);
                if (node != null) {
                    removeNode(node);
                }
            } finally {
                lock.writeLock().unlock();
            }
        }

        private void addToHead(CacheNode<K, V> node) {
            node.prev = head;
            node.next = head.next;
            head.next.prev = node;
            head.next = node;
        }

        private void removeNode(CacheNode<K, V> node) {
            node.prev.next = node.next;
            node.next.prev = node.prev;
        }

        private void moveToHead(CacheNode<K, V> node) {
            removeNode(node);
            addToHead(node);
        }

        private CacheNode<K, V> popTail() {
            CacheNode<K, V> res = tail.prev;
            removeNode(res);
            return res;
        }
    }

    // ========= GENERIC CACHING SERVICE =========
    static class CachedEntityService<K, V> {
        private final ThreadSafeLruCache<K, V> cache;
        private final Function<K, Optional<V>> sourceOfTruthFetcher;

        public CachedEntityService(ThreadSafeLruCache<K, V> cache, Function<K, Optional<V>> sourceOfTruthFetcher) {
            this.cache = cache;
            this.sourceOfTruthFetcher = sourceOfTruthFetcher;
        }

        public Optional<V> findById(K key) {
            // Cache-Aside Pattern
            return cache.get(key).or(() -> {
                System.out.printf("[SERVICE-MISS] Thread %d cache miss for key %s\n", Thread.currentThread().getId(), key.toString().substring(0, 8));
                Optional<V> freshData = sourceOfTruthFetcher.apply(key);
                freshData.ifPresent(data -> cache.put(key, data));
                return freshData;
            });
        }

        public void invalidate(K key) {
            System.out.printf("[SERVICE-INVALIDATE] Thread %d invalidating key %s\n", Thread.currentThread().getId(), key.toString().substring(0, 8));
            cache.delete(key);
        }
    }

    // ========= MAIN APPLICATION =========
    public static void main(String[] args) throws InterruptedException {
        ThreadSafeLruCache<UUID, Post> postCache = new ThreadSafeLruCache<>(3, 10, TimeUnit.SECONDS);
        CachedEntityService<UUID, Post> postService = new CachedEntityService<>(postCache, DataStore::findPost);

        UUID[] postIds = DataStore.posts.keySet().toArray(new UUID[0]);
        ExecutorService executor = Executors.newFixedThreadPool(10);

        System.out.println("--- Simulating 20 concurrent read requests for 5 posts ---");
        for (int i = 0; i < 20; i++) {
            final int index = i % 5;
            executor.submit(() -> {
                postService.findById(postIds[index]).ifPresent(p -> {
                    System.out.printf("[APP-READ] Thread %d got post: %s\n", Thread.currentThread().getId(), p.title);
                });
            });
        }

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        System.out.println("\n--- Simulating an update and subsequent reads ---");
        UUID postToUpdate = postIds[0];
        Optional<Post> postOpt = DataStore.findPost(postToUpdate);
        if (postOpt.isPresent()) {
            Post p = postOpt.get();
            p.title = "Updated Concurrently";
            DataStore.savePost(p); // 1. Update DB
            postService.invalidate(p.getId()); // 2. Invalidate Cache
        }

        ExecutorService executor2 = Executors.newFixedThreadPool(5);
        System.out.println("--- Reading the updated post 5 times concurrently (first should miss) ---");
        for (int i = 0; i < 5; i++) {
            executor2.submit(() -> {
                postService.findById(postToUpdate).ifPresent(post -> {
                    System.out.printf("[APP-READ-AFTER-UPDATE] Thread %d got post: %s\n", Thread.currentThread().getId(), post.title);
                });
            });
        }
        executor2.shutdown();
        executor2.awaitTermination(5, TimeUnit.SECONDS);
    }
}