package com.example.variation3

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.javalin.Javalin
import io.javalin.http.Context
import io.javalin.http.HttpStatus
import io.javalin.json.JavalinJackson
import java.time.Instant
import java.util.UUID
import kotlin.collections.set

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

data class UserCreationPayload(val email: String, val password: String, val role: Role)
data class UserUpdatePayload(val email: String?, val role: Role?, val is_active: Boolean?)

// --- Data Access Layer (Mock) ---

object UserDAO {
    private val users = mutableMapOf<UUID, User>()

    init {
        val user1 = User(UUID.randomUUID(), "test1@dev.com", "hash1", Role.USER, true, Instant.now())
        val user2 = User(UUID.randomUUID(), "test2@dev.com", "hash2", Role.ADMIN, false, Instant.now())
        users[user1.id] = user1
        users[user2.id] = user2
    }

    fun getById(id: UUID): User? = users[id]
    fun getAll(): List<User> = users.values.toList()
    fun save(user: User) { users[user.id] = user }
    fun delete(id: UUID) { users.remove(id) }
    fun existsByEmail(email: String): Boolean = users.values.any { it.email == email }
}

// --- Modular Routing Interface ---

interface RouteModule {
    fun addRoutes(app: Javalin)
}

// --- User Feature Module ---

class UserRoutes : RouteModule {

    override fun addRoutes(app: Javalin) {
        app.routes {
            app.post("/users", this::createUser)
            app.get("/users", this::listAllUsers)
            app.get("/users/{id}", this::getUser)
            app.put("/users/{id}", this::updateUser)
            app.delete("/users/{id}", this::deleteUser)
        }
    }

    private fun createUser(ctx: Context) {
        val payload = ctx.bodyAsClass<UserCreationPayload>()
        if (UserDAO.existsByEmail(payload.email)) {
            ctx.status(HttpStatus.CONFLICT).json(mapOf("error" to "Email already in use"))
            return
        }
        val newUser = User(
            id = UUID.randomUUID(),
            email = payload.email,
            password_hash = payload.password.reversed(), // Dummy hash
            role = payload.role,
            is_active = true,
            created_at = Instant.now()
        )
        UserDAO.save(newUser)
        ctx.status(HttpStatus.CREATED).json(newUser)
    }

    private fun listAllUsers(ctx: Context) {
        val page = ctx.queryParam("page")?.toIntOrNull() ?: 1
        val size = ctx.queryParam("size")?.toIntOrNull() ?: 15
        val roleParam = ctx.queryParam("role")
        val activeParam = ctx.queryParam("is_active")

        val users = UserDAO.getAll()
            .asSequence()
            .filter { roleParam == null || it.role.name.equals(roleParam, ignoreCase = true) }
            .filter { activeParam == null || it.is_active.toString() == activeParam }
            .drop((page - 1) * size)
            .take(size)
            .toList()
        ctx.json(users)
    }

    private fun getUser(ctx: Context) {
        val userId = UUID.fromString(ctx.pathParam("id"))
        val user = UserDAO.getById(userId)
        if (user != null) {
            ctx.json(user)
        } else {
            ctx.status(HttpStatus.NOT_FOUND)
        }
    }

    private fun updateUser(ctx: Context) {
        val userId = UUID.fromString(ctx.pathParam("id"))
        val existingUser = UserDAO.getById(userId) ?: run {
            ctx.status(HttpStatus.NOT_FOUND)
            return
        }
        val payload = ctx.bodyAsClass<UserUpdatePayload>()
        val updatedUser = existingUser.copy(
            email = payload.email ?: existingUser.email,
            role = payload.role ?: existingUser.role,
            is_active = payload.is_active ?: existingUser.is_active
        )
        UserDAO.save(updatedUser)
        ctx.json(updatedUser)
    }

    private fun deleteUser(ctx: Context) {
        val userId = UUID.fromString(ctx.pathParam("id"))
        UserDAO.delete(userId)
        ctx.status(HttpStatus.NO_CONTENT)
    }
}

// --- Application Entrypoint ---

fun main() {
    val app = Javalin.create { config ->
        config.jsonMapper(JavalinJackson.create {
            it.registerModule(JavaTimeModule())
            it.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        })
        config.showJavalinBanner = false
    }

    // Register all route modules
    val modules = listOf(UserRoutes())
    modules.forEach { it.addRoutes(app) }

    app.start(7072)
    println("Modular style server is listening on port 7072")
}