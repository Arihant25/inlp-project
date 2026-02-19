package com.example.variation2

import java.util.UUID

// ========= DOMAIN MODELS (as data classes) =========

enum class Role { ADMIN, USER }
enum class Status { DRAFT, PUBLISHED }

data class User(
    val id: UUID,
    val email: String,
    val passwordHash: String,
    val role: Role,
    val isActive: Boolean,
    val createdAt: Long
)

data class Post(
    val id: UUID,
    val userId: UUID,
    val title: String,
    val content: String,
    val status: Status
)

// ========= VALIDATION LOGIC (Functional Approach) =========

typealias ValidationResult<T> = Result<T, List<String>>

private fun validateEmail(email: String?): String? =
    if (email != null && Regex("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,6}$").matches(email)) null
    else "Email is invalid."

private fun validateRequired(value: Any?, fieldName: String): String? =
    if (value == null || (value is String && value.isBlank())) "$fieldName is required." else null

private fun validateMinLength(value: String?, min: Int, fieldName: String): String? =
    if (value == null || value.length < min) "$fieldName must be at least $min characters long." else null

fun validateUserFromMap(data: Map<String, Any?>): ValidationResult<User> {
    val errors = listOfNotNull(
        validateRequired(data["email"], "email"),
        data["email"]?.let { validateEmail(it as? String) },
        validateRequired(data["password_hash"], "password_hash"),
        data["password_hash"]?.let { validateMinLength(it as? String, 8, "password_hash") },
        validateRequired(data["role"], "role")
    )

    return if (errors.isEmpty()) {
        try {
            Result.success(
                User(
                    id = data["id"] as? UUID ?: UUID.randomUUID(),
                    email = data["email"] as String,
                    passwordHash = data["password_hash"] as String,
                    role = Role.valueOf(data["role"] as String),
                    isActive = data["is_active"] as? Boolean ?: true,
                    createdAt = data["created_at"] as? Long ?: System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Result.failure(listOf("Type conversion failed: ${e.message}"))
        }
    } else {
        Result.failure(errors)
    }
}

// ========= SERIALIZATION LOGIC (Functional Approach) =========

fun userToJson(user: User): String {
    fun escape(s: String) = s.replace("\"", "\\\"")
    return """
    {"id":"${user.id}","email":"${escape(user.email)}","password_hash":"${escape(user.passwordHash)}","role":"${user.role}","is_active":${user.isActive},"created_at":${user.createdAt}}
    """.trimIndent().replace("\n", "")
}

fun jsonToUser(json: String): ValidationResult<User> {
    val map = mutableMapOf<String, Any>()
    val pattern = Regex("\"(\\w+)\":\\s*(?:\"([^\"]*)\"|(\\d+|true|false))")
    pattern.findAll(json).forEach {
        val key = it.groupValues[1]
        val value = it.groupValues[2].ifEmpty { it.groupValues[3] }
        map[key] = when {
            value == "true" -> true
            value == "false" -> false
            value.toLongOrNull() != null -> value.toLong()
            else -> value
        }
    }
    // Coerce types for validation
    map["id"] = map["id"]?.let { UUID.fromString(it as String) }
    return validateUserFromMap(map)
}

fun postToXml(post: Post): String =
    """
    <post><id>${post.id}</id><user_id>${post.userId}</user_id><title>${post.title}</title><content>${post.content}</content><status>${post.status}</status></post>
    """.trimIndent().replace("\n", "")

fun xmlToPost(xml: String): Result<Post, String> {
    fun extract(tag: String): String? {
        val match = Regex("<$tag>(.*?)</$tag>").find(xml)
        return match?.groupValues?.get(1)
    }
    return try {
        Result.success(
            Post(
                id = UUID.fromString(extract("id")!!),
                userId = UUID.fromString(extract("user_id")!!),
                title = extract("title")!!,
                content = extract("content")!!,
                status = Status.valueOf(extract("status")!!)
            )
        )
    } catch (e: Exception) {
        Result.failure("XML parsing failed: ${e.message}")
    }
}

// ========= ERROR FORMATTING =========

fun formatErrorMessages(errors: List<String>): String =
    "Found ${errors.size} errors:\n" + errors.joinToString("\n") { "  - $it" }

// ========= MAIN EXECUTION =========

fun main() {
    println("--- Variation 2: Functional Programmer ---")

    // 1. Validate valid user data
    val validUserData = mapOf(
        "email" to "functional.dev@example.com",
        "password_hash" to "strongpassword123",
        "role" to "USER"
    )
    validateUserFromMap(validUserData).fold(
        onSuccess = { println("User validation successful: $it") },
        onFailure = { println(formatErrorMessages(it)) }
    )

    // 2. Validate invalid user data
    val invalidUserData = mapOf(
        "email" to "bad-email",
        "password_hash" to "short"
    )
    println("\n--- Validating incorrect data ---")
    validateUserFromMap(invalidUserData).fold(
        onSuccess = { println("This should not happen.") },
        onFailure = { println(formatErrorMessages(it)) }
    )

    // 3. JSON Serialization/Deserialization
    val user = validateUserFromMap(validUserData).getOrThrow()
    val userJson = userToJson(user)
    println("\nSerialized User (JSON): $userJson")
    jsonToUser(userJson).fold(
        onSuccess = { println("Deserialized User is equal to original: ${it == user}") },
        onFailure = { println("Deserialization failed: $it") }
    )

    // 4. XML Serialization/Deserialization
    val post = Post(UUID.randomUUID(), user.id, "Functional Post", "Content is king.", Status.PUBLISHED)
    val postXml = postToXml(post)
    println("\nSerialized Post (XML): $postXml")
    xmlToPost(postXml).fold(
        onSuccess = { println("Deserialized Post is equal to original: ${it == post}") },
        onFailure = { println("Deserialization failed: $it") }
    )
}