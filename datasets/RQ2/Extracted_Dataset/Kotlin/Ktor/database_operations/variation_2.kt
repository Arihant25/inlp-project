package com.example.variation2

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
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.dao.id.UUIDTable
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
// implementation("org.jetbrains.exposed:exposed-dao:$exposed_version")
// implementation("org.jetbrains.exposed:exposed-jdbc:$exposed_version")
// implementation("org.jetbrains.exposed:exposed-java-time:$exposed_version")
// implementation("com.h2database:h2:$h2_version")
// implementation("ch.qos.logback:logback-classic:$logback_version")

// --- 1. Database Tables (Exposed DAO style) ---
object UsersTbl : UUIDTable("users") {
    val email = varchar("email", 128).uniqueIndex()
    val password_hash = varchar("password_hash", 256)
    val is_active = bool("is_active").default(true)
    val created_at = timestamp("created_at").default(Instant.now())
}

object PostsTbl : UUIDTable("posts") {
    val user_id = reference("user_id", UsersTbl)
    val title = varchar("title", 255)
    val content = text("content")
    val status = enumerationByName("status", 10, PostStatus::class).default(PostStatus.DRAFT)
}

enum class PostStatus { DRAFT, PUBLISHED }

object RolesTbl : IntIdTable("roles") {
    val name = varchar("name", 50).uniqueIndex()
}

object UserRolesTbl : Table("user_roles") {
    val user = reference("user_id", UsersTbl)
    val role = reference("role_id", RolesTbl)
    override val primaryKey = PrimaryKey(user, role)
}

// --- 2. DAO Entities (ActiveRecord Pattern) ---
class UserEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<UserEntity>(UsersTbl)
    var email by UsersTbl.email
    var password_hash by UsersTbl.password_hash
    var is_active by UsersTbl.is_active
    var created_at by UsersTbl.created_at
    val posts by PostEntity referrersOn PostsTbl.user_id
    var roles by RoleEntity via UserRolesTbl
}

class PostEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<PostEntity>(PostsTbl)
    var user by UserEntity referencedOn PostsTbl.user_id
    var title by PostsTbl.title
    var content by PostsTbl.content
    var status by PostsTbl.status
}

class RoleEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<RoleEntity>(RolesTbl)
    var name by RolesTbl.name
    var users by UserEntity via UserRolesTbl
}

// --- 3. DTOs for API Layer ---
@Serializable
data class UserResponse(val id: String, val email: String, val isActive: Boolean, val roles: List<String>)
@Serializable
data class PostResponse(val id: String, val title: String, val status: String)
@Serializable
data class UserPostRequest(val email: String, val password_hash: String, val roles: List<String>)
@Serializable
data class PostRequest(val title: String, val content: String)

// --- Mappers ---
fun UserEntity.toResponse() = UserResponse(
    id = this.id.value.toString(),
    email = this.email,
    isActive = this.is_active,
    roles = this.roles.map { it.name }
)
fun PostEntity.toResponse() = PostResponse(id = this.id.value.toString(), title = this.title, status = this.status.name)

// --- 4. Ktor Application & Routing ---
fun main() {
    embeddedServer(Netty, port = 8080) {
        DatabaseConfig.connectAndMigrate()
        install(ContentNegotiation) { json() }
        
        routing {
            // CRUD for Users
            route("/users") {
                post {
                    val req = call.receive<UserPostRequest>()
                    val createdUser = dbQuery {
                        val roles = RoleEntity.find { RolesTbl.name inList req.roles }
                        UserEntity.new {
                            email = req.email
                            password_hash = req.password_hash
                            this.roles = SizedCollection(roles)
                        }
                    }
                    call.respond(HttpStatusCode.Created, createdUser.toResponse())
                }

                get("/{id}") {
                    val userId = UUID.fromString(call.parameters["id"])
                    val user = dbQuery { UserEntity.findById(userId) }
                    if (user != null) {
                        call.respond(user.toResponse())
                    } else {
                        call.respond(HttpStatusCode.NotFound)
                    }
                }

                put("/{id}") {
                    val userId = UUID.fromString(call.parameters["id"])
                    val req = call.receive<Map<String, Boolean>>()
                    val updatedUser = dbQuery {
                        UserEntity.findById(userId)?.apply {
                            is_active = req["is_active"] ?: is_active
                        }
                    }
                    if (updatedUser != null) {
                        call.respond(updatedUser.toResponse())
                    } else {
                        call.respond(HttpStatusCode.NotFound)
                    }
                }

                delete("/{id}") {
                    val userId = UUID.fromString(call.parameters["id"])
                    val deleted = dbQuery {
                        UserEntity.findById(userId)?.delete()
                    }
                    if (deleted != null) {
                        call.respond(HttpStatusCode.NoContent)
                    } else {
                        call.respond(HttpStatusCode.NotFound)
                    }
                }
            }

            // One-to-many relationship (User -> Posts)
            get("/users/{userId}/posts") {
                val userId = UUID.fromString(call.parameters["userId"])
                val posts = dbQuery {
                    UserEntity.findById(userId)?.posts?.map { it.toResponse() } ?: emptyList()
                }
                call.respond(posts)
            }

            // Query building with filters
            get("/users/search") {
                val isActive = call.request.queryParameters["is_active"]?.toBoolean()
                val roleName = call.request.queryParameters["role"]
                
                val users = dbQuery {
                    val conditions = mutableListOf<Op<Boolean>>()
                    isActive?.let { conditions.add(UsersTbl.is_active eq it) }
                    
                    val query = if (roleName != null) {
                        val role = RoleEntity.find { RolesTbl.name eq roleName }.firstOrNull()
                        role?.users?.find(conditions.reduceOrNull { a, b -> a and b } ?: Op.TRUE)
                    } else {
                        UserEntity.find(conditions.reduceOrNull { a, b -> a and b } ?: Op.TRUE)
                    }
                    
                    query?.map { it.toResponse() } ?: emptyList()
                }
                call.respond(users)
            }

            // Transaction and Rollback example
            post("/users/transaction-fail") {
                val userReq = UserPostRequest("fail@test.com", "hash", listOf("USER"))
                val postReq = PostRequest("", "This post should not be created") // Invalid title
                
                try {
                    dbQuery { // Entire block is one transaction
                        val userRole = RoleEntity.find { RolesTbl.name eq "USER" }.first()
                        val newUser = UserEntity.new {
                            email = userReq.email
                            password_hash = userReq.password_hash
                            roles = SizedCollection(listOf(userRole))
                        }
                        
                        if (postReq.title.isBlank()) {
                            throw IllegalStateException("Title is blank, rolling back transaction.")
                        }
                        
                        PostEntity.new {
                            user = newUser
                            title = postReq.title
                            content = postReq.content
                        }
                    }
                    call.respond(HttpStatusCode.InternalServerError, "Transaction should have failed")
                } catch (e: IllegalStateException) {
                    val userExists = dbQuery { UserEntity.find { UsersTbl.email eq userReq.email }.empty() }
                    call.respond(HttpStatusCode.Conflict, "Transaction rolled back. User was not created: $userExists")
                }
            }
        }
    }.start(wait = true)
}

// --- 5. Database Setup ---
object DatabaseConfig {
    fun connectAndMigrate() {
        val driver = "org.h2.Driver"
        val url = "jdbc:h2:mem:test_db_2;DB_CLOSE_DELAY=-1"
        Database.connect(url, driver)
        
        transaction {
            // Migrations
            SchemaUtils.create(UsersTbl, PostsTbl, RolesTbl, UserRolesTbl)

            // Seed Data
            val adminRole = RoleEntity.new { name = "ADMIN" }
            val userRole = RoleEntity.new { name = "USER" }

            val user1 = UserEntity.new {
                email = "active.user@example.com"
                password_hash = "hash123"
                is_active = true
                roles = SizedCollection(listOf(userRole))
            }

            PostEntity.new {
                user = user1
                title = "First Post by Active User"
                content = "Hello World"
                status = PostStatus.PUBLISHED
            }
        }
    }
}

suspend fun <T> dbQuery(block: () -> T): T = newSuspendedTransaction { block() }