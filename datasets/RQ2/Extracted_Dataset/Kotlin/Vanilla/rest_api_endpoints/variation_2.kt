package com.enterprise.api.v2

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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

// --- Data Persistence Layer ---
interface UserRepository {
    fun findById(id: UUID): User?
    fun findAll(): List<User>
    fun save(user: User): User
    fun deleteById(id: UUID): Boolean
    fun findByEmail(email: String): User?
}

class InMemoryUserRepository : UserRepository {
    private val users = ConcurrentHashMap<UUID, User>()

    init {
        val admin = User(UUID.randomUUID(), "admin@corp.com", "hash1", UserRole.ADMIN, true, Instant.now())
        val user = User(UUID.randomUUID(), "user@corp.com", "hash2", UserRole.USER, true, Instant.now())
        users[admin.id] = admin
        users[user.id] = user
    }

    override fun findById(id: UUID): User? = users[id]
    override fun findAll(): List<User> = users.values.sortedBy { it.createdAt }
    override fun save(user: User): User {
        users[user.id] = user
        return user
    }
    override fun deleteById(id: UUID): Boolean = users.remove(id) != null
    override fun findByEmail(email: String): User? = users.values.find { it.email == email }
}

// --- Controller Layer ---
class UserController(private val userRepository: UserRepository) {

    fun handleGetUsers(exchange: HttpExchange) {
        val params = parseQueryParameters(exchange.requestURI.query)
        val page = params["page"]?.toIntOrNull() ?: 1
        val size = params["size"]?.toIntOrNull() ?: 10
        
        val allUsers = userRepository.findAll()
        
        val filtered = allUsers.filter { user ->
            val roleMatch = params["role"]?.let { user.role.name == it.uppercase() } ?: true
            val activeMatch = params["isActive"]?.let { user.isActive == it.toBoolean() } ?: true
            roleMatch && activeMatch
        }

        val paginated = filtered.drop((page - 1) * size).take(size)
        val responseJson = paginated.joinToString(",", "[", "]") { userToJson(it) }
        val responsePayload = """{"page":$page, "size":$size, "total":${filtered.size}, "users":$responseJson}"""
        
        sendJsonResponse(exchange, 200, responsePayload)
    }

    fun handleGetUserById(exchange: HttpExchange, id: UUID) {
        val user = userRepository.findById(id)
        if (user != null) {
            sendJsonResponse(exchange, 200, userToJson(user))
        } else {
            sendJsonResponse(exchange, 404, """{"error":"User with ID $id not found"}""")
        }
    }

    fun handleCreateUser(exchange: HttpExchange) {
        val requestBody = exchange.requestBody.reader().readText()
        val properties = parseJsonToMap(requestBody)

        val email = properties["email"] ?: return sendJsonResponse(exchange, 400, """{"error":"Email is required"}""")
        if (userRepository.findByEmail(email) != null) {
            return sendJsonResponse(exchange, 409, """{"error":"Email already in use"}""")
        }

        val newUser = User(
            id = UUID.randomUUID(),
            email = email,
            passwordHash = "hashed:" + (properties["password"] ?: ""),
            role = properties["role"]?.let { UserRole.valueOf(it.uppercase()) } ?: UserRole.USER,
            isActive = properties["isActive"]?.toBoolean() ?: true,
            createdAt = Instant.now()
        )
        userRepository.save(newUser)
        sendJsonResponse(exchange, 201, userToJson(newUser))
    }

    fun handleUpdateUser(exchange: HttpExchange, id: UUID) {
        val user = userRepository.findById(id) ?: return sendJsonResponse(exchange, 404, """{"error":"User not found"}""")
        
        val requestBody = exchange.requestBody.reader().readText()
        val properties = parseJsonToMap(requestBody)

        val updatedUser = user.copy(
            passwordHash = properties["password"]?.let { "hashed:$it" } ?: user.passwordHash,
            role = properties["role"]?.let { UserRole.valueOf(it.uppercase()) } ?: user.role,
            isActive = properties["isActive"]?.toBoolean() ?: user.isActive
        )
        userRepository.save(updatedUser)
        sendJsonResponse(exchange, 200, userToJson(updatedUser))
    }

    fun handleDeleteUser(exchange: HttpExchange, id: UUID) {
        if (userRepository.deleteById(id)) {
            exchange.sendResponseHeaders(204, -1)
        } else {
            sendJsonResponse(exchange, 404, """{"error":"User not found"}""")
        }
        exchange.close()
    }

    // --- Utility methods scoped to the controller ---
    private fun sendJsonResponse(exchange: HttpExchange, statusCode: Int, jsonPayload: String) {
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        val bytes = jsonPayload.toByteArray()
        exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun userToJson(user: User): String {
        return """{"id":"${user.id}","email":"${user.email}","role":"${user.role}","isActive":${user.isActive},"createdAt":"${user.createdAt}"}"""
    }

    private fun parseQueryParameters(query: String?): Map<String, String> {
        return query?.split('&')?.mapNotNull {
            val parts = it.split('=', limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }?.toMap() ?: emptyMap()
    }
    
    private fun parseJsonToMap(json: String): Map<String, String> {
        return json.trim().removeSurrounding("{", "}").split(",").associate {
            val (key, value) = it.split(":", limit = 2)
            key.trim().removeSurrounding("\"") to value.trim().removeSurrounding("\"")
        }
    }
}

// --- Main Application Entry Point ---
fun main() {
    val userRepository = InMemoryUserRepository()
    val userController = UserController(userRepository)
    val userPathRegex = Regex("^/users/([a-fA-F0-9\\-]+)$")

    val server = HttpServer.create(InetSocketAddress(8080), 0)
    println("Server starting on http://localhost:8080")

    server.createContext("/") { exchange ->
        val path = exchange.requestURI.path
        val method = exchange.requestMethod

        val matchResult = userPathRegex.find(path)

        try {
            when (method) {
                "GET" -> when {
                    path == "/users" -> userController.handleGetUsers(exchange)
                    matchResult != null -> userController.handleGetUserById(exchange, UUID.fromString(matchResult.groupValues[1]))
                    else -> exchange.sendResponseHeaders(404, -1)
                }
                "POST" -> if (path == "/users") userController.handleCreateUser(exchange) else exchange.sendResponseHeaders(404, -1)
                "PUT", "PATCH" -> if (matchResult != null) userController.handleUpdateUser(exchange, UUID.fromString(matchResult.groupValues[1])) else exchange.sendResponseHeaders(404, -1)
                "DELETE" -> if (matchResult != null) userController.handleDeleteUser(exchange, UUID.fromString(matchResult.groupValues[1])) else exchange.sendResponseHeaders(404, -1)
                else -> exchange.sendResponseHeaders(405, -1) // Method Not Allowed
            }
        } catch (e: Exception) {
            println("Error processing request: ${e.message}")
            exchange.sendResponseHeaders(500, -1)
        } finally {
            exchange.close()
        }
    }
    server.start()
}