package com.example.variation1

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.javalin.Javalin
import io.javalin.apibuilder.ApiBuilder.*
import io.javalin.http.BadRequestResponse
import io.javalin.http.Context
import io.javalin.http.NotFoundResponse
import io.javalin.json.JavalinJackson
import io.javalin.validation.JavalinValidation
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// --- Domain Models & DTOs ---

enum class Role { ADMIN, USER }
enum class Status { DRAFT, PUBLISHED }

data class User(
    val id: UUID,
    val email: String,
    val password_hash: String,
    val role: Role,
    val is_active: Boolean,
    val created_at: Instant
)

data class Post(
    val id: UUID,
    val user_id: UUID,
    val title: String,
    val content: String,
    val status: Status
)

data class CreateUserRequest(val email: String, val password: String, val role: Role)
data class UpdateUserRequest(val email: String?, val role: Role?, val is_active: Boolean?)

// --- Persistence Layer (Mock) ---

class UserRepository {
    private val users = ConcurrentHashMap<UUID, User>()

    init {
        // Seed with some initial data
        val adminId = UUID.randomUUID()
        users[adminId] = User(
            id = adminId,
            email = "admin@example.com",
            password_hash = "admin_hashed",
            role = Role.ADMIN,
            is_active = true,
            created_at = Instant.now()
        )
        val userId = UUID.randomUUID()
        users[userId] = User(
            id = userId,
            email = "user@example.com",
            password_hash = "user_hashed",
            role = Role.USER,
            is_active = false,
            created_at = Instant.now().minusSeconds(86400)
        )
    }

    fun findById(id: UUID): User? = users[id]
    fun findByEmail(email: String): User? = users.values.find { it.email == email }
    fun findAll(): List<User> = users.values.toList()
    fun save(user: User): User {
        users[user.id] = user
        return user
    }
    fun delete(id: UUID): Boolean = users.remove(id) != null
}

// --- Service Layer ---

class UserService(private val userRepository: UserRepository) {
    fun createUser(request: CreateUserRequest): User {
        if (userRepository.findByEmail(request.email) != null) {
            throw BadRequestResponse("User with this email already exists.")
        }
        val newUser = User(
            id = UUID.randomUUID(),
            email = request.email,
            password_hash = "hashed_${request.password}", // Mock hashing
            role = request.role,
            is_active = true,
            created_at = Instant.now()
        )
        return userRepository.save(newUser)
    }

    fun getUserById(id: UUID): User? = userRepository.findById(id)

    fun updateUser(id: UUID, request: UpdateUserRequest): User {
        val existingUser = userRepository.findById(id) ?: throw NotFoundResponse("User not found")
        val updatedUser = existingUser.copy(
            email = request.email ?: existingUser.email,
            role = request.role ?: existingUser.role,
            is_active = request.is_active ?: existingUser.is_active
        )
        return userRepository.save(updatedUser)
    }

    fun deleteUser(id: UUID): Boolean = userRepository.delete(id)

    fun listUsers(page: Int, size: Int, role: Role?, isActive: Boolean?): List<User> {
        return userRepository.findAll()
            .asSequence()
            .filter { role == null || it.role == role }
            .filter { isActive == null || it.is_active == isActive }
            .sortedByDescending { it.created_at }
            .drop((page - 1) * size)
            .take(size)
            .toList()
    }
}

// --- Controller Layer ---

class UserController(private val userService: UserService) {
    fun createUser(ctx: Context) {
        val request = ctx.bodyValidator<CreateUserRequest>()
            .check({ it.email.contains("@") }, "Invalid email format")
            .check({ it.password.length >= 8 }, "Password must be at least 8 characters long")
            .get()
        val newUser = userService.createUser(request)
        ctx.status(201).json(newUser)
    }

    fun getUserById(ctx: Context) {
        val userId = UUID.fromString(ctx.pathParam("id"))
        val user = userService.getUserById(userId) ?: throw NotFoundResponse()
        ctx.json(user)
    }

    fun updateUser(ctx: Context) {
        val userId = UUID.fromString(ctx.pathParam("id"))
        val request = ctx.body<UpdateUserRequest>()
        val updatedUser = userService.updateUser(userId, request)
        ctx.json(updatedUser)
    }

    fun deleteUser(ctx: Context) {
        val userId = UUID.fromString(ctx.pathParam("id"))
        if (userService.deleteUser(userId)) {
            ctx.status(204)
        } else {
            throw NotFoundResponse()
        }
    }

    fun listUsers(ctx: Context) {
        val page = ctx.queryParam("page", "1")!!.toInt()
        val size = ctx.queryParam("size", "10")!!.toInt()
        val role = ctx.queryParam("role")?.let { Role.valueOf(it.uppercase()) }
        val isActive = ctx.queryParam("is_active")?.toBoolean()

        val users = userService.listUsers(page, size, role, isActive)
        ctx.json(users)
    }
}

// --- Main Application ---

fun main() {
    // Dependency Injection Setup
    val userRepository = UserRepository()
    val userService = UserService(userRepository)
    val userController = UserController(userService)

    // Jackson configuration for proper JSON handling
    val objectMapper = jacksonObjectMapper().apply {
        registerModule(JavaTimeModule())
        disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    }
    JavalinValidation.register(UUID::class.java) { UUID.fromString(it) }


    val app = Javalin.create { config ->
        config.jsonMapper(JavalinJackson(objectMapper))
        config.http.defaultContentType = "application/json"
        config.router.apiBuilder {
            path("/users") {
                post(userController::createUser)
                get(userController::listUsers)
                path("/{id}") {
                    get(userController::getUserById)
                    put(userController::updateUser)
                    delete(userController::deleteUser)
                }
            }
        }
    }.start(7070)

    println("Server started at http://localhost:7070")
}