package com.auth.functional

import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// --- Domain & Data ---

enum class Role { ADMIN, USER }
enum class Status { DRAFT, PUBLISHED }

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
    val status: Status
)

sealed class AuthResult<out T> {
    data class Success<T>(val data: T) : AuthResult<T>()
    data class Failure(val message: String) : AuthResult<Nothing>()
}

object MockDb {
    val users = mutableMapOf<UUID, User>()
    val posts = mutableMapOf<UUID, Post>()
}

object SessionStore {
    val sessions = mutableMapOf<String, UUID>() // JWT -> UserId
}

// --- Core Functions ---

fun hash(password: String, salt: String): String {
    val spec = PBEKeySpec(password.toCharArray(), salt.toByteArray(), 65536, 256)
    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    val hash = factory.generateSecret(spec).encoded
    return Base64.getEncoder().encodeToString(hash)
}

fun verifyHash(password: String, salt: String, expectedHash: String): Boolean {
    val newHash = hash(password, salt)
    return MessageDigest.isEqual(newHash.toByteArray(), expectedHash.toByteArray())
}

fun createJwt(user: User, secret: String): String {
    fun toBase64Url(input: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(input.toByteArray())

    val header = toBase64Url("""{"alg":"HS256","typ":"JWT"}""")
    val payload = toBase64Url("""
        {"sub":"${user.id}","role":"${user.role}","exp":${Instant.now().plusSeconds(3600).epochSecond}}
    """.trimIndent())
    
    val signatureInput = "$header.$payload"
    val hmac = Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
    }
    val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac.doFinal(signatureInput.toByteArray()))
    
    return "$signatureInput.$signature"
}

fun validateJwt(token: String, secret: String): AuthResult<Map<String, String>> {
    val parts = token.split('.')
    if (parts.size != 3) return AuthResult.Failure("Invalid token format")

    val (header, payload, signature) = parts
    val signatureInput = "$header.$payload"

    val hmac = Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
    }
    val expectedSignature = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac.doFinal(signatureInput.toByteArray()))

    if (!MessageDigest.isEqual(signature.toByteArray(), expectedSignature.toByteArray())) {
        return AuthResult.Failure("Invalid signature")
    }

    val decodedPayload = String(Base64.getUrlDecoder().decode(payload))
    val claims = decodedPayload.removeSurrounding("{", "}").split(",").associate {
        val (k, v) = it.split(":", limit = 2)
        k.trim().removeSurrounding("\"") to v.trim().removeSurrounding("\"")
    }

    val exp = claims["exp"]?.toLongOrNull()
    if (exp != null && Instant.ofEpochSecond(exp).isBefore(Instant.now())) {
        return AuthResult.Failure("Token expired")
    }

    return AuthResult.Success(claims)
}

fun login(email: String, pass: String, salt: String, secret: String): AuthResult<String> {
    val user = MockDb.users.values.find { it.email == email }
        ?: return AuthResult.Failure("User not found")

    if (!user.isActive) return AuthResult.Failure("User account is inactive")

    return if (verifyHash(pass, salt, user.passwordHash)) {
        val token = createJwt(user, secret)
        SessionStore.sessions[token] = user.id
        AuthResult.Success(token)
    } else {
        AuthResult.Failure("Invalid password")
    }
}

fun handleOauth(provider: String, code: String): AuthResult<Map<String, String>> {
    println("Handling OAuth login for $provider with code $code")
    return if (provider == "github" && code == "valid_gh_code") {
        AuthResult.Success(mapOf("email" to "git.user@hub.com", "id" to "gh_12345"))
    } else {
        AuthResult.Failure("Invalid OAuth code")
    }
}

// --- RBAC Higher-Order Function ---

fun <T> withAuthorization(
    token: String,
    secret: String,
    requiredRole: Role,
    action: (User) -> AuthResult<T>
): AuthResult<T> {
    val validationResult = validateJwt(token, secret)
    return when (validationResult) {
        is AuthResult.Failure -> validationResult
        is AuthResult.Success -> {
            val userId = UUID.fromString(validationResult.data["sub"])
            val user = MockDb.users[userId] ?: return AuthResult.Failure("User from token not found")
            
            if (user.role == requiredRole || user.role == Role.ADMIN) { // Admins can do anything
                action(user)
            } else {
                AuthResult.Failure("Authorization failed: required role $requiredRole")
            }
        }
    }
}

fun main() {
    println("--- Variation 2: Functional Programmer ---")
    
    // Setup
    val APP_SALT = "a-static-salt-for-demo-purposes"
    val JWT_SECRET = "a-very-functional-and-secret-key"
    
    val admin = User(UUID.randomUUID(), "admin@fn.com", hash("adminpass", APP_SALT), Role.ADMIN, true, Instant.now())
    val user = User(UUID.randomUUID(), "user@fn.com", hash("userpass", APP_SALT), Role.USER, true, Instant.now())
    MockDb.users[admin.id] = admin
    MockDb.users[user.id] = user
    
    val userPost = Post(UUID.randomUUID(), user.id, "A Post", "...", Status.DRAFT)
    MockDb.posts[userPost.id] = userPost

    // 1. Login
    println("\n1. Testing Login...")
    val loginSuccess = login("user@fn.com", "userpass", APP_SALT, JWT_SECRET)
    val loginFail = login("user@fn.com", "wrong", APP_SALT, JWT_SECRET)
    println("Successful login: ${loginSuccess is AuthResult.Success}")
    println("Failed login: ${loginFail is AuthResult.Failure}")

    // 2. JWT & Session
    val userToken = (loginSuccess as AuthResult.Success).data
    println("\n2. Testing JWT and Session...")
    println("Session created: ${SessionStore.sessions.containsKey(userToken)}")
    when (val validation = validateJwt(userToken, JWT_SECRET)) {
        is AuthResult.Success -> println("Token validation successful. Role: ${validation.data["role"]}")
        is AuthResult.Failure -> println("Token validation failed: ${validation.message}")
    }

    // 3. RBAC
    println("\n3. Testing RBAC...")
    val createPostAction = { u: User ->
        println("User ${u.email} is creating a post.")
        AuthResult.Success("Post created successfully")
    }
    val adminAction = { u: User ->
        println("Admin ${u.email} is performing an admin task.")
        AuthResult.Success("Admin task complete")
    }

    val userCreateResult = withAuthorization(userToken, JWT_SECRET, Role.USER, createPostAction)
    println("User creating post: $userCreateResult")

    val userAdminResult = withAuthorization(userToken, JWT_SECRET, Role.ADMIN, adminAction)
    println("User attempting admin task: $userAdminResult")

    val adminToken = (login("admin@fn.com", "adminpass", APP_SALT, JWT_SECRET) as AuthResult.Success).data
    val adminAdminResult = withAuthorization(adminToken, JWT_SECRET, Role.ADMIN, adminAction)
    println("Admin attempting admin task: $adminAdminResult")

    // 4. OAuth
    println("\n4. Testing OAuth...")
    val oauthResult = handleOauth("github", "valid_gh_code")
    println("OAuth result: $oauthResult")
}