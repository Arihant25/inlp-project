package com.example.javalin.extensions

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.javalin.Javalin
import io.javalin.apibuilder.ApiBuilder.*
import io.javalin.http.Context
import io.javalin.http.HttpStatus
import io.javalin.json.JavalinJackson
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

// --- Mock Data ---
object MockData {
    val sampleUserId: UUID = UUID.randomUUID()
    val posts: List<Post> = listOf(
        Post(UUID.randomUUID(), sampleUserId, "First Post", "Content here", PostStatus.PUBLISHED),
        Post(UUID.randomUUID(), sampleUserId, "Second Post", "More content", PostStatus.DRAFT)
    )
}

// --- Middleware as Extension Functions ---

fun Javalin.applyRequestLogging(): Javalin = this.before { ctx ->
    println("IN [${ctx.method()}] ${ctx.path()} - From: ${ctx.ip()}")
}.after { ctx ->
    println("OUT [${ctx.method()}] ${ctx.path()} - Status: ${ctx.status()}")
}

fun Javalin.applyCors(): Javalin = this.before { ctx ->
    ctx.header("Access-Control-Allow-Origin", "*")
    ctx.header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
    if (ctx.method() == "OPTIONS") {
        ctx.status(HttpStatus.NO_CONTENT)
    }
}

object RateLimiter {
    private const val MAX_REQUESTS = 15
    private val ipRequestCounts = ConcurrentHashMap<String, Int>()
    private val window = ConcurrentHashMap<String, Long>()

    fun handle(ctx: Context) {
        val ip = ctx.ip()
        val currentMinute = System.currentTimeMillis() / 60000
        
        if (window.getOrDefault(ip, 0L) != currentMinute) {
            window[ip] = currentMinute
            ipRequestCounts[ip] = 1
        } else {
            ipRequestCounts.compute(ip) { _, count -> (count ?: 0) + 1 }
        }

        if (ipRequestCounts.getOrDefault(ip, 0) > MAX_REQUESTS) {
            ctx.status(HttpStatus.TOO_MANY_REQUESTS).result("Rate limit exceeded.")
        }
    }
}

fun Javalin.applyRateLimiting(path: String = "*"): Javalin = this.before(path, RateLimiter::handle)

fun Javalin.applyResponseTransformation(): Javalin = this.after { ctx ->
    ctx.header("X-Request-ID", ctx.attribute("request-id") ?: "unknown")
    ctx.header("Content-Security-Policy", "default-src 'self'")
}

fun Javalin.applyErrorHandling(): Javalin = this.exception(Exception::class.java) { e, ctx ->
    val errorId = UUID.randomUUID()
    System.err.println("Error $errorId: ${e.message}")
    val (status, message) = when (e) {
        is NumberFormatException -> Pair(HttpStatus.BAD_REQUEST, "Invalid number format in path.")
        else -> Pair(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected server error occurred.")
    }
    ctx.status(status).json(mapOf("error" to message, "errorId" to errorId))
}

// --- Main Application ---
fun main() {
    Javalin.create { config ->
        config.jsonMapper(JavalinJackson(jacksonObjectMapper().apply {
            registerModule(JavaTimeModule())
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        }))
        config.showJavalinBanner = false
        config.requestLogger.http { ctx, ms ->
            // Using built-in logger for another style of logging
            println("Javalin-internal-logger: Request to ${ctx.path()} took ${ms}ms")
        }
    }
    .before { ctx -> // Middleware to add request ID for transformation
        ctx.attribute("request-id", UUID.randomUUID().toString())
    }
    .applyRequestLogging()
    .applyCors()
    .applyRateLimiting("/api/*")
    .applyResponseTransformation()
    .applyErrorHandling()
    .routes {
        path("/api") {
            get("/posts") { ctx ->
                ctx.json(MockData.posts)
            }
            get("/posts/{id}") { ctx ->
                // This will trigger the NumberFormatException handler if id is not a number
                val idAsInt = ctx.pathParam("id").toInt() 
                ctx.result("Fetching post with numeric ID: $idAsInt (demo only)")
            }
        }
    }
    .start(7072)

    println("Server with extension functions started on http://localhost:7072")
}