package com.example.variation1

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap

// --- Domain Models ---

enum class Role { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }

data class User(
    val id: UUID,
    var email: String,
    var passwordHash: String,
    var role: Role,
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

// --- DTOs for API Layer ---

@Serializable
data class UserResponse(
    val id: String,
    val email: String,
    val role: String,
    val isActive: Boolean,
    val createdAt: String
)

@Serializable
data class CreateUserRequest(
    val email: String,
    val password: String,
    val role: Role = Role.USER
)

@Serializable
data class UpdateUserRequest(
    val email: String?,
    val role: Role?,
    val isActive: Boolean?
)

// --- Data to Presentation Mapping ---

fun User.toResponse(): UserResponse = UserResponse(
    id = this.id.toString(),
    email = this.email,
    role = this.role.name,
    isActive = this.isActive,
    createdAt = this.createdAt.toString()
)

// --- Service Layer (Business Logic) ---

class UserService {
    private val users = ConcurrentHashMap<UUID, User>()

    init {
        // Seed with some mock data
        val adminId = UUID.randomUUID()
        users[adminId] = User(
            id = adminId,
            email = "admin@example.com",
            passwordHash = "hashed_password_admin",
            role = Role.ADMIN,
            isActive = true,
            createdAt = Instant.now()
        )
        val userId = UUID.randomUUID()
        users[userId] = User(
            id = userId,
            email = "user@example.com",
            passwordHash = "hashed_password_user",
            role = Role.USER,
            isActive = false,
            createdAt = Instant.now().minusSeconds(86400)
        )
    }

    fun findAll(page: Int, size: Int, role: Role?, isActive: Boolean?): List<User> {
        val filteredUsers = users.values
            .filter { role == null || it.role == role }
            .filter { isActive == null || it.isActive == isActive }
            .sortedByDescending { it.createdAt }

        return filteredUsers
            .drop(page * size)
            .take(size)
    }

    fun findById(id: UUID): User? = users[id]

    fun create(request: CreateUserRequest): User {
        if (users.values.any { it.email == request.email }) {
            throw IllegalArgumentException("User with email ${request.email} already exists.")
        }
        val newUser = User(
            id = UUID.randomUUID(),
            email = request.email,
            passwordHash = "hashed_${request.password}", // In real-world, use a proper hashing library
            role = request.role,
            isActive = true,
            createdAt = Instant.now()
        )
        users[newUser.id] = newUser
        return newUser
    }

    fun update(id: UUID, request: UpdateUserRequest): User? {
        val existingUser = users[id] ?: return null
        
        request.email?.let { existingUser.email = it }
        request.role?.let { existingUser.role = it }
        request.isActive?.let { existingUser.isActive = it }
        
        users[id] = existingUser
        return existingUser
    }

    fun delete(id: UUID): Boolean = users.remove(id) != null
}

// --- Routing (Functional Style with Extension Functions) ---

fun Route.userRoutes(userService: UserService) {
    route("/users") {
        // POST /users - Create user
        post {
            try {
                val request = call.receive<CreateUserRequest>()
                val newUser = userService.create(request)
                call.respond(HttpStatusCode.Created, newUser.toResponse())
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.Conflict, mapOf("error" to e.message))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body"))
            }
        }

        // GET /users - List users with pagination and filtering
        get {
            val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0
            val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 10
            val role = call.request.queryParameters["role"]?.let { runCatching { Role.valueOf(it.uppercase()) }.getOrNull() }
            val isActive = call.request.queryParameters["isActive"]?.toBooleanStrictOrNull()

            val users = userService.findAll(page, size, role, isActive)
            call.respond(HttpStatusCode.OK, users.map { it.toResponse() })
        }

        route("/{id}") {
            // GET /users/{id} - Get user by ID
            get {
                val id = call.parameters["id"]
                val userUuid = try {
                    UUID.fromString(id)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid UUID format"))
                    return@get
                }

                val user = userService.findById(userUuid)
                if (user != null) {
                    call.respond(HttpStatusCode.OK, user.toResponse())
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "User not found"))
                }
            }

            // PUT /users/{id} - Update user
            put {
                val id = call.parameters["id"]
                val userUuid = try {
                    UUID.fromString(id)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid UUID format"))
                    return@put
                }

                try {
                    val request = call.receive<UpdateUserRequest>()
                    val updatedUser = userService.update(userUuid, request)
                    if (updatedUser != null) {
                        call.respond(HttpStatusCode.OK, updatedUser.toResponse())
                    } else {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "User not found"))
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body"))
                }
            }

            // DELETE /users/{id} - Delete user
            delete {
                val id = call.parameters["id"]
                val userUuid = try {
                    UUID.fromString(id)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid UUID format"))
                    return@delete
                }

                if (userService.delete(userUuid)) {
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "User not found"))
                }
            }
        }
    }
}

// --- Main Application ---

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        val userService = UserService()
        install(ContentNegotiation) {
            json()
        }
        routing {
            userRoutes(userService)
        }
    }.start(wait = true)
}