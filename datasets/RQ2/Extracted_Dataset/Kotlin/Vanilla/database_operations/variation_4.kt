package com.db.variation4

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID
import javax.sql.DataSource
import org.h2.jdbcx.JdbcDataSource

// NOTE: Add H2 database driver to your dependencies (e.g., "com.h2database:h2:2.1.214")

// --- Data Models ---
enum class PostStatus { DRAFT, PUBLISHED }

data class User(
    val id: UUID,
    val email: String,
    val passwordHash: String,
    val isActive: Boolean,
    val createdAt: Timestamp,
    val roles: List<Role> = emptyList()
)

data class Post(
    val id: UUID,
    val userId: UUID,
    val title: String,
    val content: String,
    val status: PostStatus
)

data class Role(
    val id: UUID,
    val name: String
)

// --- Central Database Helper Object ---
object DbHelper {
    private val ds: DataSource by lazy {
        JdbcDataSource().apply {
            setURL("jdbc:h2:mem:db4;DB_CLOSE_DELAY=-1")
            user = "sa"
            password = ""
        }
    }

    fun initSchema() {
        val scripts = listOf(
            "CREATE TABLE IF NOT EXISTS users_t4 (id UUID PRIMARY KEY, email VARCHAR(255) UNIQUE, password_hash VARCHAR(255), is_active BOOLEAN, created_at TIMESTAMP)",
            "CREATE TABLE IF NOT EXISTS posts_t4 (id UUID PRIMARY KEY, user_id UUID, title VARCHAR(255), content TEXT, status VARCHAR(50), FOREIGN KEY(user_id) REFERENCES users_t4(id))",
            "CREATE TABLE IF NOT EXISTS roles_t4 (id UUID PRIMARY KEY, name VARCHAR(50) UNIQUE)",
            "CREATE TABLE IF NOT EXISTS user_roles_t4 (user_id UUID, role_id UUID, PRIMARY KEY(user_id, role_id), FOREIGN KEY(user_id) REFERENCES users_t4(id), FOREIGN KEY(role_id) REFERENCES roles_t4(id))"
        )
        ds.connection.use { conn ->
            conn.createStatement().use { stmt ->
                scripts.forEach { stmt.execute(it) }
            }
        }
        println("DB schema initialized.")
    }

    fun <T> transaction(block: (Connection) -> T): T {
        return ds.connection.use { conn ->
            conn.autoCommit = false
            try {
                block(conn).also { conn.commit() }
            } catch (e: Exception) {
                conn.rollback()
                println("Transaction failed and rolled back.")
                throw e
            }
        }
    }

    // --- CRUD Operations ---
    fun saveUser(conn: Connection, user: User) {
        val sql = "INSERT INTO users_t4 (id, email, password_hash, is_active, created_at) VALUES (?, ?, ?, ?, ?)"
        conn.prepareStatement(sql).use { ps ->
            ps.setObject(1, user.id)
            ps.setString(2, user.email)
            ps.setString(3, user.passwordHash)
            ps.setBoolean(4, user.isActive)
            ps.setTimestamp(5, user.createdAt)
            ps.executeUpdate()
        }
    }

    fun saveRole(conn: Connection, role: Role) {
        conn.prepareStatement("INSERT INTO roles_t4 (id, name) VALUES (?, ?)").use { ps ->
            ps.setObject(1, role.id)
            ps.setString(2, role.name)
            ps.executeUpdate()
        }
    }

    fun linkUserToRole(conn: Connection, userId: UUID, roleId: UUID) {
        conn.prepareStatement("INSERT INTO user_roles_t4 (user_id, role_id) VALUES (?, ?)").use { ps ->
            ps.setObject(1, userId)
            ps.setObject(2, roleId)
            ps.executeUpdate()
        }
    }

    fun savePost(conn: Connection, post: Post) {
        val sql = "INSERT INTO posts_t4 (id, user_id, title, content, status) VALUES (?, ?, ?, ?, ?)"
        conn.prepareStatement(sql).use { ps ->
            ps.setObject(1, post.id)
            ps.setObject(2, post.userId)
            ps.setString(3, post.title)
            ps.setString(4, post.content)
            ps.setString(5, post.status.name)
            ps.executeUpdate()
        }
    }

    fun getUserById(id: UUID): User? {
        return ds.connection.use { conn ->
            conn.prepareStatement("SELECT * FROM users_t4 WHERE id = ?").use { ps ->
                ps.setObject(1, id)
                ps.executeQuery().use { rs ->
                    if (rs.next()) mapToUser(rs).copy(roles = getRolesForUser(conn, id)) else null
                }
            }
        }
    }
    
    fun getPostsForUser(userId: UUID): List<Post> {
        return ds.connection.use { conn ->
            conn.prepareStatement("SELECT * FROM posts_t4 WHERE user_id = ?").use { ps ->
                ps.setObject(1, userId)
                ps.executeQuery().use { rs ->
                    generateSequence { if (rs.next()) mapToPost(rs) else null }.toList()
                }
            }
        }
    }

    fun findUsers(isActive: Boolean?, emailLike: String?): List<User> {
        val sql = StringBuilder("SELECT * FROM users_t4")
        val params = mutableListOf<Any>()
        if (isActive != null || emailLike != null) {
            sql.append(" WHERE ")
            val conditions = mutableListOf<String>()
            isActive?.let { conditions.add("is_active = ?"); params.add(it) }
            emailLike?.let { conditions.add("email LIKE ?"); params.add(it) }
            sql.append(conditions.joinToString(" AND "))
        }

        return ds.connection.use { conn ->
            conn.prepareStatement(sql.toString()).use { ps ->
                params.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
                ps.executeQuery().use { rs ->
                    generateSequence { if (rs.next()) mapToUser(rs) else null }.toList()
                }
            }
        }
    }

    private fun getRolesForUser(conn: Connection, userId: UUID): List<Role> {
        val sql = "SELECT r.* FROM roles_t4 r JOIN user_roles_t4 ur ON r.id = ur.role_id WHERE ur.user_id = ?"
        return conn.prepareStatement(sql).use { ps ->
            ps.setObject(1, userId)
            ps.executeQuery().use { rs ->
                generateSequence { if (rs.next()) mapToRole(rs) else null }.toList()
            }
        }
    }

    // --- Mappers ---
    private fun mapToUser(rs: ResultSet) = User(
        id = rs.getObject("id", UUID::class.java),
        email = rs.getString("email"),
        passwordHash = rs.getString("password_hash"),
        isActive = rs.getBoolean("is_active"),
        createdAt = rs.getTimestamp("created_at")
    )

    private fun mapToPost(rs: ResultSet) = Post(
        id = rs.getObject("id", UUID::class.java),
        userId = rs.getObject("user_id", UUID::class.java),
        title = rs.getString("title"),
        content = rs.getString("content"),
        status = PostStatus.valueOf(rs.getString("status"))
    )

    private fun mapToRole(rs: ResultSet) = Role(
        id = rs.getObject("id", UUID::class.java),
        name = rs.getString("name")
    )
}

// --- Main Application ---
fun main() {
    DbHelper.initSchema()

    // 1. Create roles
    val adminRole = Role(UUID.randomUUID(), "ADMIN")
    val userRole = Role(UUID.randomUUID(), "USER")
    DbHelper.transaction { conn ->
        DbHelper.saveRole(conn, adminRole)
        DbHelper.saveRole(conn, userRole)
    }
    println("Created roles.")

    // 2. Create user and assign roles in a transaction
    val user = User(UUID.randomUUID(), "pragmatic@example.com", "hash000", true, Timestamp(System.currentTimeMillis()))
    DbHelper.transaction { conn ->
        DbHelper.saveUser(conn, user)
        DbHelper.linkUserToRole(conn, user.id, adminRole.id)
    }
    println("Created user ${user.email}")

    // 3. Create posts for the user
    val post1 = Post(UUID.randomUUID(), user.id, "A Simple Post", "Content.", PostStatus.PUBLISHED)
    val post2 = Post(UUID.randomUUID(), user.id, "Another Post", "Draft.", PostStatus.DRAFT)
    DbHelper.transaction { conn ->
        DbHelper.savePost(conn, post1)
        DbHelper.savePost(conn, post2)
    }
    println("Created 2 posts.")

    // 4. Retrieve data
    val retrievedUser = DbHelper.getUserById(user.id)
    val userPosts = DbHelper.getPostsForUser(user.id)
    println("Retrieved User: $retrievedUser")
    println("User's Posts: $userPosts")

    // 5. Query with filters
    val activeUsers = DbHelper.findUsers(isActive = true, emailLike = "%pragmatic%")
    println("Found ${activeUsers.size} active users with filter.")

    // 6. Rollback demo
    try {
        DbHelper.transaction { conn ->
            val u2 = User(UUID.randomUUID(), "ghost@example.com", "p", false, Timestamp(System.currentTimeMillis()))
            DbHelper.saveUser(conn, u2)
            // This will fail due to unique constraint on email
            val u3 = User(UUID.randomUUID(), "pragmatic@example.com", "p", false, Timestamp(System.currentTimeMillis()))
            DbHelper.saveUser(conn, u3)
        }
    } catch (e: Exception) {
        // Expected
    }

    val ghostUser = DbHelper.findUsers(isActive = null, emailLike = "ghost@example.com")
    println("Ghost user exists after rollback: ${ghostUser.isNotEmpty()}") // Should be false
}