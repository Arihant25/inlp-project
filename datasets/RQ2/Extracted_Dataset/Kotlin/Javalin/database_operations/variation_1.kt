package com.example.variation1

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.javalin.Javalin
import io.javalin.apibuilder.ApiBuilder.*
import io.javalin.json.JavalinJackson
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.sql.Timestamp
import java.time.Instant
import java.util.*
import javax.sql.DataSource
import org.h2.jdbcx.JdbcDataSource

// --- DTOs (Data Transfer Objects) for API Layer ---
data class UserDTO(val id: UUID, val email: String, val isActive: Boolean, val roles: List<String>, val createdAt: Instant)
data class PostDTO(val id: UUID, val userId: UUID, val title: String, val content: String, val status: String)
data class CreateUserRequest(val email: String, val password: String, val roles: List<String>)
data class CreatePostRequest(val title: String, val content: String)

// --- Domain Enums ---
enum class RoleName { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }

// --- Database Schema (Exposed Tables) ---
object Users : Table("users") {
    val id = uuid("id").autoGenerate()
    val email = varchar("email", 255).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val isActive = bool("is_active").default(true)
    val createdAt = timestamp("created_at").default(Timestamp.from(Instant.now()))
    override val primaryKey = PrimaryKey(id)
}

object Roles : Table("roles") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 50).uniqueIndex()
    override val primaryKey = PrimaryKey(id)
}

object UserRoles : Table("user_roles") {
    val userId = uuid("user_id").references(Users.id, onDelete = ReferenceOption.CASCADE)
    val roleId = integer("role_id").references(Roles.id, onDelete = ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(userId, roleId)
}

object Posts : Table("posts") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").references(Users.id, onDelete = ReferenceOption.CASCADE)
    val title = varchar("title", 255)
    val content = text("content")
    val status = enumerationByName("status", 10, PostStatus::class).default(PostStatus.DRAFT)
    override val primaryKey = PrimaryKey(id)
}

// --- Repository Layer (Data Access) ---
interface UserRepository {
    fun create(request: CreateUserRequest, passwordHash: String): UUID
    fun findById(id: UUID): UserDTO?
    fun findByEmail(email: String): UserDTO?
    fun findAll(isActive: Boolean?): List<UserDTO>
}

class ExposedUserRepository : UserRepository {
    override fun create(request: CreateUserRequest, passwordHash: String): UUID {
        val newUserId = Users.insertAndGetId {
            it[email] = request.email
            it[this.passwordHash] = passwordHash
        }
        val roleIds = Roles.select { Roles.name inList request.roles }.map { it[Roles.id] }
        UserRoles.batchInsert(roleIds) { roleId ->
            this[UserRoles.userId] = newUserId
            this[UserRoles.roleId] = roleId
        }
        return newUserId
    }

    override fun findById(id: UUID): UserDTO? {
        return (Users leftJoin UserRoles leftJoin Roles)
            .select { Users.id eq id }
            .groupBy(Users.id)
            .singleOrNull()
            ?.toUserDTO()
    }
    
    override fun findByEmail(email: String): UserDTO? {
        return (Users leftJoin UserRoles leftJoin Roles)
            .select { Users.email eq email }
            .groupBy(Users.id)
            .singleOrNull()
            ?.toUserDTO()
    }

    override fun findAll(isActive: Boolean?): List<UserDTO> {
        val query = (Users leftJoin UserRoles leftJoin Roles).selectAll()
        isActive?.let { query.adjustWhere { Users.isActive eq it } }
        return query.groupBy(Users.id).map { it.toUserDTO() }
    }

    private fun ResultRow.toUserDTO(): UserDTO {
        val userId = this[Users.id]
        val userRoles = transaction {
            (UserRoles innerJoin Roles)
                .select { UserRoles.userId eq userId }
                .map { it[Roles.name] }
        }
        return UserDTO(
            id = userId,
            email = this[Users.email],
            isActive = this[Users.isActive],
            roles = userRoles,
            createdAt = this[Users.createdAt].toInstant()
        )
    }
}

// --- Service Layer (Business Logic) ---
interface UserService {
    fun create(request: CreateUserRequest): UUID
    fun findById(id: UUID): UserDTO?
    fun findAll(isActive: Boolean?): List<UserDTO>
    fun createWithInitialPost(userRequest: CreateUserRequest, postRequest: CreatePostRequest): Pair<UUID, UUID>
}

class UserServiceImpl(private val userRepository: UserRepository) : UserService {
    override fun create(request: CreateUserRequest): UUID = transaction {
        // In a real app, hash the password properly
        userRepository.create(request, "hashed_${request.password}")
    }

    override fun findById(id: UUID): UserDTO? = transaction {
        userRepository.findById(id)
    }

    override fun findAll(isActive: Boolean?): List<UserDTO> = transaction {
        userRepository.findAll(isActive)
    }

    override fun createWithInitialPost(userRequest: CreateUserRequest, postRequest: CreatePostRequest): Pair<UUID, UUID> {
        // Demonstrates a transaction across multiple operations
        return transaction {
            try {
                val userId = userRepository.create(userRequest, "hashed_${userRequest.password}")
                if (postRequest.title.isBlank()) {
                    throw IllegalArgumentException("Post title cannot be blank")
                }
                val postId = Posts.insertAndGetId {
                    it[this.userId] = userId
                    it[title] = postRequest.title
                    it[content] = postRequest.content
                    it[status] = PostStatus.PUBLISHED
                }
                Pair(userId, postId)
            } catch (e: Exception) {
                // Transaction will be rolled back automatically on exception
                println("Transaction failed and rolled back: ${e.message}")
                throw e
            }
        }
    }
}

// --- Controller Layer (API Endpoints) ---
class UserController(private val userService: UserService) {
    fun getAll(ctx: io.javalin.http.Context) {
        val isActive = ctx.queryParam("is_active")?.toBoolean()
        ctx.json(userService.findAll(isActive))
    }

    fun getOne(ctx: io.javalin.http.Context) {
        val id = UUID.fromString(ctx.pathParam("id"))
        userService.findById(id)?.let { ctx.json(it) } ?: ctx.status(404)
    }

    fun create(ctx: io.javalin.http.Context) {
        val request = ctx.bodyAsClass<CreateUserRequest>()
        val newId = userService.create(request)
        ctx.status(201).json(mapOf("id" to newId))
    }
}

// --- Main Application ---
object ServiceRepoApp {
    private fun createDataSource(): DataSource {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:variation1;DB_CLOSE_DELAY=-1;")
        ds.user = "sa"
        ds.password = "sa"
        return ds
    }

    fun initDatabase(dataSource: DataSource) {
        // Database Migrations with Flyway
        val flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/v1").load()
        flyway.migrate() // In a real app, migration files would be in src/main/resources/db/migration/v1
        
        // Connect Exposed
        Database.connect(dataSource)

        // Seed initial data
        transaction {
            SchemaUtils.create(Users, Roles, UserRoles, Posts) // For H2 in-mem, simpler than real migration files
            Roles.batchInsert(RoleName.values().toList()) { name ->
                this[Roles.name] = name.name
            }
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val dataSource = createDataSource()
        initDatabase(dataSource)

        // Dependency Injection
        val userRepository = ExposedUserRepository()
        val userService = UserServiceImpl(userRepository)
        val userController = UserController(userService)

        val app = Javalin.create { config ->
            config.jsonMapper(JavalinJackson(jacksonObjectMapper()
                .registerModule(JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)))
            config.showJavalinBanner = false
        }.routes {
            path("/users") {
                get(userController::getAll)
                post(userController::create)
                path("/{id}") {
                    get(userController::getOne)
                }
            }
        }.start(7001)

        println("Variation 1 (Service-Repository) running on http://localhost:7001")
    }
}