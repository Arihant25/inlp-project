package com.example.fileops.oop

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.util.*
import javax.imageio.ImageIO

// --- Domain Schema ---
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

// --- Mock Database ---
object UserRepository {
    private val users = mutableListOf<User>()
    fun saveAll(newUsers: List<User>) = users.addAll(newUsers)
}

// --- Service Layer ---
interface IFileService {
    fun importUsersFromCsv(inputStream: InputStream): Int
    fun processAndSaveImage(inputStream: InputStream, originalFileName: String): String
    fun getFileForDownload(fileName: String): File?
}

class FileService(private val uploadPath: String) : IFileService {
    companion object {
        private const val RESIZED_IMAGE_WIDTH = 200
    }

    init {
        File(uploadPath).mkdirs()
    }

    // 2. CSV Parsing
    override fun importUsersFromCsv(inputStream: InputStream): Int {
        val newUsers = inputStream.bufferedReader().use { reader ->
            reader.readLines().drop(1).mapNotNull {
                val parts = it.split(',')
                if (parts.size < 3) return@mapNotNull null
                User(
                    id = UUID.randomUUID(),
                    email = parts[0].trim(),
                    password_hash = parts[1].trim().hashCode().toString(),
                    role = UserRole.valueOf(parts[2].trim().uppercase()),
                    is_active = true,
                    created_at = Instant.now()
                )
            }
        }
        UserRepository.saveAll(newUsers)
        return newUsers.size
    }

    // 3. Image Resizing/Processing
    override fun processAndSaveImage(inputStream: InputStream, originalFileName: String): String {
        val originalImage = ImageIO.read(inputStream)
        val resized = resize(originalImage, RESIZED_IMAGE_WIDTH)
        val extension = originalFileName.substringAfterLast('.', "png")
        val newFileName = "avatar-${UUID.randomUUID()}.$extension"
        val outputFile = File(uploadPath, newFileName)
        ImageIO.write(resized, extension, outputFile)
        return newFileName
    }

    override fun getFileForDownload(fileName: String): File? {
        val file = File(uploadPath, fileName)
        return if (file.exists() && file.isFile) file else null
    }

    private fun resize(img: BufferedImage, targetWidth: Int): BufferedImage {
        val scaledInstance = img.getScaledInstance(targetWidth, -1, Image.SCALE_SMOOTH)
        val newImage = BufferedImage(scaledInstance.getWidth(null), scaledInstance.getHeight(null), BufferedImage.TYPE_INT_ARGB)
        val g = newImage.createGraphics()
        g.drawImage(scaledInstance, 0, 0, null)
        g.dispose()
        return newImage
    }
}

// --- Controller Layer ---
class FileController(private val fileService: IFileService) {

    // 1. File Upload Handling
    suspend fun handleUpload(call: ApplicationCall) {
        val multipart = call.receiveMultipart()
        val responses = mutableMapOf<String, String>()

        multipart.forEachPart { part ->
            if (part is PartData.FileItem) {
                // 5. Temporary File Management (Ktor handles this implicitly with streamProvider)
                val fileName = part.originalFileName ?: "unnamed"
                part.streamProvider().use { input ->
                    when (fileName.substringAfterLast('.').lowercase()) {
                        "csv" -> {
                            val count = fileService.importUsersFromCsv(input)
                            responses[fileName] = "Imported $count users."
                        }
                        "png", "jpg", "jpeg" -> {
                            val newName = fileService.processAndSaveImage(input, fileName)
                            responses[fileName] = "Image saved as $newName."
                        }
                        else -> responses[fileName] = "Unsupported file type."
                    }
                }
            }
            part.dispose()
        }
        call.respond(HttpStatusCode.Accepted, responses)
    }

    // 4. File Download with Streaming
    suspend fun handleDownload(call: ApplicationCall) {
        val key = call.parameters["key"] ?: return call.respond(HttpStatusCode.BadRequest)
        val file = fileService.getFileForDownload(key)
        if (file != null) {
            call.respondFile(file)
        } else {
            call.respond(HttpStatusCode.NotFound)
        }
    }
}

// --- Main Application and Routing ---
fun main() {
    embeddedServer(Netty, port = 8080, module = Application::mainModule).start(wait = true)
}

fun Application.mainModule() {
    val fileService: IFileService = FileService("server_uploads")
    val fileController = FileController(fileService)

    routing {
        route("/v1") {
            post("/upload") {
                fileController.handleUpload(call)
            }
            get("/download/{key}") {
                fileController.handleDownload(call)
            }
        }
    }
}