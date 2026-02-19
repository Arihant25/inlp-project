package com.example.variation3

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
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

// --- Mock Data Store ---

object DataStore {
    private val users = mutableMapOf<UUID, User>()
    private val posts = mutableMapOf<UUID, Post>()

    init {
        val userId = UUID.randomUUID()
        users[userId] = User(userId.toString(), "plugin-user@example.com", "hash3", Role.USER, false, Clock.System.now())
        posts[UUID.randomUUID()] = Post(UUID.randomUUID().toString(), userId.toString(), "Ktor Plugins", "Are powerful", Status.DRAFT)
    }

    fun getUser(id: UUID): User? = users[id]
    fun getPost(id: UUID): Post? = posts[id]
    fun saveUser(user: User) { users[UUID.fromString(user.id)] = user }
}

// --- Caching Layer (Ktor Plugin) ---

class AppCache(private val config: Caching.Configuration) {
    private val cache = object : LinkedHashMap<String, CacheValue>(config.cacheSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheValue>?): Boolean {
            return size > config.cacheSize
        }
    }
    private data class CacheValue(val data: Any, val expiresAt: Instant)

    @Synchronized
    fun <T: Any> get(key: String): T? {
        val value = cache[key] ?: return null
        if (Clock.System.now() > value.expiresAt) {
            cache.remove(key)
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return value.data as T
    }

    @Synchronized
    fun set(key: String, data: Any, ttl: Duration) {
        cache[key] = CacheValue(data, Clock.System.now().plus(ttl))
    }

    @Synchronized
    fun invalidate(key: String) {
        cache.remove(key)
    }
}

class Caching(val cache: AppCache) {
    class Configuration {
        var cacheSize: Int = 256
    }

    companion object Plugin : BaseApplicationPlugin<Application, Configuration, Caching> {
        override val key: AttributeKey<Caching> = AttributeKey("Caching")

        override fun install(pipeline: Application, configure: Configuration.() -> Unit): Caching {
            val configuration = Configuration().apply(configure)
            val appCache = AppCache(configuration)
            return Caching(appCache)
        }
    }
}

// Extension property to easily access the cache from a call
val ApplicationCall.cache: AppCache
    get() = application.plugin(Caching).cache

// --- Ktor Application ---

fun main() {
    embeddedServer(Netty, port = 8082, module = Application::variation3Module).start(wait = true)
}

fun Application.variation3Module() {
    install(ContentNegotiation) {
        json()
    }

    install(Caching) {
        cacheSize = 500 // Configure the plugin
    }

    routing {
        route("/api") {
            get("/users/{id}") {
                val id = UUID.fromString(call.parameters["id"])
                val cacheKey = "user:$id"

                // Cache-Aside Pattern implemented directly in the route handler
                var user: User? = call.cache.get(cacheKey)

                if (user == null) {
                    user = DataStore.getUser(id)
                    if (user != null) {
                        call.cache.set(cacheKey, user, 5.minutes)
                    }
                }

                if (user != null) {
                    call.respond(user)
                } else {
                    call.respond(io.ktor.http.HttpStatusCode.NotFound)
                }
            }

            get("/posts/{id}") {
                val id = UUID.fromString(call.parameters["id"])
                val cacheKey = "post:$id"

                val post = call.cache.get<Post>(cacheKey) ?: DataStore.getPost(id)?.also {
                    call.cache.set(cacheKey, it, 10.minutes)
                }

                post?.let { call.respond(it) } ?: call.respond(io.ktor.http.HttpStatusCode.NotFound)
            }
            
            put("/users") {
                // Assume user is received in the body and deserialized
                val updatedUser = User(UUID.randomUUID().toString(), "updated@example.com", "newhash", Role.USER, true, Clock.System.now())
                DataStore.saveUser(updatedUser)
                
                // Cache Invalidation
                call.cache.invalidate("user:${updatedUser.id}")
                
                call.respond(io.ktor.http.HttpStatusCode.OK, updatedUser)
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