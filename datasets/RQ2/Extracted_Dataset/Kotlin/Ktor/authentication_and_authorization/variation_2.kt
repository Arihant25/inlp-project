package com.example.variation2

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap

// --- Dependencies needed in build.gradle.kts ---
// implementation("io.ktor:ktor-server-core-jvm:$ktor_version")
// implementation("io.ktor:ktor-server-netty-jvm:$ktor_version")
// implementation("io.ktor:ktor-server-auth-jvm:$ktor_version")
// implementation("io.ktor:ktor-server-auth-jwt-jvm:$ktor_version")
// implementation("io.ktor:ktor-server-auth-oauth-jvm:$ktor_version")
// implementation("io.ktor:ktor-client-cio-jvm:$ktor_version")
// implementation("io.ktor:ktor-server-sessions-jvm:$ktor_version")
// implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktor_version")
// implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktor_version")
// implementation("com.auth0:java-jwt:4.3.0")
// implementation("ch.qos.logback:logback-classic:$logback_version")
// NOTE: For password hashing, a library like BCrypt is recommended.
// implementation("at.favre.lib:bcrypt:0.10.2")

// --- Models ---
object Models {
    enum class Role { ADMIN, USER }
    enum class PublicationStatus { DRAFT, PUBLISHED }

    @Serializable
    data class User(
        val id: String, val email: String, val passwordHash: String,
        val role: Role, val isActive: Boolean, val createdAt: Long
    )

    @Serializable
    data class Post(
        val id: String, val userId: String, val title: String,
        val content: String, val status: PublicationStatus
    )

    @Serializable
    data class LoginPayload(val email: String, val password: String)
    @Serializable
    data class TokenResponse(val jwt: String)
    @Serializable
    data class NewPostPayload(val title: String, val content: String)
}

// --- Data Layer ---
class UserRepository {
    private val userTable = ConcurrentHashMap<String, Models.User>()

    init {
        val adminId = UUID.randomUUID().toString()
        userTable[adminId] = Models.User(
            id = adminId, email = "admin@example.com",
            passwordHash = "adminpass_hashed", // Hashed with BCrypt in prod
            role = Models.Role.ADMIN, isActive = true, createdAt = Instant.now().toEpochMilli()
        )
        val userId = UUID.randomUUID().toString()
        userTable[userId] = Models.User(
            id = userId, email = "user@example.com",
            passwordHash = "userpass_hashed", // Hashed with BCrypt in prod
            role = Models.Role.USER, isActive = true, createdAt = Instant.now().toEpochMilli()
        )
    }

    fun fetchByEmail(email: String): Models.User? = userTable.values.find { it.email == email }
    fun fetchById(id: String): Models.User? = userTable[id]
}

class PostRepository {
    private val postTable = ConcurrentHashMap<String, Models.Post>()
    fun insert(post: Models.Post) {
        postTable[post.id] = post
    }
}

// --- Services ---
class PasswordService {
    // In production, use a library like BCrypt
    fun check(plain: String, hashed: String): Boolean = "${plain}_hashed" == hashed
}

class JwtService(private val config: AppConfig) {
    private val algorithm = Algorithm.HMAC256(config.jwtSecret)
    val verifier = JWT.require(algorithm)
        .withAudience(config.jwtAudience)
        .withIssuer(config.jwtIssuer)
        .build()

    fun generateToken(user: Models.User): String = JWT.create()
        .withAudience(config.jwtAudience)
        .withIssuer(config.jwtIssuer)
        .withClaim("email", user.email)
        .withClaim("role", user.role.name)
        .withSubject(user.id)
        .withExpiresAt(Date(System.currentTimeMillis() + 3_600_000)) // 1 hour
        .sign(algorithm)
}

// --- Controllers (Route Handlers) ---
class AuthController(
    private val userRepo: UserRepository,
    private val passwordSvc: PasswordService,
    private val jwtSvc: JwtService
) {
    suspend fun login(call: ApplicationCall) {
        val payload = call.receive<Models.LoginPayload>()
        val user = userRepo.fetchByEmail(payload.email)
        if (user == null || !user.isActive || !passwordSvc.check(payload.password, user.passwordHash)) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid credentials"))
            return
        }
        val token = jwtSvc.generateToken(user)
        call.respond(HttpStatusCode.OK, Models.TokenResponse(token))
    }

    suspend fun handleOAuthCallback(call: ApplicationCall) {
        val principal: OAuthAccessTokenResponse.OAuth2? = call.principal()
        // In a real app:
        // 1. Use the access token to fetch user profile from OAuth provider.
        // 2. Find or create a user in the UserRepository.
        // 3. Create a session for that user.
        val userSession = AppSession(userId = "user_from_oauth", providerToken = principal?.accessToken ?: "")
        call.sessions.set(userSession)
        call.respondRedirect("/profile")
    }
}

class PostController(private val postRepo: PostRepository) {
    suspend fun createPost(call: ApplicationCall) {
        val principal = call.principal<JWTPrincipal>()
        val userId = principal?.subject
        if (userId == null) {
            call.respond(HttpStatusCode.Unauthorized)
            return
        }
        val payload = call.receive<Models.NewPostPayload>()
        val newPost = Models.Post(
            id = UUID.randomUUID().toString(),
            userId = userId,
            title = payload.title,
            content = payload.content,
            status = Models.PublicationStatus.DRAFT
        )
        postRepo.insert(newPost)
        call.respond(HttpStatusCode.Created, newPost)
    }
}

// --- Configuration ---
data class AppConfig(
    val jwtSecret: String,
    val jwtIssuer: String,
    val jwtAudience: String,
    val jwtRealm: String
)

data class AppSession(val userId: String, val providerToken: String) : Principal

class SecurityConfigurator(private val jwtService: JwtService, private val userRepo: UserRepository) {
    fun configure(application: Application) {
        application.install(Authentication) {
            jwt("jwt") {
                realm = "Ktor App Realm"
                verifier(jwtService.verifier)
                validate { credential ->
                    val userId = credential.payload.subject
                    userRepo.fetchById(userId)?.let { JWTPrincipal(credential.payload) }
                }
            }
            oauth("oauth-google") {
                urlProvider = { "http://localhost:8080/auth/google/callback" }
                providerLookup = {
                    OAuthServerSettings.OAuth2ServerSettings(
                        name = "google",
                        authorizeUrl = "https://accounts.google.com/o/oauth2/auth",
                        accessTokenUrl = "https://accounts.google.com/o/oauth2/token",
                        requestMethod = HttpMethod.Post,
                        clientId = System.getenv("GOOGLE_CLIENT_ID") ?: "test-client-id",
                        clientSecret = System.getenv("GOOGLE_CLIENT_SECRET") ?: "test-client-secret",
                        defaultScopes = listOf("profile", "email")
                    )
                }
                client = HttpClient(CIO)
            }
            session<AppSession>("session") {
                validate { session ->
                    if (userRepo.fetchById(session.userId) != null) session else null
                }
            }
        }
        application.install(Sessions) {
            cookie<AppSession>("APP_SESSION")
        }
    }
}

fun createRoleBasedAuthorizer(roles: Set<Models.Role>) = createRouteScopedPlugin("RoleAuthorizer") {
    on(AuthenticationChecked) { call ->
        val principal = call.principal<JWTPrincipal>()
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized)
            return@on
        }
        val userRoleStr = principal.getClaim("role", String::class)
        val userRole = userRoleStr?.let { Models.Role.valueOf(it) }
        if (userRole == null || userRole !in roles) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Insufficient permissions"))
        }
    }
}

// --- Main Application ---
fun main() {
    // --- Dependency Injection Setup ---
    val appConfig = AppConfig(
        jwtSecret = "a-very-long-and-secure-secret-key",
        jwtIssuer = "http://0.0.0.0:8080",
        jwtAudience = "app-users",
        jwtRealm = "Access to protected resources"
    )
    val userRepo = UserRepository()
    val postRepo = PostRepository()
    val passwordSvc = PasswordService()
    val jwtSvc = JwtService(appConfig)
    val securityConfigurator = SecurityConfigurator(jwtSvc, userRepo)
    val authController = AuthController(userRepo, passwordSvc, jwtSvc)
    val postController = PostController(postRepo)

    embeddedServer(Netty, port = 8080) {
        install(ContentNegotiation) { json() }
        securityConfigurator.configure(this)

        routing {
            route("/auth") {
                post("/login") { authController.login(call) }
                authenticate("oauth-google") {
                    get("/google/login") { /* Redirects to Google */ }
                    get("/google/callback") { authController.handleOAuthCallback(call) }
                }
            }

            authenticate("jwt") {
                route("/posts") {
                    install(createRoleBasedAuthorizer(setOf(Models.Role.USER, Models.Role.ADMIN)))
                    post { postController.createPost(call) }
                }
                route("/admin") {
                    install(createRoleBasedAuthorizer(setOf(Models.Role.ADMIN)))
                    get("/dashboard") {
                        call.respondText("Welcome to the Admin Dashboard!")
                    }
                }
            }

            authenticate("session") {
                get("/profile") {
                    val session = call.principal<AppSession>()!!
                    call.respondText("Hello, user ${session.userId} from your session!")
                }
            }
        }
    }.start(wait = true)
}