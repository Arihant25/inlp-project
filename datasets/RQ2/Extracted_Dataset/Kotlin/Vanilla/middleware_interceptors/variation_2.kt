package com.developertwo.app

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// --- Domain Model ---
enum class UserRole { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }

data class User(
    val id: UUID, val email: String, val password_hash: String,
    val role: UserRole, val is_active: Boolean, val created_at: Instant
)

data class Post(
    val id: UUID, val user_id: UUID, val title: String,
    val content: String, val status: PostStatus
)

// --- Mock Data Store ---
object DataStore {
    private val userTable = mutableMapOf<UUID, User>()
    private val postTable = mutableMapOf<UUID, Post>()

    init {
        val admin = User(UUID.randomUUID(), "admin@corp.com", "phash1", UserRole.ADMIN, true, Instant.now())
        val user = User(UUID.randomUUID(), "user@corp.com", "phash2", UserRole.USER, true, Instant.now())
        userTable[admin.id] = admin
        userTable[user.id] = user
        postTable[UUID.randomUUID()] = Post(UUID.randomUUID(), user.id, "A Post", "Content.", PostStatus.PUBLISHED)
    }

    fun getAllUsers(): List<User> = userTable.values.toList()
}

// --- HTTP Abstractions ---
data class HttpRequest(
    val method: String, val path: String,
    val headers: Map<String, String>, val body: String? = null
)

data class HttpResponse(
    val statusCode: Int, val headers: MutableMap<String, String>, val body: String
)

typealias RequestHandler = (HttpRequest) -> HttpResponse

// --- Middleware Interfaces and Implementations (OOP) ---
interface Middleware {
    fun process(request: HttpRequest, next: RequestHandler): HttpResponse
}

class LoggingMiddleware : Middleware {
    override fun process(request: HttpRequest, next: RequestHandler): HttpResponse {
        println("[LOG] Request received: ${request.method} ${request.path}")
        val response = next(request)
        println("[LOG] Responded with status: ${response.statusCode}")
        return response
    }
}

class ErrorHandlingMiddleware : Middleware {
    override fun process(request: HttpRequest, next: RequestHandler): HttpResponse {
        return try {
            next(request)
        } catch (ex: Throwable) {
            System.err.println("[ERROR] Unhandled exception: ${ex.message}")
            HttpResponse(500, mutableMapOf("Content-Type" to "text/plain"), "An internal error occurred.")
        }
    }
}

class CorsMiddleware(private val allowedOrigins: Set<String>) : Middleware {
    override fun process(request: HttpRequest, next: RequestHandler): HttpResponse {
        val origin = request.headers["Origin"]
        val isAllowed = origin != null && origin in allowedOrigins

        if (request.method == "OPTIONS") {
            val headers = mutableMapOf(
                "Access-Control-Allow-Methods" to "GET",
                "Access-Control-Max-Age" to "3600"
            )
            if (isAllowed) {
                headers["Access-Control-Allow-Origin"] = origin!!
            }
            return HttpResponse(204, headers, "")
        }

        val response = next(request)
        if (isAllowed) {
            response.headers["Access-Control-Allow-Origin"] = origin!!
        }
        return response
    }
}

class RateLimitingMiddleware(private val limit: Int, private val windowSeconds: Int) : Middleware {
    private val clientRequests = ConcurrentHashMap<String, MutableList<Long>>()

    override fun process(request: HttpRequest, next: RequestHandler): HttpResponse {
        val clientIp = request.headers["Remote-Addr"] ?: "127.0.0.1"
        val now = System.currentTimeMillis()
        val windowStart = now - windowSeconds * 1000

        clientRequests.compute(clientIp) { _, timestamps ->
            val recentTimestamps = timestamps?.filter { it >= windowStart }?.toMutableList() ?: mutableListOf()
            recentTimestamps.add(now)
            recentTimestamps
        }

        if ((clientRequests[clientIp]?.size ?: 0) > limit) {
            return HttpResponse(429, mutableMapOf(), "Rate limit exceeded.")
        }

        return next(request)
    }
}

class ResponseTransformerMiddleware : Middleware {
    override fun process(request: HttpRequest, next: RequestHandler): HttpResponse {
        val response = next(request)
        // Decorator pattern: wrap the original response body
        val newBody = """
        {
          "payload": ${if (response.body.startsWith("{") || response.body.startsWith("[")) response.body else "\"${response.body}\""},
          "server_time": "${Instant.now()}"
        }
        """.trimIndent()
        response.headers["Content-Type"] = "application/json"
        return response.copy(body = newBody)
    }
}

// --- Middleware Chain Runner ---
class MiddlewareChain(
    private val middlewares: List<Middleware>,
    private val finalHandler: RequestHandler
) {
    fun execute(request: HttpRequest): HttpResponse {
        // Create the chain by wrapping handlers inside each other from last to first
        val chain = middlewares.reversed().fold(finalHandler) { nextHandler, middleware ->
            { req -> middleware.process(req, nextHandler) }
        }
        return chain(request)
    }
}

// --- Application ---
object Handlers {
    val listUsersHandler: RequestHandler = { _ ->
        val users = DataStore.getAllUsers()
        val usersJson = users.joinToString(",", "[", "]") {
            """{"id":"${it.id}","email":"${it.email}"}"""
        }
        HttpResponse(200, mutableMapOf(), usersJson)
    }

    val notFoundHandler: RequestHandler = { _ ->
        HttpResponse(404, mutableMapOf(), """{"error":"Not Found"}""")
    }
}

fun main() {
    val middlewareStack = listOf(
        ErrorHandlingMiddleware(),
        LoggingMiddleware(),
        CorsMiddleware(setOf("https://my-app.com")),
        RateLimitingMiddleware(limit = 10, windowSeconds = 60),
        ResponseTransformerMiddleware()
    )

    val router: RequestHandler = { request ->
        when (request.path) {
            "/api/v1/users" -> Handlers.listUsersHandler(request)
            else -> Handlers.notFoundHandler(request)
        }
    }

    val app = MiddlewareChain(middlewareStack, router)

    println("--- Simulating a valid API call ---")
    val request1 = HttpRequest("GET", "/api/v1/users", mapOf("Origin" to "https://my-app.com", "Remote-Addr" to "10.0.0.1"))
    val response1 = app.execute(request1)
    println("Response Body:\n${response1.body}\n")

    println("--- Simulating a request from a disallowed origin ---")
    val request2 = HttpRequest("GET", "/api/v1/users", mapOf("Origin" to "https://evil-site.com", "Remote-Addr" to "10.0.0.2"))
    val response2 = app.execute(request2)
    println("Response Headers: ${response2.headers}")
    println("Response Body:\n${response2.body}\n")

    println("--- Simulating a 404 Not Found ---")
    val request3 = HttpRequest("GET", "/api/v1/posts", mapOf("Origin" to "https://my-app.com", "Remote-Addr" to "10.0.0.3"))
    val response3 = app.execute(request3)
    println("Response Body:\n${response3.body}\n")
}