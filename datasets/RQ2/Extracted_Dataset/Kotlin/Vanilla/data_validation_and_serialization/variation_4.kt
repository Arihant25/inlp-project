package com.example.variation4

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

// ========= VALIDATION DSL =========

class Validator<T>(private val target: T) {
    private val errors = mutableListOf<String>()

    fun check(field: String, condition: () -> Boolean, message: String) {
        if (!condition()) {
            errors.add("$field: $message")
        }
    }

    fun getErrors(): List<String> = errors
}

fun <T> validate(target: T, block: Validator<T>.() -> Unit): List<String> {
    val validator = Validator(target)
    validator.block()
    return validator.getErrors()
}

// ========= SERIALIZATION (via Extension & Companion) =========

// JSON
fun User.toJson(): String {
    return """{"id":"$id","email":"$email","password_hash":"$passwordHash","role":"$role","is_active":$isActive,"created_at":$createdAt}"""
}

fun Post.toJson(): String {
    return """{"id":"$id","user_id":"$userId","title":"$title","content":"$content","status":"$status"}"""
}

// XML
fun User.toXml(): String {
    return "<user><id>$id</id><email>$email</email><password_hash>$passwordHash</password_hash><role>$role</role><is_active>$isActive</is_active><created_at>$createdAt</created_at></user>"
}

fun Post.toXml(): String {
    return "<post><id>$id</id><user_id>$userId</user_id><title>$title</title><content>$content</content><status>$status</status></post>"
}

// Deserialization Factories in Companion Objects
object DeserializationFactory {
    private val jsonPattern = "\"(.*?)\":\"?(.*?)\"?[,}]".toRegex()
    private fun xmlTagValue(xml: String, tag: String) = xml.substringAfter("<$tag>").substringBefore("</$tag>")

    fun userFromJson(json: String): User {
        val values = jsonPattern.findAll(json).map { it.groupValues[2] }.toList()
        return User(
            id = UUID.fromString(values[0]),
            email = values[1],
            passwordHash = values[2],
            role = UserRole.valueOf(values[3]),
            isActive = values[4].toBoolean(),
            createdAt = values[5].toLong()
        )
    }

    fun postFromXml(xml: String): Post {
        return Post(
            id = UUID.fromString(xmlTagValue(xml, "id")),
            userId = UUID.fromString(xmlTagValue(xml, "user_id")),
            title = xmlTagValue(xml, "title"),
            content = xmlTagValue(xml, "content"),
            status = PostStatus.valueOf(xmlTagValue(xml, "status"))
        )
    }
}

// ========= ERROR FORMATTING =========

fun formatErrorsForDisplay(errors: List<String>): String {
    return if (errors.isEmpty()) "No validation errors found."
    else "Validation failed:\n" + errors.joinToString("\n") { " - $it" }
}

// ========= MAIN EXECUTION =========

fun main() {
    println("--- Variation 4: DSL-ish Fluent Builder ---")

    val user = User(
        id = UUID.randomUUID(),
        email = "dsl-user@example.com",
        passwordHash = "short", // Invalid
        role = UserRole.USER,
        isActive = true,
        createdAt = System.currentTimeMillis()
    )

    // 1. Validate user with DSL
    val userErrors = validate(user) {
        check("Email", { email.contains("@") && email.contains(".") }, "Must be a valid email format.")
        check("Password Hash", { passwordHash.length >= 12 }, "Must be at least 12 characters long.")
        check("Required Fields", { id != null && role != null }, "ID and Role are required.")
    }
    println(formatErrorsForDisplay(userErrors))

    val post = Post(
        id = UUID.randomUUID(),
        userId = user.id,
        title = " ", // Invalid
        content = "This is valid content.",
        status = PostStatus.DRAFT
    )

    // 2. Validate post with DSL
    val postErrors = validate(post) {
        check("Title", { title.isNotBlank() }, "Cannot be empty or just whitespace.")
        check("Content", { content.length > 10 }, "Must be longer than 10 characters.")
    }
    println("\n" + formatErrorsForDisplay(postErrors))

    // 3. Serialization using extension functions
    val validUser = user.copy(passwordHash = "a_much_longer_and_valid_password_hash")
    val userJson = validUser.toJson()
    println("\nSerialized User (JSON):\n$userJson")

    val validPost = post.copy(title = "A Valid Title")
    val postXml = validPost.toXml()
    println("\nSerialized Post (XML):\n$postXml")

    // 4. Deserialization using factory object
    val deserializedUser = DeserializationFactory.userFromJson(userJson)
    println("\nDeserialized User from JSON is same: ${validUser == deserializedUser}")

    val deserializedPost = DeserializationFactory.postFromXml(postXml)
    println("Deserialized Post from XML is same: ${validPost == deserializedPost}")
}