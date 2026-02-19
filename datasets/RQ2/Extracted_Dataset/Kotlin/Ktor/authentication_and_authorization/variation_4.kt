package com.example.variation4

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

// --- Domain & Principals ---
enum class UserRole { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }

@Serializable
data class User(val id: UUID, val email: String, val passwordHash: String, val role: UserRole, val isActive: Boolean, val createdAt: Instant)
@Serializable
data class Post(val id: UUID, val userId: UUID, val title: String, val content: String, val status: PostStatus)

// Type-safe principals
data class UserPrincipal(val id: UUID, val email: String, val role: UserRole) : Principal
data class UserSession(val userId: UUID) : Principal

// DTOs
@Serializable
data class LoginRequest(val email: String, val password: String)
@Serializable
data class CreatePostRequest(val title: String, val content: String)

// --- Data Layer ---
object Repository {
    private val users = ConcurrentHashMap<UUID, User>()
    private val posts = ConcurrentHashMap<UUID, Post>()

    init {
        val adminId = UUID.randomUUID()
        users[adminId] = User(adminId, "admin@corp.com", HashingService.hash("secure_admin"), UserRole.ADMIN, true, Instant.now())
        val userId = UUID.randomUUID()
        users[userId] = User(userId, "user@corp.com", HashingService.hash("secure_user"), UserRole.USER, true, Instant.now())
    }

    fun findUserByEmail(email: String): User? = users.values.find { it.email == email }
    fun findUserById(id: UUID): User? = users[id]
    fun savePost(post: Post) { posts[post.id] = post }
}

// --- Services ---
object HashingService {
    // Replace with a real BCrypt implementation in production
    fun hash(password: String): String = "hashed::$password"
    fun verify(password: String, hash: String): Boolean = hash == "hashed::$password"
}

object JwtProvider {
    private val secret = "a-very-secure-and-random-secret-for-variation-4"
    private val algorithm = Algorithm.HMAC256(secret)
    const val issuer = "com.example.variation4"
    const val audience = "app-v4"

    val verifier = JWT.require(algorithm).withAudience(audience).withIssuer(issuer).build()

    fun createToken(principal: UserPrincipal): String = JWT.create()
        .withAudience(audience)
        .withIssuer(issuer)
        .withSubject(principal.id.toString())
        .withClaim("email", principal.email)
        .withClaim("role", principal.role.name)
        .withExpiresAt(Date(System.currentTimeMillis() + 3_600_000)) // 1 hour
        .sign(algorithm)
}

// --- Custom Ktor Plugins ---

// 1. A comprehensive plugin for all authentication methods
val AppAuthPlugin = createApplicationPlugin(name = "AppAuthPlugin") {
    application.install(Sessions) {
        cookie<UserSession>("user_session") {
            cookie.path = "/"
            cookie.secure = false // Set to true in production with HTTPS
        }
    }

    application.install(Authentication) {
        jwt("jwt") {
            realm = "Protected API"
            verifier(JwtProvider.verifier)
            validate { credential ->
                val userId = credential.payload.subject?.let { UUID.fromString(it) }
                val user = userId?.let { Repository.findUserById(it) }
                if (user != null && user.isActive) {
                    UserPrincipal(user.id, user.email, user.role)
                } else {
                    null
                }
            }
        }
        session<UserSession>("session") {
            validate { session ->
                if (Repository.findUserById(session.userId) != null) session else null
            }
        }
        oauth("google-oauth") {
            client = HttpClient(CIO)
            urlProvider = { "http://localhost:8080/auth/google/callback" }
            providerLookup = {
                OAuthServerSettings.OAuth2ServerSettings(
                    name = "google",
                    authorizeUrl = "https://accounts.google.com/o/oauth2/auth",
                    accessTokenUrl = "https://accounts.google.com/o/oauth2/token",
                    requestMethod = HttpMethod.Post,
                    clientId = System.getenv("GOOGLE_CLIENT_ID") ?: "test-client-id",
                    clientSecret = System.getenv("GOOGLE_CLIENT_SECRET") ?: "test-client-secret",
                    defaultScopes = listOf("profile")
                )
            }
        }
    }
}

// 2. A reusable, type-safe RBAC plugin
class RbacConfig {
    lateinit var requiredRole: UserRole
}

val RbacPlugin = createRouteScopedPlugin(
    name = "RbacPlugin",
    createConfiguration = ::RbacConfig
) {
    on(AuthenticationChecked) { call ->
        val principal = call.principal<UserPrincipal>()
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, "Not authenticated")
            return@on
        }
        if (principal.role != pluginConfig.requiredRole && principal.role != UserRole.ADMIN) { // Admins can do anything
            call.respond(HttpStatusCode.Forbidden, "You do not have the required role: ${pluginConfig.requiredRole}")
            return@on
        }
    }
}

// --- Routing ---
fun Application.configureRouting() {
    routing {
        route("/auth") {
            post("/login") {
                val req = call.receive<LoginRequest>()
                val user = Repository.findUserByEmail(req.email)
                if (user == null || !HashingService.verify(req.password, user.passwordHash)) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid credentials"))
                    return@post
                }
                val principal = UserPrincipal(user.id, user.email, user.role)
                val token = JwtProvider.createToken(principal)
                call.respond(HttpStatusCode.OK, mapOf("token" to token))
            }

            authenticate("google-oauth") {
                get("/google/login") { /* Redirects to Google */ }
                get("/google/callback") {
                    val oauthPrincipal: OAuthAccessTokenResponse.OAuth2? = call.principal()
                    // Logic to map OAuth user to local user
                    val user = Repository.findUserByEmail("user@corp.com")!! // Mock
                    call.sessions.set(UserSession(user.id))
                    call.respondRedirect("/dashboard")
                }
            }
        }

        authenticate("session") {
            get("/dashboard") {
                val session = call.principal<UserSession>()!!
                val user = Repository.findUserById(session.userId)
                call.respondText("Welcome to your dashboard, ${user?.email}!")
            }
        }

        authenticate("jwt") {
            route("/api/posts") {
                post {
                    val principal = call.principal<UserPrincipal>()!!
                    val req = call.receive<CreatePostRequest>()
                    val post = Post(UUID.randomUUID(), principal.id, req.title, req.content, PostStatus.DRAFT)
                    Repository.savePost(post)
                    call.respond(HttpStatusCode.Created, post)
                }
            }

            route("/api/admin") {
                install(RbacPlugin) { requiredRole = UserRole.ADMIN }
                get("/system-health") {
                    val principal = call.principal<UserPrincipal>()!!
                    call.respondText("System is healthy. Accessed by ${principal.email} (Role: ${principal.role})")
                }
            }
        }
    }
}

// --- Main Application ---
fun main() {
    embeddedServer(Netty, port = 8080) {
        install(ContentNegotiation) { json() }
        install(AppAuthPlugin)
        configureRouting()
    }.start(wait = true)
}