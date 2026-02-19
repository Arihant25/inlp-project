package com.auth.oop_purist

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// --- Domain Model ---

enum class UserRole { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }

data class User(
    val id: UUID,
    val email: String,
    val passwordHash: String,
    val role: UserRole,
    val isActive: Boolean,
    val createdAt: Instant
)

data class Post(
    val id: UUID,
    val userId: UUID,
    val title: String,
    val content: String,
    val status: PostStatus
)

// --- Core Services ---

class PasswordHasher(private val salt: ByteArray) {
    private val algorithm = "PBKDF2WithHmacSHA256"
    private val iterations = 65536
    private val keyLength = 256

    fun hashPassword(password: String): String {
        val spec = javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, iterations, keyLength)
        val factory = javax.crypto.SecretKeyFactory.getInstance(algorithm)
        val hash = factory.generateSecret(spec).encoded
        return Base64.getEncoder().encodeToString(hash)
    }

    fun verifyPassword(password: String, storedHash: String): Boolean {
        val newHash = hashPassword(password)
        return MessageDigest.isEqual(newHash.toByteArray(), storedHash.toByteArray())
    }
}

class JwtManager(private val secretKey: String) {
    private val hmacAlgorithm = "HmacSHA256"

    private fun toBase64Url(data: ByteArray): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data)
    }

    fun generateAuthenticationToken(user: User): String {
        val header = """{"alg":"HS256","typ":"JWT"}"""
        val now = Instant.now()
        val expiration = now.plusSeconds(3600) // 1 hour
        val payload = """
            {"sub":"${user.id}","email":"${user.email}","role":"${user.role}","iat":${now.epochSecond},"exp":${expiration.epochSecond}}
        """.trimIndent()

        val encodedHeader = toBase64Url(header.toByteArray())
        val encodedPayload = toBase64Url(payload.toByteArray())
        val signingInput = "$encodedHeader.$encodedPayload"

        val mac = Mac.getInstance(hmacAlgorithm)
        mac.init(SecretKeySpec(secretKey.toByteArray(), hmacAlgorithm))
        val signature = mac.doFinal(signingInput.toByteArray())
        val encodedSignature = toBase64Url(signature)

        return "$signingInput.$encodedSignature"
    }

    fun validateTokenAndExtractClaims(token: String): Map<String, Any>? {
        val parts = token.split('.')
        if (parts.size != 3) return null

        val signingInput = "${parts[0]}.${parts[1]}"
        val mac = Mac.getInstance(hmacAlgorithm)
        mac.init(SecretKeySpec(secretKey.toByteArray(), hmacAlgorithm))
        val expectedSignature = mac.doFinal(signingInput.toByteArray())
        
        val providedSignature = Base64.getUrlDecoder().decode(parts[2])

        if (!MessageDigest.isEqual(expectedSignature, providedSignature)) {
            return null
        }

        val payloadJson = String(Base64.getUrlDecoder().decode(parts[1]))
        val expiration = payloadJson.substringAfter("\"exp\":").substringBefore("}").toLongOrNull()
        if (expiration != null && Instant.ofEpochSecond(expiration).isBefore(Instant.now())) {
            return null // Token expired
        }
        
        // Simple JSON parsing for demonstration
        val claims = mutableMapOf<String, Any>()
        payloadJson.removeSurrounding("{", "}").split(",").forEach {
            val (key, value) = it.split(":", limit = 2)
            claims[key.trim().removeSurrounding("\"")] = value.trim().removeSurrounding("\"")
        }
        return claims
    }
}

class SessionManager {
    private val activeSessions = mutableMapOf<String, UUID>() // Token -> UserId

    fun createSession(token: String, userId: UUID) {
        activeSessions[token] = userId
    }

    fun getSession(token: String): UUID? {
        return activeSessions[token]
    }

    fun endSession(token: String) {
        activeSessions.remove(token)
    }
}

class AccessControlManager {
    fun canUserModifyPost(user: User, post: Post): Boolean {
        return user.id == post.userId || user.role == UserRole.ADMIN
    }

    fun canUserPublishPost(user: User): Boolean {
        return user.role == UserRole.ADMIN || user.role == UserRole.USER
    }

    fun hasAdminRights(user: User): Boolean {
        return user.role == UserRole.ADMIN
    }
}

class MockOAuth2Client(private val providerName: String) {
    fun handleRedirect(authCode: String): Map<String, String>? {
        println("[$providerName OAuth2] Received auth code: $authCode. Exchanging for user profile.")
        if (authCode == "valid_google_code") {
            return mapOf(
                "email" to "oauth.user@google.com",
                "name" to "OAuth User"
            )
        }
        return null
    }
}

// --- Application Layer ---

class AuthenticationService(
    private val userRepository: Map<String, User>,
    private val passwordHasher: PasswordHasher,
    private val jwtManager: JwtManager,
    private val sessionManager: SessionManager
) {
    fun login(email: String, password: String): String? {
        val user = userRepository[email] ?: return null
        if (!user.isActive) return null

        if (passwordHasher.verifyPassword(password, user.passwordHash)) {
            val token = jwtManager.generateAuthenticationToken(user)
            sessionManager.createSession(token, user.id)
            return token
        }
        return null
    }
}

// --- Main Execution ---

fun main() {
    println("--- Variation 1: OOP Purist ---")

    // Setup
    val salt = SecureRandom().generateSeed(16)
    val passwordHasher = PasswordHasher(salt)
    val jwtManager = JwtManager("a-very-secret-key-that-is-long-enough")
    val sessionManager = SessionManager()
    val accessControl = AccessControlManager()

    val adminUser = User(UUID.randomUUID(), "admin@example.com", passwordHasher.hashPassword("admin123"), UserRole.ADMIN, true, Instant.now())
    val normalUser = User(UUID.randomUUID(), "user@example.com", passwordHasher.hashPassword("user123"), UserRole.USER, true, Instant.now())
    val userRepository = mapOf(adminUser.email to adminUser, normalUser.email to normalUser)
    
    val userPost = Post(UUID.randomUUID(), normalUser.id, "User's Post", "Content by user.", PostStatus.DRAFT)

    val authService = AuthenticationService(userRepository, passwordHasher, jwtManager, sessionManager)

    // 1. Login
    println("\n1. Testing Login...")
    val badLoginToken = authService.login("user@example.com", "wrongpassword")
    println("Login with wrong password successful: ${badLoginToken != null}")

    val userToken = authService.login("user@example.com", "user123")
    println("Login with correct password successful: ${userToken != null}")
    
    val adminToken = authService.login("admin@example.com", "admin123")

    // 2. JWT Validation
    println("\n2. Testing JWT Validation...")
    val claims = userToken?.let { jwtManager.validateTokenAndExtractClaims(it) }
    println("Token validation successful: ${claims != null}")
    println("User from token: ${claims?.get("email")}")

    // 3. Role-Based Access Control (RBAC)
    println("\n3. Testing RBAC...")
    println("Can User modify their own post? ${accessControl.canUserModifyPost(normalUser, userPost)}")
    println("Can Admin modify user's post? ${accessControl.canUserModifyPost(adminUser, userPost)}")
    println("Does User have admin rights? ${accessControl.hasAdminRights(normalUser)}")
    println("Does Admin have admin rights? ${accessControl.hasAdminRights(adminUser)}")

    // 4. Session Management
    println("\n4. Testing Session Management...")
    val sessionUserId = userToken?.let { sessionManager.getSession(it) }
    println("Session found for user token: ${sessionUserId == normalUser.id}")
    userToken?.let { sessionManager.endSession(it) }
    val endedSessionUserId = userToken?.let { sessionManager.getSession(it) }
    println("Session exists after ending: ${endedSessionUserId != null}")

    // 5. OAuth2 Client Simulation
    println("\n5. Testing OAuth2 Client...")
    val oauthClient = MockOAuth2Client("Google")
    val oauthProfile = oauthClient.handleRedirect("valid_google_code")
    println("OAuth profile received: $oauthProfile")
}