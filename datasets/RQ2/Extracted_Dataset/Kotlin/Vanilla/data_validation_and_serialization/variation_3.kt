package com.example.variation3

import java.util.UUID

// ========= DOMAIN MODELS =========

enum class UserRole { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }

data class User(
    val id: UUID,
    val email: String,
    val passwordHash: String,
    val role: UserRole,
    val isActive: Boolean,
    val createdAt: Long
)

data class Post(
    val id: UUID,
    val userId: UUID,
    val title: String,
    val content: String,
    val status: PostStatus
)

// ========= UTILITY SINGLETONS =========

object ValidationUtils {
    private val EMAIL_REGEX = "^[\\w-_.+]*[\\w-_.]@([\\w]+\\.)+[\\w]+[\\w]$".toRegex()
    private val PHONE_REGEX = "^\\+?[1-9]\\d{1,14}$".toRegex() // E.164 format

    fun validateUser(user: User): List<String> {
        val errors = mutableListOf<String>()
        if (user.email.isBlank()) {
            errors.add("User email is a required field.")
        } else if (!EMAIL_REGEX.matches(user.email)) {
            errors.add("User email has an invalid format.")
        }
        if (user.passwordHash.length < 8) {
            errors.add("User password must be at least 8 characters.")
        }
        return errors
    }

    fun validatePost(post: Post): List<String> {
        val errors = mutableListOf<String>()
        if (post.title.trim().isEmpty()) {
            errors.add("Post title cannot be blank.")
        }
        if (post.content.length < 20) {
            errors.add("Post content is too short (min 20 chars).")
        }
        return errors
    }

    // Custom validator example
    fun isValidPhoneNumber(phone: String): Boolean {
        return PHONE_REGEX.matches(phone)
    }
}

object SerializationUtils {
    fun userToJson(user: User): String {
        return "{\"id\":\"${user.id}\",\"email\":\"${user.email}\",\"password_hash\":\"${user.passwordHash}\",\"role\":\"${user.role}\",\"is_active\":${user.isActive},\"created_at\":${user.createdAt}}"
    }

    fun userFromJson(json: String): User {
        val props = json.removeSurrounding("{", "}").split(",").associate {
            val (key, value) = it.split(":", limit = 2)
            key.trim().removeSurrounding("\"") to value.trim().removeSurrounding("\"")
        }
        return User(
            id = UUID.fromString(props["id"]!!),
            email = props["email"]!!,
            passwordHash = props["password_hash"]!!,
            role = UserRole.valueOf(props["role"]!!),
            isActive = props["is_active"]!!.toBoolean(),
            createdAt = props["created_at"]!!.toLong()
        )
    }

    fun postToXml(post: Post): String {
        return "<post><id>${post.id}</id><user_id>${post.userId}</user_id><title>${post.title}</title><content>${post.content}</content><status>${post.status}</status></post>"
    }

    fun postFromXml(xml: String): Post {
        fun findValue(tag: String) = xml.substringAfter("<$tag>").substringBefore("</$tag>")
        return Post(
            id = UUID.fromString(findValue("id")),
            userId = UUID.fromString(findValue("user_id")),
            title = findValue("title"),
            content = findValue("content"),
            status = PostStatus.valueOf(findValue("status"))
        )
    }
}

// ========= ERROR FORMATTING =========

fun formatValidationErrors(header: String, errors: List<String>): String {
    if (errors.isEmpty()) return "$header: All validations passed."
    return "$header:\n" + errors.joinToString(separator = "\n") { "  * $it" }
}

// ========= MAIN EXECUTION =========

fun main() {
    println("--- Variation 3: Pragmatic Singleton ---")

    // 1. Create a valid user and validate it
    val user = User(
        id = UUID.randomUUID(),
        email = "pragmatic.dev@example.com",
        passwordHash = "a_very_secure_password",
        role = UserRole.ADMIN,
        isActive = false,
        createdAt = System.currentTimeMillis()
    )
    val userErrors = ValidationUtils.validateUser(user)
    println(formatValidationErrors("User Validation", userErrors))

    // 2. Create an invalid post and validate it
    val invalidPost = Post(
        id = UUID.randomUUID(),
        userId = user.id,
        title = " ",
        content = "short",
        status = PostStatus.DRAFT
    )
    val postErrors = ValidationUtils.validatePost(invalidPost)
    println(formatValidationErrors("\nInvalid Post Validation", postErrors))

    // 3. Custom Phone Validator
    println("\n--- Custom Phone Validator ---")
    println("Is '+14155552671' a valid phone number? ${ValidationUtils.isValidPhoneNumber("+14155552671")}")
    println("Is '555-1234' a valid phone number? ${ValidationUtils.isValidPhoneNumber("555-1234")}")

    // 4. JSON Serialization/Deserialization
    val userJson = SerializationUtils.userToJson(user)
    println("\nSerialized User (JSON): $userJson")
    val deserializedUser = SerializationUtils.userFromJson(userJson)
    println("Deserialized User is identical: ${user == deserializedUser}")

    // 5. XML Serialization/Deserialization
    val post = Post(UUID.randomUUID(), user.id, "A Valid Post Title", "This content is definitely long enough to pass validation.", PostStatus.PUBLISHED)
    val postXml = SerializationUtils.postToXml(post)
    println("\nSerialized Post (XML): $postXml")
    val deserializedPost = SerializationUtils.postFromXml(postXml)
    println("Deserialized Post is identical: ${post == deserializedPost}")
}