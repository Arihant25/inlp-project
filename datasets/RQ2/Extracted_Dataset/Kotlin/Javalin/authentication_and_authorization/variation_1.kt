package com.example.variation1

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import at.favre.lib.crypto.bcrypt.BCrypt
import io.javalin.Javalin
import io.javalin.apibuilder.ApiBuilder.*
import io.javalin.http.Context
import io.javalin.http.ForbiddenResponse
import io.javalin.http.Handler
import io.javalin.http.UnauthorizedResponse
import io.javalin.security.RouteRole
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

// --- Domain Model ---
enum class Role : RouteRole { ADMIN, USER, ANYONE }
enum class Status { DRAFT, PUBLISHED }

data class User(
    val id: UUID,
    val email: String,
    val passwordHash: String,
    val role: Role,
    val isActive: Boolean = true,
    val createdAt: Instant = Instant.now()
)

data class Post(
    val id: UUID,
    val userId: UUID,
    val title: String,
    val content: String,
    val status: Status
)

data class LoginRequest(val email: String, val password: String)
data class PostRequest(val title: String, val content: String)

// --- Mock Database ---
val users = mutableMapOf<UUID, User>()
val posts = mutableMapOf<UUID, Post>()

// --- JWT & Hashing Configuration ---
private const val JWT_SECRET = "a-very-secure-secret-key-for-dev"
private val jwtAlgorithm = Algorithm.HMAC256(JWT_SECRET)
private val jwtVerifier = JWT.require(jwtAlgorithm).withIssuer("auth-service").build()
private val bcryptVerifier = BCrypt.verifyer()

// --- Helper Functions ---
fun generateJwtToken(user: User): String = JWT.create()
    .withIssuer("auth-service")
    .withClaim("userId", user.id.toString())
    .withClaim("role", user.role.name)
    .withExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
    .sign(jwtAlgorithm)

fun getUserFromContext(ctx: Context): User? {
    return ctx.attribute<User>("currentUser")
}

// --- Main Application ---
fun main() {
    // Populate mock data
    val adminPasswordHash = BCrypt.withDefaults().hashToString(12, "adminpass".toCharArray())
    val userPasswordHash = BCrypt.withDefaults().hashToString(12, "userpass".toCharArray())
    val adminUser = User(UUID.randomUUID(), "admin@example.com", adminPasswordHash, Role.ADMIN)
    val regularUser = User(UUID.randomUUID(), "user@example.com", userPasswordHash, Role.USER)
    users[adminUser.id] = adminUser
    users[regularUser.id] = regularUser

    val app = Javalin.create { config ->
        config.accessManager { handler, ctx, permittedRoles ->
            val userRole = ctx.attribute<Role>("userRole") ?: Role.ANYONE
            if (permittedRoles.contains(userRole)) {
                handler.handle(ctx)
            } else {
                throw ForbiddenResponse("Access denied")
            }
        }
        config.http.defaultContentType = "application/json"
    }.start(7070)

    // --- Middleware ---
    app.before { ctx ->
        val token = ctx.header("Authorization")?.removePrefix("Bearer ")
        if (token != null) {
            try {
                val decodedJWT = jwtVerifier.verify(token)
                val userId = UUID.fromString(decodedJWT.getClaim("userId").asString())
                val userRole = Role.valueOf(decodedJWT.getClaim("role").asString())
                
                users[userId]?.let {
                    ctx.attribute("currentUser", it)
                    ctx.attribute("userRole", it.role)
                } ?: run {
                    ctx.attribute("userRole", Role.ANYONE)
                }
            } catch (e: JWTVerificationException) {
                ctx.attribute("userRole", Role.ANYONE)
            }
        } else {
            ctx.attribute("userRole", Role.ANYONE)
        }
    }

    // --- Routing ---
    app.routes {
        // --- Public Routes ---
        path("/auth") {
            post("/login") { ctx ->
                val loginRequest = ctx.bodyAsClass<LoginRequest>()
                val user = users.values.find { it.email == loginRequest.email }
                if (user != null && user.isActive && bcryptVerifier.verify(loginRequest.password.toCharArray(), user.passwordHash).verified) {
                    val token = generateJwtToken(user)
                    ctx.json(mapOf("token" to token))
                } else {
                    throw UnauthorizedResponse("Invalid credentials")
                }
            }
            // Mock OAuth2 flow start
            get("/oauth/start") { ctx ->
                ctx.sessionAttribute("oauth_state", "random_state_string")
                // In a real app, this would redirect to the provider
                ctx.redirect("https://provider.com/auth?state=random_state_string&client_id=...&redirect_uri=/auth/oauth/callback")
            }
            // Mock OAuth2 callback
            get("/oauth/callback") { ctx ->
                val state = ctx.queryParam("state")
                if (state != ctx.sessionAttribute("oauth_state")) {
                    throw ForbiddenResponse("Invalid OAuth state")
                }
                // In a real app, exchange code for token, fetch user profile
                // For this mock, we'll just log in our regular user
                val user = users.values.first { it.role == Role.USER }
                val token = generateJwtToken(user)
                ctx.json(mapOf("token" to token, "message" to "OAuth login successful"))
            }
        }

        // --- Protected API Routes ---
        path("/api") {
            // USER and ADMIN roles can access
            path("/posts") {
                get(roles = setOf(Role.USER, Role.ADMIN)) { ctx ->
                    ctx.json(posts.values)
                }
                post(roles = setOf(Role.USER, Role.ADMIN)) { ctx ->
                    val currentUser = getUserFromContext(ctx) ?: throw UnauthorizedResponse()
                    val postRequest = ctx.bodyAsClass<PostRequest>()
                    val newPost = Post(
                        id = UUID.randomUUID(),
                        userId = currentUser.id,
                        title = postRequest.title,
                        content = postRequest.content,
                        status = Status.DRAFT
                    )
                    posts[newPost.id] = newPost
                    ctx.status(201).json(newPost)
                }
            }
            // ADMIN only
            path("/admin") {
                get("/users", roles = setOf(Role.ADMIN)) { ctx ->
                    ctx.json(users.values.map { it.copy(passwordHash = "[REDACTED]") })
                }
            }
        }
    }

    println("Server started on http://localhost:7070")
}