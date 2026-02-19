package com.example.variation2

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.javalin.Javalin
import io.javalin.apibuilder.ApiBuilder.*
import io.javalin.http.Context
import io.javalin.json.JavalinJackson
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.Timestamp
import java.time.Instant
import java.util.*
import javax.sql.DataSource

// --- Domain Model ---
enum class PostStatusV2 { DRAFT, PUBLISHED }
enum class RoleNameV2 { ADMIN, USER }

data class UserV2(val id: UUID, val email: String, val isActive: Boolean, val roles: List<String>, val createdAt: Instant)
data class PostV2(val id: UUID, val userId: UUID, val title: String, val content: String, val status: PostStatusV2)

// --- Database Schema ---
object UsersTable : Table("users") {
    val id = uuid("id").autoGenerate()
    val email = varchar("email", 255).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val isActive = bool("is_active").default(true)
    val createdAt = timestamp("created_at").default(Timestamp.from(Instant.now()))
    override val primaryKey = PrimaryKey(id)
}

object RolesTable : Table("roles") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 50).uniqueIndex()
    override val primaryKey = PrimaryKey(id)
}

object UserRolesTable : Table("user_roles") {
    val userId = uuid("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val roleId = integer("role_id").references(RolesTable.id, onDelete = ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(userId, roleId)
}

object PostsTable : Table("posts") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val title = varchar("title", 255)
    val content = text("content")
    val status = enumerationByName("status", 10, PostStatusV2::class).default(PostStatusV2.DRAFT)
    override val primaryKey = PrimaryKey(id)
}

// --- Data Access Functions ---
object UserDataAccess {
    fun fetchAll(onlyActive: Boolean?): List<UserV2> = transaction {
        val query = (UsersTable leftJoin UserRolesTable leftJoin RolesTable).selectAll()
        onlyActive?.let { query.adjustWhere { UsersTable.isActive eq it } }
        query.groupBy(UsersTable.id).map { it.toUser() }
    }

    fun fetchOne(id: UUID): UserV2? = transaction {
        (UsersTable leftJoin UserRolesTable leftJoin RolesTable)
            .select { UsersTable.id eq id }
            .groupBy(UsersTable.id)
            .singleOrNull()
            ?.toUser()
    }

    fun insert(email: String, password: String, roles: List<String>): UUID = transaction {
        val newUserId = UsersTable.insertAndGetId {
            it[UsersTable.email] = email
            it[passwordHash] = "hashed_$password"
        }
        val roleIds = RolesTable.select { RolesTable.name inList roles }.map { it[RolesTable.id] }
        UserRolesTable.batchInsert(roleIds) { roleId ->
            this[UserRolesTable.userId] = newUserId
            this[UserRolesTable.roleId] = roleId
        }
        newUserId
    }
    
    private fun ResultRow.toUser(): UserV2 {
        val currentUserId = this[UsersTable.id]
        val userRoles = (UserRolesTable innerJoin RolesTable)
            .select { UserRolesTable.userId eq currentUserId }
            .map { it[RolesTable.name] }
        
        return UserV2(
            id = currentUserId,
            email = this[UsersTable.email],
            isActive = this[UsersTable.isActive],
            roles = userRoles,
            createdAt = this[UsersTable.createdAt].toInstant()
        )
    }
}

object PostDataAccess {
    fun fetchForUser(userId: UUID): List<PostV2> = transaction {
        PostsTable.select { PostsTable.userId eq userId }.map {
            PostV2(
                id = it[PostsTable.id],
                userId = it[PostsTable.userId],
                title = it[PostsTable.title],
                content = it[PostsTable.content],
                status = it[PostsTable.status]
            )
        }
    }
}

// --- Transactional Business Logic ---
fun registerUserAndCreateDraft(email: String, password: String, roles: List<String>, postTitle: String, postContent: String): Pair<UUID, UUID> {
    return transaction {
        try {
            val userId = UserDataAccess.insert(email, password, roles)
            if (postTitle.isEmpty()) {
                // This will cause the transaction to roll back
                throw IllegalStateException("Post title cannot be empty for initial draft.")
            }
            val postId = PostsTable.insertAndGetId {
                it[this.userId] = userId
                it[title] = postTitle
                it[content] = postContent
                it[status] = PostStatusV2.DRAFT
            }
            userId to postId
        } catch (e: Exception) {
            println("Transaction rolled back: ${e.message}")
            throw e
        }
    }
}

// --- API Route Definitions ---
fun Javalin.userRoutes() {
    routes {
        path("/users") {
            get { ctx ->
                val onlyActive = ctx.queryParam("active")?.toBoolean()
                ctx.json(UserDataAccess.fetchAll(onlyActive))
            }
            post { ctx ->
                val body = ctx.bodyAsClass<Map<String, Any>>()
                val newId = UserDataAccess.insert(
                    body["email"] as String,
                    body["password"] as String,
                    body["roles"] as List<String>
                )
                ctx.status(201).json(mapOf("id" to newId))
            }
            path("/{id}") {
                get { ctx ->
                    val id = UUID.fromString(ctx.pathParam("id"))
                    UserDataAccess.fetchOne(id)?.let { ctx.json(it) } ?: ctx.status(404)
                }
            }
            path("/{id}/posts") {
                get { ctx ->
                    val id = UUID.fromString(ctx.pathParam("id"))
                    ctx.json(PostDataAccess.fetchForUser(id))
                }
            }
        }
    }
}

// --- Main Application Setup ---
object FunctionalApp {
    private fun setupDatabase(): DataSource {
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:variation2;DB_CLOSE_DELAY=-1;")
            user = "sa"
            password = "sa"
        }
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/v2").load().migrate()
        Database.connect(dataSource)
        
        transaction {
            SchemaUtils.create(UsersTable, RolesTable, UserRolesTable, PostsTable)
            RolesTable.batchInsert(RoleNameV2.values().toList()) { name ->
                this[RolesTable.name] = name.name
            }
        }
        return dataSource
    }

    @JvmStatic
    fun main(args: Array<String>) {
        setupDatabase()

        val app = Javalin.create { config ->
            config.jsonMapper(JavalinJackson(jacksonObjectMapper()
                .registerModule(JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)))
            config.showJavalinBanner = false
        }

        app.userRoutes() // Apply the functionally-defined routes

        app.start(7002)
        println("Variation 2 (Functional) running on http://localhost:7002")
    }
}