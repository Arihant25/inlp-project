package com.example.variation2

import io.javalin.Javalin
import io.javalin.http.Context
import io.javalin.http.HttpStatus
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.imageio.ImageIO

// --- Domain Layer ---
object Domain {
    enum class UserRole { ADMIN, USER }
    enum class PostStatus { DRAFT, PUBLISHED }

    data class User(
        val id: UUID, val email: String, val password_hash: String,
        val role: UserRole, val is_active: Boolean, val created_at: Timestamp
    )

    data class Post(
        val id: UUID, val user_id: UUID, val title: String,
        val content: String, val status: PostStatus
    )
}

// --- Repository Layer (Data Access) ---
interface UserRepository {
    fun saveAll(users: List<Domain.User>)
}

interface PostRepository {
    fun findById(id: UUID): Domain.Post?
    fun findAll(): List<Domain.Post>
}

class MockUserRepository : UserRepository {
    private val userStore = mutableMapOf<UUID, Domain.User>()
    override fun saveAll(users: List<Domain.User>) {
        users.forEach { userStore[it.id] = it }
        println("Saved ${users.size} users. Total users: ${userStore.size}")
    }
}

class MockPostRepository : PostRepository {
    private val postStore = mutableMapOf<UUID, Domain.Post>()
    init {
        val adminId = UUID.randomUUID()
        postStore[UUID.randomUUID()] = Domain.Post(UUID.randomUUID(), adminId, "First Post", "Content", Domain.PostStatus.PUBLISHED)
    }
    override fun findById(id: UUID): Domain.Post? = postStore[id]
    override fun findAll(): List<Domain.Post> = postStore.values.toList()
}

// --- Service Layer (Business Logic) ---
class FileService(
    private val userRepository: UserRepository,
    private val postRepository: PostRepository
) {
    fun bulkCreateUsersFromCsv(csvStream: InputStream): List<Domain.User> {
        val users = csvStream.bufferedReader().useLines { lines ->
            lines.drop(1).mapNotNull { line ->
                val parts = line.split(',')
                if (parts.size < 3) return@mapNotNull null
                Domain.User(
                    id = UUID.randomUUID(),
                    email = parts[0].trim(),
                    password_hash = "hashed:${parts[1].trim()}",
                    role = Domain.UserRole.valueOf(parts[2].trim().uppercase()),
                    is_active = true,
                    created_at = Timestamp.from(Instant.now())
                )
            }.toList()
        }
        userRepository.saveAll(users)
        return users
    }

    fun processAndSavePostImage(imageStream: InputStream, width: Int, height: Int): Path {
        val originalImage = ImageIO.read(imageStream)
        val scaledImage = originalImage.getScaledInstance(width, height, Image.SCALE_SMOOTH)
        val outputImage = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        outputImage.createGraphics().drawImage(scaledImage, 0, 0, null)

        val tempFile = Files.createTempFile("processed-image-", ".jpg")
        ImageIO.write(outputImage, "jpg", tempFile.toFile())
        return tempFile
    }

    fun generatePostsReport(): InputStream {
        val header = "id,user_id,title,status\n"
        val rows = postRepository.findAll().joinToString("\n") {
            "${it.id},${it.user_id},\"${it.title}\",${it.status}"
        }
        return (header + rows).byteInputStream()
    }
}

// --- Controller Layer (API Endpoints) ---
class FileController(private val fileService: FileService) {
    fun uploadUsers(ctx: Context) {
        ctx.uploadedFile("users_csv")?.let { file ->
            val createdUsers = fileService.bulkCreateUsersFromCsv(file.content())
            ctx.status(HttpStatus.CREATED).json(mapOf("created_count" to createdUsers.size))
        } ?: ctx.status(HttpStatus.BAD_REQUEST).result("File 'users_csv' is required.")
    }

    fun uploadPostImage(ctx: Context) {
        val postId = UUID.fromString(ctx.pathParam("id"))
        // In a real app, you'd check if the post exists via postRepository
        ctx.uploadedFile("post_image")?.let { file ->
            val tempFile = fileService.processAndSavePostImage(file.content(), 800, 600)
            ctx.json(mapOf(
                "message" to "Image processed.",
                "temp_path" to tempFile.toAbsolutePath().toString()
            ))
            // In a real app, you'd move this file to permanent storage and then delete the temp file.
            // For this example, we leave it to show temp file creation.
            // Files.deleteIfExists(tempFile)
        } ?: ctx.status(HttpStatus.BAD_REQUEST).result("File 'post_image' is required.")
    }

    fun downloadPostsReport(ctx: Context) {
        val reportStream = fileService.generatePostsReport()
        ctx.header("Content-Disposition", "attachment; filename=\"posts_report.csv\"")
            .contentType("text/csv")
            .result(reportStream)
    }
}

// --- Application Entrypoint ---
fun main() {
    // Dependency Injection
    val userRepository = MockUserRepository()
    val postRepository = MockPostRepository()
    val fileService = FileService(userRepository, postRepository)
    val fileController = FileController(fileService)

    // Javalin Setup
    Javalin.create().apply {
        post("/users/upload", fileController::uploadUsers)
        post("/posts/{id}/image", fileController::uploadPostImage)
        get("/posts/report", fileController::downloadPostsReport)
    }.start(7071)

    println("Server started at http://localhost:7071")
}