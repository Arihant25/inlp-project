package com.example.variation3

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

// --- Domain Model (defined at top-level for simplicity) ---
enum class Role { ADMIN, USER }
enum class Status { DRAFT, PUBLISHED }

@Serializable
data class User(val id: String, val email: String, val passwordHash: String, val role: Role, val isActive: Boolean)
@Serializable
data class Post(val id: String, val userId: String, val title: String, val content: String, val status: Status)

@Serializable
data class LoginInfo(val email: String, val password: String)
@Serializable
data class NewPostInfo(val title: String, val content: String)

// --- Mock DB ---
val userDb = ConcurrentHashMap<String, User>().apply {
    val adminId = UUID.randomUUID().toString()
    put(adminId, User(adminId, "admin@test.com", "admin123_hashed", Role.ADMIN, true))
    val userId = UUID.randomUUID().toString()
    put(userId, User(userId, "user@test.com", "user123_hashed", Role.USER, true))
}
val postDb = ConcurrentHashMap<String, Post>()

// --- Simple Password Hashing Mock ---
fun checkPassword(plain: String, hashed: String): Boolean {
    // In production, use a real hashing library like BCrypt
    return "${plain}_hashed" == hashed
}

// --- Main Entry Point ---
fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::mainModule).start(wait = true)
}

// --- Application Module ---
fun Application.mainModule() {
    // --- Configuration ---
    val jwtSecret = "my-super-secret-that-is-long-enough"
    val jwtIssuer = "https://jwt-provider-domain/"
    val jwtAudience = "jwt-audience"
    val jwtRealm = "ktor sample app"
    val jwtAlgorithm = Algorithm.HMAC256(jwtSecret)

    // --- Plugins ---
    install(ContentNegotiation) {
        json()
    }

    install(Sessions) {
        cookie<UserIdPrincipal>("oauth_session_id")
    }

    install(Authentication) {
        jwt("auth-jwt") {
            realm = jwtRealm
            verifier(
                JWT.require(jwtAlgorithm)
                    .withAudience(jwtAudience)
                    .withIssuer(jwtIssuer)
                    .build()
            )
            validate { credential ->
                if (credential.payload.getClaim("email").asString() != "") {
                    JWTPrincipal(credential.payload)
                } else {
                    null
                }
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, "Token is not valid or has expired")
            }
        }

        oauth("auth-oauth-google") {
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

        session<UserIdPrincipal>("auth-session") {
            validate { session -> session }
            challenge {
                call.respond(HttpStatusCode.Unauthorized, "Session is invalid")
            }
        }
    }

    // --- RBAC Plugin (defined inline) ---
    val RbacPlugin = createRouteScopedPlugin("RbacPlugin") { requiredRole: Role ->
        on(AuthenticationChecked) { call ->
            val principal = call.principal<JWTPrincipal>() ?: return@on
            val roleClaim = principal.payload.getClaim("role").asString()
            if (Role.valueOf(roleClaim) != requiredRole) {
                call.respond(HttpStatusCode.Forbidden, "Insufficient permissions.")
            }
        }
    }

    // --- Routing ---
    routing {
        // --- Public Routes ---
        post("/login") {
            val loginInfo = call.receive<LoginInfo>()
            val user = userDb.values.find { it.email == loginInfo.email }
            if (user == null || !checkPassword(loginInfo.password, user.passwordHash)) {
                call.respond(HttpStatusCode.Unauthorized, "Bad username or password")
                return@post
            }

            val token = JWT.create()
                .withAudience(jwtAudience)
                .withIssuer(jwtIssuer)
                .withClaim("email", user.email)
                .withClaim("role", user.role.name)
                .withSubject(user.id)
                .withExpiresAt(Date(System.currentTimeMillis() + 86400000)) // 24 hours
                .sign(jwtAlgorithm)
            call.respond(mapOf("token" to token))
        }

        // --- OAuth Routes ---
        authenticate("auth-oauth-google") {
            get("/login-google") { /* Redirects to Google */ }
            get("/callback") {
                val principal: OAuthAccessTokenResponse.OAuth2? = call.principal()
                // Find or create user, then create a session
                val mockUserId = userDb.values.first().id
                call.sessions.set(UserIdPrincipal(mockUserId))
                call.respondRedirect("/profile")
            }
        }

        // --- Session-protected Routes ---
        authenticate("auth-session") {
            get("/profile") {
                val principal = call.principal<UserIdPrincipal>()!!
                val user = userDb[principal.name]
                if (user != null) {
                    call.respond(user)
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }
        }

        // --- JWT-protected and Role-based Routes ---
        authenticate("auth-jwt") {
            route("/posts") {
                post {
                    val principal = call.principal<JWTPrincipal>()!!
                    val userId = principal.subject!!
                    val postInfo = call.receive<NewPostInfo>()
                    val newPost = Post(
                        id = UUID.randomUUID().toString(),
                        userId = userId,
                        title = postInfo.title,
                        content = postInfo.content,
                        status = Status.DRAFT
                    )
                    postDb[newPost.id] = newPost
                    call.respond(HttpStatusCode.Created, newPost)
                }
            }

            route("/admin") {
                install(RbacPlugin, Role.ADMIN)
                get("/users") {
                    call.respond(userDb.values.toList())
                }
            }
        }
    }
}