package fileops.robust

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

// --- Custom Exceptions for Defensive Programming ---
class FileOperationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class FileUploadException(message: String, cause: Throwable? = null) : FileOperationException(message, cause)
class CsvProcessingException(message: String, val lineNumber: Int? = null, cause: Throwable? = null) : FileOperationException(message, cause)
class ImageProcessingException(message: String, cause: Throwable? = null) : FileOperationException(message, cause)

// --- Configuration for the Service ---
data class FileServiceConfig(
    val maxFileSize: Long = 10 * 1024 * 1024, // 10 MB
    val maxUploadSize: Long = 50 * 1024 * 1024, // 50 MB
    val tempFileDirectory: File = File(System.getProperty("java.io.tmpdir")),
    val supportedImageTypes: Set<String> = setOf("image/png", "image/jpeg")
)

// --- Robust Service Implementation ---
class RobustFileService(private val config: FileServiceConfig) {

    companion object {
        private const val BUFFER_SIZE = 8192
    }

    private data class MultipartPart(val headers: Map<String, String>, val data: ByteArray)

    fun processFileUploadRequest(inputStream: InputStream, boundary: String): Map<String, Any> {
        val parts = parseMultipartStream(inputStream, boundary)
        val processedResults = mutableMapOf<String, Any>()

        parts.forEach { part ->
            val disposition = part.headers["Content-Disposition"] ?: return@forEach
            val name = disposition.substringAfter("name=\"").substringBefore("\"")
            val filename = disposition.substringAfter("filename=\"", "").substringBefore("\"").ifEmpty { null }
            
            if (filename != null) { // It's a file
                val contentType = part.headers["Content-Type"] ?: "application/octet-stream"
                
                if (contentType.startsWith("image/")) {
                    if (contentType !in config.supportedImageTypes) {
                        throw ImageProcessingException("Unsupported image type: $contentType")
                    }
                    val resized = resizeImage(part.data, 128, 128)
                    processedResults["${name}_thumbnail"] = resized
                } else if (contentType == "text/csv") {
                    val users = parseAndValidateCsv(ByteArrayInputStream(part.data))
                    processedResults["${name}_users"] = users
                }
            } else { // It's a form field
                processedResults[name] = String(part.data, StandardCharsets.UTF_8)
            }
        }
        return processedResults
    }

    private fun parseMultipartStream(inputStream: InputStream, boundary: String): List<MultipartPart> {
        val boundaryBytes = "--$boundary".toByteArray()
        val parts = mutableListOf<MultipartPart>()
        var totalBytesRead = 0L

        // This is a simplified parser for demonstration. A production one would be a state machine.
        try {
            val data = inputStream.readBytes()
            totalBytesRead = data.size.toLong()
            if (totalBytesRead > config.maxUploadSize) {
                throw FileUploadException("Total upload size exceeds limit of ${config.maxUploadSize} bytes.")
            }

            val boundaryStr = String(boundaryBytes)
            val dataStr = String(data)
            val partStrings = dataStr.split(boundaryStr).filter { it.isNotBlank() && it != "--" }

            for (partStr in partStrings) {
                val headerBodySplit = partStr.split("\r\n\r\n", limit = 2)
                if (headerBodySplit.size != 2) continue

                val headers = headerBodySplit[0].lines().filter { it.contains(":") }.associate {
                    val (key, value) = it.split(":", limit = 2)
                    key.trim() to value.trim()
                }
                val body = headerBodySplit[1].trimEnd('\r', '\n').toByteArray()
                if (body.size > config.maxFileSize) {
                    throw FileUploadException("File part size exceeds limit of ${config.maxFileSize} bytes.")
                }
                parts.add(MultipartPart(headers, body))
            }
        } catch (e: Exception) {
            throw FileUploadException("Failed to parse multipart stream", e)
        }
        return parts
    }

    private fun parseAndValidateCsv(inputStream: InputStream): List<User> {
        val users = mutableListOf<User>()
        inputStream.bufferedReader().useLines { lines ->
            lines.forEachIndexed { index, line ->
                if (index == 0) { // Header validation
                    if (line != "id,email,password_hash,role,is_active,created_at") {
                        throw CsvProcessingException("Invalid CSV header.", lineNumber = 0)
                    }
                    return@forEachIndexed
                }
                val fields = line.split(',')
                if (fields.size != 6) {
                    throw CsvProcessingException("Incorrect number of columns.", lineNumber = index + 1)
                }
                try {
                    users.add(User(
                        id = UUID.fromString(fields[0]), email = fields[1], password_hash = fields[2],
                        role = UserRole.valueOf(fields[3]), is_active = fields[4].toBoolean(),
                        created_at = Instant.parse(fields[5])
                    ))
                } catch (e: Exception) {
                    throw CsvProcessingException("Failed to parse row: ${e.message}", lineNumber = index + 1, cause = e)
                }
            }
        }
        return users
    }

    private fun resizeImage(imageData: ByteArray, width: Int, height: Int): ByteArray {
        try {
            val sourceImage = ImageIO.read(ByteArrayInputStream(imageData))
                ?: throw ImageProcessingException("Could not decode image data.")
            val resultImage = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            val graphics = resultImage.createGraphics()
            graphics.drawImage(sourceImage, 0, 0, width, height, null)
            graphics.dispose()
            return ByteArrayOutputStream().use {
                ImageIO.write(resultImage, "png", it)
                it.toByteArray()
            }
        } catch (e: Exception) {
            throw ImageProcessingException("Failed to resize image.", e)
        }
    }

    fun streamFileForDownload(file: File, outputStream: OutputStream) {
        if (!file.exists() || !file.isFile) {
            throw FileOperationException("File not found or is not a regular file: ${file.path}")
        }
        var inputStream: InputStream? = null
        try {
            inputStream = file.inputStream().buffered(BUFFER_SIZE)
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
        } catch (e: Exception) {
            throw FileOperationException("Failed during file streaming.", e)
        } finally {
            try {
                inputStream?.close()
                outputStream.flush()
            } catch (e: Exception) {
                // Log this closing error
            }
        }
    }

    fun createManagedTempFile(prefix: String, suffix: String): File {
        if (!config.tempFileDirectory.exists()) {
            config.tempFileDirectory.mkdirs()
        }
        val tempFile = File.createTempFile(prefix, suffix, config.tempFileDirectory)
        tempFile.deleteOnExit()
        return tempFile
    }
}

// --- Main function to demonstrate usage ---
fun main() {
    println("--- Robust/Defensive Demo ---")
    val config = FileServiceConfig()
    val service = RobustFileService(config)

    // 1. Successful Upload Scenario
    val boundary = "boundary-42"
    val csvContent = "id,email,password_hash,role,is_active,created_at\n" +
                     "1a7b8b52-3c3c-4e4e-8f8f-1a1a1a1a1a1a,admin@test.com,hash1,ADMIN,true,2023-01-01T12:00:00Z"
    val dummyImageBytes = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB), "png", it)
    }.toByteArray()

    val requestBody = """
    --$boundary
    Content-Disposition: form-data; name="user_import"; filename="users.csv"
    Content-Type: text/csv

    $csvContent
    --$boundary
    Content-Disposition: form-data; name="user_avatar"; filename="avatar.png"
    Content-Type: image/png

    """.trimIndent().toByteArray() + dummyImageBytes + "\r\n--$boundary--".toByteArray()

    try {
        val result = service.processFileUploadRequest(ByteArrayInputStream(requestBody), boundary)
        println("Upload processed successfully: ${result.keys}")
    } catch (e: FileOperationException) {
        println("Caught expected exception: ${e.message}")
    }

    // 2. Failure Scenario (Invalid CSV)
    val badCsvContent = "id,email\n1,bad@data.com"
    val badRequestBody = """
    --$boundary
    Content-Disposition: form-data; name="user_import"; filename="users.csv"
    Content-Type: text/csv

    $badCsvContent
    --$boundary--
    """.trimIndent().toByteArray()

    try {
        service.processFileUploadRequest(ByteArrayInputStream(badRequestBody), boundary)
    } catch (e: CsvProcessingException) {
        println("Caught expected CSV exception: ${e.message} at line ${e.lineNumber}")
    }

    // 3. Temp File and Download
    val tempFile = service.createManagedTempFile("robust_", ".dat")
    try {
        println("Created managed temp file: ${tempFile.absolutePath}")
        tempFile.writeText("This is a test download.")
        val downloadStream = ByteArrayOutputStream()
        service.streamFileForDownload(tempFile, downloadStream)
        println("Downloaded content: '${downloadStream.toString(StandardCharsets.UTF_8)}'")
    } finally {
        tempFile.delete() // Manual cleanup for this demo
        println("Temp file cleaned up.")
    }
}