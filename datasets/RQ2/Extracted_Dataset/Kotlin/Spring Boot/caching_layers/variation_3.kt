package com.example.caching.v3

import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.CacheEvict
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
import java.util.*
import java.util.concurrent.TimeUnit

// --- Domain Model ---
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

// --- Cache Constants ---
object CacheNames {
    const val USERS_BY_ID = "usersById"
    const val POSTS_BY_USER = "postsByUserId"
}

// --- Configuration-driven Caching Setup ---

// Represents cache settings, typically from application.yml
@ConfigurationProperties(prefix = "caching")
data class CacheProperties(val specs: Map<String, CacheSpec> = emptyMap()) {
    data class CacheSpec(
        val timeout: Long = 600, // seconds
        val maxSize: Long = 1000
    )
}

@Configuration
@EnableCaching
@EnableConfigurationProperties(CacheProperties::class)
class CentralCacheConfig(private val cacheProperties: CacheProperties) {

    @Bean
    fun applicationCacheManager(): CacheManager {
        val manager = CaffeineCacheManager()
        // Programmatically create caches based on external config
        val specs = cacheProperties.specs.ifEmpty { getDefaultCacheSpecs() }
        
        specs.forEach { (name, spec) ->
            val caffeineSpec = Caffeine.newBuilder()
                .expireAfterWrite(spec.timeout, TimeUnit.SECONDS)
                .maximumSize(spec.maxSize) // LRU
            manager.registerCustomCache(name, caffeineSpec.build())
        }
        return manager
    }

    // Fallback if no config is provided in application.yml
    private fun getDefaultCacheSpecs(): Map<String, CacheProperties.CacheSpec> {
        return mapOf(
            CacheNames.USERS_BY_ID to CacheProperties.CacheSpec(timeout = 300, maxSize = 500),
            CacheNames.POSTS_BY_USER to CacheProperties.CacheSpec(timeout = 60, maxSize = 2000)
        )
    }
}

// --- Mock Data Repositories ---
@Repository
class MockUserRepo {
    private val users = mutableMapOf<UUID, User>()
    init {
        val userId = UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11")
        users[userId] = User(userId, "config.user@example.com", "hash", UserRole.USER, true, Timestamp.from(Instant.now()))
    }
    fun findById(id: UUID): User? {
        println("--- MOCK DB: Querying for user $id ---")
        Thread.sleep(500)
        return users[id]
    }
    fun delete(id: UUID) {
        println("--- MOCK DB: Deleting user $id ---")
        users.remove(id)
    }
}

@Repository
class MockPostRepo {
    private val posts = mutableListOf<Post>()
    init {
        val userId = UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11")
        posts.add(Post(UUID.randomUUID(), userId, "Post 1 by Config User", "Content 1", PostStatus.PUBLISHED))
        posts.add(Post(UUID.randomUUID(), userId, "Post 2 by Config User", "Content 2", PostStatus.DRAFT))
    }
    fun findByUserId(userId: UUID): List<Post> {
        println("--- MOCK DB: Querying posts for user $userId ---")
        Thread.sleep(1200) // Slower query
        return posts.filter { it.user_id == userId }
    }
}

// --- Service Layer (Clean, relies on configured annotations) ---
@Service
class UserProfileService(
    private val userRepo: MockUserRepo,
    private val postRepo: MockPostRepo
) {
    @Cacheable(cacheNames = [CacheNames.USERS_BY_ID], key = "#id")
    fun findUser(id: UUID): User? {
        return userRepo.findById(id)
    }

    @Cacheable(cacheNames = [CacheNames.POSTS_BY_USER], key = "#userId")
    fun findPostsForUser(userId: UUID): List<Post> {
        return postRepo.findByUserId(userId)
    }

    @CacheEvict(cacheNames = [CacheNames.USERS_BY_ID], key = "#id")
    fun removeUser(id: UUID) {
        // Also invalidate related caches
        // This is a more complex invalidation strategy
        // For simplicity, we are not invalidating POSTS_BY_USER here, but in a real app you would.
        userRepo.delete(id)
    }
}

// --- API Layer ---
@RestController
@RequestMapping("/v3/profiles")
class UserProfileController(private val service: UserProfileService) {
    @GetMapping("/{id}")
    fun getUserProfile(@PathVariable id: UUID): User? = service.findUser(id)

    @GetMapping("/{id}/posts")
    fun getUserPosts(@PathVariable id: UUID): List<Post> = service.findPostsForUser(id)

    @DeleteMapping("/{id}")
    fun deleteUserProfile(@PathVariable id: UUID) = service.removeUser(id)
}

// --- Application ---
@SpringBootApplication
class ConfigDrivenApp

fun main(args: Array<String>) {
    runApplication<ConfigDrivenApp>(*args)
    /*
    To use external configuration, add to application.yml:
    caching:
      specs:
        usersById:
          timeout: 300 # 5 minutes
          maxSize: 500
        postsByUserId:
          timeout: 60 # 1 minute
          maxSize: 2000
    */
}