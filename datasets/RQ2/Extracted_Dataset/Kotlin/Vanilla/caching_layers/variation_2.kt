package com.example.caching.functional

import java.time.Instant
import java.util.UUID
import kotlin.concurrent.thread

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

// 2. LRU CACHE IMPLEMENTATION (Self-contained class)
// This implementation is thread-safe using synchronized blocks.
class ConcurrentLruCache<K, V>(private val capacity: Int) {
    private inner class Node(
        val key: K,
        var value: V,
        var expiresAt: Instant
    ) {
        var prev: Node? = null
        var next: Node? = null
    }

    private val map = mutableMapOf<K, Node>()
    private val head = Node(Any() as K, Any() as V, Instant.MAX)
    private val tail = Node(Any() as K, Any() as V, Instant.MAX)

    init {
        head.next = tail
        tail.prev = head
    }

    fun get(key: K): V? = synchronized(this) {
        val node = map[key] ?: return null
        if (Instant.now().isAfter(node.expiresAt)) {
            remove(key)
            return null
        }
        detach(node)
        attach(node)
        return node.value
    }

    fun put(key: K, value: V, ttlSeconds: Long = 300) = synchronized(this) {
        val expiresAt = Instant.now().plusSeconds(ttlSeconds)
        val node = map[key]
        if (node != null) {
            detach(node)
            node.value = value
            node.expiresAt = expiresAt
            attach(node)
        } else {
            if (map.size >= capacity) {
                val lruNode = tail.prev!!
                detach(lruNode)
                map.remove(lruNode.key)
            }
            val newNode = Node(key, value, expiresAt)
            attach(newNode)
            map[key] = newNode
        }
    }

    fun remove(key: K): V? = synchronized(this) {
        val node = map.remove(key) ?: return null
        detach(node)
        return node.value
    }

    private fun attach(node: Node) {
        node.next = head.next
        node.prev = head
        head.next?.prev = node
        head.next = node
    }

    private fun detach(node: Node) {
        node.prev?.next = node.next
        node.next?.prev = node.prev
    }
}

// 3. SINGLETON CACHE MANAGER
object CacheManager {
    val userCache = ConcurrentLruCache<UUID, User>(capacity = 100)
    val postCache = ConcurrentLruCache<UUID, Post>(capacity = 200)
}

// 4. DATA ACCESS OBJECT (Simulates DB)
object DataAccess {
    private val userTable = mutableMapOf<UUID, User>()
    private val postTable = mutableMapOf<UUID, Post>()

    fun findUserInDb(id: UUID): User? {
        println("DB_ACCESS: Reading user $id")
        return userTable[id]?.copy() // Return a copy to simulate immutability
    }

    fun persistUser(user: User) {
        println("DB_ACCESS: Writing user ${user.id}")
        userTable[user.id] = user
    }
}

// 5. SERVICE LOGIC (Top-level functions implementing cache-aside)
fun fetchUser(id: UUID): User? {
    return CacheManager.userCache.get(id)?.also {
        println("CACHE_HIT: User $id")
    } ?: run {
        println("CACHE_MISS: User $id")
        DataAccess.findUserInDb(id)?.also {
            CacheManager.userCache.put(id, it)
            println("CACHE_SET: User $id")
        }
    }
}

fun updateUserAndInvalidate(user: User) {
    DataAccess.persistUser(user)
    CacheManager.userCache.remove(user.id)
    println("CACHE_INVALIDATE: User ${user.id}")
}

// 6. DEMONSTRATION
fun main() {
    // Setup initial data
    val user1 = User(UUID.randomUUID(), "user1@domain.com", "hash1", UserRole.USER, true, Instant.now())
    val user2 = User(UUID.randomUUID(), "user2@domain.com", "hash2", UserRole.ADMIN, true, Instant.now())
    DataAccess.persistUser(user1)
    DataAccess.persistUser(user2)

    println("--- Initial Fetch ---")
    val fetchedUser1 = fetchUser(user1.id)
    println("Fetched: ${fetchedUser1?.email}")

    println("\n--- Second Fetch (should be a cache hit) ---")
    val cachedUser1 = fetchUser(user1.id)
    println("Fetched from cache: ${cachedUser1?.email}")

    println("\n--- Update and Invalidate ---")
    val updatedUser1 = user1.copy(email = "user1.updated@domain.com")
    updateUserAndInvalidate(updatedUser1)

    println("\n--- Fetch After Invalidation (should be a miss, then set) ---")
    val reFetchedUser1 = fetchUser(user1.id)
    println("Re-fetched: ${reFetchedUser1?.email}")

    println("\n--- Time-based Expiration Demo ---")
    CacheManager.userCache.put(user2.id, user2, ttlSeconds = 2)
    println("Fetched user2 immediately: ${fetchUser(user2.id)?.email}") // Hit
    println("Waiting for 3 seconds...")
    Thread.sleep(3000)
    println("Fetching user2 after expiration:")
    fetchUser(user2.id) // Miss
}