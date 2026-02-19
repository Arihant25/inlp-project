package com.example.variation1

import io.javalin.Javalin
import io.javalin.apibuilder.ApiBuilder.*
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

// --- Data Layer (Mock Database) ---
interface UserRepository {
    fun findById(id: UUID): User?
    fun update(user: User): User
    fun delete(id: UUID): Boolean
}

class InMemoryUserRepository : UserRepository {
    private val users = ConcurrentHashMap<UUID, User>()

    init {
        val adminId = UUID.randomUUID()
        users[adminId] = User(
            id = adminId,
            email = "admin@system.com",
            password_hash = "hashed_password_1",
            role = UserRole.ADMIN,
            is_active = true,
            created_at = Timestamp.from(Instant.now())
        )
    }

    override fun findById(id: UUID): User? {
        println("DATABASE: Fetching user $id")
        Thread.sleep(50) // Simulate DB latency
        return users[id]
    }

    override fun update(user: User): User {
        println("DATABASE: Updating user ${user.id}")
        Thread.sleep(50)
        users[user.id] = user
        return user
    }

    override fun delete(id: UUID): Boolean {
        println("DATABASE: Deleting user $id")
        Thread.sleep(50)
        return users.remove(id) != null
    }
}

// --- Caching Layer ---
interface CacheService<K, V> {
    fun get(key: K): V?
    fun set(key: K, value: V)
    fun delete(key: K)
}

class TimeBasedCacheService<K, V>(
    private val expirationMillis: Long
) : CacheService<K, V> {

    private val cache = ConcurrentHashMap<K, V>()
    private val timestamps = ConcurrentHashMap<K, Long>()

    override fun get(key: K): V? {
        val expiryTime = timestamps[key]
        if (expiryTime != null && System.currentTimeMillis() > expiryTime) {
            println("CACHE: Expired entry for key $key")
            cache.remove(key)
            timestamps.remove(key)
            return null
        }
        val value = cache[key]
        if (value != null) {
            println("CACHE: HIT for key $key")
        } else {
            println("CACHE: MISS for key $key")
        }
        return value
    }

    override fun set(key: K, value: V) {
        println("CACHE: SET for key $key")
        cache[key] = value
        timestamps[key] = System.currentTimeMillis() + expirationMillis
    }

    override fun delete(key: K) {
        println("CACHE: DELETE for key $key")
        cache.remove(key)
        timestamps.remove(key)
    }
}

// --- Service Layer ---
class UserService(
    private val userRepository: UserRepository,
    private val userCache: CacheService<UUID, User>
) {
    // Cache-Aside Pattern Implementation
    fun findUserById(id: UUID): User? {
        // 1. Try to get from cache
        var user = userCache.get(id)
        if (user == null) {
            // 2. On miss, get from data source
            user = userRepository.findById(id)
            if (user != null) {
                // 3. Put data into cache
                userCache.set(id, user)
            }
        }
        return user
    }

    fun updateUserEmail(id: UUID, newEmail: String): User? {
        val user = userRepository.findById(id) ?: return null
        val updatedUser = user.copy(email = newEmail)
        userRepository.update(updatedUser)
        // Invalidation strategy: Write-through invalidation
        userCache.delete(id)
        return updatedUser
    }

    fun deleteUser(id: UUID): Boolean {
        val success = userRepository.delete(id)
        if (success) {
            // Invalidation strategy
            userCache.delete(id)
        }
        return success
    }
}

// --- Controller/API Layer ---
class UserController(private val userService: UserService) {
    fun getOne(ctx: Context) {
        val userId = UUID.fromString(ctx.pathParam("id"))
        val user = userService.findUserById(userId) ?: throw NotFoundResponse("User not found")
        ctx.json(user)
    }

    fun update(ctx: Context) {
        val userId = UUID.fromString(ctx.pathParam("id"))
        val newEmail = ctx.queryParam("email") ?: "new.email@example.com"
        val updatedUser = userService.updateUserEmail(userId, newEmail) ?: throw NotFoundResponse("User not found")
        ctx.json(updatedUser)
    }

    fun delete(ctx: Context) {
        val userId = UUID.fromString(ctx.pathParam("id"))
        if (userService.deleteUser(userId)) {
            ctx.status(204)
        } else {
            throw NotFoundResponse("User not found")
        }
    }
}

// --- Main Application ---
fun main() {
    // Dependency Injection Setup
    val userRepository = InMemoryUserRepository()
    val userCache = TimeBasedCacheService<UUID, User>(TimeUnit.SECONDS.toMillis(30))
    val userService = UserService(userRepository, userCache)
    val userController = UserController(userService)

    Javalin.create().routes {
        path("users/{id}") {
            get(userController::getOne)
            put(userController::update)
            delete(userController::delete)
        }
    }.start(7001)

    println("Server started at http://localhost:7001")
    println("Try GET http://localhost:7001/users/<some-uuid-from-startup-log>")
    println("Then try PUT http://localhost:7001/users/<uuid>?email=changed@email.com to see invalidation.")
}