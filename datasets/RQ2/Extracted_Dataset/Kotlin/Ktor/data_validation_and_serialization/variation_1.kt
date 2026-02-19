package com.example.variation1

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
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
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.*

// --- Gradle Dependencies (build.gradle.kts) ---
// implementation("io.ktor:ktor-server-core-jvm:$ktor_version")
// implementation("io.ktor:ktor-server-netty-jvm:$ktor_version")
// implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktor_version")
// implementation("io.ktor:ktor-server-status-pages-jvm:$ktor_version")
// implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktor_version")
// implementation("io.ktor:ktor-serialization-jackson-jvm:$ktor_version")
// implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml:$jackson_version")
// implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jackson_version")
// implementation("ch.qos.logback:logback-classic:$logback_version")

// --- Domain Model ---
enum class UserRole { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }

data class User(
    val id: UUID,
    val email: String,
    val passwordHash: String,
    val role: UserRole,
    val isActive: Boolean,
    val createdAt: Instant
)

data class Post(
    val id: UUID,
    val userId: UUID,
    val title: String,
    val content: String,
    val status: PostStatus
)

// --- Data Transfer Objects (DTOs) ---
@Serializable
data class UserCreateRequest(
    val email: String,
    val password: String,
    val phone: String, // Added for phone validation
    val role: UserRole = UserRole.USER
)

@Serializable
data class PostCreateRequest(
    val userId: String,
    val title: String,
    val content: String
)

@Serializable
data class ErrorResponse(val errors: List<String>)

// --- Custom Exception for Validation ---
class ValidationException(val messages: List<String>) : IllegalArgumentException("Validation failed")

// --- Validation Logic (Functional Approach) ---
private val EMAIL_REGEX = "^[A-Za-z0-9+_.-]+@(.+)\$".toRegex()
private val PHONE_REGEX = "^\\+?[1-9]\\d{1,14}\$".toRegex()

fun validateUserCreate(dto: UserCreateRequest) {
    val errors = mutableListOf<String>()
    if (!EMAIL_REGEX.matches(dto.email)) {
        errors.add("Invalid email format.")
    }
    if (dto.password.length < 8) {
        errors.add("Password must be at least 8 characters long.")
    }
    if (!PHONE_REGEX.matches(dto.phone)) {
        errors.add("Invalid phone number format. E.g., +12223334444")
    }
    if (errors.isNotEmpty()) {
        throw ValidationException(errors)
    }
}

fun validatePostCreate(dto: PostCreateRequest) {
    val errors = mutableListOf<String>()
    if (dto.title.isBlank()) {
        errors.add("Title cannot be empty.")
    }
    try {
        UUID.fromString(dto.userId)
    } catch (e: IllegalArgumentException) {
        errors.add("Invalid userId format. Must be a UUID.")
    }
    if (errors.isNotEmpty()) {
        throw ValidationException(errors)
    }
}

// --- Ktor Extension for Validated DTO Reception ---
suspend inline fun <reified T : Any> ApplicationCall.receiveAndValidate(validator: (T) -> Unit): T {
    val dto = receive<T>()
    validator(dto)
    return dto
}

// --- Main Application Setup ---
fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::functionalModule).start(wait = true)
}

fun Application.functionalModule() {
    // In-memory storage
    val userStorage = mutableMapOf<UUID, User>()

    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
        })
        jackson(ContentType.Application.Xml) {
            registerKotlinModule()
            registerModule(JavaTimeModule())
            enable(SerializationFeature.INDENT_OUTPUT)
        }
    }

    install(StatusPages) {
        exception<ValidationException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.messages))
        }
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(listOf(cause.localizedMessage ?: "Unknown error")))
        }
    }

    configureRouting(userStorage)
}

// --- Routing (Functional Extension) ---
fun Application.configureRouting(userStorage: MutableMap<UUID, User>) {
    routing {
        route("/v1/users") {
            post {
                val request = call.receiveAndValidate(::validateUserCreate)
                val newUser = User(
                    id = UUID.randomUUID(),
                    email = request.email,
                    passwordHash = "hashed_${request.password}", // In production, use a real hashing library
                    role = request.role,
                    isActive = true,
                    createdAt = Instant.now()
                )
                userStorage[newUser.id] = newUser
                call.respond(HttpStatusCode.Created, newUser)
            }
        }
        route("/v1/posts") {
            // This endpoint accepts and returns XML
            post("/xml") {
                val request = call.receiveAndValidate(::validatePostCreate)
                val newPost = Post(
                    id = UUID.randomUUID(),
                    userId = UUID.fromString(request.userId),
                    title = request.title,
                    content = request.content,
                    status = PostStatus.DRAFT
                )
                call.respond(HttpStatusCode.Created, newPost)
            }
        }
    }
}