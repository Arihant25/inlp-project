package com.example.variation2

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

// --- Domain Schema ---

enum class UserRole { ADMIN, USER }
enum class PublicationStatus { DRAFT, PUBLISHED }

data class UserEntity(
    val id: UUID,
    var email: String,
    var passwordHash: String,
    var role: UserRole,
    var isActive: Boolean,
    val createdAt: Instant
)

data class PostEntity(
    val id: UUID,
    val userId: UUID,
    val title: String,
    val content: String,
    val status: PublicationStatus
)

// --- Data Transfer Objects ---

@Serializable
data class UserDTO(
    val id: String,
    val email: String,
    val role: String,
    val isActive: Boolean,
    val createdAt: String
)

@Serializable
data class UserCreationPayload(
    val email: String,
    val password: String,
    val role: UserRole = UserRole.USER
)

@Serializable
data class UserUpdatePayload(
    val email: String?,
    val role: UserRole?,
    val isActive: Boolean?
)

// --- Service Layer ---

class UserPersistenceService {
    private val userStorage = ConcurrentHashMap<UUID, UserEntity>()

    init {
        val adminId = UUID.randomUUID()
        userStorage[adminId] = UserEntity(adminId, "admin@example.com", "hash1", UserRole.ADMIN, true, Instant.now())
        val userId = UUID.randomUUID()
        userStorage[userId] = UserEntity(userId, "user@example.com", "hash2", UserRole.USER, true, Instant.now())
    }

    fun getAll(page: Int, size: Int, roleFilter: UserRole?, activeFilter: Boolean?): List<UserEntity> {
        return userStorage.values
            .asSequence()
            .filter { roleFilter == null || it.role == roleFilter }
            .filter { activeFilter == null || it.isActive == activeFilter }
            .sortedBy { it.createdAt }
            .drop(page * size)
            .take(size)
            .toList()
    }

    fun getById(id: UUID): UserEntity? = userStorage[id]

    fun save(payload: UserCreationPayload): UserEntity {
        val newUser = UserEntity(
            id = UUID.randomUUID(),
            email = payload.email,
            passwordHash = Base64.getEncoder().encodeToString(payload.password.toByteArray()),
            role = payload.role,
            isActive = true,
            createdAt = Instant.now()
        )
        userStorage[newUser.id] = newUser
        return newUser
    }

    fun modify(id: UUID, payload: UserUpdatePayload): UserEntity? {
        val user = userStorage[id] ?: return null
        payload.email?.let { user.email = it }
        payload.role?.let { user.role = it }
        payload.isActive?.let { user.isActive = it }
        return user
    }

    fun remove(id: UUID): Boolean = userStorage.remove(id) != null
}

// --- Controller/Resource Layer (OOP Style) ---

class UserController(private val userService: UserPersistenceService) {

    private fun UserEntity.toDTO() = UserDTO(
        id = this.id.toString(),
        email = this.email,
        role = this.role.name,
        isActive = this.isActive,
        createdAt = this.createdAt.toString()
    )

    suspend fun createUser(call: ApplicationCall) {
        val payload = call.receive<UserCreationPayload>()
        val newUser = userService.save(payload)
        call.respond(HttpStatusCode.Created, newUser.toDTO())
    }

    suspend fun listUsers(call: ApplicationCall) {
        val params = call.request.queryParameters
        val page = params["page"]?.toIntOrNull() ?: 0
        val size = params["size"]?.toIntOrNull() ?: 10
        val role = params["role"]?.let { runCatching { UserRole.valueOf(it) }.getOrNull() }
        val isActive = params["isActive"]?.toBooleanStrictOrNull()

        val users = userService.getAll(page, size, role, isActive)
        call.respond(users.map { it.toDTO() })
    }

    suspend fun getUserById(call: ApplicationCall) {
        val id = call.parameters["id"] ?: return call.respond(HttpStatusCode.BadRequest)
        val user = UUID.fromString(id).let { userService.getById(it) }
        if (user == null) {
            call.respond(HttpStatusCode.NotFound)
        } else {
            call.respond(user.toDTO())
        }
    }

    suspend fun updateUser(call: ApplicationCall) {
        val id = call.parameters["id"] ?: return call.respond(HttpStatusCode.BadRequest)
        val payload = call.receive<UserUpdatePayload>()
        val updatedUser = UUID.fromString(id).let { userService.modify(it, payload) }
        if (updatedUser == null) {
            call.respond(HttpStatusCode.NotFound)
        } else {
            call.respond(updatedUser.toDTO())
        }
    }

    suspend fun deleteUser(call: ApplicationCall) {
        val id = call.parameters["id"] ?: return call.respond(HttpStatusCode.BadRequest)
        val deleted = UUID.fromString(id).let { userService.remove(it) }
        if (deleted) {
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respond(HttpStatusCode.NotFound)
        }
    }
}

// --- Main Application Setup ---

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module).start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        json()
    }

    val userPersistenceService = UserPersistenceService()
    val userController = UserController(userPersistenceService)

    routing {
        route("/users") {
            post { userController.createUser(call) }
            get { userController.listUsers(call) }
            route("/{id}") {
                get { userController.getUserById(call) }
                put { userController.updateUser(call) }
                delete { userController.deleteUser(call) }
            }
        }
    }
}