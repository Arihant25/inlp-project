package com.example.variation2

import com.fasterxml.jackson.databind.util.StdDateFormat
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.javalin.Javalin
import io.javalin.apibuilder.ApiBuilder.*
import io.javalin.http.BadRequestResponse
import io.javalin.http.NotFoundResponse
import io.javalin.json.JavalinJackson
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// --- Domain Models & DTOs (defined at top-level for simplicity) ---

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

data class UserCreateDto(val email: String, val password: String, val role: Role = Role.USER)
data class UserUpdateDto(val email: String?, val role: Role?, val is_active: Boolean?)

// --- In-Memory Data Store (Singleton Object) ---

object UserData {
    private val userStore = ConcurrentHashMap<UUID, User>()

    init {
        val adminId = UUID.randomUUID()
        userStore[adminId] = User(adminId, "admin.user@example.com", "hashed_pw1", Role.ADMIN, true, Instant.now())
        val regularUserId = UUID.randomUUID()
        userStore[regularUserId] = User(regularUserId, "regular.user@example.com", "hashed_pw2", Role.USER, true, Instant.now().minusSeconds(10000))
    }

    fun add(user: User) { userStore[user.id] = user }
    fun get(id: UUID): User? = userStore[id]
    fun getAll(): List<User> = userStore.values.toList()
    fun update(id: UUID, user: User) { userStore[id] = user }
    fun delete(id: UUID) { userStore.remove(id) }
    fun findByEmail(email: String): User? = userStore.values.find { it.email == email }
}

// --- Main Application with Functional Route Definitions ---

fun main() {
    Javalin.create { config ->
        // Configure Jackson to handle ISO-8601 dates
        config.jsonMapper(JavalinJackson.create {
            it.registerModule(JavaTimeModule())
            it.setDateFormat(StdDateFormat().withColonInTimeZone(true))
        })
    }.routes {
        path("/users") {
            // POST /users - Create user
            post { ctx ->
                val dto = ctx.bodyAsClass<UserCreateDto>()
                if (UserData.findByEmail(dto.email) != null) {
                    throw BadRequestResponse("Email is already taken")
                }
                val newUser = User(
                    id = UUID.randomUUID(),
                    email = dto.email,
                    password_hash = "hashed_${dto.password}",
                    role = dto.role,
                    is_active = true,
                    created_at = Instant.now()
                )
                UserData.add(newUser)
                ctx.status(201).json(newUser)
            }

            // GET /users - List and filter users
            get { ctx ->
                val page = ctx.queryParamAsClass<Int>("page").getOrDefault(1)
                val size = ctx.queryParamAsClass<Int>("size").getOrDefault(20)
                val roleFilter = ctx.queryParamAsClass<Role>("role").getOrNull()
                val activeFilter = ctx.queryParamAsClass<Boolean>("is_active").getOrNull()

                val filteredUsers = UserData.getAll()
                    .filter { roleFilter == null || it.role == roleFilter }
                    .filter { activeFilter == null || it.is_active == activeFilter }
                    .drop((page - 1) * size)
                    .take(size)

                ctx.json(filteredUsers)
            }

            path("/{id}") {
                // GET /users/{id} - Get user by ID
                get { ctx ->
                    val user = UserData.get(ctx.pathParamAsClass<UUID>("id").get()) ?: throw NotFoundResponse("User not found")
                    ctx.json(user)
                }

                // PUT /users/{id} - Update user
                put { ctx ->
                    val userId = ctx.pathParamAsClass<UUID>("id").get()
                    val user = UserData.get(userId) ?: throw NotFoundResponse("User not found")
                    val dto = ctx.bodyAsClass<UserUpdateDto>()

                    val updatedUser = user.copy(
                        email = dto.email ?: user.email,
                        role = dto.role ?: user.role,
                        is_active = dto.is_active ?: user.is_active
                    )
                    UserData.update(userId, updatedUser)
                    ctx.json(updatedUser)
                }

                // DELETE /users/{id} - Delete user
                delete { ctx ->
                    val userId = ctx.pathParamAsClass<UUID>("id").get()
                    UserData.get(userId) ?: throw NotFoundResponse("User not found")
                    UserData.delete(userId)
                    ctx.status(204)
                }
            }
        }
    }.start(7071)

    println("Functional style server running on http://localhost:7071")
}