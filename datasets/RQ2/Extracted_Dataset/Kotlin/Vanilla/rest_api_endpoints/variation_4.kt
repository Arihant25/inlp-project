package dev.kotlinic.api.v4

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

// --- Domain Model ---
enum class UserRole { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }

data class User(
    val id: UUID,
    val email: String,
    var passwordHash: String,
    var role: UserRole,
    var isActive: Boolean,
    val createdAt: Instant
)

data class Post(
    val id: UUID,
    val userId: UUID,
    val title: String,
    val content: String,
    val status: PostStatus
)

// --- Data Store as a Singleton Object ---
object UserDataStore {
    private val users = ConcurrentHashMap<UUID, User>()

    init {
        // Seed data
        sequenceOf(
            User(UUID.randomUUID(), "kt.admin@example.dev", "hash", UserRole.ADMIN, true, Instant.now()),
            User(UUID.randomUUID(), "kt.user@example.dev", "hash", UserRole.USER, true, Instant.now()),
            User(UUID.randomUUID(), "kt.inactive@example.dev", "hash", UserRole.USER, false, Instant.now())
        ).forEach { users[it.id] = it }
    }

    fun findById(id: UUID): User? = users[id]
    fun findAll(): List<User> = users.values.toList()
    fun save(user: User) { users[user.id] = user }
    fun delete(id: UUID): User? = users.remove(id)
    fun existsByEmail(email: String): Boolean = users.values.any { it.email == email }
}

// --- Web Server with Idiomatic Routing ---
class WebServer(private val port: Int) {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(port), 0)
    private val routes = mutableListOf<Route>()

    init {
        defineRoutes()
        server.createContext("/") { exchange ->
            val matchingRoute = routes.firstNotNullOfOrNull { it.match(exchange.requestMethod, exchange.requestURI.path) }
            if (matchingRoute != null) {
                matchingRoute.handler(exchange, matchingRoute.pathParams)
            } else {
                exchange.sendJson(404, mapOf("error" to "Endpoint not found"))
            }
        }
    }

    fun start() {
        server.start()
        println("Kotlin-Idiomatic Server listening on port $port")
    }

    private fun defineRoutes() {
        get("/users", ::handleListUsers)
        post("/users", ::handleCreateUser)
        get("/users/{id}", ::handleGetUser)
        put("/users/{id}", ::handleUpdateUser)
        patch("/users/{id}", ::handleUpdateUser)
        delete("/users/{id}", ::handleDeleteUser)
    }

    // --- Route Handlers ---
    private fun handleListUsers(exchange: HttpExchange, params: Map<String, String>) {
        val query = exchange.parseQuery()
        val page = query["page"]?.toIntOrNull() ?: 1
        val size = query["size"]?.toIntOrNull() ?: 10
        val role = query["role"]?.let { runCatching { UserRole.valueOf(it.uppercase()) }.getOrNull() }
        val isActive = query["is_active"]?.toBooleanStrictOrNull()

        val filtered = UserDataStore.findAll().filter {
            (role == null || it.role == role) && (isActive == null || it.isActive == isActive)
        }

        val total = filtered.size
        val data = filtered.drop((page - 1) * size).take(size).map { it.toPublicJsonMap() }
        
        exchange.sendJson(200, mapOf(
            "page" to page, "size" to size, "total" to total, "totalPages" to ceil(total.toDouble() / size).toInt(), "data" to data
        ))
    }

    private fun handleCreateUser(exchange: HttpExchange, params: Map<String, String>) {
        val body = exchange.parseJsonBody()
        val email = body["email"] as? String ?: return exchange.sendJson(400, mapOf("error" to "Email required"))
        val password = body["password"] as? String ?: return exchange.sendJson(400, mapOf("error" to "Password required"))

        if (UserDataStore.existsByEmail(email)) {
            return exchange.sendJson(409, mapOf("error" to "Email already exists"))
        }

        val user = User(
            id = UUID.randomUUID(),
            email = email,
            passwordHash = "hashed::${password}",
            role = (body["role"] as? String)?.let { UserRole.valueOf(it.uppercase()) } ?: UserRole.USER,
            isActive = body["is_active"] as? Boolean ?: true,
            createdAt = Instant.now()
        )
        UserDataStore.save(user)
        exchange.sendJson(201, user.toPublicJsonMap())
    }

    private fun handleGetUser(exchange: HttpExchange, params: Map<String, String>) {
        val userId = UUID.fromString(params["id"])
        UserDataStore.findById(userId)?.let {
            exchange.sendJson(200, it.toPublicJsonMap())
        } ?: exchange.sendJson(404, mapOf("error" to "User not found"))
    }

    private fun handleUpdateUser(exchange: HttpExchange, params: Map<String, String>) {
        val userId = UUID.fromString(params["id"])
        val user = UserDataStore.findById(userId) ?: return exchange.sendJson(404, mapOf("error" to "User not found"))
        
        val body = exchange.parseJsonBody()
        (body["password"] as? String)?.let { user.passwordHash = "hashed::${it}" }
        (body["role"] as? String)?.let { user.role = UserRole.valueOf(it.uppercase()) }
        (body["is_active"] as? Boolean)?.let { user.isActive = it }
        
        UserDataStore.save(user)
        exchange.sendJson(200, user.toPublicJsonMap())
    }

    private fun handleDeleteUser(exchange: HttpExchange, params: Map<String, String>) {
        val userId = UUID.fromString(params["id"])
        if (UserDataStore.delete(userId) != null) {
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        } else {
            exchange.sendJson(404, mapOf("error" to "User not found"))
        }
    }

    // --- Routing DSL ---
    private fun get(path: String, handler: (HttpExchange, Map<String, String>) -> Unit) = addRoute("GET", path, handler)
    private fun post(path: String, handler: (HttpExchange, Map<String, String>) -> Unit) = addRoute("POST", path, handler)
    private fun put(path: String, handler: (HttpExchange, Map<String, String>) -> Unit) = addRoute("PUT", path, handler)
    private fun patch(path: String, handler: (HttpExchange, Map<String, String>) -> Unit) = addRoute("PATCH", path, handler)
    private fun delete(path: String, handler: (HttpExchange, Map<String, String>) -> Unit) = addRoute("DELETE", path, handler)

    private fun addRoute(method: String, path: String, handler: (HttpExchange, Map<String, String>) -> Unit) {
        routes.add(Route(method, path, handler))
    }
}

// --- Helper Classes and Extension Functions ---
private data class RouteMatch(val handler: (HttpExchange, Map<String, String>) -> Unit, val pathParams: Map<String, String>)

private class Route(val method: String, path: String, val handler: (HttpExchange, Map<String, String>) -> Unit) {
    private val pathParamNames = mutableListOf<String>()
    private val pathRegex = path.split('/').map {
        if (it.startsWith("{") && it.endsWith("}")) {
            pathParamNames.add(it.removeSurrounding("{", "}"))
            "([^/]+)"
        } else {
            it
        }
    }.joinToString("/", "^", "$").toRegex()

    fun match(requestMethod: String, requestPath: String): RouteMatch? {
        if (requestMethod != method) return null
        val matchResult = pathRegex.matchEntire(requestPath) ?: return null
        val params = pathParamNames.zip(matchResult.groupValues.drop(1)).toMap()
        return RouteMatch(handler, params)
    }
}

private fun HttpExchange.sendJson(code: Int, data: Map<String, Any?>) {
    val jsonString = data.toJsonString()
    responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
    val bytes = jsonString.toByteArray()
    sendResponseHeaders(code, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

private fun HttpExchange.parseQuery(): Map<String, String> =
    requestURI.query?.split('&')?.mapNotNull {
        it.split('=', limit = 2).let { parts -> if (parts.size > 1) parts[0] to parts[1] else null }
    }?.toMap() ?: emptyMap()

private fun HttpExchange.parseJsonBody(): Map<String, Any> {
    val text = requestBody.reader().readText()
    val map = mutableMapOf<String, Any>()
    text.trim().removeSurrounding("{", "}").split(",").forEach {
        val (key, value) = it.split(":", limit = 2).map { part -> part.trim().removeSurrounding("\"") }
        map[key] = when {
            value == "true" -> true
            value == "false" -> false
            value.toIntOrNull() != null -> value.toInt()
            else -> value
        }
    }
    return map
}

private fun User.toPublicJsonMap(): Map<String, Any> = mapOf(
    "id" to id.toString(),
    "email" to email,
    "role" to role.name,
    "is_active" to isActive,
    "created_at" to createdAt.toString()
)

private fun Map<String, Any?>.toJsonString(): String = this.entries
    .joinToString(",", "{", "}") { (key, value) ->
        "\"$key\":${value.toJsonValue()}"
    }

private fun Any?.toJsonValue(): String = when (this) {
    is String -> "\"${this.replace("\"", "\\\"")}\""
    is List<*> -> this.joinToString(",", "[", "]") { (it as? Map<String, Any?>)?.toJsonString() ?: "null" }
    else -> this.toString()
}

// --- Application Entry Point ---
fun main() {
    WebServer(port = 8080).start()
}