package com.example.variation2

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.javalin.Javalin
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

// --- DOMAIN MODELS ---
enum class UserRole { ADMIN, USER }
enum class PublicationStatus { DRAFT, PUBLISHED }

data class UserEntity(
    val id: UUID,
    val email: String,
    val passwordHash: String,
    val role: UserRole,
    val isActive: Boolean,
    val createdAt: Timestamp
)

data class PostEntity(
    val id: UUID,
    val authorId: UUID,
    val postTitle: String,
    val postContent: String,
    val status: PublicationStatus
)

// --- DTOs ---
data class CreateUserRequest(
    val emailAddress: String,
    val secret: String, // Renamed from password
    val phoneNumber: String,
    val role: UserRole = UserRole.USER
)

// --- MOCK DATA STORE ---
object Database {
    val users = ConcurrentHashMap<UUID, UserEntity>()
    val posts = ConcurrentHashMap<UUID, PostEntity>()
}

// --- CONTROLLERS (OOP Style) ---
class UserController {
    fun registerRoutes(app: Javalin) {
        app.post("/api/v1/users", this::handleCreateUser)
        app.get("/api/v1/users/{user-id}", this::handleGetUser)
    }

    fun handleCreateUser(ctx: Context) {
        val requestDto = ctx.bodyAs<CreateUserRequest>()
        validateUserCreation(requestDto) // Encapsulated validation

        val newUser = UserEntity(
            id = UUID.randomUUID(),
            email = requestDto.emailAddress,
            passwordHash = "hashed:${requestDto.secret}",
            role = requestDto.role,
            isActive = false, // Users start as inactive
            createdAt = Timestamp.from(Instant.now())
        )
        Database.users[newUser.id] = newUser
        ctx.status(HttpStatus.CREATED).json(newUser)
    }

    fun handleGetUser(ctx: Context) {
        val userId = ctx.pathParamAsClass<UUID>("user-id").get()
        val user = Database.users[userId]
        if (user != null) {
            ctx.json(user)
        } else {
            ctx.status(HttpStatus.NOT_FOUND)
        }
    }

    private fun validateUserCreation(dto: CreateUserRequest) {
        val errors = mutableMapOf<String, List<String>>()

        if (!dto.emailAddress.contains("@")) {
            errors.computeIfAbsent("emailAddress") { mutableListOf() }.add("Must be a valid email address")
        }
        if (dto.secret.length < 10) {
            errors.computeIfAbsent("secret") { mutableListOf() }.add("Secret must be at least 10 characters long")
        }
        if (dto.phoneNumber.isBlank()) {
            errors.computeIfAbsent("phoneNumber") { mutableListOf() }.add("Phone number is a required field")
        }
        // Custom validator logic
        if (!dto.phoneNumber.matches(Regex("""^\(\d{3}\) \d{3}-\d{4}$"""))) {
             errors.computeIfAbsent("phoneNumber") { mutableListOf() }.add("Phone number must be in the format (XXX) XXX-XXXX")
        }

        if (errors.isNotEmpty()) {
            throw ValidationException(errors)
        }
    }
}

class PostController {
    fun registerRoutes(app: Javalin) {
        app.post("/api/v1/posts", this::handleCreatePost)
    }

    fun handleCreatePost(ctx: Context) {
        val newPost = ctx.bodyValidator<PostEntity>()
            .check({ it.postTitle.length in 5..100 }, "Title must be between 5 and 100 characters")
            .check({ it.postContent.isNotEmpty() }, "Content cannot be empty")
            .check({ Database.users.containsKey(it.authorId) }, "Author does not exist")
            .get()
            .copy(id = UUID.randomUUID(), status = PublicationStatus.DRAFT)

        Database.posts[newPost.id] = newPost
        ctx.status(HttpStatus.CREATED).json(newPost)
    }
}

// --- APPLICATION BOOTSTRAP ---
object Application {
    @JvmStatic
    fun main(args: Array<String>) {
        val app = Javalin.create { config ->
            config.jsonMapper(JavalinJackson().updateMapper {
                it.registerModule(JavaTimeModule())
                it.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            })
            config.router.contextPath = "/app"
        }.start(7070)

        // Register global error handler
        app.exception(ValidationException::class.java) { e, ctx ->
            ctx.status(HttpStatus.BAD_REQUEST).json(mapOf("validation_errors" to e.errors))
        }

        // Instantiate and register controllers
        UserController().registerRoutes(app)
        PostController().registerRoutes(app)

        println("Server running at http://localhost:7070/app")
    }
}