package com.example.variation3

import io.javalin.Javalin
import io.javalin.http.Context
import io.javalin.http.NotFoundResponse
import java.sql.Timestamp
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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

// --- Data Layer (Singleton Repository) ---
object UserRepository {
    private val userStore = ConcurrentHashMap<UUID, User>()
    val defaultUserId: UUID = UUID.randomUUID()

    init {
        userStore[defaultUserId] = User(
            id = defaultUserId,
            email = "singleton.user@example.com",
            password_hash = "some_hash",
            role = UserRole.USER,
            is_active = true,
            created_at = Timestamp.from(Instant.now())
        )
    }

    fun getUserFromDb(id: UUID): User? {
        println("DATABASE: Accessing user table for ID: $id")
        Thread.sleep(50) // Simulate I/O
        return userStore[id]
    }

    fun saveUserToDb(user: User) {
        println("DATABASE: Saving user to table for ID: ${user.id}")
        userStore[user.id] = user
    }

    fun deleteUserFromDb(id: UUID) {
        println("DATABASE: Deleting user from table for ID: $id")
        userStore.remove(id)
    }
}

// --- Caching Layer (Singleton Manager) ---
object CacheManager {
    private const val USER_CACHE_EXPIRATION_MS = 30_000L // 30 seconds

    private data class CacheEntry<T>(val data: T, val expiryTime: Long)

    private val userCache = ConcurrentHashMap<UUID, CacheEntry<User>>()

    // Implements Cache-Aside pattern
    fun getUser(id: UUID): User? {
        val entry = userCache[id]

        // Check for existence and expiration
        if (entry != null && System.currentTimeMillis() < entry.expiryTime) {
            println("CACHE: HIT for user $id")
            return entry.data
        }

        if (entry != null) {
            println("CACHE: EXPIRED entry for user $id")
        } else {
            println("CACHE: MISS for user $id")
        }

        // On miss or expiration, fetch from DB
        val userFromDb = UserRepository.getUserFromDb(id)
        userFromDb?.let {
            println("CACHE: SET for user $id")
            val newExpiry = System.currentTimeMillis() + USER_CACHE_EXPIRATION_MS
            userCache[id] = CacheEntry(it, newExpiry)
        }
        return userFromDb
    }

    fun invalidateUser(id: UUID) {
        println("CACHE: INVALIDATE for user $id")
        userCache.remove(id)
    }
}

// --- API Handler Layer (Singleton) ---
object UserHandler {
    fun getUserById(ctx: Context) {
        val userId = UUID.fromString(ctx.pathParam("id"))
        val user = CacheManager.getUser(userId) ?: throw NotFoundResponse("User not found")
        ctx.json(user)
    }

    fun updateUser(ctx: Context) {
        val userId = UUID.fromString(ctx.pathParam("id"))
        val newEmail = ctx.queryParam("email") ?: "updated.singleton@example.com"

        val user = UserRepository.getUserFromDb(userId) ?: throw NotFoundResponse("User not found")
        val updatedUser = user.copy(email = newEmail)
        UserRepository.saveUserToDb(updatedUser)

        // Invalidate cache after successful DB write
        CacheManager.invalidateUser(userId)

        ctx.json(updatedUser)
    }

    fun deleteUser(ctx: Context) {
        val userId = UUID.fromString(ctx.pathParam("id"))
        UserRepository.deleteUserFromDb(userId)

        // Invalidate cache after successful DB write
        CacheManager.invalidateUser(userId)
        ctx.status(204)
    }
}

// --- Main Application ---
fun main() {
    Javalin.create { config ->
        config.jsonMapper(io.javalin.plugin.json.JavalinJackson())
    }.apply {
        get("/users/{id}", UserHandler::getUserById)
        put("/users/{id}", UserHandler::updateUser)
        delete("/users/{id}", UserHandler::deleteUser)
    }.start(7003)

    println("Server started at http://localhost:7003")
    println("Try GET http://localhost:7003/users/${UserRepository.defaultUserId}")
}