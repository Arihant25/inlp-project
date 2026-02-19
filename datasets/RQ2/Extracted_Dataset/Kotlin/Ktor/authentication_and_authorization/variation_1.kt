package com.example.variation1

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

// --- Domain Model ---
enum class UserRole { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }

@Serializable
data class User(
    val id: String,
    val email: String,
    val passwordHash: String,
    val role: UserRole,
    val isActive: Boolean,
    val createdAt: Long
)

@Serializable
data class Post(
    val id: String,
    val userId: String,
    val title: String,
    val content: String,
    val status: PostStatus
)

@Serializable
data class LoginRequest(val email: String, val password: String)
@Serializable
data class AuthResponse(val token: String)
@Serializable
data class PostRequest(val title: String, val content: String)

// --- Mock Data Store ---
object UserRepository {
    private val users = ConcurrentHashMap<String, User>()

    init {
        // NOTE: In a real app, use a strong hashing algorithm like BCrypt.
        // val hashedAdminPassword = BCrypt.withDefaults().hashToString(12, "adminpass".toCharArray())
        val hashedAdminPassword = "adminpass_hashed"
        val adminId = UUID.randomUUID().toString()
        users[adminId] = User(
            id = adminId,
            email = "admin@example.com",
            passwordHash = hashedAdminPassword,
            role = UserRole.ADMIN,
            isActive = true,
            createdAt = Instant.now().toEpochMilli()
        )

        // val hashedUserPassword = BCrypt.withDefaults().hashToString(12, "userpass".toCharArray())
        val hashedUserPassword = "userpass_hashed"
        val userId = UUID.randomUUID().toString()
        users[userId] = User(
            id = userId,
            email = "user@example.com",
            passwordHash = hashedUserPassword,
            role = UserRole.USER,
            isActive = true,
            createdAt = Instant.now().toEpochMilli()
        )
    }

    fun findByEmail(email: String): User? = users.values.find { it.email == email }
    fun findById(id: String): User? = users[id]
}

object PostRepository {
    private val posts = ConcurrentHashMap<String, Post>()
    fun save(post: Post) {
        posts[post.id] = post
    }
    fun findByUserId(userId: String): List<Post> = posts.values.filter { it.userId == userId }
}

// --- Services ---
object AuthService {
    private const val FAKE_BCRYPT_PREFIX = "_hashed"

    fun verifyPassword(plain: String, hashed: String): Boolean {
        // In a real app, use: BCrypt.verifyer().verify(plain.toCharArray(), hashed).verified
        return "$plain$FAKE_BCRYPT_PREFIX" == hashed
    }
}

object JwtProvider {
    private const val secret = "your-super-secret-for-jwt"
    private const val issuer = "http://0.0.0.0:8080"
    private const val audience = "ktor-app-users"
    const val realm = "Ktor App"
    private val algorithm = Algorithm.HMAC256(secret)

    val verifier = JWT.require(algorithm)
        .withAudience(audience)
        .withIssuer(issuer)
        .build()

    fun createToken(user: User): String = JWT.create()
        .withAudience(audience)
        .withIssuer(issuer)
        .withClaim("email", user.email)
        .withClaim("role", user.role.name)
        .withSubject(user.id)
        .withExpiresAt(Date(System.currentTimeMillis() + 600000)) // 10 minutes
        .sign(algorithm)
}

// --- Ktor Plugins Configuration ---
fun Application.configureSecurity() {
    data class UserSession(val id: String, val accessToken: String) : Principal

    install(Authentication) {
        jwt("jwt-auth") {
            realm = JwtProvider.realm
            verifier(JwtProvider.verifier)
            validate { credential ->
                if (credential.payload.getClaim("email").asString() != "") {
                    JWTPrincipal(credential.payload)
                } else {
                    null
                }
            }
        }

        oauth("google-oauth") {
            urlProvider = { "http://localhost:8080/callback" }
            providerLookup = {
                OAuthServerSettings.OAuth2ServerSettings(
                    name = "google",
                    authorizeUrl = "https://accounts.google.com/o/oauth2/auth",
                    accessTokenUrl = "https://accounts.google.com/o/oauth2/token",
                    requestMethod = HttpMethod.Post,
                    clientId = System.getenv("GOOGLE_CLIENT_ID") ?: "test-client-id",
                    clientSecret = System.getenv("GOOGLE_CLIENT_SECRET") ?: "test-client-secret",
                    defaultScopes = listOf("https://www.googleapis.com/auth/userinfo.profile")
                )
            }
            client = HttpClient(CIO)
        }

        session<UserSession>("session-auth") {
            validate { session ->
                // Here you might check if the session is still valid in your DB
                if (UserRepository.findById(session.id) != null) session else null
            }
            challenge {
                call.respond(HttpStatusCode.Unauthorized, "Session expired or invalid")
            }
        }
    }

    install(Sessions) {
        cookie<UserSession>("user_session", directorySessionStorage(java.io.File(".sessions"))) {
            cookie.path = "/"
            cookie.maxAgeInSeconds = 60 * 60 * 24 // 1 day
        }
    }
}

val RoleBasedAuthorizationPlugin = createRouteScopedPlugin(
    name = "RoleBasedAuthorizationPlugin",
    createConfiguration = ::PluginConfiguration
) {
    pluginConfig.apply {
        on(AuthenticationChecked) { call ->
            val principal = call.principal<JWTPrincipal>() ?: return@on
            val userRole = principal.payload.getClaim("role").asString()
            val userIdFromToken = principal.subject

            if (userIdFromToken == null) {
                call.respond(HttpStatusCode.Unauthorized, "User ID not in token")
                return@on
            }

            val user = UserRepository.findById(userIdFromToken)
            if (user == null || user.role.name != userRole) {
                call.respond(HttpStatusCode.Forbidden, "Invalid user role")
                return@on
            }

            if (!requiredRoles.contains(user.role)) {
                call.respond(HttpStatusCode.Forbidden, "You don't have access to this resource")
                return@on
            }
        }
    }
}

class PluginConfiguration {
    var requiredRoles: Set<UserRole> = emptySet()
}

// --- Routing ---
fun Application.configureRouting() {
    routing {
        authRoutes()
        postRoutes()
    }
}

fun Route.authRoutes() {
    route("/auth") {
        post("/login") {
            val loginRequest = call.receive<LoginRequest>()
            val user = UserRepository.findByEmail(loginRequest.email)
            if (user == null || !AuthService.verifyPassword(loginRequest.password, user.passwordHash)) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid credentials")
                return@post
            }
            val token = JwtProvider.createToken(user)
            call.respond(HttpStatusCode.OK, AuthResponse(token))
        }

        authenticate("google-oauth") {
            get("/login/google") {
                // Redirects to Google
            }
            get("/callback") {
                val principal: OAuthAccessTokenResponse.OAuth2? = call.principal()
                // Here you would typically use the token to get user info,
                // find or create a user in your DB, and create a session/JWT.
                val session = UserSession("some_user_id_from_google", principal?.accessToken.toString())
                call.sessions.set(session)
                call.respondRedirect("/posts/mine")
            }
        }
    }
}

fun Route.postRoutes() {
    authenticate("jwt-auth") {
        route("/posts") {
            install(RoleBasedAuthorizationPlugin) {
                requiredRoles = setOf(UserRole.ADMIN, UserRole.USER)
            }
            post {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.subject!!
                val postRequest = call.receive<PostRequest>()
                val newPost = Post(
                    id = UUID.randomUUID().toString(),
                    userId = userId,
                    title = postRequest.title,
                    content = postRequest.content,
                    status = PostStatus.DRAFT
                )
                PostRepository.save(newPost)
                call.respond(HttpStatusCode.Created, newPost)
            }
        }

        route("/admin/posts") {
            install(RoleBasedAuthorizationPlugin) {
                requiredRoles = setOf(UserRole.ADMIN)
            }
            get {
                call.respondText("Welcome, Admin! Here are all the posts...")
            }
        }
    }

    authenticate("session-auth") {
        get("/posts/mine") {
            val session = call.principal<UserSession>()!!
            val posts = PostRepository.findByUserId(session.id)
            call.respond(HttpStatusCode.OK, posts)
        }
    }
}

// --- Main Application ---
fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        install(ContentNegotiation) {
            json()
        }
        configureSecurity()
        configureRouting()
    }.start(wait = true)
}