package com.example.fileops.functional

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
val users = mutableListOf<User>()

// --- Constants ---
private const val UPLOAD_DIR = "uploads"
private const val RESIZED_IMAGE_WIDTH = 200

// --- Main Application Entry Point ---
fun main() {
    File(UPLOAD_DIR).mkdirs() // Ensure upload directory exists
    embeddedServer(Netty, port = 8080, module = Application::fileOpsModule).start(wait = true)
}

// --- Ktor Module ---
fun Application.fileOpsModule() {
    routing {
        configureFileRoutes()
    }
}

// --- Functional Route Definitions ---
fun Route.configureFileRoutes() {
    route("/files") {
        // 1. File Upload Handling (CSV and Image)
        post("/upload") {
            val multipartData = call.receiveMultipart()
            val results = mutableListOf<String>()

            multipartData.forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        val fileName = part.originalFileName ?: "unknown.bin"
                        val tempFile = createTempFile(directory = File(UPLOAD_DIR))
                        part.streamProvider().use { input -> tempFile.outputStream().buffered().use { output -> input.copyTo(output) } }

                        try {
                            when (tempFile.extension.lowercase()) {
                                "csv" -> {
                                    val userCount = processCsvUpload(tempFile.inputStream())
                                    results.add("Successfully imported $userCount users from $fileName.")
                                }
                                "jpg", "jpeg", "png" -> {
                                    val newFileName = processImageUpload(tempFile, fileName)
                                    results.add("Processed image $fileName and saved as $newFileName.")
                                }
                                else -> results.add("Unsupported file type for $fileName.")
                            }
                        } finally {
                            tempFile.delete() // 5. Temporary File Management
                        }
                    }
                    is PartData.FormItem -> {
                        // Handle other form fields if necessary
                    }
                    else -> {}
                }
                part.dispose()
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "processed", "details" to results))
        }

        // 4. File Download with Streaming
        get("/download/{name}") {
            val filename = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.BadRequest, "File name not specified")
            val file = File(UPLOAD_DIR, filename)
            if (file.exists()) {
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, filename).toString()
                )
                call.respondOutputStream(ContentType.Application.OctetStream) {
                    file.inputStream().copyTo(this)
                }
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }
    }
}

// --- Helper Functions ---

// 2. CSV Parsing
private fun processCsvUpload(inputStream: InputStream): Int {
    val newUsers = inputStream.bufferedReader().lineSequence()
        .drop(1) // Skip header row
        .mapNotNull { line ->
            val (email, password, role) = line.split(',', limit = 3)
            if (email.isNotBlank() && password.isNotBlank()) {
                User(
                    id = UUID.randomUUID(),
                    email = email.trim(),
                    password_hash = password.trim().hashCode().toString(), // Dummy hash
                    role = UserRole.valueOf(role.trim().uppercase()),
                    is_active = true,
                    created_at = Instant.now()
                )
            } else {
                null
            }
        }.toList()

    users.addAll(newUsers)
    return newUsers.size
}

// 3. Image Resizing/Processing
private fun processImageUpload(tempFile: File, originalFileName: String): String {
    val originalImage = ImageIO.read(tempFile)
    val resizedImage = resizeImage(originalImage, RESIZED_IMAGE_WIDTH)
    val newFileName = "resized_${UUID.randomUUID()}.${originalFileName.substringAfterLast('.')}"
    val outputFile = File(UPLOAD_DIR, newFileName)
    ImageIO.write(resizedImage, "png", outputFile)
    return newFileName
}

private fun resizeImage(originalImage: BufferedImage, targetWidth: Int): BufferedImage {
    val originalWidth = originalImage.width
    val originalHeight = originalImage.height
    if (originalWidth <= targetWidth) return originalImage

    val targetHeight = (originalHeight.toDouble() / originalWidth.toDouble() * targetWidth).toInt()
    val resultingImage = originalImage.getScaledInstance(targetWidth, targetHeight, Image.SCALE_SMOOTH)
    val outputImage = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
    outputImage.graphics.drawImage(resultingImage, 0, 0, null)
    return outputImage
}