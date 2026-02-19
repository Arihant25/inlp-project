package com.example.variation3

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.callid.*
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
@Serializable
data class User(
    val id: String, val email: String, val password_hash: String,
    val role: Role, val is_active: Boolean, val created_at: String
)

@Serializable
data class Post(
    val id: String, val user_id: String, val title: String,
    val content: String, val status: Status
)

enum class Role { ADMIN, USER }
enum class Status { DRAFT, PUBLISHED }

// --- Main Entry Point ---
fun main(args: Array<String>): Unit = io.ktor.server.cio.EngineMain.main(args)

// --- Application Module using Extension Functions ---
fun Application.module() {
    configureSerialization()
    configureLogging()
    configureCors()
    configureRateLimiting()
    configureCustomHeaders()
    configureErrorHandling()
    configureRouting()
}

// --- Middleware Configurations via Extension Functions ---

private fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json()
    }
}

private fun Application.configureLogging() {
    install(CallId) {
        header(HttpHeaders.XRequestId)
        verify { callId: String -> callId.isNotEmpty() }
    }
    install(CallLogging) {
        level = Level.INFO
        callIdMdc("call-id")
    }
}

private fun Application.configureCors() {
    install(CORS) {
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowHeader(HttpHeaders.AccessControlAllowOrigin)
        anyHost()
    }
}

private fun Application.configureRateLimiting() {
    install(RateLimit) {
        global {
            rate = 10
            period = 10.seconds
        }
    }
}

private fun Application.configureCustomHeaders() {
    val customHeaderPlugin = createApplicationPlugin("CustomHeaderPlugin") {
        onCall { call ->
            call.response.header("X-Content-Type-Options", "nosniff")
        }
    }
    install(customHeaderPlugin)
}

private fun Application.configureErrorHandling() {
    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, status ->
            call.respondText(text = "404: Page Not Found", status = status)
        }
        exception<Throwable> { call, cause ->
            log.error("Caught exception: ${cause.message}", cause)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "An internal error has occurred."))
        }
    }
}

private fun Application.configureRouting() {
    val dbUser = User(
        id = UUID.randomUUID().toString(), email = "user@domain.com", password_hash = "...",
        role = Role.USER, is_active = true, created_at = Clock.System.now().toString()
    )

    routing {
        get("/") {
            call.respondText("Welcome to the API")
        }
        route("/v1") {
            get("/user") {
                call.respond(dbUser)
            }
            get("/post") {
                val post = Post(
                    id = UUID.randomUUID().toString(), user_id = dbUser.id, title = "Hello Ktor",
                    content = "This is a post about Ktor extensions.", status = Status.PUBLISHED
                )
                call.respond(post)
            }
            get("/fail") {
                throw IllegalStateException("This endpoint is designed to fail.")
            }
        }
    }
}