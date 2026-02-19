package com.developerone.app

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// --- Domain Model ---
enum class Role { ADMIN, USER }
enum class Status { DRAFT, PUBLISHED }

data class User(
    val id: UUID,
    val email: String,
    val password_hash: String,
    val role: Role,
    val is_active: Boolean,
    val created_at: Instant
) {
    // A simple representation, excluding sensitive fields
    fun toSafeString(): String = """{"id": "$id", "email": "$email", "role": "$role", "is_active": $is_active}"""
}

data class Post(
    val id: UUID,
    val user_id: UUID,
    val title: String,
    val content: String,
    val status: Status
) {
    override fun toString(): String = """{"id": "$id", "user_id": "$user_id", "title": "$title", "status": "$status"}"""
}

// --- Mock Database ---
object Database {
    private val users = mutableMapOf<UUID, User>()
    private val posts = mutableMapOf<UUID, Post>()
    val regularUserId: UUID = UUID.randomUUID()

    init {
        val adminUser = User(UUID.randomUUID(), "admin@example.com", "hash1", Role.ADMIN, true, Instant.now())
        val regularUser = User(regularUserId, "user@example.com", "hash2", Role.USER, true, Instant.now())
        users[adminUser.id] = adminUser
        users[regularUser.id] = regularUser

        val post1 = Post(UUID.randomUUID(), regularUser.id, "First Post", "Content here.", Status.PUBLISHED)
        val post2 = Post(UUID.randomUUID(), regularUser.id, "Draft Post", "Draft content.", Status.DRAFT)
        posts[post1.id] = post1
        posts[post2.id] = post2
    }

    fun findAllUsers(): List<User> = users.values.toList()
    fun findPostsByUserId(userId: UUID): List<Post> = posts.values.filter { it.user_id == userId }
}

// --- HTTP Simulation ---
data class Request(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String? = null
)

data class Response(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: String
)

typealias HttpHandler = (Request) -> Response

// --- Middleware Implementation (Functional) ---
typealias Middleware = (HttpHandler) -> HttpHandler

val loggingMiddleware: Middleware = { next ->
    { request ->
        println("--> ${request.method} ${request.path} from IP: ${request.headers["X-Forwarded-For"] ?: "unknown"}")
        val startTime = System.currentTimeMillis()
        val response = next(request)
        val duration = System.currentTimeMillis() - startTime
        println("<-- ${response.statusCode} (${duration}ms)")
        response
    }
}

val errorHandlingMiddleware: Middleware = { next ->
    { request ->
        try {
            next(request)
        } catch (e: Exception) {
            println("!!! ERROR: ${e.message}")
            Response(500, mapOf("Content-Type" to "application/json"), """{"error": "Internal Server Error"}""")
        }
    }
}

val corsHandlingMiddleware: Middleware = { next ->
    { request ->
        val origin = request.headers["Origin"]
        val allowedOrigins = setOf("https://example.com", "http://localhost:3000")

        if (request.method == "OPTIONS") {
            Response(
                204,
                mapOf(
                    "Access-Control-Allow-Origin" to (if (origin in allowedOrigins) origin!! else "null"),
                    "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
                    "Access-Control-Allow-Headers" to "Content-Type, Authorization"
                ),
                ""
            )
        } else {
            val response = next(request)
            val newHeaders = response.headers.toMutableMap()
            if (origin in allowedOrigins) {
                newHeaders["Access-Control-Allow-Origin"] = origin!!
            }
            response.copy(headers = newHeaders)
        }
    }
}

object RateLimiter {
    private const val MAX_REQUESTS = 5
    private const val WINDOW_MS = 10000 // 10 seconds
    private val requests = ConcurrentHashMap<String, Pair<Int, Long>>()

    fun isAllowed(ip: String): Boolean {
        val now = System.currentTimeMillis()
        val (count, timestamp) = requests[ip] ?: (0 to 0L)

        return if (now - timestamp > WINDOW_MS) {
            requests[ip] = 1 to now
            true
        } else if (count < MAX_REQUESTS) {
            requests[ip] = (count + 1) to timestamp
            true
        } else {
            false
        }
    }
}

val rateLimitingMiddleware: Middleware = { next ->
    { request ->
        val ip = request.headers["X-Forwarded-For"] ?: "127.0.0.1"
        if (RateLimiter.isAllowed(ip)) {
            next(request)
        } else {
            Response(429, mapOf("Content-Type" to "application/json"), """{"error": "Too Many Requests"}""")
        }
    }
}

val responseTransformationMiddleware: Middleware = { next ->
    { request ->
        val response = next(request)
        if (response.statusCode in 200..299) {
            val transformedBody = """
            {
              "data": ${response.body},
              "timestamp": "${Instant.now()}",
              "request_id": "${UUID.randomUUID()}"
            }
            """.trimIndent()
            response.copy(body = transformedBody, headers = response.headers + ("Content-Type" to "application/json"))
        } else {
            response
        }
    }
}

// --- Business Logic Handlers ---
val getUsersHandler: HttpHandler = { _ ->
    val users = Database.findAllUsers()
    val userListJson = users.joinToString(separator = ", ", prefix = "[", postfix = "]") { it.toSafeString() }
    Response(200, emptyMap(), userListJson)
}

val getPostsForUserHandler: HttpHandler = { request ->
    val userIdStr = request.path.substringAfterLast('/')
    try {
        val userId = UUID.fromString(userIdStr)
        val posts = Database.findPostsByUserId(userId)
        val postListJson = posts.joinToString(separator = ", ", prefix = "[", postfix = "]") { it.toString() }
        Response(200, emptyMap(), postListJson)
    } catch (e: IllegalArgumentException) {
        Response(400, emptyMap(), """{"error": "Invalid user ID format"}""")
    }
}

// --- Application Entry Point ---
fun chain(handler: HttpHandler, middlewares: List<Middleware>): HttpHandler {
    return middlewares.foldRight(handler) { middleware, acc -> middleware(acc) }
}

fun main() {
    val middlewares = listOf(
        loggingMiddleware,
        errorHandlingMiddleware,
        corsHandlingMiddleware,
        rateLimitingMiddleware,
        responseTransformationMiddleware
    )

    val router: HttpHandler = { request ->
        when {
            request.path == "/users" && request.method == "GET" -> getUsersHandler(request)
            request.path.startsWith("/users/") && request.path.endsWith("/posts") && request.method == "GET" -> {
                val pathParts = request.path.split("/")
                // /users/{uuid}/posts -> parts are "", "users", "{uuid}", "posts"
                if (pathParts.size == 4) {
                    getPostsForUserHandler(request.copy(path = "/users/${pathParts[2]}"))
                } else {
                    Response(404, emptyMap(), """{"error": "Not Found"}""")
                }
            }
            else -> Response(404, emptyMap(), """{"error": "Not Found"}""")
        }
    }

    val app = chain(router, middlewares)

    println("--- Simulating a valid request ---")
    val req1 = Request("GET", "/users", mapOf("Origin" to "http://localhost:3000", "X-Forwarded-For" to "1.1.1.1"))
    println("Response:\n${app(req1).body}\n")

    println("--- Simulating a rate-limited request ---")
    for (i in 1..6) {
        println("Attempt #${i}")
        val req = Request("GET", "/users", mapOf("Origin" to "http://localhost:3000", "X-Forwarded-For" to "2.2.2.2"))
        val res = app(req)
        if (res.statusCode == 429) {
            println("Response: Rate limited as expected.\n")
            break
        }
    }

    println("--- Simulating a request for a user's posts ---")
    val req3 = Request("GET", "/users/${Database.regularUserId}/posts", mapOf("Origin" to "https://example.com", "X-Forwarded-For" to "3.3.3.3"))
    println("Response:\n${app(req3).body}\n")

    println("--- Simulating a not found request ---")
    val req4 = Request("POST", "/users", mapOf("Origin" to "https://example.com", "X-Forwarded-For" to "4.4.4.4"))
    println("Response:\n${app(req4).body}\n")
}