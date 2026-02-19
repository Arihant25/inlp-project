package com.example.fileops.featurebased

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.awt.image.BufferedImage
import java.io.File
import java.io.InputStream
import java.util.*
import javax.imageio.ImageIO

// --- Domain Schema with kotlinx-datetime ---
enum class UserRole { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }

data class User(
    val id: UUID,
    val email: String,
    val password_hash: String,
    val role: UserRole,
    val is_active: Boolean,
    val created_at: Instant
)

data class Post(
    val id: UUID,
    val user_id: UUID,
    val title: String,
    val content: String,
    val status: PostStatus
)

// --- Mock Data Store ---
object DataStore {
    val users = mutableMapOf<UUID, User>()
    fun addUser(user: User) {
        users[user.id] = user
    }
}

// --- Utility Objects / Services ---
object CsvUserParser {
    // 2. CSV Parsing
    fun parse(inputStream: InputStream): List<User> {
        return inputStream.bufferedReader().useLines { lines ->
            lines.drop(1)
                 .map { it.split(",").map(String::trim) }
                 .filter { it.size >= 2 }
                 .map {
                     User(
                         id = UUID.randomUUID(),
                         email = it[0],
                         password_hash = it[1].hashCode().toString(),
                         role = if (it.size > 2) UserRole.valueOf(it[2].uppercase()) else UserRole.USER,
                         is_active = true,
                         created_at = Clock.System.now()
                     )
                 }.toList()
        }
    }
}

object ImageProcessor {
    // 3. Image Resizing
    fun resizeToAvatar(imageStream: InputStream): BufferedImage {
        val image = ImageIO.read(imageStream)
        val targetWidth = 128
        val targetHeight = 128
        val resized = BufferedImage(targetWidth, targetHeight, image.type)
        val g = resized.createGraphics()
        g.drawImage(image, 0, 0, targetWidth, targetHeight, null)
        g.dispose()
        return resized
    }
}

// --- Result Wrapper ---
sealed class UploadResult {
    data class Success(val message: String) : UploadResult()
    data class Failure(val error: String, val code: HttpStatusCode) : UploadResult()
}

// --- Main Application ---
fun main() {
    embeddedServer(Netty, port = 8080, module = Application::featureModule).start(wait = true)
}

fun Application.featureModule() {
    val uploadDir = environment.config.property("ktor.deployment.upload_dir").getString()
    File(uploadDir).mkdirs()

    routing {
        userFileFeatures(uploadDir)
        publicDownloads(uploadDir)
    }
}

// --- Feature-based Route Extensions ---
fun Route.userFileFeatures(uploadDir: String) {
    route("/users") {
        // 1. File Upload Handling (CSV)
        post("/import") {
            val part = call.receiveMultipart().readPart() as? PartData.FileItem
            if (part == null || part.originalFileName?.endsWith(".csv") != true) {
                call.respond(HttpStatusCode.BadRequest, "CSV file part is required.")
                return@post
            }

            val result = try {
                part.streamProvider().use { input ->
                    val newUsers = CsvUserParser.parse(input)
                    newUsers.forEach { DataStore.addUser(it) }
                    UploadResult.Success("Successfully imported ${newUsers.size} users.")
                }
            } catch (e: Exception) {
                UploadResult.Failure("Failed to parse CSV: ${e.message}", HttpStatusCode.InternalServerError)
            } finally {
                part.dispose()
            }

            when (result) {
                is UploadResult.Success -> call.respond(HttpStatusCode.OK, result.message)
                is UploadResult.Failure -> call.respond(result.code, result.error)
            }
        }

        // 1. File Upload Handling (Image)
        put("/{id}/avatar") {
            val userId = call.parameters["id"]?.let { UUID.fromString(it) }
            if (userId == null || DataStore.users[userId] == null) {
                call.respond(HttpStatusCode.NotFound, "User not found")
                return@put
            }

            val part = call.receiveMultipart().readPart() as? PartData.FileItem
            if (part == null) {
                call.respond(HttpStatusCode.BadRequest, "Image file part is required.")
                return@put
            }

            // 5. Temporary File Management (implicit via streams)
            try {
                part.streamProvider().use { input ->
                    val resizedAvatar = ImageProcessor.resizeToAvatar(input)
                    val avatarFile = File(uploadDir, "$userId-avatar.png")
                    ImageIO.write(resizedAvatar, "png", avatarFile)
                }
                call.respond(HttpStatusCode.OK, "Avatar updated for user $userId")
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Could not process image.")
            } finally {
                part.dispose()
            }
        }
    }
}

fun Route.publicDownloads(uploadDir: String) {
    // 4. File Download with Streaming
    get("/avatars/{id}") {
        val userId = call.parameters["id"]
        val avatarFile = File(uploadDir, "$userId-avatar.png")

        if (avatarFile.exists()) {
            call.respondFile(avatarFile)
        } else {
            call.respond(HttpStatusCode.NotFound)
        }
    }
}