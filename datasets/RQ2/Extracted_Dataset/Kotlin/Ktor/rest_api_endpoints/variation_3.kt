package com.example.variation3

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
import kotlin.collections.set

// --- Domain Model (Unified) ---
enum class Role { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }

@Serializable
data class User(
    val id: String,
    var email: String,
    var password_hash: String,
    var role: Role,
    var is_active: Boolean,
    val created_at: String
)

@Serializable
data class Post(
    val id: String,
    val user_id: String,
    val title: String,
    val content: String,
    val status: PostStatus
)

// --- Request Payloads ---
@Serializable
data class NewUserReq(val email: String, val password: String)

@Serializable
data class PatchUserReq(val email: String?, val is_active: Boolean?)

// --- Mock Data Store (Top-level, simple) ---
val userStorage = mutableMapOf<UUID, User>()

fun main() {
    // Pre-populate some data
    val adminId = UUID.randomUUID()
    userStorage[adminId] = User(
        id = adminId.toString(),
        email = "admin@test.com",
        password_hash = "hash_admin",
        role = Role.ADMIN,
        is_active = true,
        created_at = Instant.now().toString()
    )
    val userId = UUID.randomUUID()
    userStorage[userId] = User(
        id = userId.toString(),
        email = "user@test.com",
        password_hash = "hash_user",
        role = Role.USER,
        is_active = false,
        created_at = Instant.now().minusSeconds(1000).toString()
    )

    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        install(ContentNegotiation) {
            json()
        }

        // All logic is inline within the routing block
        routing {
            route("/users") {
                // Create user (POST)
                post {
                    val req = call.receive<NewUserReq>()
                    if (userStorage.values.any { it.email == req.email }) {
                        call.respond(HttpStatusCode.Conflict, "Email already in use")
                        return@post
                    }
                    val newId = UUID.randomUUID()
                    val newUser = User(
                        id = newId.toString(),
                        email = req.email,
                        password_hash = "hashed:${req.password}",
                        role = Role.USER,
                        is_active = true,
                        created_at = Instant.now().toString()
                    )
                    userStorage[newId] = newUser
                    call.respond(HttpStatusCode.Created, newUser)
                }

                // List and search/filter users (GET)
                get {
                    val params = call.request.queryParameters
                    val page = params["page"]?.toIntOrNull() ?: 0
                    val size = params["size"]?.toIntOrNull() ?: 10
                    val roleFilter = params["role"]?.let { runCatching { Role.valueOf(it.uppercase()) }.getOrNull() }
                    val activeFilter = params["isActive"]?.toBooleanStrictOrNull()

                    val results = userStorage.values
                        .filter { user -> roleFilter == null || user.role == roleFilter }
                        .filter { user -> activeFilter == null || user.is_active == activeFilter }
                        .sortedByDescending { it.created_at }
                        .drop(page * size)
                        .take(size)
                    
                    call.respond(results)
                }

                // Get user by ID (GET)
                get("/{id}") {
                    val idStr = call.parameters["id"]
                    val uuid = try {
                        UUID.fromString(idStr)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, "Invalid ID format")
                        return@get
                    }
                    
                    val user = userStorage[uuid]
                    if (user == null) {
                        call.respond(HttpStatusCode.NotFound)
                    } else {
                        call.respond(user)
                    }
                }

                // Update user (PATCH)
                patch("/{id}") {
                    val idStr = call.parameters["id"]
                    val uuid = try {
                        UUID.fromString(idStr)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, "Invalid ID format")
                        return@patch
                    }

                    val existingUser = userStorage[uuid]
                    if (existingUser == null) {
                        call.respond(HttpStatusCode.NotFound)
                        return@patch
                    }

                    val req = call.receive<PatchUserReq>()
                    val updatedUser = existingUser.copy(
                        email = req.email ?: existingUser.email,
                        is_active = req.is_active ?: existingUser.is_active
                    )
                    userStorage[uuid] = updatedUser
                    call.respond(HttpStatusCode.OK, updatedUser)
                }

                // Delete user (DELETE)
                delete("/{id}") {
                    val idStr = call.parameters["id"]
                    val uuid = try {
                        UUID.fromString(idStr)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, "Invalid ID format")
                        return@delete
                    }

                    if (userStorage.remove(uuid) != null) {
                        call.respond(HttpStatusCode.NoContent)
                    } else {
                        call.respond(HttpStatusCode.NotFound)
                    }
                }
            }
        }
    }.start(wait = true)
}