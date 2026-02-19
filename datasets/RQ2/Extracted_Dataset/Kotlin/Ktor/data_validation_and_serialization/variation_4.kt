package com.example.variation4

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
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.*
import kotlin.reflect.KProperty1

// --- Gradle Dependencies (build.gradle.kts) ---
// Same as Variation 1

// --- Domain Model ---
enum class UserRole { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }

@Serializable
data class User(
    val id: UUID, val email: String, val passwordHash: String, val role: UserRole,
    val isActive: Boolean, val createdAt: Instant
)

@Serializable
data class Post(
    val id: UUID, val userId: UUID, val title: String, val content: String, val status: PostStatus
)

// --- DTOs ---
@Serializable
data class UserRegistrationPayload(
    val emailAddress: String,
    val secret: String,
    val contactPhone: String? = null
)

@Serializable
data class PostCreationPayload(
    val authorId: String,
    val headline: String,
    val body: String
)

// --- Custom Fluent Validator DSL ---
class Validator<T>(private val target: T) {
    val errors = mutableMapOf<String, MutableList<String>>()

    inner class PropertyValidator<P>(private val property: KProperty1<T, P>) {
        private val value: P = property.get(target)

        private fun addError(message: String) {
            errors.getOrPut(property.name) { mutableListOf() }.add(message)
        }

        fun isNotEmpty(): PropertyValidator<P> {
            if (value is String && value.isBlank()) addError("must not be empty")
            return this
        }

        fun minLength(len: Int): PropertyValidator<P> {
            if (value is String && value.length < len) addError("must be at least $len characters long")
            return this
        }

        fun isEmail(): PropertyValidator<P> {
            if (value is String && !value.matches("^[A-Za-z0-9+_.-]+@(.+)\$".toRegex())) {
                addError("must be a valid email address")
            }
            return this
        }

        fun isPhoneNumber(): PropertyValidator<P> {
            if (value is String? && value != null && !value.matches("^\\+?[1-9]\\d{1,14}\$".toRegex())) {
                addError("must be a valid E.164 phone number")
            }
            return this
        }

        fun isUuid(): PropertyValidator<P> {
            if (value is String) {
                try { UUID.fromString(value) } catch (e: Exception) { addError("must be a valid UUID") }
            }
            return this
        }
    }

    fun <P> check(property: KProperty1<T, P>): PropertyValidator<P> {
        return PropertyValidator(property)
    }
}

fun <T> validate(target: T, block: Validator<T>.() -> Unit): Map<String, List<String>> {
    val validator = Validator(target)
    validator.block()
    return validator.errors
}

// --- Main Application Setup ---
fun main() {
    embeddedServer(Netty, port = 8083, host = "0.0.0.0", module = Application::dslModule).start(wait = true)
}

fun Application.dslModule() {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
        })
    }

    routing {
        route("/v4") {
            post("/register") {
                val payload = call.receive<UserRegistrationPayload>()

                val validationErrors = validate(payload) {
                    check(UserRegistrationPayload::emailAddress).isNotEmpty().isEmail()
                    check(UserRegistrationPayload::secret).isNotEmpty().minLength(10)
                    check(UserRegistrationPayload::contactPhone).isPhoneNumber()
                }

                if (validationErrors.isNotEmpty()) {
                    call.respond(HttpStatusCode.UnprocessableEntity, mapOf("validation_errors" to validationErrors))
                    return@post
                }

                val newUser = User(
                    id = UUID.randomUUID(),
                    email = payload.emailAddress,
                    passwordHash = "hashed:${payload.secret}",
                    role = UserRole.USER,
                    isActive = true,
                    createdAt = Instant.now()
                )
                call.respond(HttpStatusCode.Created, newUser)
            }

            post("/articles") {
                val payload = call.receive<PostCreationPayload>()

                val validationErrors = validate(payload) {
                    check(PostCreationPayload::authorId).isNotEmpty().isUuid()
                    check(PostCreationPayload::headline).isNotEmpty().minLength(5)
                    check(PostCreationPayload::body).isNotEmpty()
                }

                if (validationErrors.isNotEmpty()) {
                    call.respond(HttpStatusCode.UnprocessableEntity, mapOf("validation_errors" to validationErrors))
                    return@post
                }

                val newPost = Post(
                    id = UUID.randomUUID(),
                    userId = UUID.fromString(payload.authorId),
                    title = payload.headline,
                    content = payload.body,
                    status = PostStatus.PUBLISHED
                )
                call.respond(HttpStatusCode.Created, newPost)
            }
        }
    }
}