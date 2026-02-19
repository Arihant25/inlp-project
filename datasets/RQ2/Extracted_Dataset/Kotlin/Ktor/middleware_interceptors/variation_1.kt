package com.example.variation1

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import org.slf4j.event.Level
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

// --- Domain Schema ---

enum class UserRole { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }

@Serializable
data class User(
    val id: String,
    val email: String,
    val password_hash: String,
    val role: UserRole,
    val is_active: Boolean,
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

@Serializable
data class ErrorResponse(val message: String, val code: Int)

// --- Mock Data ---
val mockUser = User(
    id = UUID.randomUUID().toString(),
    email = "admin@example.com",
    password_hash = "hashed_password_value",
    role = UserRole.ADMIN,
    is_active = true,
    created_at = Clock.System.now().toString()
)

// --- Main Application Entry Point ---
fun main() {
    embeddedServer(CIO, port = 8080, host = "0.0.0.0") {
        appModule()
    }.start(wait = true)
}

// --- Ktor Application Module ---
fun Application.appModule() {
    // 1. Error Handling Middleware
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            val statusCode = when (cause) {
                is IllegalArgumentException -> HttpStatusCode.BadRequest
                else -> HttpStatusCode.InternalServerError
            }
            call.respond(statusCode, ErrorResponse(cause.message ?: "An unknown error occurred", statusCode.value))
        }
    }

    // 2. Request Logging Middleware
    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path().startsWith("/") }
        format { call ->
            val status = call.response.status()
            val httpMethod = call.request.httpMethod.value
            val userAgent = call.request.headers["User-Agent"]
            "Status: $status, HTTP method: $httpMethod, Path: ${call.request.path()}, User-Agent: $userAgent"
        }
    }

    // 3. CORS Handling Middleware
    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        anyHost() // In production, you should restrict this to specific domains.
    }

    // 4. Rate Limiting Middleware
    install(RateLimit) {
        register(RateLimitName("public")) {
            rate = 5
            period = 60.seconds
        }
    }

    // 5. Request/Response Transformation (Custom Header) Middleware
    val addVersionHeaderPlugin = createApplicationPlugin(name = "AddVersionHeaderPlugin") {
        onCallRespond { call ->
            call.response.headers.append("X-App-Version", "1.0.0")
        }
    }
    install(addVersionHeaderPlugin)

    // Standard Content Negotiation for JSON
    install(ContentNegotiation) {
        json()
    }

    // --- Routing ---
    routing {
        get("/") {
            call.respondText("Hello, Ktor!")
        }

        route("/api/v1") {
            rateLimit(RateLimitName("public")) {
                get("/user") {
                    call.respond(HttpStatusCode.OK, mockUser)
                }
                get("/posts") {
                    val posts = listOf(
                        Post(
                            id = UUID.randomUUID().toString(),
                            user_id = mockUser.id,
                            title = "Ktor is Awesome",
                            content = "Here is why Ktor is great for building APIs.",
                            status = PostStatus.PUBLISHED
                        )
                    )
                    call.respond(HttpStatusCode.OK, posts)
                }
            }
            get("/error") {
                throw IllegalArgumentException("This is a simulated bad request.")
            }
            get("/fatal-error") {
                throw Exception("This is a simulated server error.")
            }
        }
    }
}