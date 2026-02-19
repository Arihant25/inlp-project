package com.developerfour.app

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// --- Domain Model ---
enum class UserRole { ADMIN, USER }
enum class PublicationStatus { DRAFT, PUBLISHED }
data class User(val id: UUID, val email: String, val password_hash: String, val role: UserRole, val is_active: Boolean, val created_at: Instant)
data class Post(val id: UUID, val user_id: UUID, val title: String, val content: String, val status: PublicationStatus)

// --- Mock Data Access Object ---
object DAO {
    private val users = mutableMapOf<UUID, User>()
    init {
        users[UUID.randomUUID()] = User(UUID.randomUUID(), "test@user.com", "...", UserRole.USER, true, Instant.now())
    }
    fun listAllUsers(): List<User> = users.values.toList()
}

// --- HTTP Primitives ---
data class Req(val method: String, val path: String, val headers: Map<String, String>, val body: String? = null)
data class Res(val code: Int, val headers: Map<String, String>, val body: String)
typealias Handler = (Req) -> Res

// --- Middleware via Extension Functions ---
fun Handler.withLogging(): Handler = { req ->
    val start = System.nanoTime()
    println("-> ${req.method} ${req.path}")
    val res = this(req)
    val duration = (System.nanoTime() - start) / 1_000_000
    println("<- ${res.code} in ${duration}ms")
    res
}

fun Handler.withErrorHandling(): Handler = { req ->
    try {
        this(req)
    } catch (e: Exception) {
        System.err.println("!! Exception caught: ${e.message}")
        Res(500, mapOf("Content-Type" to "application/json"), """{"error": "An unexpected error occurred"}""")
    }
}

fun Handler.withCors(allowedOrigins: Set<String>): Handler = { req ->
    val origin = req.headers["Origin"]
    val res = this(req)
    if (origin in allowedOrigins) {
        res.copy(headers = res.headers + ("Access-Control-Allow-Origin" to origin!!))
    } else {
        res
    }
}

class RateLimiter(private val maxReqs: Int, private val perSeconds: Int) {
    private val store = ConcurrentHashMap<String, List<Long>>()
    fun apply(handler: Handler): Handler = { req ->
        val ip = req.headers["X-Real-IP"] ?: "unknown"
        val now = Instant.now().epochSecond
        val window = now - perSeconds
        
        val currentTimestamps = store[ip]?.filter { it > window } ?: emptyList()
        
        if (currentTimestamps.size >= maxReqs) {
            Res(429, mapOf("Retry-After" to perSeconds.toString()), "Rate limit exceeded")
        } else {
            store[ip] = currentTimestamps + now
            handler(req)
        }
    }
}

fun Handler.withJsonResponseTransformer(): Handler = { req ->
    val res = this(req)
    // Decorator pattern applied to the response
    if (res.code in 200..299 && res.headers["Content-Type"] != "application/json") {
        val newBody = """
        {
          "meta": {"code": ${res.code}, "request_time": "${Instant.now()}"},
          "response": ${res.body}
        }
        """.trimIndent()
        res.copy(body = newBody, headers = res.headers + ("Content-Type" to "application/json"))
    } else {
        res
    }
}

// --- Route Handlers ---
val getUsersHandler: Handler = {
    val users = DAO.listAllUsers()
    val json = users.joinToString(",", "[", "]") { """{"id":"${it.id}","email":"${it.email}"}""" }
    Res(200, emptyMap(), json)
}

val getPostsHandler: Handler = {
    // Simulate a failure for the error handler
    check(false) { "Database connection failed" }
    Res(200, emptyMap(), "[]")
}

val notFoundHandler: Handler = {
    Res(404, emptyMap(), """{"error":"Route not found"}""")
}

// --- Main Application Setup ---
fun main() {
    val rateLimiter = RateLimiter(maxReqs = 100, perSeconds = 60)

    // Chain middleware using extension functions for a fluent, readable style
    val finalGetUsersHandler = getUsersHandler
        .withJsonResponseTransformer()
        .let(rateLimiter::apply) // Use 'let' for class-based middleware
        .withCors(setOf("http://localhost:8080"))
        .withErrorHandling()
        .withLogging()

    val finalGetPostsHandler = getPostsHandler
        .withJsonResponseTransformer()
        .withCors(setOf("http://localhost:8080"))
        .withErrorHandling()
        .withLogging()

    val router: (Req) -> Res = { req ->
        when (req.path) {
            "/users" -> finalGetUsersHandler(req)
            "/posts" -> finalGetPostsHandler(req)
            else -> notFoundHandler(req)
        }
    }

    println("--- SIMULATION 1: Successful call to /users ---")
    val req1 = Req("GET", "/users", mapOf("Origin" to "http://localhost:8080", "X-Real-IP" to "1.2.3.4"))
    val res1 = router(req1)
    println("Final Response Body:\n${res1.body}\n")

    println("--- SIMULATION 2: Call to /posts to trigger error handler ---")
    val req2 = Req("GET", "/posts", mapOf("Origin" to "http://localhost:8080", "X-Real-IP" to "1.2.3.5"))
    val res2 = router(req2)
    println("Final Response Body:\n${res2.body}\n")

    println("--- SIMULATION 3: Call from a disallowed origin ---")
    val req3 = Req("GET", "/users", mapOf("Origin" to "http://bad-actor.com", "X-Real-IP" to "1.2.3.6"))
    val res3 = router(req3)
    println("Final Response Headers: ${res3.headers}\n")
}