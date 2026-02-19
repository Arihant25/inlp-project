package fileops.idiomatic

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import javax.imageio.ImageIO

// --- Domain Schema ---
enum class UserRole { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }

data class User(
    val id: UUID, val email: String, val password_hash: String,
    val role: UserRole, val is_active: Boolean, val created_at: Instant
)

data class Post(
    val id: UUID, val user_id: UUID, val title: String,
    val content: String, val status: PostStatus
)

// --- Result Wrapper ---
sealed class OperationResult<out T> {
    data class Success<out T>(val data: T) : OperationResult<T>()
    data class Failure(val error: Throwable) : OperationResult<Nothing>()
}

// --- Idiomatic Kotlin Implementation with Extension Functions ---

data class ParsedPart(val name: String, val filename: String?, val contentType: String?, val content: ByteArray)

fun InputStream.parseMultipart(boundary: String): OperationResult<List<ParsedPart>> = runCatching {
    val boundaryBytes = "--$boundary".toByteArray()
    val streamBytes = this.readBytes()
    val parts = mutableListOf<ParsedPart>()

    streamBytes.split(boundaryBytes)
        .filter { it.isNotEmpty() && !it.contentEquals("--\r\n".toByteArray()) }
        .forEach { partBytes ->
            val headerEnd = partBytes.indexOf("\r\n\r\n".toByteArray())
            if (headerEnd != -1) {
                val headers = String(partBytes, 0, headerEnd).trim()
                val content = partBytes.sliceArray(headerEnd + 4 until partBytes.size - 2) // -2 for trailing \r\n

                val name = headers.extractHeaderValue("name") ?: "unknown"
                val filename = headers.extractHeaderValue("filename")
                val contentType = headers.lines().find { it.startsWith("Content-Type:") }?.substringAfter(":")?.trim()

                parts.add(ParsedPart(name, filename, contentType, content))
            }
        }
    OperationResult.Success(parts)
}.getOrElse { OperationResult.Failure(it) }

// Helper for parsing multipart parts
private fun ByteArray.split(delimiter: ByteArray): List<ByteArray> {
    val result = mutableListOf<ByteArray>()
    var start = 0
    while (start <= this.size) {
        val index = this.indexOf(delimiter, start)
        if (index == -1) {
            result.add(this.sliceArray(start until this.size))
            break
        }
        result.add(this.sliceArray(start until index))
        start = index + delimiter.size
    }
    return result
}

private fun ByteArray.indexOf(sub: ByteArray, start: Int = 0): Int {
    if (sub.isEmpty()) return start
    for (i in start..this.size - sub.size) {
        if (this.sliceArray(i until i + sub.size).contentEquals(sub)) return i
    }
    return -1
}

private fun String.extractHeaderValue(key: String): String? =
    "\\b$key=\"([^\"]*)\"".toRegex().find(this)?.groups?.get(1)?.value

// Extension function for CSV parsing
fun ByteArray.toUsersFromCsv(): OperationResult<List<User>> = runCatching {
    val users = this.inputStream().bufferedReader().useLines { lines ->
        lines.drop(1)
            .map { it.split(',') }
            .filter { it.size == 6 }
            .map {
                User(
                    id = UUID.fromString(it[0]), email = it[1], password_hash = it[2],
                    role = UserRole.valueOf(it[3]), is_active = it[4].toBoolean(),
                    created_at = Instant.parse(it[5])
                )
            }.toList()
    }
    OperationResult.Success(users)
}.getOrElse { OperationResult.Failure(IllegalArgumentException("CSV parsing failed", it)) }

// Extension function for image resizing
fun ByteArray.asThumbnail(width: Int, height: Int): OperationResult<ByteArray> = runCatching {
    val originalImage = ImageIO.read(this.inputStream())
    val scaledImage = BufferedImage(width, height, originalImage.type.takeIf { it != 0 } ?: BufferedImage.TYPE_INT_ARGB)
    scaledImage.createGraphics().run {
        drawImage(originalImage, 0, 0, width, height, null)
        dispose()
    }
    ByteArrayOutputStream().use {
        ImageIO.write(scaledImage, "png", it)
        OperationResult.Success(it.toByteArray())
    }
}.getOrElse { OperationResult.Failure(IllegalStateException("Image processing failed", it)) }

// Extension function for streaming download
fun File.streamTo(output: OutputStream) {
    this.inputStream().use { it.copyTo(output) }
}

// Higher-order function for temporary file management
inline fun <R> useTempFile(prefix: String = "temp", suffix: String = ".dat", block: (File) -> R): R {
    val file = File.createTempFile(prefix, suffix)
    try {
        return block(file)
    } finally {
        file.delete()
    }
}

// --- Main function to demonstrate usage ---
fun main() {
    println("--- Modern/Idiomatic Kotlin Demo ---")

    // 1. File Upload Handling
    val boundary = "WebAppBoundary"
    val csvData = "1a7b8b52-3c3c-4e4e-8f8f-1a1a1a1a1a1a,admin@test.com,hash1,ADMIN,true,2023-01-01T12:00:00Z"
    val dummyImageBytes = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB), "png", it)
    }.toByteArray()

    val multipartBody = """
    --$boundary
    Content-Disposition: form-data; name="user_data"; filename="users.csv"
    Content-Type: text/csv

    id,email,password_hash,role,is_active,created_at
    $csvData
    --$boundary
    Content-Disposition: form-data; name="avatar_image"; filename="avatar.png"
    Content-Type: image/png

    """.trimIndent().toByteArray() + dummyImageBytes + "\r\n--$boundary--\r\n".toByteArray()

    when (val uploadResult = multipartBody.inputStream().parseMultipart(boundary)) {
        is OperationResult.Success -> {
            println("Multipart parsing successful. Found ${uploadResult.data.size} parts.")
            
            // 2. CSV Parsing
            uploadResult.data.find { it.filename == "users.csv" }?.let { csvPart ->
                when (val userResult = csvPart.content.toUsersFromCsv()) {
                    is OperationResult.Success -> println("CSV parsed successfully: ${userResult.data.first().email}")
                    is OperationResult.Failure -> println("CSV parsing failed: ${userResult.error}")
                }
            }

            // 3. Image Resizing
            uploadResult.data.find { it.filename == "avatar.png" }?.let { imagePart ->
                when (val thumbResult = imagePart.content.asThumbnail(50, 50)) {
                    is OperationResult.Success -> println("Thumbnail created: ${thumbResult.data.size} bytes.")
                    is OperationResult.Failure -> println("Thumbnail creation failed: ${thumbResult.error}")
                }
            }
        }
        is OperationResult.Failure -> println("Multipart parsing failed: ${uploadResult.error}")
    }

    // 4. Temp File Management & 5. Download
    useTempFile(suffix = ".txt") { tempFile ->
        println("Using temp file: ${tempFile.name}")
        tempFile.writeText("Streaming this content.")
        
        val downloadedContent = ByteArrayOutputStream().also { tempFile.streamTo(it) }.toString()
        println("Streamed content from temp file: '$downloadedContent'")
    }
    println("Temp file block finished and file deleted.")
}