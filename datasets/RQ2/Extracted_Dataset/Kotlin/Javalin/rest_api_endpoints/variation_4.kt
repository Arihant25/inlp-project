package com.example.variation4

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.javalin.Javalin
import io.javalin.apibuilder.ApiBuilder.*
import io.javalin.http.Context
import io.javalin.http.HttpStatus
import io.javalin.json.JavalinJackson
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

data class CreateUserDto(val email: String, val password: String, val role: Role)
data class UpdateUserDto(val email: String?, val role: Role?, val is_active: Boolean?)

// --- Service Layer (Handles Business Logic) ---

class UserService {
    private val users = ConcurrentHashMap<UUID, User>()

    init {
        // Seed data
        val initialUser = User(UUID.randomUUID(), "initial.user@system.com", "pwhash", Role.USER, true, Instant.now())
        users[initialUser.id] = initialUser
    }

    fun create(dto: CreateUserDto): Result<User> {
        if (users.values.any { it.email == dto.email }) {
            return Result.failure(IllegalArgumentException("Email already exists"))
        }
        val user = User(
            id = UUID.randomUUID(),
            email = dto.email,
            password_hash = "hashed:${dto.password}",
            role = dto.role,
            is_active = true,
            created_at = Instant.now()
        )
        users[user.id] = user
        return Result.success(user)
    }

    fun findById(id: UUID): User? = users[id]

    fun findAll(page: Int, size: Int, role: Role?, isActive: Boolean?): List<User> {
        return users.values.asSequence()
            .filter { role == null || it.role == role }
            .filter { isActive == null || it.is_active == isActive }
            .sortedBy { it.created_at }
            .drop((page - 1) * size)
            .take(size)
            .toList()
    }

    fun update(id: UUID, dto: UpdateUserDto): User? {
        val currentUser = users[id] ?: return null
        val updatedUser = currentUser.copy(
            email = dto.email ?: currentUser.email,
            role = dto.role ?: currentUser.role,
            is_active = dto.is_active ?: currentUser.is_active
        )
        users[id] = updatedUser
        return updatedUser
    }

    fun delete(id: UUID): Boolean = users.remove(id) != null
}

// --- API Definition via Extension Functions ---

fun Javalin.registerUserApi(userService: UserService) {
    routes {
        path("users") {
            post { ctx ->
                val dto = ctx.bodyAsClass<CreateUserDto>()
                userService.create(dto)
                    .onSuccess { user -> ctx.status(HttpStatus.CREATED).json(user) }
                    .onFailure { err -> ctx.status(HttpStatus.BAD_REQUEST).json(mapOf("error" to err.message)) }
            }
            get { ctx ->
                val users = userService.findAll(
                    page = ctx.queryParam("page", "1")!!.toInt(),
                    size = ctx.queryParam("size", "10")!!.toInt(),
                    role = ctx.queryParam("role")?.let { Role.valueOf(it.uppercase()) },
                    isActive = ctx.queryParam("is_active")?.toBoolean()
                )
                ctx.json(users)
            }
            path("{id}") {
                get { ctx ->
                    val user = userService.findById(UUID.fromString(ctx.pathParam("id")))
                    user?.let { ctx.json(it) } ?: ctx.status(HttpStatus.NOT_FOUND)
                }
                put { ctx ->
                    val id = UUID.fromString(ctx.pathParam("id"))
                    val dto = ctx.bodyAsClass<UpdateUserDto>()
                    val updatedUser = userService.update(id, dto)
                    updatedUser?.let { ctx.json(it) } ?: ctx.status(HttpStatus.NOT_FOUND)
                }
                delete { ctx ->
                    val id = UUID.fromString(ctx.pathParam("id"))
                    if (userService.delete(id)) {
                        ctx.status(HttpStatus.NO_CONTENT)
                    } else {
                        ctx.status(HttpStatus.NOT_FOUND)
                    }
                }
            }
        }
    }
}

// --- Main Application Entrypoint ---

fun main() {
    val userService = UserService()

    Javalin.create { config ->
        val jackson = jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        config.jsonMapper(JavalinJackson(jackson))
        config.requestLogger.http { ctx, ms ->
            println("${ctx.method()} ${ctx.path()} took ${ms}ms")
        }
    }.apply {
        registerUserApi(userService) // Use the extension function to set up routes
    }.start(7073)

    println("Kotlin DSL style server available at http://localhost:7073")
}