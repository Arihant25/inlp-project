package com.example.variation3

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

// --- 1. Data Models & Tables (Colocated) ---
@Serializable
data class UserDto(val id: String, val email: String, val isActive: Boolean, val roles: List<String>)
@Serializable
data class PostDto(val id: String, val userId: String, val title: String, val content: String, val status: String)

object Users : Table("users") {
    val id = uuid("id").autoGenerate()
    val email = varchar("email", 128).uniqueIndex()
    val passwordHash = varchar("password_hash", 256)
    val isActive = bool("is_active").default(true)
    val createdAt = timestamp("created_at").default(Instant.now())
    override val primaryKey = PrimaryKey(id)
}

enum class PostStatus { DRAFT, PUBLISHED }
object Posts : Table("posts") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").references(Users.id)
    val title = varchar("title", 255)
    val content = text("content")
    val status = enumerationByName("status", 10, PostStatus::class)
    override val primaryKey = PrimaryKey(id)
}

object Roles : Table("roles") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 50).uniqueIndex()
    override val primaryKey = PrimaryKey(id)
}

object UserRoles : Table("user_roles") {
    val userId = uuid("user_id").references(Users.id)
    val roleId = integer("role_id").references(Roles.id)
    override val primaryKey = PrimaryKey(userId, roleId)
}

// --- 2. Database Access Functions (Functional Style) ---
suspend fun <T> dbExec(block: suspend Transaction.() -> T): T = newSuspendedTransaction { block() }

fun ResultRow.toUserDto(roles: List<String>) = UserDto(
    id = this[Users.id].toString(),
    email = this[Users.email],
    isActive = this[Users.isActive],
    roles = roles
)

fun ResultRow.toPostDto() = PostDto(
    id = this[Posts.id].toString(),
    userId = this[Posts.userId].toString(),
    title = this[Posts.title],
    content = this[Posts.content],
    status = this[Posts.status].name
)

// --- 3. Routing via Extension Functions ---
fun Route.userRoutes() {
    route("/users") {
        // Create User
        post {
            val req = call.receive<Map<String, String>>()
            val email = req["email"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val password = req["password_hash"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val roleNames = req["roles"]?.split(",") ?: listOf("USER")

            val newId = dbExec {
                val roleIds = Roles.select { Roles.name inList roleNames }.map { it[Roles.id] }
                val userId = Users.insertAndGetId {
                    it[Users.email] = email
                    it[passwordHash] = password
                }
                UserRoles.batchInsert(roleIds) { roleId ->
                    this[UserRoles.userId] = userId
                    this[UserRoles.roleId] = roleId
                }
                userId
            }
            call.respond(HttpStatusCode.Created, mapOf("id" to newId.toString()))
        }

        // Get User with Posts (One-to-many) and Roles (Many-to-many)
        get("/{id}") {
            val userId = UUID.fromString(call.parameters["id"])
            val user = dbExec {
                Users.select { Users.id eq userId }.singleOrNull()?.let {
                    val roles = (UserRoles innerJoin Roles)
                        .select { UserRoles.userId eq userId }
                        .map { row -> row[Roles.name] }
                    it.toUserDto(roles)
                }
            }
            if (user != null) call.respond(user) else call.respond(HttpStatusCode.NotFound)
        }

        // Query building with filters
        get("/filter") {
            val isActive = call.request.queryParameters["active"]?.toBoolean()
            val roleName = call.request.queryParameters["role"]

            val users = dbExec {
                val query = (Users innerJoin UserRoles innerJoin Roles).selectAll()
                
                val conditions = Op.build { Op.TRUE }
                isActive?.let { conditions.and(Users.isActive eq it) }
                roleName?.let { conditions.and(Roles.name eq it) }
                
                query.where(conditions).map {
                    val roles = (UserRoles innerJoin Roles)
                        .select { UserRoles.userId eq it[Users.id] }
                        .map { r -> r[Roles.name] }
                    it.toUserDto(roles)
                }.distinctBy { it.id }
            }
            call.respond(users)
        }
    }
}

fun Route.postRoutes() {
    route("/posts") {
        // Get all posts for a user
        get("/by-user/{userId}") {
            val userId = UUID.fromString(call.parameters["userId"])
            val posts = dbExec {
                Posts.select { Posts.userId eq userId }.map { it.toPostDto() }
            }
            call.respond(posts)
        }
    }
}

fun Route.transactionDemoRoutes() {
    // Transaction and Rollback example
    post("/create-user-and-post-atomic") {
        try {
            dbExec {
                // This entire block is one transaction
                val userRoleId = Roles.select { Roles.name eq "USER" }.single()[Roles.id]
                val newUserId = Users.insertAndGetId {
                    it[email] = "atomic@test.com"
                    it[passwordHash] = "somehash"
                }
                UserRoles.insert {
                    it[userId] = newUserId
                    it[roleId] = userRoleId
                }

                // This will fail and cause a rollback of the user creation above
                Posts.insert {
                    it[userId] = newUserId
                    it[title] = "" // Assume DB has a NOT NULL or CHECK constraint, or we throw
                    it[content] = "This should not be saved"
                    it[status] = PostStatus.DRAFT
                }
            }
            call.respond(HttpStatusCode.Created)
        } catch (e: Exception) {
            // Exposed will throw an exception, e.g., on constraint violation
            call.respond(HttpStatusCode.InternalServerError, "Transaction failed and was rolled back.")
        }
    }
}

// --- 4. Main Application Setup ---
fun main() {
    initDatabase()
    embeddedServer(Netty, port = 8080) {
        install(ContentNegotiation) { json() }
        routing {
            userRoutes()
            postRoutes()
            transactionDemoRoutes()
        }
    }.start(wait = true)
}

fun initDatabase() {
    Database.connect("jdbc:h2:mem:test_db_3;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    transaction {
        // Database Migrations
        SchemaUtils.create(Users, Posts, Roles, UserRoles)

        // Seed Data
        val adminRoleId = Roles.insertAndGetId { it[name] = "ADMIN" }
        val userRoleId = Roles.insertAndGetId { it[name] = "USER" }

        val userId1 = Users.insertAndGetId {
            it[email] = "test.user@example.com"
            it[passwordHash] = "hash"
            it[isActive] = false
        }
        UserRoles.insert { it[userId] = userId1; it[roleId] = userRoleId }

        val userId2 = Users.insertAndGetId {
            it[email] = "admin.user@example.com"
            it[passwordHash] = "hash"
            it[isActive] = true
        }
        UserRoles.insert { it[userId] = userId2; it[roleId] = adminRoleId }
        UserRoles.insert { it[userId] = userId2; it[roleId] = userRoleId }

        Posts.insert {
            it[userId] = userId2
            it[title] = "An Admin Post"
            it[content] = "Content here."
            it[status] = PostStatus.PUBLISHED
        }
    }
}