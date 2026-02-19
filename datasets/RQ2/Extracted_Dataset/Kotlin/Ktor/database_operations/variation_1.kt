package com.example.variation1

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.*

// --- Gradle Dependencies (build.gradle.kts) ---
// implementation("io.ktor:ktor-server-core-jvm:$ktor_version")
// implementation("io.ktor:ktor-server-netty-jvm:$ktor_version")
// implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktor_version")
// implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktor_version")
// implementation("org.jetbrains.exposed:exposed-core:$exposed_version")
// implementation("org.jetbrains.exposed:exposed-jdbc:$exposed_version")
// implementation("org.jetbrains.exposed:exposed-java-time:$exposed_version")
// implementation("com.h2database:h2:$h2_version")
// implementation("ch.qos.logback:logback-classic:$logback_version")

// --- 1. Domain Models & DTOs ---
@Serializable
data class User(
    val id: String,
    val email: String,
    val isActive: Boolean,
    val roles: List<String>,
    val createdAt: String
)

@Serializable
data class Post(
    val id: String,
    val userId: String,
    val title: String,
    val content: String,
    val status: String
)

@Serializable
data class CreateUserRequest(val email: String, val password_hash: String, val roles: List<String>)

@Serializable
data class CreatePostRequest(val title: String, val content: String)

// --- 2. Database Layer (Tables) ---
object UsersTable : Table("users") {
    val id = uuid("id").autoGenerate().primaryKey()
    val email = varchar("email", 128).uniqueIndex()
    val passwordHash = varchar("password_hash", 256)
    val isActive = bool("is_active").default(true)
    val createdAt = timestamp("created_at").default(Instant.now())
}

object PostsTable : Table("posts") {
    val id = uuid("id").autoGenerate().primaryKey()
    val userId = uuid("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val title = varchar("title", 255)
    val content = text("content")
    val status = enumerationByName("status", 10, PostStatus::class).default(PostStatus.DRAFT)
}

enum class PostStatus { DRAFT, PUBLISHED }

object RolesTable : Table("roles") {
    val id = integer("id").autoIncrement().primaryKey()
    val name = varchar("name", 50).uniqueIndex()
}

object UserRolesTable : Table("user_roles") {
    val userId = uuid("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val roleId = integer("role_id").references(RolesTable.id, onDelete = ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(userId, roleId)
}

// --- 3. Repository Layer (Data Access) ---
class UserRepository {
    suspend fun create(req: CreateUserRequest, roleIds: List<Int>): UUID = dbQuery {
        val userId = UsersTable.insertAndGetId {
            it[email] = req.email
            it[passwordHash] = req.password_hash
        }
        UserRolesTable.batchInsert(roleIds) { roleId ->
            this[UserRolesTable.userId] = userId
            this[UserRolesTable.roleId] = roleId
        }
        userId
    }

    suspend fun findById(id: UUID): User? = dbQuery {
        (UsersTable leftJoin UserRolesTable leftJoin RolesTable)
            .select { UsersTable.id eq id }
            .groupBy(UsersTable.id)
            .map { toUser(it) }
            .singleOrNull()
    }

    suspend fun findByCriteria(isActive: Boolean?, roleName: String?): List<User> = dbQuery {
        val query = (UsersTable leftJoin UserRolesTable leftJoin RolesTable).selectAll()
        
        val conditions = mutableListOf<Op<Boolean>>()
        isActive?.let { conditions.add(UsersTable.isActive eq it) }
        roleName?.let {
            val roleId = RolesTable.select { RolesTable.name eq roleName }.map { it[RolesTable.id] }.singleOrNull()
            roleId?.let { conditions.add(UserRolesTable.roleId eq it) }
        }

        if (conditions.isNotEmpty()) {
            query.where { conditions.reduce { acc, op -> acc and op } }
        }
        
        query.groupBy(UsersTable.id, RolesTable.name).map { toUser(it) }
    }

    private fun toUser(row: ResultRow) = User(
        id = row[UsersTable.id].toString(),
        email = row[UsersTable.email],
        isActive = row[UsersTable.isActive],
        createdAt = row[UsersTable.createdAt].toString(),
        roles = RolesTable.select { UserRolesTable.userId eq row[UsersTable.id] }
            .map { it[RolesTable.name] }
    }
}

class PostRepository {
    suspend fun create(userId: UUID, req: CreatePostRequest): UUID = dbQuery {
        PostsTable.insertAndGetId {
            it[PostsTable.userId] = userId
            it[title] = req.title
            it[content] = req.content
        }
    }

    suspend fun findByUserId(userId: UUID): List<Post> = dbQuery {
        PostsTable.select { PostsTable.userId eq userId }.map { toPost(it) }
    }

    private fun toPost(row: ResultRow) = Post(
        id = row[PostsTable.id].toString(),
        userId = row[PostsTable.userId].toString(),
        title = row[PostsTable.title],
        content = row[PostsTable.content],
        status = row[PostsTable.status].name
    )
}

// --- 4. Service Layer (Business Logic) ---
class UserService(private val userRepository: UserRepository) {
    suspend fun create(req: CreateUserRequest): UUID {
        // In a real app, you'd validate the role names exist
        val roleIds = dbQuery {
            RolesTable.select { RolesTable.name inList req.roles }.map { it[RolesTable.id] }
        }
        if (roleIds.size != req.roles.size) throw IllegalArgumentException("One or more roles are invalid.")
        return userRepository.create(req, roleIds)
    }

    suspend fun find(id: UUID): User? = userRepository.findById(id)
    suspend fun search(isActive: Boolean?, role: String?): List<User> = userRepository.findByCriteria(isActive, role)
}

class TransactionService {
    // Transaction and Rollback example
    suspend fun createUserAndPost(userReq: CreateUserRequest, postReq: CreatePostRequest): Pair<UUID, UUID> = dbQuery {
        // This whole block is a single transaction.
        val roleIds = RolesTable.select { RolesTable.name inList userReq.roles }.map { it[RolesTable.id] }
        val userId = UsersTable.insertAndGetId {
            it[email] = userReq.email
            it[passwordHash] = userReq.password_hash
        }
        UserRolesTable.batchInsert(roleIds) { roleId ->
            this[UserRolesTable.userId] = userId
            this[UserRolesTable.roleId] = roleId
        }

        // Simulate an error during post creation
        if (postReq.title.isBlank()) {
            throw IllegalArgumentException("Post title cannot be blank.") // This will trigger a rollback
        }

        val postId = PostsTable.insertAndGetId {
            it[userId] = userId
            it[title] = postReq.title
            it[content] = postReq.content
        }
        Pair(userId, postId)
    }
}

// --- 5. Ktor Routing ---
fun Application.configureRouting(userService: UserService, postRepository: PostRepository, transactionService: TransactionService) {
    routing {
        route("/users") {
            post {
                val request = call.receive<CreateUserRequest>()
                val userId = userService.create(request)
                call.respond(HttpStatusCode.Created, mapOf("id" to userId.toString()))
            }
            get("/{id}") {
                val id = UUID.fromString(call.parameters["id"])
                userService.find(id)?.let { call.respond(it) } ?: call.respond(HttpStatusCode.NotFound)
            }
            get("/{id}/posts") {
                val userId = UUID.fromString(call.parameters["id"])
                val posts = postRepository.findByUserId(userId)
                call.respond(posts)
            }
            get("/search") {
                val isActive = call.request.queryParameters["is_active"]?.toBoolean()
                val role = call.request.queryParameters["role"]
                val users = userService.search(isActive, role)
                call.respond(users)
            }
            // Transaction/Rollback Demo Route
            post("/transaction-test") {
                val userReq = CreateUserRequest("tx@test.com", "hash", listOf("USER"))
                // This request will fail and rollback the user creation
                val postReq = CreatePostRequest("", "content")
                try {
                    transactionService.createUserAndPost(userReq, postReq)
                    call.respond(HttpStatusCode.Created)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message, "detail" to "Transaction was rolled back."))
                }
            }
        }
    }
}

// --- Database Configuration and Main App ---
object DatabaseFactory {
    fun init() {
        val driverClassName = "org.h2.Driver"
        val jdbcURL = "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1"
        val db = Database.connect(jdbcURL, driverClassName)
        transaction(db) {
            // Migration
            SchemaUtils.create(UsersTable, PostsTable, RolesTable, UserRolesTable)

            // Seed Data
            val adminId = RolesTable.insertAndGetId { it[name] = "ADMIN" }
            val userId = RolesTable.insertAndGetId { it[name] = "USER" }

            val user1Id = UsersTable.insertAndGetId {
                it[email] = "admin@example.com"
                it[passwordHash] = "hashed_password"
                it[isActive] = true
            }
            UserRolesTable.insert {
                it[UserRolesTable.userId] = user1Id
                it[roleId] = adminId
            }
            UserRolesTable.insert {
                it[UserRolesTable.userId] = user1Id
                it[roleId] = userId
            }

            PostsTable.insert {
                it[PostsTable.userId] = user1Id
                it[title] = "Admin's First Post"
                it[content] = "Content of the first post."
                it[status] = PostStatus.PUBLISHED
            }
        }
    }
}

suspend fun <T> dbQuery(block: suspend () -> T): T =
    newSuspendedTransaction(Dispatchers.IO) { block() }

fun main() {
    embeddedServer(Netty, port = 8080) {
        DatabaseFactory.init()
        install(ContentNegotiation) {
            json()
        }
        val userRepository = UserRepository()
        val postRepository = PostRepository()
        val userService = UserService(userRepository)
        val transactionService = TransactionService()
        configureRouting(userService, postRepository, transactionService)
    }.start(wait = true)
}