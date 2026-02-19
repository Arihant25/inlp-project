package com.example.variation4

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.javalin.Javalin
import io.javalin.json.JavalinJackson
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.Timestamp
import java.time.Instant
import java.util.*
import javax.sql.DataSource

// --- All-in-one Domain & Schema file ---
object DbSchema {
    enum class UserRole { ADMIN, USER }
    enum class PublicationStatus { DRAFT, PUBLISHED }

    data class User(val id: UUID, val email: String, val isActive: Boolean, val createdAt: Instant)
    data class Post(val id: UUID, val userId: UUID, val title: String, val content: String, val status: PublicationStatus)
    
    // Many-to-many is simplified to a single role enum as per original schema for this variation
    object Users : Table("users") {
        val id = uuid("id").autoGenerate()
        val email = varchar("email", 255).uniqueIndex()
        val passwordHash = varchar("password_hash", 255)
        val role = enumerationByName("role", 10, UserRole::class).default(UserRole.USER)
        val isActive = bool("is_active").default(true)
        val createdAt = timestamp("created_at").default(Timestamp.from(Instant.now()))
        override val primaryKey = PrimaryKey(id)
    }

    object Posts : Table("posts") {
        val id = uuid("id").autoGenerate()
        val userId = uuid("user_id").references(Users.id, onDelete = ReferenceOption.CASCADE)
        val title = varchar("title", 255)
        val content = text("content")
        val status = enumerationByName("status", 10, PublicationStatus::class).default(PublicationStatus.DRAFT)
        override val primaryKey = PrimaryKey(id)
    }
}

// --- Database Connection Utility ---
object DbConnection {
    fun connectAndMigrate() {
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:variation4;DB_CLOSE_DELAY=-1;")
            user = "sa"
            password = "sa"
        }
        // Mocking Flyway execution
        println("Running Flyway migrations for variation 4...")
        // val flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/v4").load()
        // flyway.migrate()
        
        Database.connect(dataSource)

        // For in-memory H2, we can just create the schema directly
        transaction {
            SchemaUtils.create(DbSchema.Users, DbSchema.Posts)
        }
    }
}

// --- Main Application: Pragmatic & Minimalist ---
fun main() {
    DbConnection.connectAndMigrate()

    val app = Javalin.create { config ->
        config.jsonMapper(JavalinJackson(jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)))
        config.showJavalinBanner = false
    }.start(7004)

    println("Variation 4 (Pragmatic Minimalist) running on http://localhost:7004")

    // --- USER ROUTES ---
    app.get("/users") { ctx ->
        val filters = mutableListOf<Op<Boolean>>()
        ctx.queryParam("isActive")?.toBoolean()?.let {
            filters.add(DbSchema.Users.isActive eq it)
        }
        ctx.queryParam("role")?.let {
            try {
                val role = DbSchema.UserRole.valueOf(it.uppercase())
                filters.add(DbSchema.Users.role eq role)
            } catch (e: IllegalArgumentException) { /* ignore invalid role */ }
        }

        val users = transaction {
            val query = DbSchema.Users.selectAll()
            if (filters.isNotEmpty()) {
                query.where { filters.reduce { acc, op -> acc and op } }
            }
            query.map {
                DbSchema.User(
                    id = it[DbSchema.Users.id],
                    email = it[DbSchema.Users.email],
                    isActive = it[DbSchema.Users.isActive],
                    createdAt = it[DbSchema.Users.createdAt].toInstant()
                )
            }
        }
        ctx.json(users)
    }

    app.get("/users/{id}") { ctx ->
        val userId = UUID.fromString(ctx.pathParam("id"))
        val user = transaction {
            DbSchema.Users.select { DbSchema.Users.id eq userId }.singleOrNull()?.let {
                DbSchema.User(
                    id = it[DbSchema.Users.id],
                    email = it[DbSchema.Users.email],
                    isActive = it[DbSchema.Users.isActive],
                    createdAt = it[DbSchema.Users.createdAt].toInstant()
                )
            }
        }
        user?.let { ctx.json(it) } ?: ctx.status(404)
    }

    // --- POST ROUTES ---
    app.get("/users/{id}/posts") { ctx ->
        val userId = UUID.fromString(ctx.pathParam("id"))
        val posts = transaction {
            DbSchema.Posts.select { DbSchema.Posts.userId eq userId }.map {
                DbSchema.Post(
                    id = it[DbSchema.Posts.id],
                    userId = it[DbSchema.Posts.userId],
                    title = it[DbSchema.Posts.title],
                    content = it[DbSchema.Posts.content],
                    status = it[DbSchema.Posts.status]
                )
            }
        }
        ctx.json(posts)
    }

    // --- TRANSACTIONAL ROUTE ---
    app.post("/users/register-with-post") { ctx ->
        val req = ctx.bodyAsClass<Map<String, String>>()
        val userEmail = req["email"] ?: throw Exception("Email is required")
        val userPassword = req["password"] ?: throw Exception("Password is required")
        val postTitle = req["postTitle"] ?: throw Exception("Post title is required")
        val postContent = req["postContent"] ?: ""

        try {
            val result = transaction {
                // This entire block is atomic. If any part fails, it all rolls back.
                val userId = DbSchema.Users.insertAndGetId {
                    it[email] = userEmail
                    it[passwordHash] = "hashed_$userPassword"
                    it[role] = DbSchema.UserRole.USER
                }

                if (postTitle.length < 3) {
                    throw IllegalArgumentException("Title too short, transaction will be rolled back.")
                }

                val postId = DbSchema.Posts.insertAndGetId {
                    it[this.userId] = userId
                    it[title] = postTitle
                    it[content] = postContent
                    it[status] = DbSchema.PublicationStatus.PUBLISHED
                }
                mapOf("userId" to userId, "postId" to postId)
            }
            ctx.status(201).json(result)
        } catch (e: IllegalArgumentException) {
            ctx.status(400).json(mapOf("error" to e.message))
        }
    }
}