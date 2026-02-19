package com.example.variation3

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import at.favre.lib.crypto.bcrypt.BCrypt
import io.javalin.Javalin
import io.javalin.apibuilder.ApiBuilder.*
import io.javalin.config.JavalinConfig
import io.javalin.http.Context
import io.javalin.http.ForbiddenResponse
import io.javalin.http.UnauthorizedResponse
import io.javalin.plugin.JavalinPlugin
import io.javalin.security.RouteRole
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

// --- Domain Model ---
enum class Role : RouteRole { ADMIN, USER, ANYONE }
enum class Status { DRAFT, PUBLISHED }
data class User(val id: UUID, val email: String, val passwordHash: String, val role: Role)
data class Post(val id: UUID, val userId: UUID, val title: String, val content: String, val status: Status)
data class LoginDto(val email: String, val password: String)
data class PostDto(val title: String, val content: String)

// --- In-Memory Storage ---
object UserStore {
    private val users = mutableMapOf<UUID, User>()
    fun findByEmail(email: String): User? = users.values.find { it.email == email }
    fun findById(id: UUID): User? = users[id]
    fun save(user: User) { users[user.id] = user }
    fun getAll(): List<User> = users.values.toList()
}

object PostStore {
    private val posts = mutableMapOf<UUID, Post>()
    fun save(post: Post) { posts[post.id] = post }
    fun getAll(): List<Post> = posts.values.toList()
}

// --- Reusable JWT Provider ---
class JwtProvider(secret: String) {
    private val algorithm = Algorithm.HMAC256(secret)
    private val issuer = "javalin-app"
    val verifier = JWT.require(algorithm).withIssuer(issuer).build()

    fun createToken(user: User): String = JWT.create()
        .withIssuer(issuer)
        .withSubject(user.id.toString())
        .withClaim("role", user.role.name)
        .withExpiresAt(Date.from(Instant.now().plus(1, ChronoUnit.DAYS)))
        .sign(algorithm)

    fun validateToken(token: String): DecodedJWT? = try {
        verifier.verify(token)
    } catch (e: Exception) {
        null
    }
}

// --- Modular Authentication Plugin ---
class AuthPlugin(private val jwtProvider: JwtProvider, private val userStore: UserStore) : JavalinPlugin {
    override fun apply(app: Javalin) {
        app.config.accessManager { handler, ctx, permittedRoles ->
            val userRole = getUserRoleFromToken(ctx)
            if (permittedRoles.contains(Role.ANYONE) || permittedRoles.contains(userRole)) {
                handler.handle(ctx)
            } else {
                throw ForbiddenResponse()
            }
        }

        app.routes {
            path("/auth") {
                post("/login") { ctx ->
                    val credentials = ctx.bodyAsClass<LoginDto>()
                    val user = userStore.findByEmail(credentials.email)
                    val passwordIsValid = user != null && BCrypt.verifyer()
                        .verify(credentials.password.toCharArray(), user.passwordHash).verified

                    if (passwordIsValid) {
                        ctx.json(mapOf("token" to jwtProvider.createToken(user!!)))
                    } else {
                        throw UnauthorizedResponse("Invalid credentials")
                    }
                }
                // Mock OAuth2 flow
                get("/oauth/callback") { ctx ->
                    val user = userStore.findByEmail("user@example.com")!!
                    ctx.json(mapOf("token" to jwtProvider.createToken(user)))
                }
            }
        }
    }

    private fun getUserRoleFromToken(ctx: Context): Role {
        val token = ctx.header("Authorization")?.replace("Bearer ", "") ?: return Role.ANYONE
        val decodedJWT = jwtProvider.validateToken(token) ?: return Role.ANYONE
        
        val userId = UUID.fromString(decodedJWT.subject)
        val user = userStore.findById(userId) ?: return Role.ANYONE
        
        ctx.attribute("user", user)
        return Role.valueOf(decodedJWT.getClaim("role").asString())
    }
}

// --- API Plugin for protected routes ---
class ApiPlugin : JavalinPlugin {
    override fun apply(app: Javalin) {
        app.routes {
            path("/api") {
                before { ctx ->
                    if (ctx.attribute<User>("user") == null) throw UnauthorizedResponse()
                }

                path("/posts") {
                    get(::getAllPosts, Role.USER, Role.ADMIN)
                    post(::createPost, Role.USER, Role.ADMIN)
                }
                path("/admin/users") {
                    get(::getAllUsers, Role.ADMIN)
                }
            }
        }
    }

    private fun getAllPosts(ctx: Context) = ctx.json(PostStore.getAll())
    private fun createPost(ctx: Context) {
        val user = ctx.attribute<User>("user")!!
        val postDto = ctx.bodyAsClass<PostDto>()
        val post = Post(UUID.randomUUID(), user.id, postDto.title, postDto.content, Status.DRAFT)
        PostStore.save(post)
        ctx.status(201).json(post)
    }
    private fun getAllUsers(ctx: Context) = ctx.json(UserStore.getAll().map { it.copy(passwordHash = "REDACTED") })
}

// --- Main Application ---
fun main() {
    // Initialize dependencies
    val jwtProvider = JwtProvider("my-super-secret-for-variation-3")

    // Seed data
    val adminPassword = BCrypt.withDefaults().hashToString(12, "adminpass".toCharArray())
    val userPassword = BCrypt.withDefaults().hashToString(12, "userpass".toCharArray())
    UserStore.save(User(UUID.randomUUID(), "admin@example.com", adminPassword, Role.ADMIN))
    UserStore.save(User(UUID.randomUUID(), "user@example.com", userPassword, Role.USER))

    // Create and configure Javalin instance using plugins
    Javalin.create { config ->
        config.registerPlugin(AuthPlugin(jwtProvider, UserStore))
        config.registerPlugin(ApiPlugin())
        config.showJavalinBanner = false
    }
    .start(7072)

    println("Server started on http://localhost:7072")
}