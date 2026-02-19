package com.arc.api.v3

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// --- 1. DOMAIN MODEL ---
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

// --- 2. DATA TRANSFER OBJECTS (DTOs) for API contracts ---
data class CreateUserRequest(val email: String, val password: String, val role: UserRole?)
data class UpdateUserRequest(val password: String?, val role: UserRole?, val isActive: Boolean?)
data class UserResponse(val id: UUID, val email: String, val role: UserRole, val isActive: Boolean, val createdAt: Instant)
data class PaginatedResponse<T>(val page: Int, val size: Int, val total: Int, val data: List<T>)

// --- 3. DATA ACCESS LAYER ---
interface UserDataAccess {
    fun getById(id: UUID): User?
    fun getAll(): Collection<User>
    fun persist(user: User): User
    fun remove(id: UUID): Boolean
    fun findByEmail(email: String): User?
}

class InMemoryUserDataAccess : UserDataAccess {
    private val userStore = ConcurrentHashMap<UUID, User>()
    init {
        listOf(
            User(UUID.randomUUID(), "admin.arch@example.com", "hash_admin", UserRole.ADMIN, true, Instant.now()),
            User(UUID.randomUUID(), "user.arch@example.com", "hash_user", UserRole.USER, false, Instant.now())
        ).forEach { persist(it) }
    }
    override fun getById(id: UUID): User? = userStore[id]
    override fun getAll(): Collection<User> = userStore.values
    override fun persist(user: User): User { userStore[user.id] = user; return user }
    override fun remove(id: UUID): Boolean = userStore.remove(id) != null
    override fun findByEmail(email: String): User? = userStore.values.find { it.email == email }
}

// --- 4. BUSINESS LOGIC/SERVICE LAYER ---
class UserService(private val userDataAccess: UserDataAccess) {
    fun createUser(request: CreateUserRequest): Result<User> {
        if (userDataAccess.findByEmail(request.email) != null) {
            return Result.failure(IllegalArgumentException("Email already exists"))
        }
        val user = User(
            id = UUID.randomUUID(),
            email = request.email,
            passwordHash = hashPassword(request.password),
            role = request.role ?: UserRole.USER,
            isActive = true,
            createdAt = Instant.now()
        )
        return Result.success(userDataAccess.persist(user))
    }

    fun findUserById(id: UUID): User? = userDataAccess.getById(id)

    fun findAllUsers(role: UserRole?, isActive: Boolean?): List<User> {
        return userDataAccess.getAll().filter {
            (role == null || it.role == role) && (isActive == null || it.isActive == isActive)
        }
    }

    fun updateUser(id: UUID, request: UpdateUserRequest): Result<User> {
        val existingUser = userDataAccess.getById(id) ?: return Result.failure(NoSuchElementException("User not found"))
        
        request.password?.let { existingUser.passwordHash = hashPassword(it) }
        request.role?.let { existingUser.role = it }
        request.isActive?.let { existingUser.isActive = it }
        
        return Result.success(userDataAccess.persist(existingUser))
    }

    fun deleteUser(id: UUID): Boolean = userDataAccess.remove(id)

    private fun hashPassword(password: String): String = "hashed_${password.reversed()}" // Dummy hashing
}

// --- 5. PRESENTATION/API HANDLER LAYER ---
class UserHttpHandler(private val userService: UserService) {
    private val userIdRegex = Regex("/users/([a-fA-F0-9\\-]+)")

    fun handle(exchange: HttpExchange) {
        try {
            val path = exchange.requestURI.path
            val method = exchange.requestMethod
            val match = userIdRegex.find(path)

            when (method) {
                "GET" -> when {
                    path == "/users" -> list(exchange)
                    match != null -> get(exchange, UUID.fromString(match.groupValues[1]))
                    else -> sendJson(exchange, 404, mapOf("error" to "Not Found"))
                }
                "POST" -> if (path == "/users") create(exchange) else sendJson(exchange, 404, mapOf("error" to "Not Found"))
                "PUT", "PATCH" -> if (match != null) update(exchange, UUID.fromString(match.groupValues[1])) else sendJson(exchange, 404, mapOf("error" to "Not Found"))
                "DELETE" -> if (match != null) delete(exchange, UUID.fromString(match.groupValues[1])) else sendJson(exchange, 404, mapOf("error" to "Not Found"))
                else -> sendJson(exchange, 405, mapOf("error" to "Method Not Allowed"))
            }
        } catch (e: Exception) {
            sendJson(exchange, 500, mapOf("error" to "Internal Server Error", "message" to e.message))
        } finally {
            exchange.close()
        }
    }

    private fun create(exchange: HttpExchange) {
        val body = JsonParser.parse(exchange.requestBody.reader().readText())
        val request = CreateUserRequest(
            email = body["email"] ?: "",
            password = body["password"] ?: "",
            role = body["role"]?.let { UserRole.valueOf(it.uppercase()) }
        )
        userService.createUser(request).onSuccess {
            sendJson(exchange, 201, it.toResponse())
        }.onFailure {
            sendJson(exchange, 409, mapOf("error" to it.message))
        }
    }

    private fun get(exchange: HttpExchange, id: UUID) {
        userService.findUserById(id)?.let {
            sendJson(exchange, 200, it.toResponse())
        } ?: sendJson(exchange, 404, mapOf("error" to "User not found"))
    }
    
    private fun list(exchange: HttpExchange) {
        val params = exchange.requestURI.query?.split('&')?.associate {
            val (k, v) = it.split('=', limit = 2); k to v
        } ?: emptyMap()
        
        val role = params["role"]?.let { UserRole.valueOf(it.uppercase()) }
        val isActive = params["is_active"]?.toBoolean()
        val page = params["page"]?.toIntOrNull() ?: 1
        val size = params["size"]?.toIntOrNull() ?: 10

        val users = userService.findAllUsers(role, isActive)
        val paginatedUsers = users.drop((page - 1) * size).take(size).map { it.toResponse() }
        
        sendJson(exchange, 200, PaginatedResponse(page, size, users.size, paginatedUsers))
    }

    private fun update(exchange: HttpExchange, id: UUID) {
        val body = JsonParser.parse(exchange.requestBody.reader().readText())
        val request = UpdateUserRequest(
            password = body["password"],
            role = body["role"]?.let { UserRole.valueOf(it.uppercase()) },
            isActive = body["is_active"]?.toBoolean()
        )
        userService.updateUser(id, request).onSuccess {
            sendJson(exchange, 200, it.toResponse())
        }.onFailure {
            sendJson(exchange, 404, mapOf("error" to it.message))
        }
    }

    private fun delete(exchange: HttpExchange, id: UUID) {
        if (userService.deleteUser(id)) {
            exchange.sendResponseHeaders(204, -1)
        } else {
            sendJson(exchange, 404, mapOf("error" to "User not found"))
        }
    }
    
    // --- Presentation Layer Helpers ---
    private fun User.toResponse() = UserResponse(id, email, role, isActive, createdAt)
    private fun sendJson(exchange: HttpExchange, code: Int, payload: Any) {
        val jsonString = JsonWriter.write(payload)
        exchange.responseHeaders.set("Content-Type", "application/json")
        val bytes = jsonString.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}

// --- 6. APPLICATION BOOTSTRAP ---
fun main() {
    val dataAccess = InMemoryUserDataAccess()
    val service = UserService(dataAccess)
    val handler = UserHttpHandler(service)

    val server = HttpServer.create(InetSocketAddress(8080), 0)
    server.createContext("/", handler::handle)
    server.start()
    println("Architectural Server is running on port 8080")
}

// --- Ultra-basic JSON utilities to avoid external libraries ---
object JsonParser {
    fun parse(json: String): Map<String, String> = json.trim().removeSurrounding("{", "}").split(",").associate {
        val parts = it.split(":", limit = 2)
        val key = parts[0].trim().removeSurrounding("\"")
        val value = parts.getOrNull(1)?.trim()?.removeSurrounding("\"") ?: ""
        key to value
    }
}
object JsonWriter {
    private fun Any?.toJsonValue(): String = when (this) {
        is String -> "\"${this.replace("\"", "\\\"")}\""
        is Number, is Boolean -> this.toString()
        is Instant -> "\"$this\""
        is UUID -> "\"$this\""
        is UserRole -> "\"$this\""
        is List<*> -> this.joinToString(",", "[", "]") { it.toJsonValue() }
        is Map<*, *> -> this.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":${v.toJsonValue()}" }
        is UserResponse -> write(this)
        is PaginatedResponse<*> -> write(this)
        else -> "null"
    }
    fun write(obj: Any): String = when (obj) {
        is UserResponse -> """{"id":${obj.id.toJsonValue()},"email":${obj.email.toJsonValue()},"role":${obj.role.toJsonValue()},"isActive":${obj.isActive.toJsonValue()},"createdAt":${obj.createdAt.toJsonValue()}}"""
        is PaginatedResponse<*> -> """{"page":${obj.page.toJsonValue()},"size":${obj.size.toJsonValue()},"total":${obj.total.toJsonValue()},"data":${obj.data.toJsonValue()}}"""
        is Map<*, *> -> obj.toJsonValue()
        else -> "{}"
    }
}