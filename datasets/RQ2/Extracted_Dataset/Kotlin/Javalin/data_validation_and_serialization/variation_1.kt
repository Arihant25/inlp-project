package com.example.variation1

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.javalin.Javalin
import io.javalin.apibuilder.ApiBuilder.*
import io.javalin.http.BadRequestResponse
import io.javalin.http.Context
import io.javalin.http.HttpStatus
import io.javalin.json.JavalinJackson
import io.javalin.validation.ValidationException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// --- DEPENDENCIES (for build.gradle.kts) ---
// implementation("io.javalin:javalin:6.1.3")
// implementation("org.slf4j:slf4j-simple:2.0.13")
// implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")
// implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.1")
// implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml:2.17.1")

// --- DOMAIN MODELS ---
enum class Role { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }

data class User(
    val id: UUID,
    val email: String,
    val passwordHash: String,
    val role: Role,
    val isActive: Boolean,
    val createdAt: Timestamp
)

data class Post(
    val id: UUID,
    val userId: UUID,
    val title: String,
    val content: String,
    val status: PostStatus
)

// --- DTOs for validation ---
data class UserCreateDto(
    val email: String?,
    val password: String?,
    val phone: String?, // Added for phone validation
    val role: String?
)

// --- MOCK DATABASE ---
val usersDb = ConcurrentHashMap<UUID, User>()
val postsDb = ConcurrentHashMap<UUID, Post>()

// --- XML MAPPER ---
val xmlMapper = XmlMapper().apply {
    registerModule(JavaTimeModule())
    disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
}

// --- MAIN APPLICATION ---
fun main() {
    val app = Javalin.create { config ->
        // Configure default JSON mapper
        config.jsonMapper(JavalinJackson().updateMapper {
            it.registerModule(JavaTimeModule())
            it.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        })
    }.apply {
        // --- ERROR HANDLING ---
        exception(ValidationException::class.java) { e, ctx ->
            ctx.status(HttpStatus.UNPROCESSABLE_CONTENT)
            ctx.json(mapOf("errors" to e.errors))
        }
        exception(BadRequestResponse::class.java) { e, ctx ->
            ctx.status(HttpStatus.BAD_REQUEST)
            ctx.json(mapOf("error" to (e.message ?: "Invalid request body")))
        }
    }.start(7070)

    println("Server started at http://localhost:7070")

    // --- ROUTING (Functional Style) ---
    app.routes {
        path("/users") {
            post(::createUserHandler)
            path("/{id}") {
                get(::getUserHandler)
                path("/xml") {
                    get(::getUserAsXmlHandler)
                }
            }
        }
        path("/posts") {
            post(::createPostHandler)
        }
    }
}

// --- HANDLERS (Top-level functions) ---
fun createUserHandler(ctx: Context) {
    val dto = ctx.bodyValidator<UserCreateDto>()
        .check({ it.email != null && !it.email.isNullOrBlank() }, "Email is required")
        .check({ it.email?.matches(Regex("^[\\w-.]+@([\\w-]+\\.)+[\\w-]{2,4}$")) ?: false }, "Invalid email format")
        .check({ it.password != null && it.password.length >= 8 }, "Password is required and must be at least 8 characters")
        .check({ it.phone != null }, "Phone number is required")
        // Custom inline validator for phone number
        .check({ it.phone?.matches(Regex("^\\+?[1-9]\\d{1,14}$")) ?: false }, "Invalid phone number format (E.164 expected)")
        .get()

    // Type conversion/coercion for Enum
    val role = ctx.formParamValidator("role")
        .check({ it.isNullOrBlank() || enumValues<Role>().any { e -> e.name == it } }, "Invalid role specified")
        .getOrNull()?.let { Role.valueOf(it) } ?: Role.USER

    val newUser = User(
        id = UUID.randomUUID(),
        email = dto.email!!,
        passwordHash = "hashed_${dto.password}", // In real app, use a proper hashing library
        role = role,
        isActive = true,
        createdAt = Timestamp.from(Instant.now())
    )

    usersDb[newUser.id] = newUser
    ctx.status(HttpStatus.CREATED).json(newUser)
}

fun getUserHandler(ctx: Context) {
    val userId = ctx.pathParamValidator<UUID>("id").get()
    val user = usersDb[userId]
    if (user != null) {
        ctx.json(user)
    } else {
        ctx.status(HttpStatus.NOT_FOUND).json(mapOf("message" to "User not found"))
    }
}

fun getUserAsXmlHandler(ctx: Context) {
    val userId = ctx.pathParamValidator<UUID>("id").get()
    val user = usersDb[userId]
    if (user != null) {
        ctx.contentType("application/xml").result(xmlMapper.writeValueAsString(user))
    } else {
        ctx.status(HttpStatus.NOT_FOUND)
    }
}

fun createPostHandler(ctx: Context) {
    // Type coercion from string to UUID is handled automatically
    val validatedPost = ctx.bodyValidator<Post>()
        .check({ it.title.isNotBlank() }, "Title cannot be empty")
        .check({ it.content.isNotBlank() }, "Content cannot be empty")
        .check({ usersDb.containsKey(it.userId) }, "User with specified user_id does not exist")
        .get()

    val newPost = validatedPost.copy(id = UUID.randomUUID()) // Create a new post with a generated ID
    postsDb[newPost.id] = newPost
    ctx.status(HttpStatus.CREATED).json(newPost)
}