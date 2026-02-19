package com.example.variation3

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.javalin.Javalin
import io.javalin.http.Context
import io.javalin.json.JavalinJackson
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import org.jetbrains.exposed.dao.*
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SizedCollection
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.Timestamp
import java.time.Instant
import java.util.*
import javax.sql.DataSource

// --- Exposed DAO (ActiveRecord) Schema and Entities ---

object UsersTbl : UUIDTable("users") {
    val email = varchar("email", 255).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val isActive = bool("is_active").default(true)
    val createdAt = timestamp("created_at").default(Timestamp.from(Instant.now()))
}

object RolesTbl : IntIdTable("roles") {
    val name = varchar("name", 50).uniqueIndex()
}

object UserRolesTbl : Table("user_roles") {
    val user = reference("user_id", UsersTbl, onDelete = ReferenceOption.CASCADE)
    val role = reference("role_id", RolesTbl, onDelete = ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(user, role)
}

object PostsTbl : UUIDTable("posts") {
    val userId = reference("user_id", UsersTbl, onDelete = ReferenceOption.CASCADE)
    val title = varchar("title", 255)
    val content = text("content")
    val status = enumerationByName("status", 10, PostStatusAR::class).default(PostStatusAR.DRAFT)
}

enum class PostStatusAR { DRAFT, PUBLISHED }
enum class RoleNameAR { ADMIN, USER }

class User(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<User>(UsersTbl)
    var email by UsersTbl.email
    var passwordHash by UsersTbl.passwordHash
    var isActive by UsersTbl.isActive
    var createdAt by UsersTbl.createdAt
    val posts by Post referrersOn PostsTbl.userId
    var roles by Role via UserRolesTbl
}

class Role(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<Role>(RolesTbl)
    var name by RolesTbl.name
    var users by User via UserRolesTbl
}

class Post(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<Post>(PostsTbl)
    var user by User referencedOn PostsTbl.userId
    var title by PostsTbl.title
    var content by PostsTbl.content
    var status by PostsTbl.status
}

// --- DTOs for API responses to avoid exposing entities directly ---
data class UserResponse(val id: UUID, val email: String, val isActive: Boolean, val roles: List<String>, val createdAt: Instant)
data class PostResponse(val id: UUID, val userId: UUID, val title: String, val content: String, val status: PostStatusAR)

fun User.toResponse() = UserResponse(this.id.value, this.email, this.isActive, this.roles.map { it.name }, this.createdAt.toInstant())
fun Post.toResponse() = PostResponse(this.id.value, this.user.id.value, this.title, this.content, this.status)

// --- API Handlers ---
object UserHandler {
    fun getAll(ctx: Context) = transaction {
        val isActiveFilter = ctx.queryParam("isActive")?.toBoolean()
        val users = if (isActiveFilter != null) {
            User.find { UsersTbl.isActive eq isActiveFilter }
        } else {
            User.all()
        }
        ctx.json(users.map { it.toResponse() })
    }

    fun getOne(ctx: Context) = transaction {
        val user = User.findById(UUID.fromString(ctx.pathParam("id")))
        if (user != null) {
            ctx.json(user.toResponse())
        } else {
            ctx.status(404)
        }
    }

    fun create(ctx: Context) {
        val req = ctx.bodyAsClass<Map<String, Any>>()
        val email = req["email"] as String
        val password = req["password"] as String
        val roleNames = req["roles"] as List<String>

        val newUser = transaction {
            val roles = Role.find { RolesTbl.name inList roleNames }.toList()
            if (roles.isEmpty()) {
                throw IllegalStateException("Invalid roles provided")
            }
            User.new {
                this.email = email
                this.passwordHash = "hashed_$password"
                this.roles = SizedCollection(roles)
            }
        }
        ctx.status(201).json(newUser.toResponse())
    }

    fun getPosts(ctx: Context) = transaction {
        val user = User.findById(UUID.fromString(ctx.pathParam("id")))
        if (user != null) {
            ctx.json(user.posts.map { it.toResponse() })
        } else {
            ctx.status(404)
        }
    }
    
    fun transactionRollbackDemo(ctx: Context) {
        try {
            transaction {
                val adminRole = Role.find { RolesTbl.name eq RoleNameAR.ADMIN.name }.single()
                User.new {
                    this.email = "rollback@example.com"
                    this.passwordHash = "bad_pass"
                    this.roles = SizedCollection(listOf(adminRole))
                }
                // This will fail and rollback the user creation
                Post.new {
                    this.title = "A post that will never be saved"
                    this.content = "..."
                    // Fails because this.user is not set, causing an exception
                }
            }
            ctx.status(200).result("This should not be reached")
        } catch (e: Exception) {
            ctx.status(500).json(mapOf("error" to "Transaction rolled back successfully", "message" to e.message))
        }
    }
}

// --- Main Application ---
object ActiveRecordApp {
    private fun configureDatabase(): DataSource {
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:variation3;DB_CLOSE_DELAY=-1;")
            user = "sa"
            password = "sa"
        }
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/v3").load().migrate()
        Database.connect(dataSource)
        
        transaction {
            SchemaUtils.create(UsersTbl, RolesTbl, UserRolesTbl, PostsTbl)
            RoleNameAR.values().forEach { roleName ->
                Role.new { name = roleName.name }
            }
        }
        return dataSource
    }

    @JvmStatic
    fun main(args: Array<String>) {
        configureDatabase()

        val app = Javalin.create { config ->
            config.jsonMapper(JavalinJackson(jacksonObjectMapper()
                .registerModule(JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)))
            config.showJavalinBanner = false
        }.apply {
            get("/users", UserHandler::getAll)
            post("/users", UserHandler::create)
            get("/users/{id}", UserHandler::getOne)
            get("/users/{id}/posts", UserHandler::getPosts)
            post("/transaction-demo", UserHandler::transactionRollbackDemo)
        }.start(7003)

        println("Variation 3 (ActiveRecord) running on http://localhost:7003")
    }
}