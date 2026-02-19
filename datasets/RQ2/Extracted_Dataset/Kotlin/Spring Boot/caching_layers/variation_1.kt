package com.example.caching.v1

import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.CacheConfig
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.CachePut
import org.springframework.cache.annotation.Cacheable
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.caffeine.CaffeineCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// --- Domain Models ---

enum class UserRole { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }

data class User(
    val id: UUID,
    var email: String,
    var password_hash: String,
    var role: UserRole,
    var is_active: Boolean,
    val created_at: Timestamp
)

data class Post(
    val id: UUID,
    val user_id: UUID,
    var title: String,
    var content: String,
    var status: PostStatus
)

// --- Mock Data Layer (Simulates Database) ---

@Repository
class UserRepository {
    private val userStore = ConcurrentHashMap<UUID, User>()

    init {
        val adminId = UUID.randomUUID()
        userStore[adminId] = User(
            id = adminId,
            email = "admin@example.com",
            password_hash = "hashed_password_1",
            role = UserRole.ADMIN,
            is_active = true,
            created_at = Timestamp.from(Instant.now())
        )
    }

    fun findById(id: UUID): User? {
        println("DATABASE HIT: Fetching user with id=$id")
        Thread.sleep(1000) // Simulate latency
        return userStore[id]
    }

    fun save(user: User): User {
        println("DATABASE HIT: Saving user with id=${user.id}")
        userStore[user.id] = user
        return user
    }

    fun deleteById(id: UUID) {
        println("DATABASE HIT: Deleting user with id=$id")
        userStore.remove(id)
    }
}

// --- Caching Configuration ---

@Configuration
@EnableCaching
class CachingConfig {

    companion object {
        const val USERS_CACHE = "users"
    }

    @Bean
    fun cacheManager(): CacheManager {
        val caffeineCacheManager = CaffeineCacheManager(USERS_CACHE)
        caffeineCacheManager.setCaffeine(
            Caffeine.newBuilder()
                .initialCapacity(100)
                .maximumSize(500) // LRU eviction policy based on size
                .expireAfterWrite(10, TimeUnit.MINUTES) // Time-based expiration
                .recordStats()
        )
        return caffeineCacheManager
    }
}

// --- Service Layer (Implements Cache-Aside with Annotations) ---

@Service
@CacheConfig(cacheNames = [CachingConfig.USERS_CACHE])
class UserService(private val userRepository: UserRepository) {

    // Implements Cache-Aside:
    // 1. Looks for key 'id' in 'users' cache.
    // 2. If found, returns the cached User object.
    // 3. If not found, executes the method body (fetches from DB).
    // 4. The return value is then placed in the cache with key 'id'.
    @Cacheable(key = "#id")
    fun getUserById(id: UUID): User? {
        return userRepository.findById(id)
    }

    // Implements Cache Invalidation (on update):
    // Always executes the method body.
    // Updates the 'users' cache with the new return value.
    @CachePut(key = "#user.id")
    fun updateUser(user: User): User {
        // In a real app, you'd likely merge fields instead of replacing.
        return userRepository.save(user)
    }

    // Implements Cache Invalidation (on delete):
    // Removes the entry with key 'id' from the 'users' cache.
    @CacheEvict(key = "#id")
    fun deleteUser(id: UUID) {
        userRepository.deleteById(id)
    }
}

// --- API Layer ---

@RestController
@RequestMapping("/v1/users")
class UserController(private val userService: UserService) {

    @GetMapping("/{id}")
    fun getUser(@PathVariable id: UUID): User? {
        return userService.getUserById(id)
    }

    @PutMapping("/{id}")
    fun updateUser(@PathVariable id: UUID, @RequestBody updatedUser: User): User {
        // Ensure the ID from the path is used
        val userToUpdate = updatedUser.copy(id = id)
        return userService.updateUser(userToUpdate)
    }

    @DeleteMapping("/{id}")
    fun deleteUser(@PathVariable id: UUID) {
        userService.deleteUser(id)
    }
}

// --- Main Application ---

@SpringBootApplication
class ClassicApp

fun main(args: Array<String>) {
    runApplication<ClassicApp>(*args)
}