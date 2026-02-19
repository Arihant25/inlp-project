package com.example.variation2

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// --- Domain Model ---

enum class Role { ADMIN, USER }
enum class Status { DRAFT, PUBLISHED }

@Serializable
data class User(
    val id: String,
    val email: String,
    val password_hash: String,
    val role: Role,
    val is_active: Boolean,
    @Serializable(with = InstantSerializer::class)
    val created_at: Instant
)

@Serializable
data class Post(
    val id: String,
    val user_id: String,
    val title: String,
    val content: String,
    val status: Status
)

// --- Mock Data Source ---

object DataSource {
    private val users = mutableMapOf<UUID, User>()
    private val posts = mutableMapOf<UUID, Post>()

    init {
        val userId = UUID.randomUUID()
        users[userId] = User(userId.toString(), "user@example.com", "hash2", Role.USER, true, Clock.System.now())
        posts[UUID.randomUUID()] = Post(UUID.randomUUID().toString(), userId.toString(), "Hello Ktor", "Functional caching...", Status.PUBLISHED)
    }

    suspend fun findUser(id: UUID): User? {
        delay(50) // Simulate DB latency
        return users[id]
    }

    suspend fun findPost(id: UUID): Post? {
        delay(80) // Simulate DB latency
        return posts[id]
    }
    
    suspend fun deletePost(id: UUID) {
        delay(20)
        posts.remove(id)
    }
}

// --- Caching Layer (Functional Style with background cleanup) ---

object CacheManager {
    private data class CacheItem(val data: Any, val expiresAt: Instant)
    private val cache = ConcurrentHashMap<String, CacheItem>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        scope.launch {
            while (isActive) {
                delay(30.seconds)
                val now = Clock.System.now()
                cache.entries.removeIf { it.value.expiresAt < now }
            }
        }
    }

    fun <T : Any> retrieve(key: String): T? {
        val item = cache[key] ?: return null
        if (item.expiresAt < Clock.System.now()) {
            cache.remove(key)
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return item.data as? T
    }

    fun store(key: String, value: Any, ttl: Duration) {
        cache[key] = CacheItem(value, Clock.System.now().plus(ttl))
    }

    fun invalidate(key: String) {
        cache.remove(key)
    }
}

// --- Functional/Extension-based Logic ---

suspend fun fetchUserWithCache(id: UUID): User? {
    val key = "user-v2:$id"
    // Cache-Aside Pattern
    return CacheManager.retrieve<User>(key) ?: DataSource.findUser(id)?.also {
        CacheManager.store(key, it, 5.minutes)
    }
}

suspend fun fetchPostWithCache(id: UUID): Post? {
    val key = "post-v2:$id"
    return CacheManager.retrieve<Post>(key) ?: DataSource.findPost(id)?.also {
        CacheManager.store(key, it, 2.minutes)
    }
}

suspend fun deletePostAndInvalidate(id: UUID) {
    DataSource.deletePost(id)
    // Invalidation
    CacheManager.invalidate("post-v2:$id")
}

// --- Ktor Application ---

fun main() {
    embeddedServer(Netty, port = 8081, module = Application::variation2Module).start(wait = true)
}

fun Application.variation2Module() {
    install(ContentNegotiation) {
        json()
    }

    routing {
        get("/v2/users/{id}") {
            val userId = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
            if (userId == null) {
                call.respond(io.ktor.http.HttpStatusCode.BadRequest, "Malformed UUID")
                return@get
            }

            val user = fetchUserWithCache(userId)
            user?.let { call.respond(it) } ?: call.respond(io.ktor.http.HttpStatusCode.NotFound)
        }

        get("/v2/posts/{id}") {
            val postId = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
            if (postId == null) {
                call.respond(io.ktor.http.HttpStatusCode.BadRequest, "Malformed UUID")
                return@get
            }

            val post = fetchPostWithCache(postId)
            post?.let { call.respond(it) } ?: call.respond(io.ktor.http.HttpStatusCode.NotFound)
        }
        
        delete("/v2/posts/{id}") {
            val postId = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
            if (postId == null) {
                call.respond(io.ktor.http.HttpStatusCode.BadRequest, "Malformed UUID")
                return@delete
            }
            deletePostAndInvalidate(postId)
            call.respond(io.ktor.http.HttpStatusCode.NoContent)
        }
    }
}

// --- Utils for Serialization ---
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}