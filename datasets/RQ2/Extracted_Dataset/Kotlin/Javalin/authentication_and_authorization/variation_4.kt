package com.example.variation4

import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.javalin.Javalin
import io.javalin.http.Context
import io.javalin.http.ForbiddenResponse
import io.javalin.http.Handler
import io.javalin.http.UnauthorizedResponse
import io.javalin.oauth.OAuth2
import io.javalin.oauth.OAuthClient
import io.javalin.oauth.OAuthProvider
import io.javalin.security.RouteRole
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

// --- Domain Model ---
enum class UserRole : RouteRole { ADMIN, USER, GUEST }
enum class PublicationStatus { DRAFT, PUBLISHED }
data class User(val id: UUID, val email: String, val passwordHash: String, val role: UserRole, val isActive: Boolean)
data class Post(val id: UUID, val userId: UUID, val title: String, val content: String, val status: PublicationStatus)
data class LoginRequest(val email: String, val password: String)

// --- Abstractions (Interfaces) ---
interface IUserRepository {
    fun findByEmail(email: String): User?
    fun findById(id: UUID): User?
    fun save(user: User)
    fun listAll(): List<User>
}
interface ITokenManager {
    fun generateToken(user: User): String
    fun validateTokenAndGetUser(token: String): User?
}
interface IPasswordHasher {
    fun hash(password: String): String
    fun verify(password: String, hash: String): Boolean
}

// --- Concrete Implementations ---
class InMemoryUserRepository : IUserRepository {
    private val userTable = ConcurrentHashMap<UUID, User>()
    override fun findByEmail(email: String): User? = userTable.values.find { it.email == email }
    override fun findById(id: UUID): User? = userTable[id]
    override fun save(user: User) { userTable[user.id] = user }
    override fun listAll(): List<User> = userTable.values.toList()
}

class BcryptPasswordHasher : IPasswordHasher {
    private val bcrypt = BCrypt.withDefaults()
    private val verifier = BCrypt.verifyer()
    override fun hash(password: String): String = bcrypt.hashToString(12, password.toCharArray())
    override fun verify(password: String, hash: String): Boolean = verifier.verify(password.toCharArray(), hash).verified
}

class JwtTokenManager(secret: String, private val userRepository: IUserRepository) : ITokenManager {
    private val algorithm: Algorithm = Algorithm.HMAC256(secret)
    private val verifier = JWT.require(algorithm).withIssuer("api.myapp.com").build()

    override fun generateToken(user: User): String = JWT.create()
        .withIssuer("api.myapp.com")
        .withClaim("userId", user.id.toString())
        .withClaim("role", user.role.name)
        .withExpiresAt(Instant.now().plus(2, ChronoUnit.HOURS))
        .sign(algorithm)

    override fun validateTokenAndGetUser(token: String): User? {
        return try {
            val decoded = verifier.verify(token)
            val userId = UUID.fromString(decoded.getClaim("userId").asString())
            userRepository.findById(userId)
        } catch (e: Exception) {
            null
        }
    }
}

// --- Application Layer (Controllers & Services) ---
class AuthenticationController(
    private val userRepository: IUserRepository,
    private val passwordHasher: IPasswordHasher,
    private val tokenManager: ITokenManager
) {
    fun handleLogin(ctx: Context) {
        val req = ctx.bodyAsClass<LoginRequest>()
        val user = userRepository.findByEmail(req.email)
        if (user != null && user.isActive && passwordHasher.verify(req.password, user.passwordHash)) {
            ctx.json(mapOf("access_token" to tokenManager.generateToken(user)))
        } else {
            throw UnauthorizedResponse("Invalid email or password")
        }
    }
}

class AuthorizationManager(private val tokenManager: ITokenManager) {
    fun manageAccess(handler: Handler, ctx: Context, permittedRoles: Set<RouteRole>) {
        val token = ctx.header("Authorization")?.removePrefix("Bearer ")
        val user = token?.let { tokenManager.validateTokenAndGetUser(it) }

        ctx.attribute("currentUser", user)
        val userRole = user?.role ?: UserRole.GUEST

        if (permittedRoles.contains(userRole)) {
            handler.handle(ctx)
        } else {
            if (user == null) throw UnauthorizedResponse()
            else throw ForbiddenResponse()
        }
    }
}

// --- DI Container ---
class DIContainer {
    private val jwtSecret = "a-very-long-and-secure-secret-for-di-variation"
    val userRepository: IUserRepository by lazy { InMemoryUserRepository() }
    val passwordHasher: IPasswordHasher by lazy { BcryptPasswordHasher() }
    val tokenManager: ITokenManager by lazy { JwtTokenManager(jwtSecret, userRepository) }
    val authController: AuthenticationController by lazy { AuthenticationController(userRepository, passwordHasher, tokenManager) }
    val authorizationManager: AuthorizationManager by lazy { AuthorizationManager(tokenManager) }
}

// --- Main Entrypoint ---
fun main() {
    val di = DIContainer()

    // Seed data
    di.userRepository.save(User(UUID.randomUUID(), "admin@example.com", di.passwordHasher.hash("adminpass"), UserRole.ADMIN, true))
    di.userRepository.save(User(UUID.randomUUID(), "user@example.com", di.passwordHasher.hash("userpass"), UserRole.USER, true))

    // OAuth2 Client Setup (using Javalin's built-in module)
    val githubClient = OAuthClient(
        provider = OAuthProvider.GITHUB,
        clientId = System.getenv("GITHUB_CLIENT_ID") ?: "mock_client_id",
        clientSecret = System.getenv("GITHUB_CLIENT_SECRET") ?: "mock_client_secret",
        callbackUrl = "http://localhost:7073/oauth/callback/github"
    )

    val app = Javalin.create { config ->
        config.accessManager(di.authorizationManager::manageAccess)
        config.http.defaultContentType = "application/json"
    }

    // --- Routes ---
    app.post("/login", di.authController::handleLogin, UserRole.GUEST)

    // OAuth2 Routes
    val oauth2 = OAuth2(app, githubClient)
    app.get("/oauth/login/github", oauth2.redirect(), UserRole.GUEST)
    app.get("/oauth/callback/github", oauth2.callback { token, client, ctx ->
        // In a real app, you'd fetch the user profile from GitHub
        // For this mock, we'll find or create a local user and issue a JWT
        val localUser = di.userRepository.findByEmail("user@example.com")!!
        val jwt = di.tokenManager.generateToken(localUser)
        ctx.json(mapOf("message" => "OAuth successful", "access_token" => jwt))
    }, UserRole.GUEST)

    // Protected Routes
    app.get("/api/posts", { ctx -> ctx.result("List of posts") }, UserRole.USER, UserRole.ADMIN)
    app.get("/api/admin/dashboard", { ctx -> ctx.result("Admin Dashboard Data") }, UserRole.ADMIN)
    app.get("/api/me", { ctx ->
        val user = ctx.attribute<User>("currentUser") ?: throw UnauthorizedResponse()
        ctx.json(mapOf("id" to user.id, "email" to user.email, "role" to user.role))
    }, UserRole.USER, UserRole.ADMIN)

    app.start(7073)
    println("Server started on http://localhost:7073")
}