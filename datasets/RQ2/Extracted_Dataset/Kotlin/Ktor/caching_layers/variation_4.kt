package com.example.variation4

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

// --- Generic Cache Implementation ---

interface Cache<K, V> {
    fun get(key: K): V?
    fun set(key: K, value: V)
    fun evict(key: K)
}

class InMemoryLruCache<K, V>(private val capacity: Int, private val ttl: Duration) : Cache<K, V> {
    private data class Entry<V>(val value: V, val expires: Instant)
    private val store = object : LinkedHashMap<K, Entry<V>>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>?) = size > capacity
    }

    override fun get(key: K): V? = synchronized(this) {
        val entry = store[key]
        if (entry != null && Clock.System.now() > entry.expires) {
            store.remove(key)
            return null
        }
        return entry?.value
    }

    override fun set(key: K, value: V) = synchronized(this) {
        store[key] = Entry(value, Clock.System.now().plus(ttl))
    }

    override fun evict(key: K) = synchronized(this) {
        store.remove(key)
    }
}

// --- Repository Pattern (Interface, DB Impl, Cache Decorator) ---

interface UserRepository {
    suspend fun findById(id: UUID): User?
    suspend fun save(user: User): User
    suspend fun delete(id: UUID)
}

// 1. "Database" implementation
class DatabaseUserRepository : UserRepository {
    private val userStorage = mutableMapOf<UUID, User>()

    init {
        val id = UUID.randomUUID()
        userStorage[id] = User(id.toString(), "repo.user@example.com", "hash4", Role.USER, true, Clock.System.now())
    }

    override suspend fun findById(id: UUID): User? = userStorage[id]
    override suspend fun save(user: User): User {
        userStorage[UUID.fromString(user.id)] = user
        return user
    }
    override suspend fun delete(id: UUID) {
        userStorage.remove(id)
    }
}

// 2. Caching Decorator implementation
class CachingUserRepository(
    private val delegate: UserRepository,
    private val cache: Cache<UUID, User>
) : UserRepository {

    // Cache-Aside logic
    override suspend fun findById(id: UUID): User? {
        return cache.get(id) ?: delegate.findById(id)?.also { user ->
            cache.set(id, user)
        }
    }

    // Write-through with invalidation
    override suspend fun save(user: User): User {
        val savedUser = delegate.save(user)
        cache.evict(UUID.fromString(savedUser.id)) // Invalidate on save/update
        return savedUser
    }

    // Invalidation on delete
    override suspend fun delete(id: UUID) {
        delegate.delete(id)
        cache.evict(id)
    }
}

// --- Ktor Application ---

fun main() {
    embeddedServer(Netty, port = 8083, module = Application::variation4Module).start(wait = true)
}

fun Application.variation4Module() {
    install(ContentNegotiation) {
        json()
    }

    // --- Dependency Injection Setup ---
    val userCache = InMemoryLruCache<UUID, User>(capacity = 100, ttl = 5.minutes)
    val dbUserRepository = DatabaseUserRepository()
    val userRepository: UserRepository = CachingUserRepository(dbUserRepository, userCache)
    // The rest of the app only knows about the `UserRepository` interface.

    routing {
        get("/repo/users/{id}") {
            val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (id == null) {
                call.respond(io.ktor.http.HttpStatusCode.BadRequest)
                return@get
            }
            // The route is clean, caching is handled by the repository decorator
            val user = userRepository.findById(id)
            if (user != null) {
                call.respond(user)
            } else {
                call.respond(io.ktor.http.HttpStatusCode.NotFound)
            }
        }
        
        delete("/repo/users/{id}") {
            val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (id == null) {
                call.respond(io.ktor.http.HttpStatusCode.BadRequest)
                return@delete
            }
            userRepository.delete(id)
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