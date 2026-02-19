package com.example.variation1

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

// ========= VALIDATION INFRASTRUCTURE =========

class ValidationException(val errors: List<String>) : Exception("Validation failed")

interface Validator<T> {
    fun validate(data: T)
}

class UserValidator : Validator<User> {
    private val emailRegex = Regex("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,6}$")

    override fun validate(data: User) {
        val errors = mutableListOf<String>()
        if (!emailRegex.matches(data.email)) {
            errors.add("User email '${data.email}' is invalid.")
        }
        if (data.passwordHash.length < 8) {
            errors.add("User password hash is too short.")
        }
        if (errors.isNotEmpty()) {
            throw ValidationException(errors)
        }
    }
}

class PostValidator : Validator<Post> {
    override fun validate(data: Post) {
        val errors = mutableListOf<String>()
        if (data.title.isBlank()) {
            errors.add("Post title cannot be empty.")
        }
        if (data.content.length < 10) {
            errors.add("Post content must be at least 10 characters long.")
        }
        if (errors.isNotEmpty()) {
            throw ValidationException(errors)
        }
    }
}

// ========= SERIALIZATION INFRASTRUCTURE =========

interface Serializer<T> {
    fun serialize(data: T): String
    fun deserialize(source: String): T
}

class UserJsonSerializer : Serializer<User> {
    override fun serialize(data: User): String {
        return """
        {
          "id": "${data.id}",
          "email": "${data.email}",
          "password_hash": "${data.passwordHash}",
          "role": "${data.role}",
          "is_active": ${data.isActive},
          "created_at": ${data.createdAt}
        }
        """.trimIndent()
    }

    override fun deserialize(source: String): User {
        val map = parseJsonToMap(source)
        return User(
            id = UUID.fromString(map["id"] ?: throw IllegalArgumentException("Missing id")),
            email = map["email"] ?: throw IllegalArgumentException("Missing email"),
            passwordHash = map["password_hash"] ?: throw IllegalArgumentException("Missing password_hash"),
            role = UserRole.valueOf(map["role"] ?: throw IllegalArgumentException("Missing role")),
            isActive = map["is_active"]?.toBoolean() ?: throw IllegalArgumentException("Missing is_active"),
            createdAt = map["created_at"]?.toLong() ?: throw IllegalArgumentException("Missing created_at")
        )
    }
}

class PostXmlSerializer : Serializer<Post> {
    override fun serialize(data: Post): String {
        return """
        <post>
            <id>${data.id}</id>
            <user_id>${data.userId}</user_id>
            <title>${data.title}</title>
            <content>${data.content}</content>
            <status>${data.status}</status>
        </post>
        """.trimIndent()
    }

    override fun deserialize(source: String): Post {
        return Post(
            id = UUID.fromString(extractTagValue(source, "id")),
            userId = UUID.fromString(extractTagValue(source, "user_id")),
            title = extractTagValue(source, "title"),
            content = extractTagValue(source, "content"),
            status = PostStatus.valueOf(extractTagValue(source, "status"))
        )
    }
}

// ========= UTILITY FUNCTIONS =========

fun formatErrors(errors: List<String>): String {
    return "Validation Errors:\n" + errors.joinToString("\n") { " - $it" }
}

private fun parseJsonToMap(json: String): Map<String, String> {
    val map = mutableMapOf<String, String>()
    val keyValueRegex = Regex("\"(.*?)\"\\s*:\\s*(?:\"(.*?)\"|(\\d+\\.?\\d*|true|false))")
    keyValueRegex.findAll(json).forEach { matchResult ->
        val key = matchResult.groupValues[1]
        val value = matchResult.groupValues[2].ifEmpty { matchResult.groupValues[3] }
        map[key] = value
    }
    return map
}

private fun extractTagValue(xml: String, tagName: String): String {
    val startTag = "<$tagName>"
    val endTag = "</$tagName>"
    val startIndex = xml.indexOf(startTag) + startTag.length
    val endIndex = xml.indexOf(endTag)
    if (startIndex == -1 || endIndex == -1) throw IllegalArgumentException("Tag '$tagName' not found in XML")
    return xml.substring(startIndex, endIndex)
}

// ========= MAIN EXECUTION =========

fun main() {
    println("--- Variation 1: OOP Purist ---")

    // 1. Create and Validate a User
    val user = User(
        id = UUID.randomUUID(),
        email = "test@example.com",
        passwordHash = "a_very_secure_hash_string",
        role = UserRole.ADMIN,
        isActive = true,
        createdAt = System.currentTimeMillis()
    )
    val userValidator = UserValidator()
    try {
        userValidator.validate(user)
        println("User validation successful.")
    } catch (e: ValidationException) {
        println(formatErrors(e.errors))
    }

    // 2. Serialize and Deserialize User to JSON
    val userSerializer = UserJsonSerializer()
    val userJson = userSerializer.serialize(user)
    println("\nSerialized User (JSON):\n$userJson")
    val deserializedUser = userSerializer.deserialize(userJson)
    println("\nDeserialized User is equal to original: ${user == deserializedUser}")

    // 3. Create and Validate a Post
    val invalidPost = Post(
        id = UUID.randomUUID(),
        userId = user.id,
        title = "",
        content = "short",
        status = PostStatus.DRAFT
    )
    val postValidator = PostValidator()
    try {
        postValidator.validate(invalidPost)
    } catch (e: ValidationException) {
        println("\n--- Validation errors for invalid post ---")
        println(formatErrors(e.errors))
    }

    // 4. Serialize and Deserialize Post to XML
    val post = Post(
        id = UUID.randomUUID(),
        userId = user.id,
        title = "My First Post",
        content = "This is the content of my very first post.",
        status = PostStatus.PUBLISHED
    )
    val postSerializer = PostXmlSerializer()
    val postXml = postSerializer.serialize(post)
    println("\nSerialized Post (XML):\n$postXml")
    val deserializedPost = postSerializer.deserialize(postXml)
    println("\nDeserialized Post is equal to original: ${post == deserializedPost}")
}