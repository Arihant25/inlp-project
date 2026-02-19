package com.auth.pragmatic_minimalist

import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// --- Domain Model ---
enum class Role { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }

data class User(val id: UUID, val email: String, val passwordHash: String, val role: Role, val isActive: Boolean)
data class Post(val id: UUID, val userId: UUID, val title: String, val content: String)

// --- In-memory Storage ---
object DataStore {
    val users = mutableListOf<User>()
    val posts = mutableListOf<Post>()
    val activeTokens = mutableSetOf<String>() // Simple session management
}

// --- All-in-one Security Utility Object ---
object SecurityUtils {
    private const val PWD_SALT = "a_simple_static_salt"
    private const val JWT_SECRET = "this_is_a_pragmatic_secret_key_32_bytes"
    private const val JWT_ALGO = "HmacSHA256"

    // --- Password Hashing ---
    fun pwdHash(password: String): String {
        val spec = PBEKeySpec(password.toCharArray(), PWD_SALT.toByteArray(), 1024, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return Base64.getEncoder().encodeToString(factory.generateSecret(spec).encoded)
    }

    // --- JWT ---
    private fun String.toB64Url() = Base64.getUrlEncoder().withoutPadding().encodeToString(this.toByteArray())
    private fun ByteArray.toB64Url() = Base64.getUrlEncoder().withoutPadding().encodeToString(this)

    fun createJwt(user: User): String {
        val header = """{"alg":"HS256"}""".toB64Url()
        val payload = """{"uid":"${user.id}","role":"${user.role}","exp":${Instant.now().epochSecond + 86400}}""".toB64Url()
        val data = "$header.$payload"
        val mac = Mac.getInstance(JWT_ALGO).apply { init(SecretKeySpec(JWT_SECRET.toByteArray(), JWT_ALGO)) }
        val signature = mac.doFinal(data.toByteArray()).toB64Url()
        return "$data.$signature"
    }

    fun verifyJwt(token: String): Map<String, String>? {
        val parts = token.split('.')
        if (parts.size != 3) return null

        val data = "${parts[0]}.${parts[1]}"
        val mac = Mac.getInstance(JWT_ALGO).apply { init(SecretKeySpec(JWT_SECRET.toByteArray(), JWT_ALGO)) }
        val expectedSig = mac.doFinal(data.toByteArray())
        val providedSig = Base64.getUrlDecoder().decode(parts[2])

        if (!MessageDigest.isEqual(expectedSig, providedSig)) return null

        val payload = String(Base64.getUrlDecoder().decode(parts[1]))
        val claims = payload.removeSurrounding("{", "}").split(",").associate {
            val (k, v) = it.split(":", limit = 2)
            k.trim().removeSurrounding("\"") to v.trim().removeSurrounding("\"")
        }

        if (claims["exp"]?.toLong() ?: 0 < Instant.now().epochSecond) return null // Expired
        
        return claims
    }

    // --- Authentication ---
    fun login(email: String, password: String): String? {
        val user = DataStore.users.find { it.email == email && it.isActive } ?: return null
        if (user.passwordHash != pwdHash(password)) return null
        
        val token = createJwt(user)
        DataStore.activeTokens.add(token) // Session start
        return token
    }
    
    fun logout(token: String) {
        DataStore.activeTokens.remove(token) // Session end
    }

    fun processOauth(provider: String, token: String): User? {
        println("Processing OAuth token from $provider...")
        return if (provider == "apple" && token == "valid_apple_id_token") {
            DataStore.users.find { it.email == "user@example.com" }
        } else null
    }

    // --- Authorization ---
    fun isAuthorized(token: String, requiredRole: Role? = null, ownerId: UUID? = null): Boolean {
        if (!DataStore.activeTokens.contains(token)) return false // Invalid session
        
        val claims = verifyJwt(token) ?: return false
        val tokenUserId = UUID.fromString(claims["uid"])
        val tokenUserRole = Role.valueOf(claims["role"] ?: "USER")

        // Check ownership if required
        if (ownerId != null && tokenUserId != ownerId && tokenUserRole != Role.ADMIN) {
            return false
        }

        // Check role if required
        if (requiredRole != null && tokenUserRole != requiredRole && tokenUserRole != Role.ADMIN) {
            return false
        }
        
        return true
    }
}

// --- Main Execution ---
fun main() {
    println("--- Variation 4: Pragmatic Minimalist ---")

    // 1. Setup
    println("\n1. Setting up data...")
    val admin = User(UUID.randomUUID(), "admin@example.com", SecurityUtils.pwdHash("adminpass"), Role.ADMIN, true)
    val user = User(UUID.randomUUID(), "user@example.com", SecurityUtils.pwdHash("userpass"), Role.USER, true)
    DataStore.users.addAll(listOf(admin, user))
    val userPost = Post(UUID.randomUUID(), user.id, "User's Title", "Some content")
    DataStore.posts.add(userPost)
    println("Setup complete.")

    // 2. Authentication
    println("\n2. Testing Authentication...")
    val userToken = SecurityUtils.login("user@example.com", "userpass")
    val adminToken = SecurityUtils.login("admin@example.com", "adminpass")
    println("User login successful: ${userToken != null}")
    println("Admin login successful: ${adminToken != null}")
    
    val failedLoginToken = SecurityUtils.login("user@example.com", "wrongpass")
    println("Failed login attempt: ${failedLoginToken == null}")

    // 3. Authorization (RBAC)
    println("\n3. Testing Authorization...")
    if (userToken != null && adminToken != null) {
        // User tries to access a generic USER resource
        val canUserAccessUserResource = SecurityUtils.isAuthorized(userToken, requiredRole = Role.USER)
        println("User can access USER resource: $canUserAccessUserResource")

        // User tries to access an ADMIN resource
        val canUserAccessAdminResource = SecurityUtils.isAuthorized(userToken, requiredRole = Role.ADMIN)
        println("User can access ADMIN resource: $canUserAccessAdminResource")

        // Admin tries to access an ADMIN resource
        val canAdminAccessAdminResource = SecurityUtils.isAuthorized(adminToken, requiredRole = Role.ADMIN)
        println("Admin can access ADMIN resource: $canAdminAccessAdminResource")

        // Ownership check: User tries to modify their own post
        val canUserModifyOwnPost = SecurityUtils.isAuthorized(userToken, ownerId = userPost.userId)
        println("User can modify their own post: $canUserModifyOwnPost")

        // Ownership check: Admin tries to modify user's post (succeeds due to ADMIN override)
        val canAdminModifyUserPost = SecurityUtils.isAuthorized(adminToken, ownerId = userPost.userId)
        println("Admin can modify user's post: $canAdminModifyUserPost")
    }

    // 4. Session Management
    println("\n4. Testing Session Management...")
    if (userToken != null) {
        println("Token is active before logout: ${DataStore.activeTokens.contains(userToken)}")
        SecurityUtils.logout(userToken)
        println("Token is active after logout: ${DataStore.activeTokens.contains(userToken)}")
        println("Authorization check on logged-out token: ${SecurityUtils.isAuthorized(userToken)}")
    }
    
    // 5. OAuth
    println("\n5. Testing OAuth...")
    val oauthUser = SecurityUtils.processOauth("apple", "valid_apple_id_token")
    println("OAuth user found: ${oauthUser?.email}")
}