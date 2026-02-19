package com.db.variation2

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID
import javax.sql.DataSource
import org.h2.jdbcx.JdbcDataSource

// NOTE: Add H2 database driver to your dependencies (e.g., "com.h2database:h2:2.1.214")

// --- Data Models ---
enum class PostStatusV2 { DRAFT, PUBLISHED }

data class UserV2(
    val id: UUID,
    val email: String,
    val passwordHash: String,
    val isActive: Boolean,
    val createdAt: Timestamp,
    val roles: List<RoleV2> = emptyList()
)

data class PostV2(
    val id: UUID,
    val userId: UUID,
    val title: String,
    val content: String,
    val status: PostStatusV2
)

data class RoleV2(
    val id: UUID,
    val name: String
)

// --- Database Connection & Transaction Handling ---
object DbConnection {
    private val dataSource: DataSource by lazy {
        JdbcDataSource().apply {
            setURL("jdbc:h2:mem:db2;DB_CLOSE_DELAY=-1")
            user = "sa"
            password = ""
        }
    }

    fun <T> execute(block: (Connection) -> T): T = dataSource.connection.use(block)

    fun <T> transaction(block: (Connection) -> T): T = execute { conn ->
        conn.autoCommit = false
        try {
            val result = block(conn)
            conn.commit()
            result
        } catch (e: Exception) {
            conn.rollback()
            println("Transaction rolled back: ${e.message}")
            throw e
        }
    }
}

// --- Schema Migrations ---
object Schema {
    private val MIGRATIONS = listOf(
        """
        CREATE TABLE IF NOT EXISTS users_v2 (
            id UUID PRIMARY KEY,
            email VARCHAR(255) NOT NULL UNIQUE,
            password_hash VARCHAR(255) NOT NULL,
            is_active BOOLEAN NOT NULL,
            created_at TIMESTAMP NOT NULL
        );
        """,
        """
        CREATE TABLE IF NOT EXISTS posts_v2 (
            id UUID PRIMARY KEY,
            user_id UUID NOT NULL,
            title VARCHAR(255) NOT NULL,
            content TEXT NOT NULL,
            status VARCHAR(50) NOT NULL,
            FOREIGN KEY (user_id) REFERENCES users_v2(id) ON DELETE CASCADE
        );
        """,
        """
        CREATE TABLE IF NOT EXISTS roles_v2 (
            id UUID PRIMARY KEY,
            name VARCHAR(50) NOT NULL UNIQUE
        );
        """,
        """
        CREATE TABLE IF NOT EXISTS user_roles_v2 (
            user_id UUID NOT NULL,
            role_id UUID NOT NULL,
            PRIMARY KEY (user_id, role_id),
            FOREIGN KEY (user_id) REFERENCES users_v2(id) ON DELETE CASCADE,
            FOREIGN KEY (role_id) REFERENCES roles_v2(id) ON DELETE CASCADE
        );
        """
    )

    fun apply() = DbConnection.execute { conn ->
        conn.createStatement().use { stmt ->
            MIGRATIONS.forEach { stmt.execute(it) }
        }
        println("Schema migrations applied.")
    }
}

// --- Query Execution Helpers ---
private fun <T> PreparedStatement.fetchOne(mapper: (ResultSet) -> T): T? = this.executeQuery().use { rs ->
    if (rs.next()) mapper(rs) else null
}

private fun <T> PreparedStatement.fetchAll(mapper: (ResultSet) -> T): List<T> = this.executeQuery().use { rs ->
    generateSequence { if (rs.next()) mapper(rs) else null }.toList()
}

private fun PreparedStatement.setParams(params: List<Any>): PreparedStatement {
    params.forEachIndexed { index, param -> this.setObject(index + 1, param) }
    return this
}

// --- Data Access Functions ---
object UserQueries {
    fun insert(conn: Connection, user: UserV2) {
        val sql = "INSERT INTO users_v2 (id, email, password_hash, is_active, created_at) VALUES (?, ?, ?, ?, ?)"
        conn.prepareStatement(sql).use { ps ->
            ps.setObject(1, user.id)
            ps.setString(2, user.email)
            ps.setString(3, user.passwordHash)
            ps.setBoolean(4, user.isActive)
            ps.setTimestamp(5, user.createdAt)
            ps.executeUpdate()
        }
    }

    fun findById(conn: Connection, id: UUID): UserV2? =
        conn.prepareStatement("SELECT * FROM users_v2 WHERE id = ?").use { ps ->
            ps.setObject(1, id)
            ps.fetchOne(::mapToUser)
        }

    fun findByEmail(conn: Connection, email: String): UserV2? =
        conn.prepareStatement("SELECT * FROM users_v2 WHERE email = ?").use { ps ->
            ps.setString(1, email)
            ps.fetchOne(::mapToUser)
        }

    fun search(conn: Connection, isActive: Boolean?, emailLike: String?): List<UserV2> {
        val queryParts = mutableListOf("SELECT * FROM users_v2")
        val params = mutableListOf<Any>()
        
        isActive?.let {
            queryParts.add(if (params.isEmpty()) "WHERE is_active = ?" else "AND is_active = ?")
            params.add(it)
        }
        emailLike?.let {
            queryParts.add(if (params.isEmpty()) "WHERE email LIKE ?" else "AND email LIKE ?")
            params.add(it)
        }
        
        return conn.prepareStatement(queryParts.joinToString(" ")).use { ps ->
            ps.setParams(params).fetchAll(::mapToUser)
        }
    }

    private fun mapToUser(rs: ResultSet) = UserV2(
        id = rs.getObject("id", UUID::class.java),
        email = rs.getString("email"),
        passwordHash = rs.getString("password_hash"),
        isActive = rs.getBoolean("is_active"),
        createdAt = rs.getTimestamp("created_at")
    )
}

object PostQueries {
    fun insert(conn: Connection, post: PostV2) {
        val sql = "INSERT INTO posts_v2 (id, user_id, title, content, status) VALUES (?, ?, ?, ?, ?)"
        conn.prepareStatement(sql).use { ps ->
            ps.setObject(1, post.id)
            ps.setObject(2, post.userId)
            ps.setString(3, post.title)
            ps.setString(4, post.content)
            ps.setString(5, post.status.name)
            ps.executeUpdate()
        }
    }

    fun findByUserId(conn: Connection, userId: UUID): List<PostV2> =
        conn.prepareStatement("SELECT * FROM posts_v2 WHERE user_id = ?").use { ps ->
            ps.setObject(1, userId)
            ps.fetchAll(::mapToPost)
        }

    private fun mapToPost(rs: ResultSet) = PostV2(
        id = rs.getObject("id", UUID::class.java),
        userId = rs.getObject("user_id", UUID::class.java),
        title = rs.getString("title"),
        content = rs.getString("content"),
        status = PostStatusV2.valueOf(rs.getString("status"))
    )
}

object RoleQueries {
    fun insert(conn: Connection, role: RoleV2) {
        conn.prepareStatement("INSERT INTO roles_v2 (id, name) VALUES (?, ?)").use { ps ->
            ps.setObject(1, role.id).setParams(listOf(role.name)).executeUpdate()
        }
    }

    fun assignToUser(conn: Connection, userId: UUID, roleId: UUID) {
        conn.prepareStatement("INSERT INTO user_roles_v2 (user_id, role_id) VALUES (?, ?)").use { ps ->
            ps.setObject(1, userId)
            ps.setObject(2, roleId)
            ps.executeUpdate()
        }
    }

    fun findForUser(conn: Connection, userId: UUID): List<RoleV2> {
        val sql = """
            SELECT r.id, r.name FROM roles_v2 r
            JOIN user_roles_v2 ur ON r.id = ur.role_id
            WHERE ur.user_id = ?
        """
        return conn.prepareStatement(sql).use { ps ->
            ps.setObject(1, userId).fetchAll(::mapToRole)
        }
    }
    
    private fun mapToRole(rs: ResultSet) = RoleV2(
        id = rs.getObject("id", UUID::class.java),
        name = rs.getString("name")
    )
}

// --- Main Application ---
fun main() {
    Schema.apply()

    // 1. Create Roles
    val adminRole = RoleV2(UUID.randomUUID(), "ADMIN")
    val userRole = RoleV2(UUID.randomUUID(), "USER")
    DbConnection.transaction { conn ->
        RoleQueries.insert(conn, adminRole)
        RoleQueries.insert(conn, userRole)
    }
    println("Created roles functionally.")

    // 2. Create User and assign roles in a transaction
    val user = UserV2(UUID.randomUUID(), "functional@example.com", "hash456", true, Timestamp(System.currentTimeMillis()))
    DbConnection.transaction { conn ->
        UserQueries.insert(conn, user)
        RoleQueries.assignToUser(conn, user.id, adminRole.id)
    }
    println("Created user: ${user.email}")

    // 3. Create posts for user
    val post1 = PostV2(UUID.randomUUID(), user.id, "Functional Post", "Content", PostStatusV2.PUBLISHED)
    DbConnection.execute { conn -> PostQueries.insert(conn, post1) }
    println("Created post: '${post1.title}'")

    // 4. Retrieve user with posts and roles
    val fullUser = DbConnection.execute { conn ->
        val foundUser = UserQueries.findById(conn, user.id)
        val roles = RoleQueries.findForUser(conn, user.id)
        val posts = PostQueries.findByUserId(conn, user.id)
        foundUser?.copy(roles = roles) to posts
    }
    println("Retrieved User: ${fullUser.first}")
    println("User's Posts: ${fullUser.second}")

    // 5. Query with filters
    val activeUsers = DbConnection.execute { conn ->
        UserQueries.search(conn, isActive = true, emailLike = "%functional%")
    }
    println("Found ${activeUsers.size} active users via search.")

    // 6. Demonstrate rollback
    try {
        DbConnection.transaction { conn ->
            val tempUser = UserV2(UUID.randomUUID(), "temp@example.com", "p", false, Timestamp(System.currentTimeMillis()))
            UserQueries.insert(conn, tempUser)
            // This will fail due to unique constraint violation
            UserQueries.insert(conn, user.copy(id = UUID.randomUUID()))
        }
    } catch (e: Exception) {
        // Expected
    }
    
    val tempUserExists = DbConnection.execute { conn ->
        UserQueries.findByEmail(conn, "temp@example.com") != null
    }
    println("Temp user exists after rollback: $tempUserExists") // Should be false
}