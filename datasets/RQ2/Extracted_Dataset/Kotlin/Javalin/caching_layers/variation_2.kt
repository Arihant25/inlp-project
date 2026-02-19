package com.example.variation2

import io.javalin.Javalin
import io.javalin.http.Context
import io.javalin.http.NotFoundResponse
import java.sql.Timestamp
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap

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

// --- Mock Data Source ---
object UserDataSource {
    private val users = ConcurrentHashMap<UUID, User>()
    val adminId: UUID = UUID.randomUUID()

    init {
        users[adminId] = User(
            id = adminId,
            email = "admin@system.com",
            password_hash = "hashed_password_1",
            role = UserRole.ADMIN,
            is_active = true,
            created_at = Timestamp.from(Instant.now())
        )
    }

    fun fetchById(id: UUID): User? {
        println("DATASOURCE: Querying for user $id")
        Thread.sleep(50) // Simulate latency
        return users[id]
    }

    fun save(user: User): User {
        println("DATASOURCE: Saving user ${user.id}")
        users[user.id] = user
        return user
    }

    fun remove(id: UUID) {
        println("DATASOURCE: Removing user $id")
        users.remove(id)
    }
}

// --- Generic LRU Cache Implementation ---
class LruCache<K, V>(
    private val capacity: Int,
    private val loader: (K) -> V?
) {
    private val cache: MutableMap<K, V> =
        object : LinkedHashMap<K, V>(capacity, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean {
                val shouldRemove = size > capacity
                if (shouldRemove) {
                    println("CACHE: LRU eviction for key ${eldest.key}")
                }
                return shouldRemove
            }
        }

    // Cache-Aside pattern is built into this function
    @Synchronized
    fun get(key: K): V? {
        if (cache.containsKey(key)) {
            println("CACHE: HIT for key $key")
            return cache[key]
        }

        println("CACHE: MISS for key $key")
        val value = loader(key)
        if (value != null) {
            println("CACHE: SET for key $key")
            cache[key] = value
        }
        return value
    }

    @Synchronized
    fun invalidate(key: K) {
        println("CACHE: INVALIDATE for key $key")
        cache.remove(key)
    }
}

// --- Application Entry Point ---
fun main() {
    // Instantiate the cache with a loader function pointing to the data source
    val userCache = LruCache(capacity = 100, loader = UserDataSource::fetchById)

    val app = Javalin.create().start(7002)
    println("Server started at http://localhost:7002")
    println("Try GET http://localhost:7002/users/${UserDataSource.adminId}")

    // Functional route definitions
    app.get("/users/{user-id}") { ctx ->
        val userId = ctx.uuidPathParam("user-id")
        val user = userCache.get(userId) ?: throw NotFoundResponse()
        ctx.json(user)
    }

    app.put("/users/{user-id}") { ctx ->
        val userId = ctx.uuidPathParam("user-id")
        val newEmail = ctx.queryParam("email") ?: "default.email@example.com"

        // Invalidation logic
        val currentUser = UserDataSource.fetchById(userId) ?: throw NotFoundResponse()
        val updatedUser = currentUser.copy(email = newEmail)
        UserDataSource.save(updatedUser)
        userCache.invalidate(userId) // Invalidate after DB write

        ctx.json(updatedUser)
    }

    app.delete("/users/{user-id}") { ctx ->
        val userId = ctx.uuidPathParam("user-id")
        UserDataSource.remove(userId)
        userCache.invalidate(userId) // Invalidate after DB write
        ctx.status(204)
    }
}

// Helper extension function for cleaner code
fun Context.uuidPathParam(key: String): UUID = UUID.fromString(this.pathParam(key))