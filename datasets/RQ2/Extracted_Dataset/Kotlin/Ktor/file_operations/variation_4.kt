package com.example.fileops.coroutine

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
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

// --- Mock DB ---
val userStorage = mutableListOf<User>()

// --- Coroutine-centric Service ---
class FileProcessingService(private val storagePath: String) {

    init {
        File(storagePath).mkdirs()
    }

    // 2. CSV Parsing with Flow for streaming large files
    fun parseUsersFromCsvStream(channel: ByteReadChannel): Flow<User> = channel.toLines()
        .drop(1) // Drop header
        .mapNotNull { line ->
            val (email, pass, role) = line.split(",", limit = 3).map { it.trim() }
            if (email.isNotBlank()) {
                User(UUID.randomUUID(), email, pass.hashCode().toString(), UserRole.valueOf(role), true, Clock.System.now())
            } else null
        }
        .flowOn(Dispatchers.Default) // Use Default dispatcher for CPU-bound parsing work

    // 3. Image Resizing in an IO-bound context
    suspend fun resizeImageAsync(bytes: ByteArray, targetWidth: Int): ByteArray = withContext(Dispatchers.IO) {
        val originalImage = ImageIO.read(ByteArrayInputStream(bytes))
        val scale = targetWidth.toDouble() / originalImage.width
        val targetHeight = (originalImage.height * scale).toInt()

        val scaledImage = originalImage.getScaledInstance(targetWidth, targetHeight, java.awt.Image.SCALE_SMOOTH)
        val bufferedImage = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
        bufferedImage.createGraphics().drawImage(scaledImage, 0, 0, null)

        ByteArrayOutputStream().use { baos ->
            ImageIO.write(bufferedImage, "jpg", baos)
            baos.toByteArray()
        }
    }

    suspend fun saveFileAsync(bytes: ByteArray, fileName: String): File = withContext(Dispatchers.IO) {
        val file = File(storagePath, fileName)
        file.writeBytes(bytes)
        file
    }
}

// --- Main Application ---
fun main() {
    embeddedServer(Netty, port = 8080, module = Application::asyncFileModule).start(wait = true)
}

fun Application.asyncFileModule() {
    val fileService = FileProcessingService("async_uploads")

    routing {
        // 1. File Upload Handling
        post("/stream/import-users") {
            val multipart = call.receiveMultipart()
            var userCount = 0

            multipart.forEachPart { part ->
                if (part is PartData.FileItem) {
                    val userFlow = fileService.parseUsersFromCsvStream(part.provider())
                    userFlow.onEach { user ->
                        // In a real app, this would be a batched DB insert
                        userStorage.add(user)
                        userCount++
                    }.launchIn(CoroutineScope(Dispatchers.IO)) // Process the flow concurrently
                }
                part.dispose()
            }
            call.respondText("Started importing users. Processed $userCount users from stream.", status = HttpStatusCode.Accepted)
        }

        post("/upload/avatar") {
            val part = call.receiveMultipart().readPart() as? PartData.FileItem
            if (part == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }

            // 5. Temporary File Management (Ktor handles temp files, we operate on byte arrays)
            val originalBytes = part.provider().readRemaining().readBytes()
            part.dispose()

            val resizedBytes = fileService.resizeImageAsync(originalBytes, 150)
            val file = fileService.saveFileAsync(resizedBytes, "avatar-${UUID.randomUUID()}.jpg")

            call.respond(HttpStatusCode.Created, mapOf("path" to "/downloads/${file.name}"))
        }

        // 4. File Download with Streaming using coroutine-native API
        get("/downloads/{filename}") {
            val filename = call.parameters["filename"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val file = File(fileService.storagePath, filename)

            if (!file.exists()) {
                return@get call.respond(HttpStatusCode.NotFound)
            }

            call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, filename).toString())
            call.respondBytesWriter(contentType = ContentType.Application.OctetStream, status = HttpStatusCode.OK) {
                withContext(Dispatchers.IO) {
                    file.inputStream().use { input ->
                        val buffer = ByteArray(4096)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            this@respondBytesWriter.writeFully(buffer, 0, bytesRead)
                            this@respondBytesWriter.flush()
                        }
                    }
                }
            }
        }
    }
}

// Helper extension to convert ByteReadChannel to a Flow of lines
fun ByteReadChannel.toLines(): Flow<String> = flow {
    while (!isClosedForRead) {
        val line = readUTF8Line()
        if (line != null) {
            emit(line)
        } else {
            break
        }
    }
}