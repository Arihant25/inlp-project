package com.caching.frameworkstyle;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

// ========= MODEL =========
class UserModel {
    enum Role { ADMIN, USER }
    final UUID id;
    final String email;
    final String passwordHash;
    final Role role;
    final boolean isActive;
    final Timestamp createdAt;

    UserModel(UUID id, String email, String passwordHash, Role role, boolean isActive) {
        this.id = id; this.email = email; this.passwordHash = passwordHash;
        this.role = role; this.isActive = isActive; this.createdAt = Timestamp.from(Instant.now());
    }
    public UUID getId() { return id; }
    @Override public String toString() { return "UserModel{id=" + id + ", email='" + email + "'}"; }
}

class PostModel {
    enum Status { DRAFT, PUBLISHED }
    final UUID id;
    final UUID userId;
    String title;
    String content;
    Status status;

    PostModel(UUID id, UUID userId, String title, String content, Status status) {
        this.id = id; this.userId = userId; this.title = title;
        this.content = content; this.status = status;
    }
    public UUID getId() { return id; }
    @Override public String toString() { return "PostModel{id=" + id + ", title='" + title + "'}"; }
}

// ========= STORAGE (Mock Data Source) =========
class SlowDataSource {
    private static final Map<UUID, UserModel> userStore = new HashMap<>();
    private static final Map<UUID, PostModel> postStore = new HashMap<>();
    private static final UUID PREDEFINED_POST_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    static {
        UserModel user = new UserModel(UUID.randomUUID(), "generic.user@system.com", "hash3", UserModel.Role.USER, true);
        PostModel post = new PostModel(PREDEFINED_POST_ID, user.getId(), "Abstract Caching", "Content about patterns.", PostModel.Status.PUBLISHED);
        userStore.put(user.getId(), user);
        postStore.put(post.getId(), post);
    }

    public static Optional<PostModel> queryForPost(UUID id) {
        System.out.println("{DB_PROVIDER} >> Simulating slow query for Post: " + id);
        try { Thread.sleep(75); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return Optional.ofNullable(postStore.get(id));
    }

    public static void persistPost(PostModel post) {
        System.out.println("{DB_PROVIDER} >> Simulating slow write for Post: " + post.getId());
        try { Thread.sleep(75); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        postStore.put(post.getId(), post);
    }
}

// ========= CACHING FRAMEWORK =========
interface ICache<K, V> {
    Optional<V> retrieve(K key);
    void store(K key, V value);
    void invalidate(K key);
}

class GenericLruCache<K, V> implements ICache<K, V> {
    private final int capacity;
    private final long ttlMillis;
    private final Map<K, DoublyLinkedNode<K, V>> lookup;
    private final DoublyLinkedNode<K, V> head, tail;

    private static class DoublyLinkedNode<K, V> {
        K key;
        V value;
        long expiresAt;
        DoublyLinkedNode<K, V> prev, next;
    }

    public GenericLruCache(int capacity, long ttl, TimeUnit unit) {
        this.capacity = capacity;
        this.ttlMillis = unit.toMillis(ttl);
        this.lookup = new HashMap<>();
        this.head = new DoublyLinkedNode<>();
        this.tail = new DoublyLinkedNode<>();
        head.next = tail;
        tail.prev = head;
    }

    @Override
    public synchronized Optional<V> retrieve(K key) {
        DoublyLinkedNode<K, V> node = lookup.get(key);
        if (node == null) return Optional.empty();
        if (System.currentTimeMillis() > node.expiresAt) {
            removeNode(node);
            lookup.remove(key);
            System.out.println("{CACHE_PROVIDER} >> Stale entry removed: " + key);
            return Optional.empty();
        }
        moveToFront(node);
        return Optional.of(node.value);
    }

    @Override
    public synchronized void store(K key, V value) {
        DoublyLinkedNode<K, V> node = lookup.get(key);
        if (node != null) {
            node.value = value;
            node.expiresAt = System.currentTimeMillis() + ttlMillis;
            moveToFront(node);
        } else {
            if (lookup.size() >= capacity) {
                DoublyLinkedNode<K, V> lru = tail.prev;
                removeNode(lru);
                lookup.remove(lru.key);
                System.out.println("{CACHE_PROVIDER} >> Evicted LRU entry: " + lru.key);
            }
            DoublyLinkedNode<K, V> newNode = new DoublyLinkedNode<>();
            newNode.key = key;
            newNode.value = value;
            newNode.expiresAt = System.currentTimeMillis() + ttlMillis;
            addNodeToFront(newNode);
            lookup.put(key, newNode);
        }
    }

    @Override
    public synchronized void invalidate(K key) {
        DoublyLinkedNode<K, V> node = lookup.get(key);
        if (node != null) {
            removeNode(node);
            lookup.remove(key);
            System.out.println("{CACHE_PROVIDER} >> Invalidated entry: " + key);
        }
    }

    private void addNodeToFront(DoublyLinkedNode<K, V> node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }
    private void removeNode(DoublyLinkedNode<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }
    private void moveToFront(DoublyLinkedNode<K, V> node) {
        removeNode(node);
        addNodeToFront(node);
    }
}

// ========= ABSTRACT REPOSITORY (Implements Cache-Aside) =========
abstract class AbstractCacheRepository<K, V> {
    private final ICache<K, V> cache;

    protected AbstractCacheRepository(ICache<K, V> cache) {
        this.cache = cache;
    }

    protected abstract Optional<V> fetchFromSource(K key);
    protected abstract void writeToSource(V value);
    protected abstract K getKey(V value);

    public Optional<V> getById(K key) {
        return cache.retrieve(key).or(() -> {
            System.out.println("{REPOSITORY} >> Cache MISS for key: " + key);
            Optional<V> freshData = fetchFromSource(key);
            freshData.ifPresent(data -> cache.store(key, data));
            return freshData;
        });
    }

    public void save(V value) {
        writeToSource(value);
        cache.invalidate(getKey(value));
    }
}

// ========= CONCRETE REPOSITORY IMPLEMENTATION =========
class PostCacheRepository extends AbstractCacheRepository<UUID, PostModel> {
    public PostCacheRepository(ICache<UUID, PostModel> cache) {
        super(cache);
    }

    @Override
    protected Optional<PostModel> fetchFromSource(UUID key) {
        return SlowDataSource.queryForPost(key);
    }

    @Override
    protected void writeToSource(PostModel value) {
        SlowDataSource.persistPost(value);
    }

    @Override
    protected UUID getKey(PostModel value) {
        return value.getId();
    }
}

// ========= MAIN APPLICATION =========
public class FrameworkStyleDemo {
    public static void main(String[] args) {
        ICache<UUID, PostModel> postCache = new GenericLruCache<>(10, 10, TimeUnit.SECONDS);
        PostCacheRepository postRepo = new PostCacheRepository(postCache);
        UUID postId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

        System.out.println("--- Attempt 1: Read post (expect DB query) ---");
        postRepo.getById(postId).ifPresent(p -> System.out.println("  Result: " + p));

        System.out.println("\n--- Attempt 2: Read post again (expect Cache HIT) ---");
        postRepo.getById(postId).ifPresent(p -> System.out.println("  Result: " + p));

        System.out.println("\n--- Attempt 3: Update post ---");
        PostModel post = postRepo.getById(postId).get();
        post.title = "New Title via Abstract Repo";
        postRepo.save(post);

        System.out.println("\n--- Attempt 4: Read post after update (expect DB query) ---");
        postRepo.getById(postId).ifPresent(p -> System.out.println("  Result: " + p));
    }
}