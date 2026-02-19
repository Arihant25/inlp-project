package com.example.variation2

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
import kotlin.time.Duration.Companion.minutes

// --- Domain Schema ---
@Serializable
data class User(
    val id: String,
    val email: String,
    val password_hash: String,
    val role: UserRole,
    val is_active: Boolean,
    val created_at: String
) {
    enum class UserRole { ADMIN, USER }
}

@Serializable
data class Post(
    val id: String,
    val user_id: String,
    val title: String,
    val content: String,
    val status: PostStatus
) {
    enum class PostStatus { DRAFT, PUBLISHED }
}

@Serializable
data class ApiError(val error: String)

// --- Middleware Configuration Objects (OOP Style) ---

object LoggingConfigurator {
    fun configure(app: Application) {
        app.install(CallLogging) {
            level = Level.DEBUG
            format { call -> "[${call.request.httpMethod.value}] ${call.request.uri} -> ${call.response.status()}" }
        }
    }
}

object CorsConfigurator {
    fun configure(app: Application) {
        app.install(CORS) {
            allowHost("localhost:3000")
            allowHeader(HttpHeaders.ContentType)
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
        }
    }
}

object RateLimiterConfigurator {
    val authenticatedRateLimit = RateLimitName("authenticated")
    fun configure(app: Application) {
        app.install(RateLimit) {
            register(authenticatedRateLimit) {
                rate = 100
                period = 1.minutes
            }
        }
    }
}

object ErrorHandler {
    fun configure(app: Application) {
        app.install(StatusPages) {
            exception<IllegalArgumentException> { call, cause ->
                call.respond(HttpStatusCode.BadRequest, ApiError(cause.localizedMessage))
            }
            exception<Throwable> { call, cause ->
                app.log.error("Unhandled error", cause)
                call.respond(HttpStatusCode.InternalServerError, ApiError("Internal Server Error"))
            }
        }
    }
}

object ResponseTransformer {
    private val transformPlugin = createApplicationPlugin("ResponseTransformer") {
        onCallRespond { call ->
            if (call.response.status()?.isSuccess() == true) {
                call.response.headers.append("X-Request-ID", UUID.randomUUID().toString())
            }
        }
    }

    fun configure(app: Application) {
        app.install(transformPlugin)
    }
}

// --- Main Application ---
fun main() {
    embeddedServer(CIO, port = 8080, host = "0.0.0.0", module = Application::mainModule).start(wait = true)
}

fun Application.mainModule() {
    // Install all middleware using configurator objects
    install(ContentNegotiation) { json() }
    LoggingConfigurator.configure(this)
    CorsConfigurator.configure(this)
    RateLimiterConfigurator.configure(this)
    ErrorHandler.configure(this)
    ResponseTransformer.configure(this)

    // --- Mock Data ---
    val sampleUser = User(
        id = UUID.randomUUID().toString(),
        email = "test.user@example.com",
        password_hash = "some_secure_hash",
        role = User.UserRole.USER,
        is_active = true,
        created_at = Clock.System.now().toString()
    )

    // --- Routing ---
    routing {
        route("/users") {
            rateLimit(RateLimiterConfigurator.authenticatedRateLimit) {
                get("/{id}") {
                    call.respond(sampleUser)
                }
            }
        }
        route("/posts") {
            get {
                val samplePost = Post(
                    id = UUID.randomUUID().toString(),
                    user_id = sampleUser.id,
                    title = "My First Post",
                    content = "Content goes here.",
                    status = Post.PostStatus.DRAFT
                )
                call.respond(listOf(samplePost))
            }
        }
        get("/force-error") {
            error("This is a forced internal server error.")
        }
    }
}