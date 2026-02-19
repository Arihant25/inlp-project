package com.example.javalin.oop

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.javalin.Javalin
import io.javalin.http.Context
import io.javalin.http.Handler
import io.javalin.http.HttpStatus
import io.javalin.json.JavalinJackson
import org.slf4j.LoggerFactory
import java.sql.Timestamp
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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

// --- Middleware Classes (OOP Style) ---

class LoggingMiddleware {
    private val logger = LoggerFactory.getLogger(LoggingMiddleware::class.java)

    fun before(): Handler = Handler { ctx ->
        ctx.attribute("startTime", System.nanoTime())
        logger.info("Request received: ${ctx.method()} ${ctx.path()} from ${ctx.ip()}")
    }

    fun after(): Handler = Handler { ctx ->
        val startTime = ctx.attribute<Long>("startTime")!!
        val durationMs = (System.nanoTime() - startTime) / 1_000_000.0
        logger.info("Request completed: ${ctx.method()} ${ctx.path()} with status ${ctx.status()} in %.2f ms".format(durationMs))
    }
}

class CorsMiddleware(private val allowedOrigins: Set<String> = setOf("*")) {
    fun handle(): Handler = Handler { ctx ->
        val origin = ctx.header("Origin")
        if (allowedOrigins.contains("*") || (origin != null && allowedOrigins.contains(origin))) {
            ctx.header("Access-Control-Allow-Origin", origin ?: "*")
        }
        ctx.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
        ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Request-ID")
        if (ctx.method() == "OPTIONS") {
            ctx.status(HttpStatus.OK)
        }
    }
}

class RateLimitingMiddleware(private val requestsPerMinute: Int) {
    private data class ClientRequestInfo(val lastRequestMinute: Long, val count: AtomicInteger)
    private val clientRequests = ConcurrentHashMap<String, ClientRequestInfo>()

    fun check(): Handler = Handler { ctx ->
        val clientIp = ctx.ip()
        val currentMinute = System.currentTimeMillis() / 60000
        
        val requestInfo = clientRequests.compute(clientIp) { _, info ->
            if (info == null || info.lastRequestMinute != currentMinute) {
                ClientRequestInfo(currentMinute, AtomicInteger(1))
            } else {
                info.apply { count.incrementAndGet() }
            }
        }!!

        if (requestInfo.count.get() > requestsPerMinute) {
            ctx.status(HttpStatus.TOO_MANY_REQUESTS)
               .json(mapOf("error" to "Too many requests. Please try again later."))
               .header("Retry-After", "60")
            // In a real app, you'd throw an exception here to halt execution.
        }
    }
}

class ResponseTransformationMiddleware {
    fun addCustomHeaders(): Handler = Handler { ctx ->
        ctx.header("X-App-Version", "1.0.0")
    }
}

class GlobalErrorHandler {
    private val logger = LoggerFactory.getLogger(GlobalErrorHandler::class.java)

    fun handle(e: Exception, ctx: Context) {
        logger.error("An unhandled exception occurred for ${ctx.path()}", e)
        when (e) {
            is NoSuchElementException -> {
                ctx.status(HttpStatus.NOT_FOUND)
                   .json(mapOf("error" to "Resource not found", "message" to e.message))
            }
            else -> {
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR)
                   .json(mapOf("error" to "An unexpected error occurred", "type" to e.javaClass.simpleName))
            }
        }
    }
}

// --- Controller ---
object PostController {
    private val mockUserId = UUID.randomUUID()
    private val posts = mapOf(
        UUID.fromString("00000000-0000-0000-0000-000000000001") to Post(UUID.fromString("00000000-0000-0000-0000-000000000001"), mockUserId, "Post 1", "Content 1", PostStatus.PUBLISHED),
        UUID.fromString("00000000-0000-0000-0000-000000000002") to Post(UUID.fromString("00000000-0000-0000-0000-000000000002"), mockUserId, "Post 2", "Content 2", PostStatus.DRAFT)
    )

    val getAll = Handler { ctx -> ctx.json(posts.values) }
    val getOne = Handler { ctx ->
        val postId = UUID.fromString(ctx.pathParam("id"))
        val post = posts[postId] ?: throw NoSuchElementException("Post with ID $postId not found.")
        ctx.json(post)
    }
}

// --- Main Application ---
fun main() {
    // Instantiate middleware components
    val loggingMiddleware = LoggingMiddleware()
    val corsMiddleware = CorsMiddleware()
    val rateLimitingMiddleware = RateLimitingMiddleware(requestsPerMinute = 20)
    val responseTransformationMiddleware = ResponseTransformationMiddleware()
    val globalErrorHandler = GlobalErrorHandler()

    val app = Javalin.create { config ->
        config.jsonMapper(JavalinJackson(jacksonObjectMapper().apply {
            registerModule(JavaTimeModule())
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        }))
        config.showJavalinBanner = false
    }

    // Register middleware
    app.before(loggingMiddleware.before())
    app.before(corsMiddleware.handle())
    app.before("/api/*", rateLimitingMiddleware.check())
    
    app.after(loggingMiddleware.after())
    app.after(responseTransformationMiddleware.addCustomHeaders())

    // Register error handler
    app.exception(Exception::class.java, globalErrorHandler::handle)

    // Register routes
    app.routes {
        path("/api/posts") {
            get(PostController.getAll)
            get("/{id}", PostController.getOne)
        }
    }

    app.start(7071)
    println("Server started on http://localhost:7071")
}