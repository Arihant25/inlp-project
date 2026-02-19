package com.example.variation4

import io.javalin.Javalin
import io.javalin.http.HttpStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.future
import kotlinx.coroutines.withContext
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.imageio.ImageIO

// --- Domain Model ---
enum class UserRole { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }

data class User(val id: UUID, val email: String, val password_hash: String, val role: UserRole, val is_active: Boolean, val created_at: Timestamp)
data class Post(val id: UUID, val user_id: UUID, val title: String, val content: String, val status: PostStatus)

// --- Mock Database (Concurrent Maps for safety) ---
val users = java.util.concurrent.ConcurrentHashMap<UUID, User>()
val posts = java.util.concurrent.ConcurrentHashMap<UUID, Post>()

// --- Suspending Helper Functions for IO/CPU-bound tasks ---

suspend fun parseCsvAndStoreUsers(inputStream: InputStream): Int = withContext(Dispatchers.IO) {
    var count = 0
    inputStream.bufferedReader().useLines { lines ->
        lines.drop(1).forEach { line ->
            val (email, password, roleStr) = line.split(",").map { it.trim() }
            val user = User(
                id = UUID.randomUUID(),
                email = email,
                password_hash = "async_hashed_$password",
                role = UserRole.valueOf(roleStr.uppercase()),
                is_active = true,
                created_at = Timestamp.from(Instant.now())
            )
            users[user.id] = user
            count++
        }
    }
    count
}

suspend fun resizeImageAsync(inputStream: InputStream, width: Int, height: Int): ByteArray = withContext(Dispatchers.Default) { // CPU-bound
    val originalImage = ImageIO.read(inputStream)
    val scaledImage = originalImage.getScaledInstance(width, height, Image.SCALE_SMOOTH)
    val outputImage = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    outputImage.graphics.drawImage(scaledImage, 0, 0, null)

    ByteArrayOutputStream().use { os ->
        ImageIO.write(outputImage, "jpeg", os)
        os.toByteArray()
    }
}

suspend fun generatePostReportAsync(): InputStream = withContext(Dispatchers.IO) {
    val reportData = StringBuilder("id,title,status\n")
    posts.values.forEach { post ->
        reportData.append("${post.id},\"${post.title}\",${post.status}\n")
    }
    ByteArrayInputStream(reportData.toString().toByteArray())
}

// --- Main Application with Coroutine-based Handlers ---
fun main() {
    // Pre-populate with some data
    val userId = UUID.randomUUID()
    users[userId] = User(userId, "test@user.com", "hash", UserRole.USER, true, Timestamp.from(Instant.now()))
    posts[UUID.randomUUID()] = Post(UUID.randomUUID(), userId, "Async Post", "Content", PostStatus.PUBLISHED)

    val app = Javalin.create().start(7073)
    println("Async Server started at http://localhost:7073")

    // 1. Async File Upload and CSV Parsing
    app.post("/async/users/upload") { ctx ->
        val uploadedFile = ctx.uploadedFile("users")
        if (uploadedFile == null) {
            ctx.status(HttpStatus.BAD_REQUEST).result("Missing 'users' file")
            return@post
        }
        
        ctx.future {
            try {
                val count = uploadedFile.content().use { parseCsvAndStoreUsers(it) }
                ctx.status(HttpStatus.CREATED).json(mapOf("message" to "Asynchronously processed and created $count users."))
            } catch (e: Exception) {
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json("Processing failed: ${e.message}")
            }
        }
    }

    // 2. Async Image Resizing and Temp File Management
    app.post("/async/posts/{id}/image") { ctx ->
        val postId = UUID.fromString(ctx.pathParam("id"))
        if (!posts.containsKey(postId)) {
            ctx.status(HttpStatus.NOT_FOUND).result("Post not found")
            return@post
        }
        val imageFile = ctx.uploadedFile("image")
        if (imageFile == null) {
            ctx.status(HttpStatus.BAD_REQUEST).result("Missing 'image' file")
            return@post
        }

        ctx.future {
            val tempFile = withContext(Dispatchers.IO) { Files.createTempFile("async-img-", ".jpeg") }
            try {
                val resizedBytes = imageFile.content().use { resizeImageAsync(it, 300, 300) }
                withContext(Dispatchers.IO) {
                    Files.write(tempFile, resizedBytes)
                }
                ctx.status(HttpStatus.OK).json(mapOf(
                    "message" to "Image resized asynchronously.",
                    "tempFile" to tempFile.toAbsolutePath().toString(),
                    "newSize" to resizedBytes.size
                ))
            } catch (e: Exception) {
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json("Image processing failed: ${e.message}")
            } finally {
                withContext(Dispatchers.IO) {
                    Files.deleteIfExists(tempFile) // Ensure temp file cleanup
                }
            }
        }
    }

    // 3. Async File Download with Streaming
    app.get("/async/posts/report") { ctx ->
        ctx.future {
            try {
                val reportStream = generatePostReportAsync()
                ctx.header("Content-Disposition", "attachment; filename=\"async_report.csv\"")
                    .contentType("text/csv")
                    .result(reportStream)
            } catch (e: Exception) {
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json("Report generation failed: ${e.message}")
            }
        }
    }
}