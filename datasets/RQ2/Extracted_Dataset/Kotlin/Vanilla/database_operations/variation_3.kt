package com.db.variation3

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID
import javax.sql.DataSource
import org.h2.jdbcx.JdbcDataSource

// NOTE: Add H2 database driver to your dependencies (e.g., "com.h2database:h2:2.1.214")

// --- Domain Models ---
enum class PostStatusDto { DRAFT, PUBLISHED }

data class UserDto(
    val id: UUID,
    val email: String,
    val passwordHash: String,
    val isActive: Boolean,
    val createdAt: Timestamp,
    var roles: List<RoleDto> = emptyList()
)

data class PostDto(
    val id: UUID,
    val userId: UUID,
    val title: String,
    val content: String,
    val status: PostStatusDto
)

data class RoleDto(
    val id: UUID,
    val name: String
)

// --- Database Connection ---
object ConnectionProvider {
    private val dataSource: DataSource by lazy {
        JdbcDataSource().apply {
            setURL("jdbc:h2:mem:db3;DB_CLOSE_DELAY=-1")
            user = "sa"
            password = ""
        }
    }

    fun getConnection(): Connection = dataSource.connection
}

// --- Schema Initializer ---
class SchemaInitializer(private val connection: Connection) {
    fun execute() {
        val sqlStatements = listOf(
            """
            CREATE TABLE IF NOT EXISTS app_users (
                id UUID PRIMARY KEY,
                email VARCHAR(255) NOT NULL UNIQUE,
                password_hash VARCHAR(255) NOT NULL,
                is_active BOOLEAN NOT NULL,
                created_at TIMESTAMP NOT NULL
            );
            """,
            """
            CREATE TABLE IF NOT EXISTS app_posts (
                id UUID PRIMARY KEY,
                user_id UUID NOT NULL,
                title VARCHAR(255) NOT NULL,
                content TEXT NOT NULL,
                status VARCHAR(50) NOT NULL,
                FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE
            );
            """,
            """
            CREATE TABLE IF NOT EXISTS app_roles (
                id UUID PRIMARY KEY,
                name VARCHAR(50) NOT NULL UNIQUE
            );
            """,
            """
            CREATE TABLE IF NOT EXISTS app_user_roles (
                user_id UUID NOT NULL,
                role_id UUID NOT NULL,
                PRIMARY KEY (user_id, role_id),
                FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE,
                FOREIGN KEY (role_id) REFERENCES app_roles(id) ON DELETE CASCADE
            );
            """
        )
        connection.createStatement().use { stmt ->
            sqlStatements.forEach { stmt.execute(it) }
        }
        println("Schema initialized.")
    }
}

// --- Query Builder ---
class QueryBuilder(private val table: String) {
    private val conditions = mutableListOf<String>()
    private val params = mutableListOf<Any>()

    fun where(column: String, op: String, value: Any): QueryBuilder {
        conditions.add("$column $op ?")
        params.add(value)
        return this
    }

    fun buildSelect(): Pair<String, List<Any>> {
        val whereClause = if (conditions.isNotEmpty()) "WHERE ${conditions.joinToString(" AND ")}" else ""
        return "SELECT * FROM $table $whereClause" to params
    }
}

// --- Data Access Objects (DAOs) ---
class UserDao {
    fun insert(conn: Connection, user: UserDto) {
        val sql = "INSERT INTO app_users (id, email, password_hash, is_active, created_at) VALUES (?, ?, ?, ?, ?)"
        conn.prepareStatement(sql).use { ps ->
            ps.setObject(1, user.id)
            ps.setString(2, user.email)
            ps.setString(3, user.passwordHash)
            ps.setBoolean(4, user.isActive)
            ps.setTimestamp(5, user.createdAt)
            ps.executeUpdate()
        }
    }

    fun find(conn: Connection, query: Pair<String, List<Any>>): List<UserDto> {
        return conn.prepareStatement(query.first).use { ps ->
            query.second.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
            ps.executeQuery().use { rs ->
                generateSequence { if (rs.next()) mapToUser(rs) else null }.toList()
            }
        }
    }

    private fun mapToUser(rs: ResultSet) = UserDto(
        id = rs.getObject("id", UUID::class.java),
        email = rs.getString("email"),
        passwordHash = rs.getString("password_hash"),
        isActive = rs.getBoolean("is_active"),
        createdAt = rs.getTimestamp("created_at")
    )
}

class PostDao {
    fun insert(conn: Connection, post: PostDto) {
        val sql = "INSERT INTO app_posts (id, user_id, title, content, status) VALUES (?, ?, ?, ?, ?)"
        conn.prepareStatement(sql).use { ps ->
            ps.setObject(1, post.id)
            ps.setObject(2, post.userId)
            ps.setString(3, post.title)
            ps.setString(4, post.content)
            ps.setString(5, post.status.name)
            ps.executeUpdate()
        }
    }
}

class RoleDao {
    fun insert(conn: Connection, role: RoleDto) {
        conn.prepareStatement("INSERT INTO app_roles (id, name) VALUES (?, ?)").use { ps ->
            ps.setObject(1, role.id)
            ps.setString(2, role.name)
            ps.executeUpdate()
        }
    }

    fun assignUserRole(conn: Connection, userId: UUID, roleId: UUID) {
        conn.prepareStatement("INSERT INTO app_user_roles (user_id, role_id) VALUES (?, ?)").use { ps ->
            ps.setObject(1, userId)
            ps.setObject(2, roleId)
            ps.executeUpdate()
        }
    }

    fun findByUserId(conn: Connection, userId: UUID): List<RoleDto> {
        val sql = """
            SELECT r.id, r.name FROM app_roles r
            JOIN app_user_roles ur ON r.id = ur.role_id
            WHERE ur.user_id = ?
        """
        return conn.prepareStatement(sql).use { ps ->
            ps.setObject(1, userId)
            ps.executeQuery().use { rs ->
                generateSequence { if (rs.next()) mapToRole(rs) else null }.toList()
            }
        }
    }
    
    private fun mapToRole(rs: ResultSet) = RoleDto(
        id = rs.getObject("id", UUID::class.java),
        name = rs.getString("name")
    )
}

// --- Service Layer ---
class UserService(
    private val userDao: UserDao,
    private val roleDao: RoleDao,
    private val postDao: PostDao
) {
    private fun <T> withTransaction(block: (Connection) -> T): T {
        return ConnectionProvider.getConnection().use { conn ->
            conn.autoCommit = false
            try {
                val result = block(conn)
                conn.commit()
                result
            } catch (e: Exception) {
                conn.rollback()
                println("Service transaction rolled back: ${e.message}")
                throw e
            }
        }
    }

    fun registerUserWithRoles(user: UserDto, roles: List<RoleDto>): UserDto {
        return withTransaction { conn ->
            userDao.insert(conn, user)
            roles.forEach { role ->
                roleDao.assignUserRole(conn, user.id, role.id)
            }
            user.copy(roles = roles)
        }
    }

    fun findUserById(id: UUID): UserDto? {
        return ConnectionProvider.getConnection().use { conn ->
            val query = QueryBuilder("app_users").where("id", "=", id).buildSelect()
            val user = userDao.find(conn, query).firstOrNull()
            user?.apply {
                this.roles = roleDao.findByUserId(conn, id)
            }
        }
    }

    fun createPostForUser(post: PostDto) {
        ConnectionProvider.getConnection().use { conn ->
            postDao.insert(conn, post)
        }
    }
    
    fun findUsers(isActive: Boolean): List<UserDto> {
        return ConnectionProvider.getConnection().use { conn ->
            val query = QueryBuilder("app_users").where("is_active", "=", isActive).buildSelect()
            userDao.find(conn, query)
        }
    }
}

// --- Main Application ---
fun main() {
    ConnectionProvider.getConnection().use { conn ->
        SchemaInitializer(conn).execute()
    }

    val userService = UserService(UserDao(), RoleDao(), PostDao())

    // 1. Create roles via DAO
    val adminRole = RoleDto(UUID.randomUUID(), "ADMIN")
    val userRole = RoleDto(UUID.randomUUID(), "USER")
    ConnectionProvider.getConnection().use { conn ->
        RoleDao().insert(conn, adminRole)
        RoleDao().insert(conn, userRole)
    }
    println("Roles created.")

    // 2. Use service to register a user with roles (transactional)
    val user = UserDto(UUID.randomUUID(), "service@example.com", "hash789", true, Timestamp(System.currentTimeMillis()))
    val registeredUser = userService.registerUserWithRoles(user, listOf(adminRole, userRole))
    println("User registered via service: $registeredUser")

    // 3. Use service to create a post
    val post = PostDto(UUID.randomUUID(), user.id, "Service Layer Post", "Content", PostStatusDto.PUBLISHED)
    userService.createPostForUser(post)
    println("Post created for user ${user.id}")

    // 4. Use service to find a user by ID
    val foundUser = userService.findUserById(user.id)
    println("Found user by ID: $foundUser")
    
    // 5. Use service with query builder
    val activeUsers = userService.findUsers(isActive = true)
    println("Found ${activeUsers.size} active users.")

    // 6. Demonstrate transaction rollback in service
    try {
        val conflictingUser = UserDto(UUID.randomUUID(), "service@example.com", "p", false, Timestamp(System.currentTimeMillis()))
        userService.registerUserWithRoles(conflictingUser, emptyList())
    } catch (e: Exception) {
        // Expected
    }
    
    val query = QueryBuilder("app_users").where("email", "=", "service@example.com").buildSelect()
    val users = ConnectionProvider.getConnection().use { conn -> UserDao().find(conn, query) }
    println("Number of users with email 'service@example.com' after failed transaction: ${users.size}") // Should be 1
}