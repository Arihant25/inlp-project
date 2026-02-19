package com.example.caching.v2

import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.cache.Cache
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.caffeine.CaffeineCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import java.sql.Timestamp
import java.time.Instant
import java.util.*
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

// --- Mock Persistence ---
@Component
class PostPersistence {
    private val db = mutableMapOf<UUID, Post>()

    init {
        val samplePostId = UUID.fromString("f47ac10b-58cc-4372-a567-0e02b2c3d479")
        db[samplePostId] = Post(
            id = samplePostId,
            user_id = UUID.randomUUID(),
            title = "First Post",
            content = "This is the content.",
            status = PostStatus.PUBLISHED
        )
    }

    fun loadById(id: UUID): Post? {
        println("--- DB ACCESS: Reading post $id ---")
        Thread.sleep(800) // Simulate I/O delay
        return db[id]
    }

    fun persist(post: Post): Post {
        println("--- DB ACCESS: Writing post ${post.id} ---")
        db[post.id] = post
        return post
    }

    fun remove(id: UUID) {
        println("--- DB ACCESS: Deleting post $id ---")
        db.remove(id)
    }
}

// --- Cache Configuration ---
@Configuration
@EnableCaching
class AppCacheConfig {
    @Bean
    fun cacheManager(): CacheManager {
        val caffeineMgr = CaffeineCacheManager("posts")
        caffeineMgr.setCaffeine(
            Caffeine.newBuilder()
                .maximumSize(200) // LRU
                .expireAfterAccess(30, TimeUnit.MINUTES) // Time-based expiration
        )
        return caffeineMgr
    }
}

// --- Service with Manual Cache-Aside Logic ---
@Service
class PostService(
    private val postPersistence: PostPersistence,
    private val cacheManager: CacheManager
) {
    private val postCache: Cache by lazy {
        cacheManager.getCache("posts") ?: throw IllegalStateException("Cache 'posts' not found")
    }

    // Manual Cache-Aside implementation
    fun findPostById(id: UUID): Post? {
        // 1. Check cache first
        val cachedPost = postCache.get(id, Post::class.java)
        if (cachedPost != null) {
            println("<<< CACHE HIT for post $id >>>")
            return cachedPost
        }

        // 2. On cache miss, fetch from persistence
        println(">>> CACHE MISS for post $id <<<")
        val postFromDb = postPersistence.loadById(id)

        // 3. Populate cache
        if (postFromDb != null) {
            postCache.put(id, postFromDb)
        }
        return postFromDb
    }

    // Manual cache update
    fun updatePostContent(id: UUID, newContent: String): Post? {
        val post = postPersistence.loadById(id) ?: return null
        val updatedPost = post.copy(content = newContent)
        postPersistence.persist(updatedPost)

        // Update the cache with the new version
        postCache.put(id, updatedPost)
        println("<<< CACHE PUT for post $id >>>")
        return updatedPost
    }

    // Manual cache invalidation
    fun deletePost(id: UUID) {
        postPersistence.remove(id)
        postCache.evict(id)
        println("<<< CACHE EVICT for post $id >>>")
    }
}

// --- Controller ---
@RestController
@RequestMapping("/v2/posts")
class PostController(private val postSvc: PostService) {

    @GetMapping("/{id}")
    fun getPost(@PathVariable id: UUID): ResponseEntity<Post> {
        val post = postSvc.findPostById(id)
        return if (post != null) ResponseEntity.ok(post) else ResponseEntity.notFound().build()
    }

    @PatchMapping("/{id}/content")
    fun updateContent(@PathVariable id: UUID, @RequestBody body: Map<String, String>): ResponseEntity<Post> {
        val newContent = body["content"] ?: return ResponseEntity.badRequest().build()
        val updatedPost = postSvc.updatePostContent(id, newContent)
        return if (updatedPost != null) ResponseEntity.ok(updatedPost) else ResponseEntity.notFound().build()
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: UUID): ResponseEntity<Void> {
        postSvc.deletePost(id)
        return ResponseEntity.noContent().build()
    }
}

// --- Application Entry Point ---
@SpringBootApplication
class ExplicitCacheApp

fun main(args: Array<String>) {
    runApplication<ExplicitCacheApp>(*args)
}