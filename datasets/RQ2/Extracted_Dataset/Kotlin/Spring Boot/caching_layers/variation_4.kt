package com.example.caching.v4

import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.cache.Cache
import org.springframework.cache.CacheManager
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

// --- Domain ---
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

// --- Coroutine-aware Cache Wrapper ---
class CoroutineCache(private val underlyingCache: Cache) {
    
    // Implements cache-aside for suspend functions
    suspend fun <T : Any> get(key: Any, type: Class<T>, fallback: suspend () -> T?): T? {
        val cachedValue = underlyingCache.get(key, type)
        if (cachedValue != null) {
            println("ASYNC CACHE HIT: key=$key")
            return cachedValue
        }

        println("ASYNC CACHE MISS: key=$key")
        val result = fallback()
        if (result != null) {
            underlyingCache.put(key, result)
        }
        return result
    }

    fun evict(key: Any) {
        underlyingCache.evict(key)
    }
}

// --- Reactive-style Mock Repository ---
@Repository
class UserAsyncRepository {
    private val userStore = mutableMapOf<UUID, User>()

    init {
        val userId = UUID.fromString("b7e6a3e1-872f-4a3d-9b8f-3d2b7a1d8e0a")
        userStore[userId] = User(userId, "async.user@example.com", "async_hash", UserRole.USER, true, Timestamp.from(Instant.now()))
    }

    suspend fun findById(id: UUID): User? {
        println("DATASTORE: Simulating non-blocking fetch for user $id")
        delay(750) // Simulate non-blocking I/O
        return userStore[id]
    }

    suspend fun deleteById(id: UUID) {
        println("DATASTORE: Simulating non-blocking delete for user $id")
        delay(200)
        userStore.remove(id)
    }
}

// --- Standard Cache Config ---
@Configuration
@EnableCaching
class AsyncCacheConfiguration {
    @Bean
    fun cacheManager(): CacheManager {
        val caffeineManager = CaffeineCacheManager("async_users")
        caffeineManager.setCaffeine(
            Caffeine.newBuilder()
                .maximumSize(100) // LRU
                .expireAfterWrite(5, TimeUnit.MINUTES) // Time-based expiration
        )
        return caffeineManager
    }
}

// --- Service Layer using Coroutine Cache Wrapper ---
@Service
class UserAccountService(
    private val userAsyncRepository: UserAsyncRepository,
    cacheManager: CacheManager
) {
    private val userCache: CoroutineCache

    init {
        val nativeCache = cacheManager.getCache("async_users") ?: throw IllegalStateException("Cache not found")
        userCache = CoroutineCache(nativeCache)
    }

    suspend fun retrieveUser(id: UUID): User? {
        return userCache.get(id, User::class.java) {
            // This fallback lambda is only executed on cache miss
            userAsyncRepository.findById(id)
        }
    }

    suspend fun removeUserAccount(id: UUID) {
        // Perform operations concurrently
        coroutineScope {
            launch { userAsyncRepository.deleteById(id) }
            launch { userCache.evict(id) }
        }
    }
}

// --- Controller with Suspend Endpoints ---
@RestController
@RequestMapping("/v4/accounts")
class UserAccountController(private val userAccountService: UserAccountService) {

    @GetMapping("/{id}")
    suspend fun getAccount(@PathVariable id: UUID): User? {
        return userAccountService.retrieveUser(id)
    }

    @DeleteMapping("/{id}")
    suspend fun deleteAccount(@PathVariable id: UUID) {
        userAccountService.removeUserAccount(id)
    }
}

// --- Application Entry Point ---
@SpringBootApplication
class ReactiveStyleApp

fun main(args: Array<String>) {
    runApplication<ReactiveStyleApp>(*args)
}