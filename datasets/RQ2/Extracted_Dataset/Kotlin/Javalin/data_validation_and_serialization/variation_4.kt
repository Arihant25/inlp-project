package com.example.variation4

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.javalin.Javalin
import io.javalin.http.Context
import io.javalin.http.HttpStatus
import io.javalin.json.JavalinJackson
import io.javalin.validation.BodyValidator
import io.javalin.validation.ValidationException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.reflect.KClass

// --- DEPENDENCIES (for build.gradle.kts) ---
// implementation("io.javalin:javalin:6.1.3")
// implementation("org.slf4j:slf4j-simple:2.0.13")
// implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")
// implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.1")
// implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml:2.17.1")

// --- DOMAIN & DTOs ---
enum class Role { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }

data class User(val id: UUID, val email: String, val role: Role, val isActive: Boolean, val createdAt: Timestamp)
data class Post(val id: UUID, val userId: UUID, val title: String, val content: String, val status: PostStatus)

data class UserCreateDTO(val email: String, val password: String, val phone: String)

// --- MOCK STORAGE ---
val userStore = mutableMapOf<UUID, User>()
val postStore = mutableMapOf<UUID, Post>()

// --- SERIALIZERS ---
val jsonMapper = JavalinJackson().updateMapper {
    it.registerModule(JavaTimeModule())
    it.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
}
val xmlMapper = XmlMapper().apply {
    registerModule(JavaTimeModule())
    disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
}

// --- KOTLIN EXTENSION FUNCTIONS for CONCISE HANDLERS ---
/**
 * A pragmatic extension to get and validate a request body in one go.
 */
inline fun <reified T : Any> Context.validatedBody(crossinline validation: (BodyValidator<T>) -> Unit): T {
    val validator = this.bodyValidator(T::class.java)
    validation(validator)
    return validator.get()
}

/**
 * An extension for content negotiation. Responds with JSON or XML based on Accept header.
 */
fun Context.render(payload: Any, status: HttpStatus = HttpStatus.OK) {
    val acceptHeader = this.header("Accept") ?: "application/json"
    when {
        acceptHeader.contains("application/xml") -> this.status(status).contentType("application/xml").result(xmlMapper.writeValueAsString(payload))
        else -> this.status(status).json(payload)
    }
}

// --- MAIN APP ---
fun main() {
    Javalin.create { config ->
        config.jsonMapper(jsonMapper)
        // Register a custom validator for re-use
        io.javalin.validation.JavalinValidation.register(String::class.java, "STRONG_PASSWORD") {
            it.length >= 10 && it.any(Char::isUpperCase) && it.any(Char::isDigit)
        }
    }.apply {
        // Custom, structured error response
        exception(ValidationException::class.java) { e, ctx ->
            val errorDetail = e.errors.mapValues { entry -> entry.value.joinToString() }
            val response = mapOf(
                "status" to "VALIDATION_FAILED",
                "details" to errorDetail
            )
            ctx.status(HttpStatus.UNPROCESSABLE_CONTENT).json(response)
        }
    }.routes {
        // --- User Routes ---
        Javalin.post("/users") { ctx ->
            val dto = ctx.validatedBody<UserCreateDTO> {
                it.check({ it.email.contains('@') }, "Invalid email format")
                it.check({ it.phone.isNotBlank() }, "Phone number is required")
                // Using the globally registered custom validator
                it.check("password", { p -> p.password.matchesRule("STRONG_PASSWORD") }, "Password must be at least 10 chars, with one uppercase and one digit")
            }

            val newUser = User(
                id = UUID.randomUUID(),
                email = dto.email,
                role = Role.USER,
                isActive = true,
                createdAt = Timestamp.from(Instant.now())
            )
            userStore[newUser.id] = newUser
            ctx.render(newUser, HttpStatus.CREATED)
        }

        Javalin.get("/users/{id}") { ctx ->
            val userId = ctx.pathParamAsClass<UUID>("id").get()
            userStore[userId]?.let { ctx.render(it) } ?: ctx.status(HttpStatus.NOT_FOUND)
        }

        // --- Post Routes ---
        Javalin.post("/posts") { ctx ->
            // Type conversion from JSON string to UUID/Enum is automatic
            val post = ctx.validatedBody<Post> {
                it.check({ p -> p.title.length in 3..150 }, "Title must be between 3 and 150 characters")
                it.check({ p -> userStore.containsKey(p.userId) }, "The specified user does not exist")
            }.copy(id = UUID.randomUUID()) // Assign a new ID

            postStore[post.id] = post
            ctx.render(post, HttpStatus.CREATED)
        }

    }.start(7070)

    println("Pragmatic server started at http://localhost:7070")
    println("Try: curl -X GET -H \"Accept: application/xml\" http://localhost:7070/users/{id} (after creating a user)")
}