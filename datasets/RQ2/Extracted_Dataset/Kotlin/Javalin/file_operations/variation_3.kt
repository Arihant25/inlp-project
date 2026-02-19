package com.example.variation3

import io.javalin.Javalin
import io.javalin.apibuilder.ApiBuilder.*
import io.javalin.http.Context
import io.javalin.http.HttpStatus
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.imageio.ImageIO

// --- Domain Model ---
enum class UserRole { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }

data class User(val id: UUID, val email: String, val password_hash: String, val role: UserRole, val is_active: Boolean, val created_at: Timestamp)
data class Post(val id: UUID, val user_id: UUID, val title: String, val content: String, val status: PostStatus)

// --- Mock Data Store ---
class DataStore {
    val users = mutableMapOf<UUID, User>()
    val posts = mutableMapOf<UUID, Post>()

    init {
        val userId = UUID.randomUUID()
        users[userId] = User(userId, "user@test.com", "hash", UserRole.USER, true, Timestamp.from(Instant.now()))
        val postId = UUID.randomUUID()
        posts[postId] = Post(postId, userId, "A Post Title", "Some content.", PostStatus.PUBLISHED)
    }
}

// --- API Handler Class ---
class FileApiHandler(private val db: DataStore) {

    fun uploadUsersFromCSV(ctx: Context) {
        val uploadedFile = ctx.uploadedFile("user_data")
            ?: return ctx.status(HttpStatus.BAD_REQUEST).json("File 'user_data' not provided.")

        val createdUsers = mutableListOf<User>()
        try {
            uploadedFile.content().bufferedReader().useLines { lines ->
                lines.drop(1).forEach {
                    val (email, pass, role) = it.split(",").map(String::trim)
                    val newUser = User(
                        id = UUID.randomUUID(),
                        email = email,
                        password_hash = "hashed:$pass",
                        role = UserRole.valueOf(role.uppercase()),
                        is_active = true,
                        created_at = Timestamp.from(Instant.now())
                    )
                    db.users[newUser.id] = newUser
                    createdUsers.add(newUser)
                }
            }
            ctx.status(HttpStatus.CREATED).json(mapOf("imported" to createdUsers.size, "ids" to createdUsers.map { it.id }))
        } catch (e: Exception) {
            ctx.status(HttpStatus.UNPROCESSABLE_CONTENT).json("Error parsing CSV: ${e.message}")
        }
    }

    fun attachImageToPost(ctx: Context) {
        val pId = UUID.fromString(ctx.pathParam("postId"))
        if (!db.posts.containsKey(pId)) {
            return ctx.status(HttpStatus.NOT_FOUND).json("Post not found.")
        }

        val imgFile = ctx.uploadedFile("image")
            ?: return ctx.status(HttpStatus.BAD_REQUEST).json("File 'image' not provided.")

        // In-memory image processing
        val originalImg = ImageIO.read(imgFile.content())
        val scaledImg = originalImg.getScaledInstance(1024, 768, Image.SCALE_SMOOTH)
        val newImg = BufferedImage(1024, 768, BufferedImage.TYPE_INT_ARGB)
        newImg.createGraphics().drawImage(scaledImg, 0, 0, null)

        val outStream = ByteArrayOutputStream()
        ImageIO.write(newImg, "png", outStream)
        val processedBytes = outStream.toByteArray()

        // Here you would typically save the bytes to a file store or DB
        // and link it to the post. We'll just confirm processing.
        ctx.status(HttpStatus.OK).json(mapOf(
            "message" to "Image processed for post $pId",
            "new_size_bytes" to processedBytes.size,
            "format" to "png"
        ))
    }

    fun downloadPostReport(ctx: Context) {
        val content = buildString {
            append("post_id,user_id,title,status\n")
            db.posts.values.forEach { p ->
                append("${p.id},${p.user_id},${p.title.replace(",", "")},${p.status}\n")
            }
        }
        val inputStream = ByteArrayInputStream(content.toByteArray())
        ctx.header("Content-Disposition", "attachment; filename=post_report.csv")
        ctx.contentType("text/csv")
        ctx.result(inputStream)
    }
}

// --- Application Setup ---
fun main() {
    val dataStore = DataStore()
    val fileApiHandler = FileApiHandler(dataStore)

    Javalin.create().routes {
        path("/api/files") {
            // 1. File Upload and CSV Parsing
            post("/users/import", fileApiHandler::uploadUsersFromCSV)

            // 2. Image Resizing
            post("/posts/{postId}/image", fileApiHandler::attachImageToPost)

            // 3. File Download with Streaming
            get("/posts/export", fileApiHandler::downloadPostReport)
        }
    }.start(7072)

    println("Server started at http://localhost:7072")
}