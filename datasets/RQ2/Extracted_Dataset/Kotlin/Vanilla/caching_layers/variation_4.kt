package com.example.caching.protocol

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

// 2. CORE ABSTRACTIONS (Interfaces/Protocols)
interface KeyValueStore<K, V> {
    fun retrieve(key: K): V?
    fun store(key: K, value: V, ttlSeconds: Long)
    fun evict(key: K)
}

interface DataSource<K, T> {
    fun fetchById(id: K): T?
    fun persist(entity: T): T
    fun removeById(id: K): Boolean
}

interface Repository<K, T> {
    fun findById(id: K): T?
    fun save(entity: T): T
    fun deleteById(id: K): Boolean
}

// 3. CONCRETE IMPLEMENTATIONS
// LRU Cache implementation of KeyValueStore
class LruCacheProvider<K, V>(private val capacity: Int) : KeyValueStore<K, V> {
    private inner class Node(val key: K, var value: V, var expiresAt: Instant) {
        var prev: Node? = null
        var next: Node? = null
    }

    private val nodeMap = HashMap<K, Node>()
    private val head = Node(Any() as K, Any() as V, Instant.MAX)
    private val tail = Node(Any() as K, Any() as V, Instant.MAX)

    init {
        head.next = tail
        tail.prev = head
    }

    override fun retrieve(key: K): V? {
        val node = nodeMap[key] ?: return null
        if (Instant.now().isAfter(node.expiresAt)) {
            evict(key)
            return null
        }
        moveToFront(node)
        return node.value
    }

    override fun store(key: K, value: V, ttlSeconds: Long) {
        val expiresAt = Instant.now().plusSeconds(ttlSeconds)
        val node = nodeMap[key]
        if (node != null) {
            node.value = value
            node.expiresAt = expiresAt
            moveToFront(node)
        } else {
            if (nodeMap.size >= capacity) {
                removeLast()
            }
            val newNode = Node(key, value, expiresAt)
            nodeMap[key] = newNode
            addToFront(newNode)
        }
    }

    override fun evict(key: K) {
        nodeMap.remove(key)?.let { removeNode(it) }
    }

    private fun moveToFront(node: Node) {
        removeNode(node)
        addToFront(node)
    }

    private fun addToFront(node: Node) {
        node.next = head.next
        node.prev = head
        head.next?.prev = node
        head.next = node
    }

    private fun removeNode(node: Node) {
        node.prev?.next = node.next
        node.next?.prev = node.prev
    }

    private fun removeLast() {
        val last = tail.prev
        if (last != head) {
            removeNode(last!!)
            nodeMap.remove(last.key)
        }
    }
}

// In-memory implementation of a DataSource
class InMemoryUserDataSource : DataSource<UUID, User> {
    private val userStorage = mutableMapOf<UUID, User>()

    override fun fetchById(id: UUID): User? {
        println("DATASOURCE: Fetching user $id")
        return userStorage[id]?.copy()
    }

    override fun persist(entity: User): User {
        println("DATASOURCE: Persisting user ${entity.id}")
        userStorage[entity.id] = entity.copy()
        return entity
    }

    override fun removeById(id: UUID): Boolean {
        println("DATASOURCE: Removing user $id")
        return userStorage.remove(id) != null
    }
}

// Generic Cached Repository implementing the Cache-Aside pattern
class CachedRepository<K, T>(
    private val primarySource: DataSource<K, T>,
    private val cache: KeyValueStore<K, T>,
    private val defaultTtl: Long = 300
) : Repository<K, T> {

    override fun findById(id: K): T? {
        return cache.retrieve(id)?.also {
            println("REPOSITORY: Cache HIT for ID $id")
        } ?: run {
            println("REPOSITORY: Cache MISS for ID $id")
            primarySource.fetchById(id)?.also {
                cache.store(id, it, defaultTtl)
                println("REPOSITORY: Cache SET for ID $id")
            }
        }
    }

    override fun save(entity: T): T {
        // Assuming the entity has an 'id' property, which is a limitation of this generic approach
        // A real implementation might require a lambda to extract the key.
        val key = (entity as? User)?.id ?: (entity as? Post)?.id as? K
        require(key != null) { "Entity must have a readable 'id' property of type K" }

        val savedEntity = primarySource.persist(entity)
        cache.evict(key)
        println("REPOSITORY: Cache INVALIDATED for ID $key")
        return savedEntity
    }

    override fun deleteById(id: K): Boolean {
        val success = primarySource.removeById(id)
        if (success) {
            cache.evict(id)
            println("REPOSITORY: Cache INVALIDATED for ID $id")
        }
        return success
    }
}

// 4. DEMONSTRATION
fun main() {
    // 1. Instantiate concrete components
    val userCacheProvider: KeyValueStore<UUID, User> = LruCacheProvider(capacity = 2)
    val userDataSource: DataSource<UUID, User> = InMemoryUserDataSource()

    // 2. Inject dependencies into the CachedRepository
    val userRepository: Repository<UUID, User> = CachedRepository(
        primarySource = userDataSource,
        cache = userCacheProvider,
        defaultTtl = 60
    )

    // 3. Populate the primary data source
    val user1 = User(UUID.randomUUID(), "u1@protocol.com", "h1", UserRole.USER, true, Instant.now())
    val user2 = User(UUID.randomUUID(), "u2@protocol.com", "h2", UserRole.USER, true, Instant.now())
    userDataSource.persist(user1)
    userDataSource.persist(user2)

    println("--- First fetch for user 1 (miss) ---")
    userRepository.findById(user1.id)

    println("\n--- Second fetch for user 1 (hit) ---")
    userRepository.findById(user1.id)

    println("\n--- Update user 1 (invalidate) ---")
    val updatedUser1 = user1.copy(email = "u1.updated@protocol.com")
    userRepository.save(updatedUser1)

    println("\n--- Fetch user 1 again (miss, then set) ---")
    val reFetchedUser = userRepository.findById(user1.id)
    println("Fetched updated email: ${reFetchedUser?.email}")
}