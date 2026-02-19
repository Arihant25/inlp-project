package com.example.variation4

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap

// --- Domain Models ---
enum class UserRole { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }

data class User(
    val id: UUID,
    var email: String,
    var passwordHash: String,
    var role: UserRole,
    var isActive: Boolean,
    val createdAt: Instant
)

data class Post(
    val id: UUID,
    val userId: UUID,
    val title: String,
    val content: String,
    val status: PostStatus
)

// --- API Data Contracts ---
@Serializable
data class UserView(
    val id: String,
    val email: String,
    val role: UserRole,
    val isActive: Boolean,
    val createdAt: String
)

@Serializable
data class CreateUserCommand(val email: String, val password: String, val role: UserRole = UserRole.USER)

@Serializable
data class UpdateUserCommand(val email: String?, val role: UserRole?, val isActive: Boolean?)

// --- Service Layer Abstraction ---
interface IUserService {
    fun getUsers(page: Int, size: Int, role: UserRole?, isActive: Boolean?): List<User>
    fun getUser(id: UUID): User?
    fun createUser(command: CreateUserCommand): Result<User>
    fun updateUser(id: UUID, command: UpdateUserCommand): User?
    fun deleteUser(id: UUID): Boolean
}

// --- Mock Service Implementation ---
class MockUserService : IUserService {
    private val users = ConcurrentHashMap<UUID, User>()

    init {
        val adminId = UUID.randomUUID()
        users[adminId] = User(adminId, "admin@ktor.dev", "hash", UserRole.ADMIN, true, Instant.now())
    }

    override fun getUsers(page: Int, size: Int, role: UserRole?, isActive: Boolean?): List<User> =
        users.values.filter {
            (role == null || it.role == role) && (isActive == null || it.isActive == isActive)
        }.drop(page * size).take(size)

    override fun getUser(id: UUID): User? = users[id]

    override fun createUser(command: CreateUserCommand): Result<User> {
        if (users.values.any { it.email == command.email }) {
            return Result.failure(Exception("Email already exists"))
        }
        val user = User(UUID.randomUUID(), command.email, command.password.reversed(), command.role, true, Instant.now())
        users[user.id] = user
        return Result.success(user)
    }

    override fun updateUser(id: UUID, command: UpdateUserCommand): User? =
        users[id]?.apply {
            command.email?.let { this.email = it }
            command.role?.let { this.role = it }
            command.isActive?.let { this.isActive = it }
        }

    override fun deleteUser(id: UUID): Boolean = users.remove(id) != null
}

// --- Ktor Feature/Plugin for User Routes ---
class UserRoutesFeature(config: Configuration) {
    private val userService: IUserService = config.userService

    private fun User.toView() = UserView(id.toString(), email, role, isActive, createdAt.toString())

    fun setupRoutes(routing: Routing) {
        routing.route("/users") {
            post {
                val command = call.receive<CreateUserCommand>()
                userService.createUser(command)
                    .onSuccess { user -> call.respond(HttpStatusCode.Created, user.toView()) }
                    .onFailure { error -> call.respond(HttpStatusCode.Conflict, mapOf("error" to error.message)) }
            }
            get {
                val page = call.parameters["page"]?.toIntOrNull() ?: 0
                val size = call.parameters["size"]?.toIntOrNull() ?: 10
                val role = call.parameters["role"]?.let { runCatching { UserRole.valueOf(it) }.getOrNull() }
                val isActive = call.parameters["isActive"]?.toBooleanStrictOrNull()
                val users = userService.getUsers(page, size, role, isActive).map { it.toView() }
                call.respond(users)
            }
            route("/{id}") {
                get {
                    val id = UUID.fromString(call.parameters["id"])
                    userService.getUser(id)?.let { call.respond(it.toView()) }
                        ?: call.respond(HttpStatusCode.NotFound)
                }
                put { // Can also be PATCH
                    val id = UUID.fromString(call.parameters["id"])
                    val command = call.receive<UpdateUserCommand>()
                    userService.updateUser(id, command)?.let { call.respond(it.toView()) }
                        ?: call.respond(HttpStatusCode.NotFound)
                }
                delete {
                    val id = UUID.fromString(call.parameters["id"])
                    if (userService.deleteUser(id)) call.respond(HttpStatusCode.NoContent)
                    else call.respond(HttpStatusCode.NotFound)
                }
            }
        }
    }

    class Configuration {
        lateinit var userService: IUserService
    }

    companion object Plugin : BaseApplicationPlugin<Application, Configuration, UserRoutesFeature> {
        override val key: AttributeKey<UserRoutesFeature> = AttributeKey("UserRoutesFeature")

        override fun install(pipeline: Application, configure: Configuration.() -> Unit): UserRoutesFeature {
            val configuration = Configuration().apply(configure)
            val feature = UserRoutesFeature(configuration)
            pipeline.routing {
                feature.setupRoutes(this)
            }
            return feature
        }
    }
}

// --- Main Application ---
fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        install(ContentNegotiation) {
            json()
        }
        
        // Instantiate dependencies
        val userService: IUserService = MockUserService()

        // Install the custom feature
        install(UserRoutesFeature) {
            this.userService = userService
        }
    }.start(wait = true)
}