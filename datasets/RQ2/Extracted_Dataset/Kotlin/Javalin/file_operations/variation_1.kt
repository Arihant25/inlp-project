package com.example.variation1

import io.javalin.Javalin
import io.javalin.http.HttpStatus
import io.javalin.http.bodyAsBytes
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.imageio.ImageIO

// --- Domain Model ---
enum class UserRole { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }

data class User(
    val id: UUID,
    val email: String,
    val password_hash: String,
    val role: UserRole,
    val is_active: Boolean,
    val created_at: Timestamp
)

data class Post(
    val id: UUID,
    val user_id: UUID,
    val title: String,
    val content: String,
    val status: PostStatus
)

// --- Mock Database ---
val usersDB = mutableMapOf<UUID, User>()
val postsDB = mutableMapOf<UUID, Post>()

// --- Helper Functions ---

fun parseUsersFromCsv(inputStream: InputStream): List<User> {
    val newUsers = mutableListOf<User>()
    inputStream.bufferedReader().useLines { lines ->
        lines.drop(1) // Skip header
            .forEach { line ->
                val tokens = line.split(',')
                if (tokens.size >= 3) {
                    val user = User(
                        id = UUID.randomUUID(),
                        email = tokens[0].trim(),
                        password_hash = "hashed_${tokens[1].trim()}", // In real app, hash properly
                        role = UserRole.valueOf(tokens[2].trim().uppercase()),
                        is_active = true,
                        created_at = Timestamp.from(Instant.now())
                    )
                    newUsers.add(user)
                }
            }
    }
    return newUsers
}

fun resizeImage(inputStream: InputStream, width: Int, height: Int): ByteArray {
    val originalImage = ImageIO.read(inputStream)
    val scaledImage = originalImage.getScaledInstance(width, height, Image.SCALE_SMOOTH)
    val outputImage = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    outputImage.graphics.drawImage(scaledImage, 0, 0, null)

    val outputStream = ByteArrayOutputStream()
    ImageIO.write(outputImage, "jpg", outputStream)
    return outputStream.toByteArray()
}

fun generatePostsCsv(): InputStream {
    val stringBuilder = StringBuilder("id,user_id,title,status\n")
    postsDB.values.forEach { post ->
        stringBuilder.append("${post.id},${post.user_id},\"${post.title}\",${post.status}\n")
    }
    return ByteArrayInputStream(stringBuilder.toString().toByteArray())
}


// --- Main Application ---
fun main() {
    // Add some mock data
    val adminId = UUID.randomUUID()
    usersDB[adminId] = User(adminId, "admin@test.com", "hash", UserRole.ADMIN, true, Timestamp.from(Instant.now()))
    postsDB[UUID.randomUUID()] = Post(UUID.randomUUID(), adminId, "First Post", "Content here", PostStatus.PUBLISHED)

    val app = Javalin.create { config ->
        config.http.maxRequestSize = 10 * 1024 * 1024 // 10 MB
    }.start(7070)

    println("Server started at http://localhost:7070")

    // 1. File Upload and CSV Parsing: Bulk create users
    app.post("/users/upload-csv") { ctx ->
        val uploadedFile = ctx.uploadedFile("users_csv")
        if (uploadedFile == null) {
            ctx.status(HttpStatus.BAD_REQUEST).result("Missing 'users_csv' file")
            return@post
        }

        try {
            val newUsers = uploadedFile.content().use { parseUsersFromCsv(it) }
            newUsers.forEach { user -> usersDB[user.id] = user }
            ctx.status(HttpStatus.CREATED).json(mapOf(
                "message" to "Successfully created ${newUsers.size} users.",
                "user_ids" to newUsers.map { it.id }
            ))
        } catch (e: Exception) {
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).result("Failed to process CSV: ${e.message}")
        }
    }

    // 2. Image Resizing and Temporary File Management
    app.post("/posts/{id}/image") { ctx ->
        val postId = UUID.fromString(ctx.pathParam("id"))
        val post = postsDB[postId]
        if (post == null) {
            ctx.status(HttpStatus.NOT_FOUND).result("Post not found")
            return@post
        }

        val imageFile = ctx.uploadedFile("post_image")
        if (imageFile == null) {
            ctx.status(HttpStatus.BAD_REQUEST).result("Missing 'post_image' file")
            return@post
        }

        // Use a temporary file to demonstrate management
        val tempFile = Files.createTempFile("post-img-", ".jpg")
        try {
            // Process image
            val resizedImageBytes = imageFile.content().use { resizeImage(it, 800, 600) }
            
            // Save to a "persistent" location (simulated by temp file)
            Files.copy(ByteArrayInputStream(resizedImageBytes), tempFile, StandardCopyOption.REPLACE_EXISTING)

            ctx.status(HttpStatus.OK).json(mapOf(
                "message" to "Image uploaded and resized successfully.",
                "path" to tempFile.toAbsolutePath().toString(),
                "size" to resizedImageBytes.size
            ))
        } catch (e: Exception) {
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).result("Failed to process image: ${e.message}")
        } finally {
            // In a real app, you might not delete it immediately if it's the final storage.
            // But for a true temp file, cleanup is crucial.
            Files.deleteIfExists(tempFile)
        }
    }

    // 3. File Download with Streaming
    app.get("/posts/report.csv") { ctx ->
        try {
            val csvInputStream = generatePostsCsv()
            ctx.header("Content-Disposition", "attachment; filename=\"posts_report.csv\"")
            ctx.contentType("text/csv")
            ctx.status(HttpStatus.OK).result(csvInputStream)
        } catch (e: Exception) {
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).result("Failed to generate report: ${e.message}")
        }
    }
}