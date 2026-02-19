package com.example.javalin.modular

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.javalin.Javalin
import io.javalin.http.Context
import io.javalin.http.HttpStatus
import io.javalin.json.JavalinJackson
import org.slf4j.LoggerFactory
import java.sql.Timestamp
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// --- Domain Schema ---
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

// --- Modular/Plugin-based Middleware ---

interface JavalinPlugin {
    fun apply(app: Javalin)
}

object LoggingPlugin : JavalinPlugin {
    private val log = LoggerFactory.getLogger("RequestLifecycle")
    override fun apply(app: Javalin) {
        app.before { ctx ->
            log.info("-> ${ctx.method()} ${ctx.fullUrl()} from ${ctx.ip()}")
        }
        app.after { ctx ->
            log.info("<- ${ctx.method()} ${ctx.path()} responded with ${ctx.status()}")
        }
    }
}

object CorsPlugin : JavalinPlugin {
    override fun apply(app: Javalin) {
        app.before { ctx ->
            ctx.header("Access-Control-Allow-Origin", "https://example.com")
            ctx.header("Access-Control-Allow-Credentials", "true")
        }
        app.options("/*") { ctx ->
            ctx.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE")
            ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization")
            ctx.status(HttpStatus.OK)
        }
    }
}

class RateLimiterPlugin(private val maxRequests: Int, private val perSeconds: Int) : JavalinPlugin {
    private val requests = ConcurrentHashMap<String, MutableList<Long>>()
    override fun apply(app: Javalin) {
        app.before("/api/*") { ctx ->
            val now = System.currentTimeMillis()
            val clientRequests = requests.computeIfAbsent(ctx.ip()) { mutableListOf() }
            clientRequests.removeAll { it < now - perSeconds * 1000 }
            clientRequests.add(now)
            if (clientRequests.size > maxRequests) {
                ctx.status(HttpStatus.TOO_MANY_REQUESTS).result("Rate limit exceeded")
            }
        }
    }
}

object ResponseTransformPlugin : JavalinPlugin {
    override fun apply(app: Javalin) {
        app.after { ctx ->
            if (ctx.res().getHeader("Content-Type")?.contains("application/json") == true) {
                // Example: wrap all JSON responses in a "data" object
                // This is a more intrusive transformation, shown for demonstration
                val originalBody = ctx.resultString()
                if (originalBody != null && !originalBody.contains("\"error\"")) {
                    ctx.json(mapOf("data" to jacksonObjectMapper().readTree(originalBody)))
                }
            }
            ctx.header("X-API-Standard", "v1.2")
        }
    }
}

object ErrorHandlingPlugin : JavalinPlugin {
    private val log = LoggerFactory.getLogger("ErrorHandling")
    data class ApiError(val error: String, val details: String?)
    override fun apply(app: Javalin) {
        app.exception(Exception::class.java) { e, ctx ->
            log.error("Caught exception for request ${ctx.path()}", e)
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR)
               .json(ApiError("An internal error occurred", e.javaClass.canonicalName))
        }
        app.exception(IllegalStateException::class.java) { e, ctx ->
            log.warn("Caught illegal state exception: ${e.message}")
            ctx.status(HttpStatus.CONFLICT)
               .json(ApiError("Request resulted in a conflict", e.message))
        }
    }
}

// --- Centralized Middleware Configuration ---
object MiddlewareRegistry {
    fun registerAll(app: Javalin) {
        val plugins = listOf(
            LoggingPlugin,
            CorsPlugin,
            RateLimiterPlugin(maxRequests = 5, perSeconds = 10),
            ErrorHandlingPlugin,
            ResponseTransformPlugin // Apply last to wrap final response
        )
        plugins.forEach { it.apply(app) }
    }
}

// --- Controller ---
object PostApi {
    private val mockUserId = UUID.randomUUID()
    private val posts = listOf(
        Post(UUID.randomUUID(), mockUserId, "Modular Design", "...", PostStatus.PUBLISHED)
    )
    fun getAll(ctx: Context) = ctx.json(posts)
    fun create(ctx: Context) {
        throw IllegalStateException("Cannot create post at this time, system is read-only.")
    }
}

// --- Main Application ---
fun main() {
    val app = Javalin.create { config ->
        config.jsonMapper(JavalinJackson(jacksonObjectMapper().apply {
            registerModule(JavaTimeModule())
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        }))
        config.showJavalinBanner = false
    }

    MiddlewareRegistry.registerAll(app)

    app.routes {
        path("/api/posts") {
            get(PostApi::getAll)
            post(PostApi::create)
        }
    }

    app.start(7073)
    println("Modular server started on http://localhost:7073")
}