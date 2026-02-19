package com.example.variation4

import io.javalin.Javalin
import io.javalin.http.Context
import io.javalin.http.NotFoundResponse
import java.sql.Timestamp
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.collections.LinkedHashMap

// --- Domain Model ---
enum class UserRole { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }

data class User(
    val id: UUID,
    val email: String,
    val password_hash: String,
    val role: UserRole,
    val is_active: Boolean,
    val created_at: Timestamp
)

data class Post(
    val id: UUID,
    val user_id: UUID,
    val title: String,
    val content: String,
    val status: PostStatus
)

// --- Mock Database ---
object MockDatabase {
    private val users = ConcurrentHashMap<UUID, User>()
    val sampleUserId: UUID = UUID.randomUUID()

    init {
        users[sampleUserId] = User(
            id = sampleUserId,
            email = "fluent.user@example.com",
            password_hash = "abc-123",
            role = UserRole.ADMIN,
            is_active = true,
            created_at = Timestamp.from(Instant.now())
        )
    }

    fun findUser(id: UUID): User? {
        println("DATABASE: Fetching user $id")
        Thread.sleep(50) // Simulate latency
        return users[id]
    }

    fun saveUser(user: User) {
        println("DATABASE: Saving user ${user.id}")
        users[user.id] = user
    }

    fun deleteUser(id: UUID) {
        println("DATABASE: Deleting user $id")
        users.remove(id)
    }
}

// --- Advanced Cache Implementation (simulating a library like Caffeine) ---
interface Cache<K, V> {
    fun get(key: K, loader: (K) -> V?): V?
    fun getIfPresent(key: K): V?
    fun invalidate(key: K)
    fun invalidateAll()
}

class CacheBuilder<K, V> {
    private var maximumSize: Long = 1000
    private var expireAfterWriteMillis: Long? = null

    fun maximumSize(size: Long): CacheBuilder<K, V> {
        this.maximumSize = size
        return this
    }

    fun expireAfterWrite(duration: Long, unit: TimeUnit): CacheBuilder<K, V> {
        this.expireAfterWriteMillis = unit.toMillis(duration)
        return this
    }

    fun build(): Cache<K, V> {
        return LocalCache(maximumSize, expireAfterWriteMillis)
    }
}

private class LocalCache<K, V>(
    private val maxSize: Long,
    private val expireAfterWriteMillis: Long?
) : Cache<K, V> {

    private data class CacheValue<V>(val value: V, val writeTime: Long)

    private val map: MutableMap<K, CacheValue<V>> =
        object : LinkedHashMap<K, CacheValue<V>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.Entry<K, CacheValue<V>>): Boolean {
                val shouldRemove = size > maxSize
                if (shouldRemove) println("CACHE: Evicting LRU key ${eldest.key} due to size limit")
                return shouldRemove
            }
        }

    @Synchronized
    override fun get(key: K, loader: (K) -> V?): V? {
        val present = getIfPresent(key)
        if (present != null) {
            return present
        }

        println("CACHE: MISS for key $key. Loading from source.")
        val newValue = loader(key)
        if (newValue != null) {
            put(key, newValue)
        }
        return newValue
    }

    @Synchronized
    override fun getIfPresent(key: K): V? {
        val entry = map[key] ?: return null

        if (expireAfterWriteMillis != null && (System.currentTimeMillis() - entry.writeTime) > expireAfterWriteMillis) {
            println("CACHE: Key $key has expired. Removing.")
            map.remove(key)
            return null
        }
        println("CACHE: HIT for key $key")
        return entry.value
    }

    @Synchronized
    private fun put(key: K, value: V) {
        println("CACHE: SET for key $key")
        map[key] = CacheValue(value, System.currentTimeMillis())
    }

    @Synchronized
    override fun invalidate(key: K) {
        println("CACHE: INVALIDATE for key $key")
        map.remove(key)
    }

    @Synchronized
    override fun invalidateAll() {
        map.clear()
    }
}

// --- Main Application ---
fun main() {
    // Build a cache instance with a fluent API
    val userCache: Cache<UUID, User> = CacheBuilder<UUID, User>()
        .maximumSize(100)
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .build()

    val app = Javalin.create().start(7004)
    println("Server started at http://localhost:7004")
    println("Try GET http://localhost:7004/users/${MockDatabase.sampleUserId}")

    // --- API Routes ---
    app.get("/users/{id}") { ctx ->
        val userId = UUID.fromString(ctx.pathParam("id"))
        // Cache-aside pattern is elegantly handled by the get() method
        val user = userCache.get(userId) { key ->
            MockDatabase.findUser(key)
        } ?: throw NotFoundResponse("User not found")
        ctx.json(user)
    }

    app.put("/users/{id}") { ctx ->
        val userId = UUID.fromString(ctx.pathParam("id"))
        val newEmail = ctx.queryParam("email") ?: "new.fluent.user@example.com"

        val user = MockDatabase.findUser(userId) ?: throw NotFoundResponse("User not found")
        val updatedUser = user.copy(email = newEmail)
        MockDatabase.saveUser(updatedUser)

        // Invalidation is an explicit call
        userCache.invalidate(userId)
        ctx.json(updatedUser)
    }

    app.delete("/users/{id}") { ctx ->
        val userId = UUID.fromString(ctx.pathParam("id"))
        MockDatabase.deleteUser(userId)
        userCache.invalidate(userId)
        ctx.status(204)
    }
}