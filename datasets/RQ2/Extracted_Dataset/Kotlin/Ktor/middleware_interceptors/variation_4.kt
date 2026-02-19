package com.example.variation4

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
import java.util.*
import kotlin.time.Duration.Companion.seconds

// --- Domain Model ---
object Domain {
    enum class UserRole { ADMIN, USER }
    enum class PostStatus { DRAFT, PUBLISHED }

    @Serializable
    data class User(
        val id: String, val email: String, val password_hash: String,
        val role: UserRole, val is_active: Boolean, val created_at: String
    )

    @Serializable
    data class Post(
        val id: String, val user_id: String, val title: String,
        val content: String, val status: PostStatus
    )

    @Serializable
    data class GenericError(val reason: String)
}

// --- Bundled Core Infrastructure Plugin ---
class CoreInfrastructureConfig {
    var appVersion: String = "0.0.1-SNAPSHOT"
    var enableDetailedLogging: Boolean = true
}

val CoreInfrastructure = createApplicationPlugin(
    name = "CoreInfrastructure",
    createConfiguration = ::CoreInfrastructureConfig
) {
    val version = pluginConfig.appVersion
    val detailedLogging = pluginConfig.enableDetailedLogging

    // 1. Install Logging
    application.install(CallLogging) {
        level = if (detailedLogging) Level.TRACE else Level.INFO
        filter { call -> call.request.path().startsWith("/api") }
    }

    // 2. Install Error Handling
    application.install(StatusPages) {
        exception<Exception> { call, cause ->
            application.log.warn("Caught unhandled exception for ${call.request.uri}", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                Domain.GenericError("An unexpected error occurred.")
            )
        }
    }

    // 3. Install Response Transformation
    onCallRespond { call ->
        call.response.headers.append("X-App-Version", version)
    }
}

// --- Main Application ---
fun main() {
    embeddedServer(CIO, port = 8080, host = "0.0.0.0", module = Application::productionModule).start(wait = true)
}

fun Application.productionModule() {
    // Install core plugins first
    install(ContentNegotiation) { json() }

    // Install the bundled infrastructure plugin
    install(CoreInfrastructure) {
        appVersion = "1.2.3-PROD"
        enableDetailedLogging = false
    }

    // Install other, more environment-specific plugins
    install(CORS) {
        allowHost("app.example.com", schemes = listOf("https"))
        allowHeader(HttpHeaders.Authorization)
        allowCredentials = true
    }

    install(RateLimit) {
        register(RateLimitName("api")) {
            rate = 20
            period = 60.seconds
        }
    }

    // Setup routing
    configureRoutes()
}

fun Application.configureRoutes() {
    val mockDataStore = mapOf(
        "user" to Domain.User(
            id = UUID.randomUUID().toString(),
            email = "prod.user@example.com",
            password_hash = "secret",
            role = Domain.UserRole.ADMIN,
            is_active = true,
            created_at = Clock.System.now().toString()
        )
    )

    routing {
        get("/") {
            call.respondText("Server is healthy.")
        }
        route("/api") {
            rateLimit(RateLimitName("api")) {
                get("/user") {
                    call.respond(mockDataStore["user"]!!)
                }
                get("/post") {
                    val post = Domain.Post(
                        id = UUID.randomUUID().toString(),
                        user_id = (mockDataStore["user"] as Domain.User).id,
                        title = "Production Post",
                        content = "This is a post from the production environment.",
                        status = Domain.PostStatus.PUBLISHED
                    )
                    call.respond(post)
                }
            }
            get("/simulate-error") {
                // This will be caught by the StatusPages in CoreInfrastructure
                Integer.parseInt("not-a-number")
            }
        }
    }
}