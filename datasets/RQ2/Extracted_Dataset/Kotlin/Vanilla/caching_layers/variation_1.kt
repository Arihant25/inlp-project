package com.example.caching.oop

import java.time.Instant
import java.util.UUID

// 1. DOMAIN SCHEMA
enum class UserRole { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }

data class User(
    val id: UUID,
    var email: String,
    val passwordHash: String,
    val role: UserRole,
    var isActive: Boolean,
    val createdAt: Instant
)

data class Post(
    val id: UUID,
    val userId: UUID,
    var title: String,
    var content: String,
    var status: PostStatus
)

// 2. CACHE ABSTRACTION (Interface-based)
interface Cache<K, V> {
    fun get(key: K): V?
    fun set(key: K, value: V, ttlSeconds: Long = 60)
    fun delete(key: K)
    fun clear()
}

// 3. LRU CACHE IMPLEMENTATION (From Scratch)
class LruCache<K, V>(private val capacity: Int) : Cache<K, V> {

    private class CacheNode<K, V>(
        val key: K,
        var value: V,
        var expiresAt: Instant
    )

    private class DoublyLinkedNode<K, V>(
        val key: K,
        var value: V,
        var expiresAt: Instant
    ) {
        var prev: DoublyLinkedNode<K, V>? = null
        var next: DoublyLinkedNode<K, V>? = null
    }

    private val cacheMap: MutableMap<K, DoublyLinkedNode<K, V>> = HashMap(capacity)
    private val head: DoublyLinkedNode<K, V> = DoublyLinkedNode(Any() as K, Any() as V, Instant.now()) // Dummy head
    private val tail: DoublyLinkedNode<K, V> = DoublyLinkedNode(Any() as K, Any() as V, Instant.now()) // Dummy tail

    init {
        head.next = tail
        tail.prev = head
    }

    override fun get(key: K): V? {
        val node = cacheMap[key] ?: return null

        if (Instant.now().isAfter(node.expiresAt)) {
            removeNode(node)
            cacheMap.remove(node.key)
            return null
        }

        moveToHead(node)
        return node.value
    }

    override fun set(key: K, value: V, ttlSeconds: Long) {
        val expiresAt = Instant.now().plusSeconds(ttlSeconds)
        val node = cacheMap[key]

        if (node != null) {
            node.value = value
            node.expiresAt = expiresAt
            moveToHead(node)
        } else {
            val newNode = DoublyLinkedNode(key, value, expiresAt)
            cacheMap[key] = newNode
            addNodeToFront(newNode)

            if (cacheMap.size > capacity) {
                val tailNode = popTail()
                tailNode?.let { cacheMap.remove(it.key) }
            }
        }
    }

    override fun delete(key: K) {
        cacheMap[key]?.let {
            removeNode(it)
            cacheMap.remove(key)
        }
    }
    
    override fun clear() {
        cacheMap.clear()
        head.next = tail
        tail.prev = head
    }

    private fun addNodeToFront(node: DoublyLinkedNode<K, V>) {
        node.prev = head
        node.next = head.next
        head.next?.prev = node
        head.next = node
    }

    private fun removeNode(node: DoublyLinkedNode<K, V>) {
        val prevNode = node.prev
        val nextNode = node.next
        prevNode?.next = nextNode
        nextNode?.prev = prevNode
    }

    private fun moveToHead(node: DoublyLinkedNode<K, V>) {
        removeNode(node)
        addNodeToFront(node)
    }

    private fun popTail(): DoublyLinkedNode<K, V>? {
        val res = tail.prev
        if (res != head) {
            removeNode(res!!)
            return res
        }
        return null
    }
}

// 4. MOCK DATA SOURCE
object MockDatabase {
    private val users = mutableMapOf<UUID, User>()
    private val posts = mutableMapOf<UUID, Post>()

    fun findUserById(id: UUID): User? {
        println("DATABASE: Querying for User ID: $id")
        return users[id]
    }

    fun saveUser(user: User) {
        println("DATABASE: Saving User ID: ${user.id}")
        users[user.id] = user
    }
    
    fun deleteUser(id: UUID) {
        println("DATABASE: Deleting User ID: $id")
        users.remove(id)
    }
}

// 5. SERVICE LAYER (Implements Cache-Aside Pattern)
class UserService(private val userCache: Cache<UUID, User>) {

    fun getUserById(id: UUID): User? {
        // 1. Try to get from cache
        val cachedUser = userCache.get(id)
        if (cachedUser != null) {
            println("CACHE HIT for User ID: $id")
            return cachedUser
        }
        println("CACHE MISS for User ID: $id")

        // 2. On miss, get from DB
        val dbUser = MockDatabase.findUserById(id)

        // 3. Set in cache and return
        dbUser?.let {
            println("CACHE SET for User ID: $id")
            userCache.set(id, it)
        }
        return dbUser
    }

    fun updateUser(user: User) {
        // 1. Update the database first
        MockDatabase.saveUser(user)
        
        // 2. Invalidate the cache
        println("CACHE INVALIDATE for User ID: ${user.id}")
        userCache.delete(user.id)
    }
}

// 6. DEMONSTRATION
fun main() {
    // Setup
    val userCache = LruCache<UUID, User>(capacity = 2)
    val userService = UserService(userCache)

    val user1 = User(UUID.randomUUID(), "alice@example.com", "hash1", UserRole.USER, true, Instant.now())
    val user2 = User(UUID.randomUUID(), "bob@example.com", "hash2", UserRole.USER, true, Instant.now())
    val user3 = User(UUID.randomUUID(), "charlie@example.com", "hash3", UserRole.ADMIN, true, Instant.now())
    MockDatabase.saveUser(user1)
    MockDatabase.saveUser(user2)
    MockDatabase.saveUser(user3)

    println("--- First-time fetch ---")
    userService.getUserById(user1.id) // Miss, then set
    userService.getUserById(user2.id) // Miss, then set

    println("\n--- Cache hit demo ---")
    userService.getUserById(user1.id) // Hit

    println("\n--- LRU Eviction demo ---")
    userService.getUserById(user3.id) // Miss, sets user3, evicts user2 (least recently used)
    userService.getUserById(user2.id) // Miss again, as it was evicted

    println("\n--- Cache invalidation demo ---")
    user1.email = "alice.new@example.com"
    userService.updateUser(user1) // Updates DB, invalidates cache
    userService.getUserById(user1.id) // Miss, fetches updated record from DB, then sets cache

    println("\n--- Time-based expiration demo ---")
    val userCacheWithShortTTL = LruCache<UUID, User>(capacity = 2)
    val shortTtlUserService = UserService(userCacheWithShortTTL)
    shortTtlUserService.userCache.set(user1.id, user1, ttlSeconds = 1)
    println("Fetching user1 immediately: ${shortTtlUserService.getUserById(user1.id)?.email}") // Hit
    Thread.sleep(1100)
    println("Fetching user1 after 1.1s:")
    shortTtlUserService.getUserById(user1.id) // Miss, because it expired
}