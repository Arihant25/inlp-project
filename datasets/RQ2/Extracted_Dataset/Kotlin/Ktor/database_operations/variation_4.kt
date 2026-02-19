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
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
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

// --- Common Module ---
package com.example.variation4.common

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

object DatabaseManager {
    fun connectAndMigrate() {
        Database.connect("jdbc:h2:mem:test_db_4;DB_CLOSE_DELAY=-1", "org.h2.Driver")
        transaction {
            SchemaUtils.create(
                com.example.variation4.features.users.Users,
                com.example.variation4.features.posts.Posts,
                com.example.variation4.features.users.Roles,
                com.example.variation4.features.users.UserRoles
            )
        }
    }
}
suspend fun <T> query(block: suspend () -> T): T = newSuspendedTransaction { block() }


// --- Users Feature Module ---
package com.example.variation4.features.users

import com.example.variation4.common.query
import com.example.variation4.features.posts.Post
import com.example.variation4.features.posts.PostService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant
import java.util.*

// Models
@Serializable
data class User(val id: String, val email: String, val isActive: Boolean, val createdAt: String, val roles: List<String>)
@Serializable
data class UserCreatePayload(val email: String, val password_hash: String, val roles: List<String>)

// Tables
object Users : Table("app_users") {
    val id = uuid("id").autoGenerate().primaryKey()
    val email = varchar("email", 128).uniqueIndex()
    val passwordHash = varchar("password_hash", 256)
    val isActive = bool("is_active").default(true)
    val createdAt = timestamp("created_at").default(Instant.now())
}
object Roles : Table("app_roles") {
    val id = integer("id").autoIncrement().primaryKey()
    val name = varchar("name", 50).uniqueIndex()
}
object UserRoles : Table("app_user_roles") {
    val userId = uuid("user_id").references(Users.id, onDelete = ReferenceOption.CASCADE)
    val roleId = integer("role_id").references(Roles.id, onDelete = ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(userId, roleId)
}

// Service
class UserService {
    suspend fun getAll(filter: UserFilter): List<User> = query {
        val query = (Users leftJoin UserRoles leftJoin Roles).selectAll()
        filter.isActive?.let { query.andWhere { Users.isActive eq it } }
        filter.role?.let { roleName ->
            val roleId = Roles.select { Roles.name eq roleName }.singleOrNull()?.get(Roles.id)
            roleId?.let { query.andWhere { UserRoles.roleId eq it } }
        }
        query.groupBy(Users.id).map { it.toModel() }
    }

    suspend fun getById(id: UUID): User? = query {
        Users.select { Users.id eq id }.singleOrNull()?.toModel()
    }

    suspend fun create(payload: UserCreatePayload): UUID = query {
        val roleIds = Roles.select { Roles.name inList payload.roles }.map { it[Roles.id] }
        val newId = Users.insertAndGetId {
            it[email] = payload.email
            it[passwordHash] = payload.password_hash
        }
        UserRoles.batchInsert(roleIds) { roleId ->
            this[UserRoles.userId] = newId
            this[UserRoles.roleId] = roleId
        }
        newId
    }

    private fun ResultRow.toModel(): User {
        val userId = this[Users.id]
        val userRoles = UserRoles.innerJoin(Roles)
            .select { UserRoles.userId eq userId }
            .map { it[Roles.name] }
        return User(userId.toString(), this[Users.email], this[Users.isActive], this[Users.createdAt].toString(), userRoles)
    }
}

data class UserFilter(val isActive: Boolean?, val role: String?)

// Routing
fun Application.usersModule() {
    val userService = UserService()
    val postService = PostService()

    routing {
        route("/api/v1/users") {
            get {
                val filter = UserFilter(
                    isActive = call.request.queryParameters["active"]?.toBoolean(),
                    role = call.request.queryParameters["role"]
                )
                call.respond(userService.getAll(filter))
            }
            post {
                val payload = call.receive<UserCreatePayload>()
                val id = userService.create(payload)
                call.respond(HttpStatusCode.Created, mapOf("id" to id.toString()))
            }
            get("/{id}") {
                val id = UUID.fromString(call.parameters["id"])
                userService.getById(id)?.let { call.respond(it) } ?: call.respond(HttpStatusCode.NotFound)
            }
            // One-to-many relationship
            get("/{id}/posts") {
                val id = UUID.fromString(call.parameters["id"])
                call.respond(postService.findByAuthor(id))
            }
        }
    }
}

// --- Posts Feature Module ---
package com.example.variation4.features.posts

import com.example.variation4.common.query
import com.example.variation4.features.users.Users
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import java.util.*

// Models
@Serializable
data class Post(val id: String, val authorId: String, val title: String, val status: String)

// Tables
object Posts : Table("app_posts") {
    val id = uuid("id").autoGenerate().primaryKey()
    val userId = uuid("user_id").references(Users.id, onDelete = ReferenceOption.CASCADE)
    val title = varchar("title", 255)
    val content = text("content")
    val status = enumerationByName("status", 10, PostStatus::class).default(PostStatus.DRAFT)
}
enum class PostStatus { DRAFT, PUBLISHED }

// Service
class PostService {
    suspend fun findByAuthor(authorId: UUID): List<Post> = query {
        Posts.select { Posts.userId eq authorId }.map { it.toModel() }
    }
    private fun ResultRow.toModel() = Post(this[Posts.id].toString(), this[Posts.userId].toString(), this[Posts.title], this[Posts.status].name)
}

// --- Transaction Demo Feature ---
package com.example.variation4.features.transactions

import com.example.variation4.common.query
import com.example.variation4.features.posts.PostStatus
import com.example.variation4.features.posts.Posts
import com.example.variation4.features.users.Roles
import com.example.variation4.features.users.UserRoles
import com.example.variation4.features.users.Users
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.transactionDemoModule() {
    routing {
        post("/api/v1/transaction-rollback-demo") {
            try {
                query {
                    val userRoleId = Roles.select { Roles.name eq "USER" }.single()[Roles.id]
                    val newUserId = Users.insertAndGetId {
                        it[email] = "rollback@example.com"
                        it[passwordHash] = "somehash"
                    }
                    UserRoles.insert { it[userId] = newUserId; it[roleId] = userRoleId }
                    
                    // This will cause an exception and rollback the transaction
                    Posts.insert {
                        it[userId] = newUserId
                        it[title] = "A valid title"
                        it[content] = "This content is too long for a varchar(5) field".repeat(100) // Simulate constraint violation
                        it[status] = PostStatus.DRAFT
                    }
                }
                call.respond(HttpStatusCode.OK)
            } catch (e: Exception) {
                val userExists = query { Users.select { Users.email eq "rollback@example.com" }.count() > 0 }
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("message" to "Transaction failed and was rolled back", "userWasCreated" to userExists)
                )
            }
        }
    }
}

// --- Main Application ---
package com.example.variation4

import com.example.variation4.common.DatabaseManager
import com.example.variation4.features.posts.Posts
import com.example.variation4.features.posts.PostStatus
import com.example.variation4.features.transactions.transactionDemoModule
import com.example.variation4.features.users.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.transactions.transaction

fun main() {
    embeddedServer(Netty, port = 8080, module = Application::mainModule).start(wait = true)
}

fun Application.mainModule() {
    DatabaseManager.connectAndMigrate()
    seedData()

    install(ContentNegotiation) { json() }

    // Install feature modules
    usersModule()
    transactionDemoModule()
}

fun seedData() {
    transaction {
        val adminId = Roles.insertAndGetId { it[name] = "ADMIN" }
        val userId = Roles.insertAndGetId { it[name] = "USER" }
        val u1 = Users.insertAndGetId {
            it[email] = "modular.user@example.com"
            it[passwordHash] = "hash"
            it[isActive] = true
        }
        UserRoles.insert { it[UserRoles.userId] = u1; it[roleId] = userId }
        Posts.insert {
            it[Posts.userId] = u1
            it[title] = "Post by modular user"
            it[content] = "..."
            it[status] = PostStatus.PUBLISHED
        }
    }
}