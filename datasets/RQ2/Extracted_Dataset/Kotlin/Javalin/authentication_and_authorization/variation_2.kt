package com.example.variation2

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import at.favre.lib.crypto.bcrypt.BCrypt
import io.javalin.Javalin
import io.javalin.http.Context
import io.javalin.http.ForbiddenResponse
import io.javalin.http.Handler
import io.javalin.http.UnauthorizedResponse
import io.javalin.security.RouteRole
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

// --- Domain Model ---
enum class AppRole : RouteRole { ADMIN, USER, ANYONE }
enum class PostStatus { DRAFT, PUBLISHED }

data class User(
    val id: UUID, val email: String, val passwordHash: String, val role: AppRole,
    val isActive: Boolean = true, val createdAt: Instant = Instant.now()
)

data class Post(
    val id: UUID, val userId: UUID, val title: String, val content: String, val status: PostStatus
)

data class LoginCredentials(val email: String, val password: String)
data class NewPostPayload(val title: String, val content: String)

// --- Data Layer (Mocks) ---
object UserRepository {
    private val users = mutableMapOf<UUID, User>()
    fun findByEmail(email: String): User? = users.values.find { it.email == email }
    fun findById(id: UUID): User? = users[id]
    fun save(user: User) { users[user.id] = user }
    fun findAll(): List<User> = users.values.toList()
}

object PostRepository {
    private val posts = mutableMapOf<UUID, Post>()
    fun save(post: Post) { posts[post.id] = post }
    fun findAll(): List<Post> = posts.values.toList()
}

// --- Service Layer ---
class AuthService(private val userRepository: UserRepository) {
    private val algorithm = Algorithm.HMAC256("another-very-secure-secret-for-dev-2")
    private val verifier = JWT.require(algorithm).withIssuer("my-app").build()
    private val bcryptVerifier = BCrypt.verifyer()

    fun authenticate(credentials: LoginCredentials): User? {
        val user = userRepository.findByEmail(credentials.email)
        return if (user != null && user.isActive && bcryptVerifier.verify(credentials.password.toCharArray(), user.passwordHash).verified) {
            user
        } else {
            null
        }
    }

    fun createToken(user: User): String = JWT.create()
        .withIssuer("my-app")
        .withClaim("uid", user.id.toString())
        .withClaim("role", user.role.name)
        .withExpiresAt(Instant.now().plus(60, ChronoUnit.MINUTES))
        .sign(algorithm)

    fun decodeToken(token: String): DecodedJWT? = try {
        verifier.verify(token)
    } catch (e: Exception) {
        null
    }
}

// --- Controller Layer ---
class AuthController(private val authService: AuthService, private val userRepository: UserRepository) {
    fun login(ctx: Context) {
        val credentials = ctx.bodyAsClass<LoginCredentials>()
        val user = authService.authenticate(credentials)
        if (user != null) {
            val token = authService.createToken(user)
            ctx.json(mapOf("jwt_token" to token))
        } else {
            throw UnauthorizedResponse("Authentication failed")
        }
    }

    fun handleOAuthCallback(ctx: Context) {
        // Mocking a successful OAuth login by creating a token for a predefined user
        val oauthUser = userRepository.findByEmail("user@example.com") ?: throw IllegalStateException("Default user not found")
        val token = authService.createToken(oauthUser)
        ctx.json(mapOf("message" to "OAuth login successful", "jwt_token" to token))
    }
}

class PostController(private val postRepository: PostRepository) {
    fun getAllPosts(ctx: Context) {
        ctx.json(postRepository.findAll())
    }

    fun createPost(ctx: Context) {
        val currentUser = ctx.attribute<User>("currentUser") ?: throw UnauthorizedResponse()
        val payload = ctx.bodyAsClass<NewPostPayload>()
        val newPost = Post(
            id = UUID.randomUUID(),
            userId = currentUser.id,
            title = payload.title,
            content = payload.content,
            status = PostStatus.DRAFT
        )
        postRepository.save(newPost)
        ctx.status(201).json(newPost)
    }
}

class AdminController(private val userRepository: UserRepository) {
    fun listUsers(ctx: Context) {
        val users = userRepository.findAll().map { it.copy(passwordHash = "HIDDEN") }
        ctx.json(users)
    }
}

// --- Security Configuration ---
class AppAccessManager(private val authService: AuthService, private val userRepository: UserRepository) {
    fun manage(handler: Handler, ctx: Context, permittedRoles: Set<RouteRole>) {
        val userRole = getUserRole(ctx)
        if (permittedRoles.isEmpty() || permittedRoles.contains(AppRole.ANYONE) || permittedRoles.contains(userRole)) {
            handler.handle(ctx)
        } else {
            throw ForbiddenResponse()
        }
    }

    private fun getUserRole(ctx: Context): AppRole {
        val token = ctx.header("Authorization")?.substringAfter("Bearer ") ?: return AppRole.ANYONE
        val decodedJWT = authService.decodeToken(token) ?: return AppRole.ANYONE
        
        val userId = UUID.fromString(decodedJWT.getClaim("uid").asString())
        val user = userRepository.findById(userId) ?: return AppRole.ANYONE
        
        ctx.attribute("currentUser", user)
        return user.role
    }
}

// --- Application Entrypoint ---
fun main() {
    // Dependency Setup
    val userRepository = UserRepository
    val postRepository = PostRepository
    val authService = AuthService(userRepository)
    val accessManager = AppAccessManager(authService, userRepository)
    val authController = AuthController(authService, userRepository)
    val postController = PostController(postRepository)
    val adminController = AdminController(userRepository)

    // Mock Data Initialization
    val adminPass = BCrypt.withDefaults().hashToString(12, "adminpass".toCharArray())
    val userPass = BCrypt.withDefaults().hashToString(12, "userpass".toCharArray())
    userRepository.save(User(UUID.randomUUID(), "admin@example.com", adminPass, AppRole.ADMIN))
    userRepository.save(User(UUID.randomUUID(), "user@example.com", userPass, AppRole.USER))

    // Javalin App Setup
    val app = Javalin.create { config ->
        config.accessManager(accessManager::manage)
        config.http.defaultContentType = "application/json"
    }

    app.routes {
        path("/v1") {
            path("/auth") {
                post("/login", authController::login, setOf(AppRole.ANYONE))
                get("/oauth/callback", authController::handleOAuthCallback, setOf(AppRole.ANYONE))
            }
            path("/posts") {
                get(postController::getAllPosts, setOf(AppRole.USER, AppRole.ADMIN))
                post(postController::createPost, setOf(AppRole.USER, AppRole.ADMIN))
            }
            path("/admin") {
                get("/users", adminController::listUsers, setOf(AppRole.ADMIN))
            }
        }
    }

    app.start(7071)
    println("Server started on http://localhost:7071")
}