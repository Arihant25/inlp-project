package com.auth.service_oriented

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

data class User(
    val id: UUID,
    val email: String,
    val passwordHash: String,
    val role: Role,
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

// --- Security Context ---

object SecurityContext {
    private var currentUser: User? = null

    fun setCurrentUser(user: User?) {
        currentUser = user
    }

    fun getCurrentUser(): User? = currentUser

    fun clear() {
        currentUser = null
    }
}

// --- Service Interfaces ---

interface CredentialManager {
    fun createHash(password: String): String
    fun verify(password: String, hash: String): Boolean
}

interface TokenProvider {
    fun issueToken(user: User): String
    fun introspectToken(token: String): UUID? // Returns UserId
}

interface AuthorizationService {
    fun check(user: User, requiredRole: Role): Boolean
    fun canModify(user: User, post: Post): Boolean
}

interface AuthenticationService {
    fun authenticateByPassword(email: String, password: String): String?
    fun authenticateByOAuth2(provider: String, code: String): User?
}

// --- Service Implementations ---

class PBKDF2CredentialManager(private val salt: String) : CredentialManager {
    override fun createHash(password: String): String {
        val spec = PBEKeySpec(password.toCharArray(), salt.toByteArray(), 10000, 512)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        return Base64.getEncoder().encodeToString(factory.generateSecret(spec).encoded)
    }

    override fun verify(password: String, hash: String): Boolean {
        val newHash = createHash(password)
        return MessageDigest.isEqual(newHash.toByteArray(), hash.toByteArray())
    }
}

class JwtTokenProvider(private val secret: ByteArray) : TokenProvider {
    private val algo = "HmacSHA256"

    override fun issueToken(user: User): String {
        val header = """{"alg":"HS256","typ":"JWT"}""".base64Url()
        val payload = """{"sub":"${user.id}","iat":${Instant.now().epochSecond}}""".base64Url()
        val content = "$header.$payload"
        val signature = Mac.getInstance(algo).run {
            init(SecretKeySpec(secret, algo))
            doFinal(content.toByteArray()).base64Url()
        }
        return "$content.$signature"
    }

    override fun introspectToken(token: String): UUID? {
        val parts = token.split('.')
        if (parts.size != 3) return null
        
        val content = "${parts[0]}.${parts[1]}"
        val signature = parts[2]

        val expectedSignature = Mac.getInstance(algo).run {
            init(SecretKeySpec(secret, algo))
            doFinal(content.toByteArray()).base64Url()
        }

        if (!MessageDigest.isEqual(signature.toByteArray(), expectedSignature.toByteArray())) return null

        val payloadJson = String(Base64.getUrlDecoder().decode(parts[1]))
        val userId = payloadJson.substringAfter("\"sub\":\"").substringBefore("\"")
        return UUID.fromString(userId)
    }

    private fun String.base64Url() = Base64.getUrlEncoder().withoutPadding().encodeToString(this.toByteArray())
    private fun ByteArray.base64Url() = Base64.getUrlEncoder().withoutPadding().encodeToString(this)
}

class RbacAuthorizationService : AuthorizationService {
    override fun check(user: User, requiredRole: Role): Boolean {
        return user.role == Role.ADMIN || user.role == requiredRole
    }

    override fun canModify(user: User, post: Post): Boolean {
        return user.id == post.userId || user.role == Role.ADMIN
    }
}

class DefaultAuthenticationService(
    private val userRepo: Map<String, User>,
    private val credentialManager: CredentialManager,
    private val tokenProvider: TokenProvider
) : AuthenticationService {
    override fun authenticateByPassword(email: String, password: String): String? {
        val user = userRepo[email] ?: return null
        if (!user.isActive) return null
        
        return if (credentialManager.verify(password, user.passwordHash)) {
            tokenProvider.issueToken(user)
        } else {
            null
        }
    }

    override fun authenticateByOAuth2(provider: String, code: String): User? {
        println("Simulating OAuth2 flow for $provider...")
        return if (provider == "facebook" && code == "valid_fb_code") {
            // In a real scenario, you'd find or create a user based on the OAuth profile
            userRepo.values.firstOrNull() 
        } else {
            null
        }
    }
}

// --- Main Execution ---

fun main() {
    println("--- Variation 3: Service-Oriented Architect ---")

    // --- Dependency Injection / Setup ---
    val credentialManager: CredentialManager = PBKDF2CredentialManager("another-secure-salt")
    val tokenProvider: TokenProvider = JwtTokenProvider("my-super-strong-service-secret-key-!@#$".toByteArray())
    val authorizationService: AuthorizationService = RbacAuthorizationService()

    val admin = User(UUID.randomUUID(), "admin@service.com", credentialManager.createHash("pass1"), Role.ADMIN, true, Instant.now())
    val user = User(UUID.randomUUID(), "user@service.com", credentialManager.createHash("pass2"), Role.USER, true, Instant.now())
    val userRepo = mapOf(admin.email to admin, user.email to user)
    val userPost = Post(UUID.randomUUID(), user.id, "My Post", "...", PostStatus.DRAFT)

    val authenticationService: AuthenticationService = DefaultAuthenticationService(userRepo, credentialManager, tokenProvider)

    // --- Flow ---
    println("\n1. Password Authentication")
    val token = authenticationService.authenticateByPassword("user@service.com", "pass2")
    println("Authentication successful, token issued: ${token != null}")

    println("\n2. Token Introspection & Context Setting")
    val userIdFromToken = token?.let { tokenProvider.introspectToken(it) }
    val authenticatedUser = userRepo.values.find { it.id == userIdFromToken }
    SecurityContext.setCurrentUser(authenticatedUser)
    println("Security context set for user: ${SecurityContext.getCurrentUser()?.email}")

    println("\n3. Authorization Checks")
    val currentUser = SecurityContext.getCurrentUser()
    if (currentUser != null) {
        val canModifyOwnPost = authorizationService.canModify(currentUser, userPost)
        println("User can modify their own post: $canModifyOwnPost")
        
        val hasAdminRole = authorizationService.check(currentUser, Role.ADMIN)
        println("User has ADMIN role: $hasAdminRole")
    }

    println("\n4. Admin Flow")
    SecurityContext.setCurrentUser(admin)
    val currentAdmin = SecurityContext.getCurrentUser()
    if (currentAdmin != null) {
        val canAdminModifyUserPost = authorizationService.canModify(currentAdmin, userPost)
        println("Admin can modify user's post: $canAdminModifyUserPost")
        
        val adminHasAdminRole = authorizationService.check(currentAdmin, Role.ADMIN)
        println("Admin has ADMIN role: $adminHasAdminRole")
    }
    SecurityContext.clear()

    println("\n5. OAuth2 Authentication")
    val oauthUser = authenticationService.authenticateByOAuth2("facebook", "valid_fb_code")
    println("OAuth authentication successful for user: ${oauthUser?.email}")
}