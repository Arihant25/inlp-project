package com.db.variation1

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.util.UUID
import javax.sql.DataSource
import org.h2.jdbcx.JdbcDataSource

// --- Data Models ---

enum class UserRoleType { ADMIN, USER }
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

// --- Database Management ---

object DatabaseManager {
    val dataSource: DataSource by lazy {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:db1;DB_CLOSE_DELAY=-1")
        ds.user = "sa"
        ds.password = ""
        ds
    }

    fun getConnection(): Connection = dataSource.connection
}

// --- Migrations ---

object MigrationManager {
    private val migrationScripts = listOf(
        """
        CREATE TABLE IF NOT EXISTS users (
            id UUID PRIMARY KEY,
            email VARCHAR(255) NOT NULL UNIQUE,
            password_hash VARCHAR(255) NOT NULL,
            is_active BOOLEAN NOT NULL,
            created_at TIMESTAMP NOT NULL
        );
        """,
        """
        CREATE TABLE IF NOT EXISTS posts (
            id UUID PRIMARY KEY,
            user_id UUID NOT NULL,
            title VARCHAR(255) NOT NULL,
            content TEXT NOT NULL,
            status VARCHAR(50) NOT NULL,
            CONSTRAINT fk_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
        );
        """,
        """
        CREATE TABLE IF NOT EXISTS roles (
            id UUID PRIMARY KEY,
            name VARCHAR(50) NOT NULL UNIQUE
        );
        """,
        """
        CREATE TABLE IF NOT EXISTS user_roles (
            user_id UUID NOT NULL,
            role_id UUID NOT NULL,
            PRIMARY KEY (user_id, role_id),
            CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
            CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE
        );
        """
    )

    fun runMigrations() {
        DatabaseManager.getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                migrationScripts.forEach { script ->
                    stmt.execute(script)
                }
            }
        }
        println("Migrations executed successfully.")
    }
}

// --- Transaction Management ---

object TransactionManager {
    fun <T> inTransaction(block: (Connection) -> T): T {
        val conn = DatabaseManager.getConnection()
        conn.autoCommit = false
        try {
            val result = block(conn)
            conn.commit()
            return result
        } catch (e: Exception) {
            conn.rollback()
            println("Transaction rolled back due to: ${e.message}")
            throw e
        } finally {
            conn.autoCommit = true
            conn.close()
        }
    }
}

// --- Repositories ---

interface UserRepository {
    fun create(conn: Connection, user: User)
    fun findById(conn: Connection, id: UUID): User?
    fun findByEmail(conn: Connection, email: String): User?
    fun update(conn: Connection, user: User)
    fun delete(conn: Connection, id: UUID)
    fun findWithFilters(conn: Connection, isActive: Boolean?, emailPattern: String?): List<User>
}

interface PostRepository {
    fun create(conn: Connection, post: Post)
    fun findByUserId(conn: Connection, userId: UUID): List<Post>
}

interface RoleRepository {
    fun create(conn: Connection, role: Role)
    fun findByName(conn: Connection, name: String): Role?
    fun assignToUser(conn: Connection, userId: UUID, roleId: UUID)
    fun findByUserId(conn: Connection, userId: UUID): List<Role>
}

class JdbcUserRepository : UserRepository {
    override fun create(conn: Connection, user: User) {
        val sql = "INSERT INTO users (id, email, password_hash, is_active, created_at) VALUES (?, ?, ?, ?, ?)"
        conn.prepareStatement(sql).use { ps ->
            ps.setObject(1, user.id)
            ps.setString(2, user.email)
            ps.setString(3, user.passwordHash)
            ps.setBoolean(4, user.isActive)
            ps.setTimestamp(5, user.createdAt)
            ps.executeUpdate()
        }
    }

    override fun findById(conn: Connection, id: UUID): User? {
        val sql = "SELECT * FROM users WHERE id = ?"
        return conn.prepareStatement(sql).use { ps ->
            ps.setObject(1, id)
            ps.executeQuery().use { rs ->
                if (rs.next()) mapRowToUser(rs) else null
            }
        }
    }
    
    override fun findByEmail(conn: Connection, email: String): User? {
        val sql = "SELECT * FROM users WHERE email = ?"
        return conn.prepareStatement(sql).use { ps ->
            ps.setString(1, email)
            ps.executeQuery().use { rs ->
                if (rs.next()) mapRowToUser(rs) else null
            }
        }
    }

    override fun update(conn: Connection, user: User) {
        val sql = "UPDATE users SET email = ?, password_hash = ?, is_active = ? WHERE id = ?"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, user.email)
            ps.setString(2, user.passwordHash)
            ps.setBoolean(3, user.isActive)
            ps.setObject(4, user.id)
            ps.executeUpdate()
        }
    }

    override fun delete(conn: Connection, id: UUID) {
        val sql = "DELETE FROM users WHERE id = ?"
        conn.prepareStatement(sql).use { ps ->
            ps.setObject(1, id)
            ps.executeUpdate()
        }
    }

    override fun findWithFilters(conn: Connection, isActive: Boolean?, emailPattern: String?): List<User> {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()

        isActive?.let {
            conditions.add("is_active = ?")
            params.add(it)
        }
        emailPattern?.let {
            conditions.add("email LIKE ?")
            params.add(it)
        }

        val whereClause = if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")
        val sql = "SELECT * FROM users $whereClause"
        
        return conn.prepareStatement(sql).use { ps ->
            params.forEachIndexed { index, param ->
                ps.setObject(index + 1, param)
            }
            ps.executeQuery().use { rs ->
                generateSequence { if (rs.next()) mapRowToUser(rs) else null }.toList()
            }
        }
    }

    private fun mapRowToUser(rs: ResultSet): User = User(
        id = rs.getObject("id", UUID::class.java),
        email = rs.getString("email"),
        passwordHash = rs.getString("password_hash"),
        isActive = rs.getBoolean("is_active"),
        createdAt = rs.getTimestamp("created_at")
    )
}

class JdbcPostRepository : PostRepository {
    override fun create(conn: Connection, post: Post) {
        val sql = "INSERT INTO posts (id, user_id, title, content, status) VALUES (?, ?, ?, ?, ?)"
        conn.prepareStatement(sql).use { ps ->
            ps.setObject(1, post.id)
            ps.setObject(2, post.userId)
            ps.setString(3, post.title)
            ps.setString(4, post.content)
            ps.setString(5, post.status.name)
            ps.executeUpdate()
        }
    }

    override fun findByUserId(conn: Connection, userId: UUID): List<Post> {
        val sql = "SELECT * FROM posts WHERE user_id = ?"
        return conn.prepareStatement(sql).use { ps ->
            ps.setObject(1, userId)
            ps.executeQuery().use { rs ->
                generateSequence { if (rs.next()) mapRowToPost(rs) else null }.toList()
            }
        }
    }

    private fun mapRowToPost(rs: ResultSet): Post = Post(
        id = rs.getObject("id", UUID::class.java),
        userId = rs.getObject("user_id", UUID::class.java),
        title = rs.getString("title"),
        content = rs.getString("content"),
        status = PostStatus.valueOf(rs.getString("status"))
    )
}

class JdbcRoleRepository : RoleRepository {
    override fun create(conn: Connection, role: Role) {
        val sql = "INSERT INTO roles (id, name) VALUES (?, ?)"
        conn.prepareStatement(sql).use { ps ->
            ps.setObject(1, role.id)
            ps.setString(2, role.name)
            ps.executeUpdate()
        }
    }

    override fun findByName(conn: Connection, name: String): Role? {
        val sql = "SELECT * FROM roles WHERE name = ?"
        return conn.prepareStatement(sql).use { ps ->
            ps.setString(1, name)
            ps.executeQuery().use { rs ->
                if (rs.next()) mapRowToRole(rs) else null
            }
        }
    }

    override fun assignToUser(conn: Connection, userId: UUID, roleId: UUID) {
        val sql = "INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)"
        conn.prepareStatement(sql).use { ps ->
            ps.setObject(1, userId)
            ps.setObject(2, roleId)
            ps.executeUpdate()
        }
    }

    override fun findByUserId(conn: Connection, userId: UUID): List<Role> {
        val sql = """
            SELECT r.id, r.name FROM roles r
            JOIN user_roles ur ON r.id = ur.role_id
            WHERE ur.user_id = ?
        """
        return conn.prepareStatement(sql).use { ps ->
            ps.setObject(1, userId)
            ps.executeQuery().use { rs ->
                generateSequence { if (rs.next()) mapRowToRole(rs) else null }.toList()
            }
        }
    }

    private fun mapRowToRole(rs: ResultSet): Role = Role(
        id = rs.getObject("id", UUID::class.java),
        name = rs.getString("name")
    )
}

// --- Main Application ---
// NOTE: Add H2 database driver to your dependencies (e.g., "com.h2database:h2:2.1.214")
fun main() {
    MigrationManager.runMigrations()

    val userRepo: UserRepository = JdbcUserRepository()
    val postRepo: PostRepository = JdbcPostRepository()
    val roleRepo: RoleRepository = JdbcRoleRepository()

    // 1. Create Roles
    val adminRole = Role(UUID.randomUUID(), "ADMIN")
    val userRole = Role(UUID.randomUUID(), "USER")
    TransactionManager.inTransaction { conn ->
        roleRepo.create(conn, adminRole)
        roleRepo.create(conn, userRole)
    }
    println("Created roles: $adminRole, $userRole")

    // 2. Create User with Roles (Transaction)
    val userId = UUID.randomUUID()
    val user = User(userId, "test@example.com", "hash123", true, Timestamp(System.currentTimeMillis()))
    
    TransactionManager.inTransaction { conn ->
        userRepo.create(conn, user)
        roleRepo.assignToUser(conn, userId, adminRole.id)
        roleRepo.assignToUser(conn, userId, userRole.id)
    }
    println("Created user: ${user.email}")

    // 3. Retrieve User and their Posts/Roles
    DatabaseManager.getConnection().use { conn ->
        val retrievedUser = userRepo.findById(conn, userId)
        val userRoles = roleRepo.findByUserId(conn, userId)
        val userWithRoles = retrievedUser?.copy(roles = userRoles)
        println("Retrieved user with roles: $userWithRoles")
    }

    // 4. Create Posts for the User (One-to-Many)
    val post1 = Post(UUID.randomUUID(), userId, "First Post", "Content here", PostStatus.PUBLISHED)
    val post2 = Post(UUID.randomUUID(), userId, "Second Post", "Draft content", PostStatus.DRAFT)
    TransactionManager.inTransaction { conn ->
        postRepo.create(conn, post1)
        postRepo.create(conn, post2)
    }
    println("Created 2 posts for user ${user.email}")

    // 5. Query building with filters
    DatabaseManager.getConnection().use { conn ->
        val activeUsers = userRepo.findWithFilters(conn, isActive = true, emailPattern = "%@example.com")
        println("Found ${activeUsers.size} active users matching pattern.")
    }

    // 6. Transaction rollback example
    try {
        TransactionManager.inTransaction { conn ->
            val anotherUser = User(UUID.randomUUID(), "fail@example.com", "hash", true, Timestamp(System.currentTimeMillis()))
            userRepo.create(conn, anotherUser)
            // This will fail due to unique constraint on email
            userRepo.create(conn, user) 
        }
    } catch (e: Exception) {
        // Expected
    }

    DatabaseManager.getConnection().use { conn ->
        val failedUser = userRepo.findByEmail(conn, "fail@example.com")
        println("User 'fail@example.com' exists: ${failedUser != null}") // Should be false
    }
}