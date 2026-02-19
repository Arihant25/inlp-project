package com.example.variation1

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

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

// --- Mock Database ---

object MockDatabase {
    private val users = mutableMapOf<UUID, User>()
    private val posts = mutableMapOf<UUID, Post>()

    init {
        val adminId = UUID.randomUUID()
        users[adminId] = User(adminId.toString(), "admin@example.com", "hash1", Role.ADMIN, true, Clock.System.now())
        posts[UUID.randomUUID()] = Post(UUID.randomUUID().toString(), adminId.toString(), "First Post", "Content here", Status.PUBLISHED)
    }

    fun findUserById(id: UUID): User? = users[id]
    fun findPostById(id: UUID): Post? = posts[id]
    fun saveUser(user: User) { users[UUID.fromString(user.id)] = user }
    fun deleteUser(id: UUID) { users.remove(id) }
}

// --- Caching Layer (LRU + Time Expiration) ---

class LruCache<K, V>(
    private val capacity: Int,
    private val timeToLive: Duration
) {
    private val cache = object : LinkedHashMap<K, CacheEntry<V>>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, CacheEntry<V>>?): Boolean {
            return size > capacity
        }
    }

    private data class CacheEntry<T>(val value: T, val expiryTime: Instant)

    @Synchronized
    fun get(key: K): V? {
        val entry = cache[key] ?: return null
        if (Clock.System.now() > entry.expiryTime) {
            cache.remove(key)
            return null
        }
        return entry.value
    }

    @Synchronized
    fun set(key: K, value: V) {
        val expiryTime = Clock.System.now().plus(timeToLive)
        cache[key] = CacheEntry(value, expiryTime)
    }

    @Synchronized
    fun delete(key: K) {
        cache.remove(key)
    }
}

// --- Service Layer ---

class UserService(
    private val db: MockDatabase,
    private val cache: LruCache<String, User>
) {
    fun getUser(id: UUID): User? {
        val cacheKey = "user:$id"
        // Cache-Aside: 1. Look in cache
        val cachedUser = cache.get(cacheKey)
        if (cachedUser != null) {
            return cachedUser
        }

        // 2. On miss, go to data source
        val userFromDb = db.findUserById(id)
        if (userFromDb != null) {
            // 3. Store in cache
            cache.set(cacheKey, userFromDb)
        }
        return userFromDb
    }

    fun updateUser(user: User) {
        db.saveUser(user)
        // Invalidation
        cache.delete("user:${user.id}")
    }
}

class PostService(
    private val db: MockDatabase,
    private val cache: LruCache<String, Post>
) {
    fun getPost(id: UUID): Post? {
        val cacheKey = "post:$id"
        return cache.get(cacheKey) ?: db.findPostById(id)?.also { cache.set(cacheKey, it) }
    }
}


// --- Ktor Application ---

fun main() {
    embeddedServer(Netty, port = 8080, module = Application::variation1Module).start(wait = true)
}

fun Application.variation1Module() {
    install(ContentNegotiation) {
        json()
    }

    // Initialize dependencies
    val userCache = LruCache<String, User>(capacity = 100, timeToLive = 5.minutes)
    val postCache = LruCache<String, Post>(capacity = 200, timeToLive = 10.minutes)
    val userService = UserService(MockDatabase, userCache)
    val postService = PostService(MockDatabase, postCache)

    routing {
        get("/users/{id}") {
            val id = call.parameters["id"]?.let { UUID.fromString(it) }
            if (id == null) {
                call.respond(io.ktor.http.HttpStatusCode.BadRequest, "Invalid ID")
                return@get
            }
            val user = userService.getUser(id)
            if (user != null) {
                call.respond(user)
            } else {
                call.respond(io.ktor.http.HttpStatusCode.NotFound)
            }
        }

        get("/posts/{id}") {
            val id = call.parameters["id"]?.let { UUID.fromString(it) }
            if (id == null) {
                call.respond(io.ktor.http.HttpStatusCode.BadRequest, "Invalid ID")
                return@get
            }
            val post = postService.getPost(id)
            if (post != null) {
                call.respond(post)
            } else {
                call.respond(io.ktor.http.HttpStatusCode.NotFound)
            }
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