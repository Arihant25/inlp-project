package com.developerthree.app

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// --- Domain Model ---
enum class Role { ADMIN, USER }
enum class Status { DRAFT, PUBLISHED }
data class User(val id: UUID, val email: String, val password_hash: String, val role: Role, val is_active: Boolean, val created_at: Instant)
data class Post(val id: UUID, val user_id: UUID, val title: String, val content: String, val status: Status)

// --- Mock Database ---
object MockDB {
    private val users = mutableListOf<User>()
    init {
        users.add(User(UUID.randomUUID(), "admin@test.com", "hash", Role.ADMIN, true, Instant.now()))
        users.add(User(UUID.randomUUID(), "user@test.com", "hash", Role.USER, true, Instant.now()))
    }
    fun getUsers(): List<User> = users
}

// --- HTTP Simulation ---
data class WebRequest(val method: String, val path: String, val headers: Map<String, String>, val body: String? = null)
data class WebResponse(val status: Int, val headers: Map<String, String>, val body: String)
typealias ControllerAction = (WebRequest) -> WebResponse

// --- Middleware Definitions ---
object Middlewares {
    fun createLogger(next: ControllerAction): ControllerAction = { req ->
        println("Request: ${req.method} ${req.path}")
        next(req).also { res -> println("Response: ${res.status}") }
    }

    fun createCors(allowedHosts: Set<String>): (ControllerAction) -> ControllerAction = { next ->
        { req ->
            val res = next(req)
            val origin = req.headers["Host"]
            if (origin != null && origin in allowedHosts) {
                res.copy(headers = res.headers + ("Access-Control-Allow-Origin" to origin))
            } else {
                res
            }
        }
    }

    fun createErrorTrap(next: ControllerAction): ControllerAction = { req ->
        try {
            next(req)
        } catch (e: Exception) {
            WebResponse(500, mapOf("Content-Type" to "application/json"), """{"error":"${e.javaClass.simpleName}"}""")
        }
    }

    class RateLimiter(private val maxHits: Int, private val periodSeconds: Long) {
        private val hits = ConcurrentHashMap<String, LongArray>()
        fun createMiddleware(next: ControllerAction): ControllerAction = { req ->
            val ip = req.headers["X-Client-IP"] ?: "localhost"
            val now = System.currentTimeMillis()
            val windowStart = now - periodSeconds * 1000
            
            val clientHits = hits.compute(ip) { _, v ->
                val recent = v?.filter { it >= windowStart }?.toLongArray() ?: longArrayOf()
                recent + now
            }!!

            if (clientHits.size > maxHits) {
                WebResponse(429, emptyMap(), "Too many requests")
            } else {
                next(req)
            }
        }
    }

    fun createResponseWrapper(next: ControllerAction): ControllerAction = { req ->
        val response = next(req)
        // Decorator implementation: wraps the original response body
        if (response.status == 200) {
            val wrappedBody = """{"status":"success", "data":${response.body}}"""
            response.copy(body = wrappedBody, headers = response.headers + ("Content-Type" to "application/json"))
        } else {
            response
        }
    }
}

// --- DSL-like Server Builder ---
class HttpApplication {
    private val globalMiddleware = mutableListOf<(ControllerAction) -> ControllerAction>()
    private val routes = mutableMapOf<Pair<String, String>, ControllerAction>()

    fun use(middleware: (ControllerAction) -> ControllerAction) {
        globalMiddleware.add(middleware)
    }

    fun get(path: String, action: ControllerAction) {
        routes["GET" to path] = action
    }

    fun build(): (WebRequest) -> WebResponse {
        val notFoundAction: ControllerAction = { WebResponse(404, emptyMap(), "Not Found") }
        
        val router: ControllerAction = { req ->
            routes[req.method to req.path]?.invoke(req) ?: notFoundAction(req)
        }

        return globalMiddleware.reversed().fold(router) { acc, middleware ->
            middleware(acc)
        }
    }
}

// --- Controllers ---
object UserController {
    val index: ControllerAction = {
        val users = MockDB.getUsers()
        val body = users.joinToString(",", "[", "]") { """{"id":"${it.id}","email":"${it.email}"}""" }
        WebResponse(200, emptyMap(), body)
    }
}

object PostController {
    val index: ControllerAction = {
        // This would throw an error if not implemented, demonstrating error handling
        throw NotImplementedError("This endpoint is not ready yet.")
    }
}

// --- Main Entry Point ---
fun main() {
    val rateLimiter = Middlewares.RateLimiter(5, 10)

    val app = HttpApplication().apply {
        // Global middleware stack
        use(Middlewares::createLogger)
        use(Middlewares::createErrorTrap)
        use(Middlewares.createCors(setOf("localhost:8080", "api.example.com")))
        use(rateLimiter::createMiddleware)
        use(Middlewares::createResponseWrapper)

        // Routes
        get("/users", UserController.index)
        get("/posts", PostController.index)
    }.build()

    println("--- 1. Test successful request with response wrapping ---")
    val req1 = WebRequest("GET", "/users", mapOf("Host" to "api.example.com", "X-Client-IP" to "192.168.1.1"))
    val res1 = app(req1)
    println("Response Body: ${res1.body}\n")

    println("--- 2. Test error handling middleware ---")
    val req2 = WebRequest("GET", "/posts", mapOf("Host" to "localhost:8080", "X-Client-IP" to "192.168.1.2"))
    val res2 = app(req2)
    println("Response Body: ${res2.body}\n")

    println("--- 3. Test rate limiting middleware ---")
    val req3 = WebRequest("GET", "/users", mapOf("Host" to "localhost:8080", "X-Client-IP" to "192.168.1.3"))
    repeat(6) {
        val res = app(req3)
        println("Attempt ${it + 1}: Status ${res.status}")
    }
    println()

    println("--- 4. Test 404 Not Found ---")
    val req4 = WebRequest("DELETE", "/users", mapOf("Host" to "localhost:8080", "X-Client-IP" to "192.168.1.4"))
    val res4 = app(req4)
    println("Response Body: ${res4.body}\n")
}