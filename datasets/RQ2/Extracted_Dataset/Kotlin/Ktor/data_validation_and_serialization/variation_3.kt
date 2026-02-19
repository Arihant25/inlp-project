package com.example.variation3

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.*

// --- Gradle Dependencies (build.gradle.kts) ---
// Same as Variation 1

// --- Domain Model / DTO (Combined for simplicity) ---
enum class UserRole { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }

@Serializable
data class User(
    val id: UUID,
    val email: String,
    val passwordHash: String,
    val role: UserRole,
    val isActive: Boolean,
    val createdAt: Instant
)

@Serializable
data class Post(
    val id: UUID,
    val userId: UUID,
    val title: String,
    val content: String,
    val status: PostStatus
)

// Using a single request object for simplicity
@Serializable
data class UserPostRequest(
    val email: String,
    val password: String,
    val phone: String,
    val role: UserRole = UserRole.USER
)

@Serializable
data class GenericError(val message: String)

// --- Main Application Setup ---
fun main() {
    embeddedServer(Netty, port = 8082, host = "0.0.0.0", module = Application::pragmaticModule).start(wait = true)
}

fun Application.pragmaticModule() {
    // In-memory storage
    val userStorage = mutableMapOf<UUID, User>()

    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        })
        jackson(ContentType.Application.Xml) {
            registerModule(JavaTimeModule())
            enable(SerializationFeature.INDENT_OUTPUT)
        }
    }

    install(StatusPages) {
        // Catch validation errors from require()
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, GenericError(cause.message ?: "Invalid input"))
        }
        // Catch malformed JSON/XML or type conversion errors
        exception<com.fasterxml.jackson.core.JsonParseException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, GenericError("Malformed XML: ${cause.message}"))
        }
        exception<kotlinx.serialization.SerializationException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, GenericError("Malformed JSON: ${cause.message}"))
        }
    }

    routing {
        route("/v3") {
            post("/users") {
                val req = call.receive<UserPostRequest>()

                // --- Inline Validation ---
                require(req.email.contains('@') && req.email.length > 5) { "A valid email is required." }
                require(req.password.length >= 8) { "Password must be at least 8 characters." }
                require(req.phone.matches("^\\+?[1-9]\\d{1,14}\$".toRegex())) { "A valid E.164 phone number is required." }

                val newUser = User(
                    id = UUID.randomUUID(),
                    email = req.email,
                    passwordHash = Base64.getEncoder().encodeToString(req.password.toByteArray()),
                    role = req.role,
                    isActive = false, // e.g., requires email verification
                    createdAt = Instant.now()
                )
                userStorage[newUser.id] = newUser
                call.respond(HttpStatusCode.Created, newUser)
            }

            post("/posts") {
                // Example of manual type coercion and validation
                val postTitle = call.request.queryParameters["title"]
                val postContent = call.receiveText()
                val userIdStr = call.request.header("X-User-ID")

                require(!userIdStr.isNullOrBlank()) { "Header X-User-ID is required." }
                val userId = try {
                    UUID.fromString(userIdStr)
                } catch (e: Exception) {
                    throw IllegalArgumentException("X-User-ID is not a valid UUID.")
                }

                require(!postTitle.isNullOrBlank() && postTitle.length <= 100) {
                    "Query parameter 'title' is required and must be 100 characters or less."
                }
                require(postContent.isNotBlank() && postContent.length > 10) {
                    "Post content in body must be longer than 10 characters."
                }

                val newPost = Post(
                    id = UUID.randomUUID(),
                    userId = userId,
                    title = postTitle,
                    content = postContent,
                    status = PostStatus.DRAFT
                )
                call.respond(HttpStatusCode.Created, newPost)
            }
        }
    }
}