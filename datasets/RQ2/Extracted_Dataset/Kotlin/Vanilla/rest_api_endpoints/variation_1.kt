package com.example.api.v1

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

// --- DOMAIN MODEL ---

enum class UserRole { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }

data class User(
    val id: UUID,
    val email: String,
    var password_hash: String,
    var role: UserRole,
    var is_active: Boolean,
    val created_at: Instant
)

data class Post(
    val id: UUID,
    val user_id: UUID,
    val title: String,
    val content: String,
    val status: PostStatus
)

// --- IN-MEMORY DATABASE ---

val userDatabase = ConcurrentHashMap<UUID, User>()

// --- MAIN APPLICATION ---

fun main() {
    // Pre-populate with some data
    val adminId = UUID.randomUUID()
    userDatabase[adminId] = User(
        id = adminId,
        email = "admin@example.com",
        password_hash = "hashed_admin_pass",
        role = UserRole.ADMIN,
        is_active = true,
        created_at = Instant.now()
    )
    val userId = UUID.randomUUID()
    userDatabase[userId] = User(
        id = userId,
        email = "user@example.com",
        password_hash = "hashed_user_pass",
        role = UserRole.USER,
        is_active = false,
        created_at = Instant.now().minusSeconds(86400)
    )

    val server = HttpServer.create(InetSocketAddress(8080), 0)
    println("Server started on port 8080")

    server.createContext("/users") { exchange ->
        handleUserRequest(exchange)
    }
    server.createContext("/users/") { exchange ->
        handleUserRequest(exchange)
    }

    server.executor = null // creates a default executor
    server.start()
}

// --- REQUEST HANDLER ---

fun handleUserRequest(exchange: HttpExchange) {
    val method = exchange.requestMethod
    val path = exchange.requestURI.path
    val userPathRegex = Regex("/users/([a-fA-F0-9\\-]+)")

    try {
        when {
            method == "GET" && path == "/users" -> listUsers(exchange)
            method == "POST" && path == "/users" -> createUser(exchange)
            method == "GET" && userPathRegex.matches(path) -> {
                val userId = UUID.fromString(userPathRegex.find(path)!!.groupValues[1])
                getUser(exchange, userId)
            }
            (method == "PUT" || method == "PATCH") && userPathRegex.matches(path) -> {
                val userId = UUID.fromString(userPathRegex.find(path)!!.groupValues[1])
                updateUser(exchange, userId)
            }
            method == "DELETE" && userPathRegex.matches(path) -> {
                val userId = UUID.fromString(userPathRegex.find(path)!!.groupValues[1])
                deleteUser(exchange, userId)
            }
            else -> sendResponse(exchange, 404, "{\"error\":\"Not Found\"}")
        }
    } catch (e: Exception) {
        e.printStackTrace()
        sendResponse(exchange, 500, "{\"error\":\"Internal Server Error: ${e.message}\"}")
    }
}

// --- API FUNCTIONALITY ---

fun listUsers(exchange: HttpExchange) {
    val params = parseQuery(exchange.requestURI.query)
    val page = params["page"]?.toIntOrNull() ?: 1
    val size = params["size"]?.toIntOrNull() ?: 10
    val roleFilter = params["role"]?.let { UserRole.valueOf(it.uppercase()) }
    val activeFilter = params["is_active"]?.toBoolean()

    var filteredUsers = userDatabase.values.toList()

    if (roleFilter != null) {
        filteredUsers = filteredUsers.filter { it.role == roleFilter }
    }
    if (activeFilter != null) {
        filteredUsers = filteredUsers.filter { it.is_active == activeFilter }
    }

    val totalItems = filteredUsers.size
    val totalPages = ceil(totalItems.toDouble() / size).toInt()
    val offset = (page - 1) * size
    val paginatedUsers = filteredUsers.drop(offset).take(size)

    val userListJson = paginatedUsers.joinToString(",", "[", "]") { userToJson(it) }
    val response = """
    {
      "page": $page,
      "size": $size,
      "total_items": $totalItems,
      "total_pages": $totalPages,
      "data": $userListJson
    }
    """.trimIndent()

    sendResponse(exchange, 200, response)
}

fun createUser(exchange: HttpExchange) {
    val body = readBody(exchange)
    val params = parseJson(body)

    val email = params["email"] ?: throw IllegalArgumentException("Email is required")
    val password = params["password"] ?: throw IllegalArgumentException("Password is required")

    if (userDatabase.values.any { it.email == email }) {
        sendResponse(exchange, 409, "{\"error\":\"User with this email already exists\"}")
        return
    }

    val newUser = User(
        id = UUID.randomUUID(),
        email = email,
        password_hash = "hashed_${password}", // In a real app, use a proper hashing library
        role = UserRole.valueOf(params["role"]?.uppercase() ?: "USER"),
        is_active = params["is_active"]?.toBoolean() ?: true,
        created_at = Instant.now()
    )
    userDatabase[newUser.id] = newUser
    sendResponse(exchange, 201, userToJson(newUser))
}

fun getUser(exchange: HttpExchange, userId: UUID) {
    val user = userDatabase[userId]
    if (user != null) {
        sendResponse(exchange, 200, userToJson(user))
    } else {
        sendResponse(exchange, 404, "{\"error\":\"User not found\"}")
    }
}

fun updateUser(exchange: HttpExchange, userId: UUID) {
    val user = userDatabase[userId]
    if (user == null) {
        sendResponse(exchange, 404, "{\"error\":\"User not found\"}")
        return
    }

    val body = readBody(exchange)
    val params = parseJson(body)

    params["password"]?.let { user.password_hash = "hashed_$it" }
    params["role"]?.let { user.role = UserRole.valueOf(it.uppercase()) }
    params["is_active"]?.let { user.is_active = it.toBoolean() }

    userDatabase[userId] = user
    sendResponse(exchange, 200, userToJson(user))
}

fun deleteUser(exchange: HttpExchange, userId: UUID) {
    if (userDatabase.containsKey(userId)) {
        userDatabase.remove(userId)
        exchange.sendResponseHeaders(204, -1) // No content
    } else {
        sendResponse(exchange, 404, "{\"error\":\"User not found\"}")
    }
    exchange.close()
}

// --- UTILITY FUNCTIONS ---

fun sendResponse(exchange: HttpExchange, code: Int, response: String) {
    exchange.responseHeaders.set("Content-Type", "application/json")
    val responseBytes = response.toByteArray(Charsets.UTF_8)
    exchange.sendResponseHeaders(code, responseBytes.size.toLong())
    exchange.responseBody.use { it.write(responseBytes) }
}

fun readBody(exchange: HttpExchange): String {
    return InputStreamReader(exchange.requestBody, Charsets.UTF_8).use { it.readText() }
}

fun parseQuery(query: String?): Map<String, String> {
    if (query.isNullOrEmpty()) return emptyMap()
    return query.split('&').mapNotNull {
        val parts = it.split('=', limit = 2)
        if (parts.size == 2) parts[0] to parts[1] else null
    }.toMap()
}

fun parseJson(jsonString: String): Map<String, String> {
    if (jsonString.isBlank()) return emptyMap()
    return jsonString.trim().removeSurrounding("{", "}").split(",")
        .map { it.trim() }
        .filter { it.contains(":") }
        .associate {
            val (key, value) = it.split(":", limit = 2)
            key.trim().removeSurrounding("\"") to value.trim().removeSurrounding("\"")
        }
}

fun userToJson(user: User): String {
    return """
    {
      "id": "${user.id}",
      "email": "${user.email}",
      "role": "${user.role}",
      "is_active": ${user.is_active},
      "created_at": "${user.created_at}"
    }
    """.trimIndent().replace("\n", "")
}