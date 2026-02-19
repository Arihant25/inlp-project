package com.example.javalin.functional

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.javalin.Javalin
import io.javalin.apibuilder.ApiBuilder.*
import io.javalin.http.Context
import io.javalin.http.HttpStatus
import io.javalin.json.JavalinJackson
import java.sql.Timestamp
import java.time.Instant
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

data class ErrorResponse(val message: String, val details: Map<String, String>? = null)

// --- Mock Data ---
val mockUserId: UUID = UUID.randomUUID()
val mockPosts = listOf(
    Post(UUID.randomUUID(), mockUserId, "Javalin is Great", "Content of the first post.", PostStatus.PUBLISHED),
    Post(UUID.randomUUID(), mockUserId, "Kotlin Rocks", "Content of the second post.", PostStatus.PUBLISHED)
)

// --- Rate Limiter State ---
const val MAX_REQUESTS_PER_MINUTE = 10
val rateLimitTracker = ConcurrentHashMap<String, Pair<Long, Int>>()

// --- Middleware Implementations (Functional) ---

fun logRequests(ctx: Context) {
    println("[Request Logger] --> ${ctx.method()} ${ctx.path()} from IP: ${ctx.ip()}")
}

fun handleCors(ctx: Context) {
    ctx.header("Access-Control-Allow-Origin", "*")
    ctx.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
    ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization")
    if (ctx.method() == "OPTIONS") {
        ctx.status(HttpStatus.NO_CONTENT)
    }
}

fun rateLimit(ctx: Context) {
    val clientIp = ctx.ip()
    val currentTime = System.currentTimeMillis()
    val windowStart = currentTime / 60000 // 1-minute window

    val (lastWindow, count) = rateLimitTracker.getOrDefault(clientIp, Pair(0L, 0))

    if (lastWindow == windowStart) {
        if (count >= MAX_REQUESTS_PER_MINUTE) {
            ctx.status(HttpStatus.TOO_MANY_REQUESTS).json(ErrorResponse("Rate limit exceeded"))
            // Note: In a real app, you might want to stop further processing.
            // Javalin's `before` handlers don't stop execution by default.
            // A common pattern is to throw a specific exception here and handle it.
            return
        }
        rateLimitTracker[clientIp] = Pair(windowStart, count + 1)
    } else {
        rateLimitTracker[clientIp] = Pair(windowStart, 1)
    }
}

fun transformResponse(ctx: Context) {
    ctx.header("X-Response-Time", "${System.currentTimeMillis() - ctx.attribute<Long>("request-start-time")!!}ms")
    ctx.header("X-Powered-By", "Javalin-Functional-API")
}

fun handleErrors(e: Exception, ctx: Context) {
    println("[Error Handler] Caught exception: ${e.message}")
    val error = when (e) {
        is IllegalArgumentException -> ErrorResponse("Bad Request", mapOf("reason" to (e.message ?: "Invalid input")))
        else -> ErrorResponse("Internal Server Error", mapOf("type" to e.javaClass.simpleName))
    }
    val status = when (e) {
        is IllegalArgumentException -> HttpStatus.BAD_REQUEST
        else -> HttpStatus.INTERNAL_SERVER_ERROR
    }
    ctx.status(status).json(error)
}

// --- Main Application ---
fun main() {
    val app = Javalin.create { config ->
        config.jsonMapper(JavalinJackson(jacksonObjectMapper().apply {
            registerModule(JavaTimeModule())
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        }))
        config.showJavalinBanner = false
    }.apply {
        // --- Middleware Registration ---

        // 1. Request Logging & CORS (applied to all requests)
        before {
            it.attribute("request-start-time", System.currentTimeMillis())
            logRequests(it)
            handleCors(it)
        }

        // 2. Rate Limiting (applied to API routes)
        before("/api/*") {
            rateLimit(it)
        }

        // 3. Response Transformation (applied after request is handled)
        after {
            transformResponse(it)
        }

        // 4. Error Handling
        exception(Exception::class.java, ::handleErrors)

        // --- API Routes ---
        routes {
            path("/api/posts") {
                get { ctx -> ctx.json(mockPosts) }
                get("/{id}") { ctx ->
                    val postId = UUID.fromString(ctx.pathParam("id"))
                    val post = mockPosts.find { it.id == postId }
                    if (post != null) {
                        ctx.json(post)
                    } else {
                        ctx.status(HttpStatus.NOT_FOUND).json(ErrorResponse("Post not found"))
                    }
                }
            }
            path("/api/error") {
                get("known") { throw IllegalArgumentException("This is a test validation error.") }
                get("unknown") { throw RuntimeException("Something unexpected happened.") }
            }
        }
    }.start(7070)

    println("Server started on http://localhost:7070")
}