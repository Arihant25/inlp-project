package fileops.oop

import java.awt.Graphics2D
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

// --- OOP Implementation ---

// Represents a single part of a multipart request
class MultipartPart(val headers: Map<String, String>, val body: ByteArray) {
    fun getHeaderValue(name: String, key: String): String? {
        return headers[name]
            ?.split(";")
            ?.map { it.trim() }
            ?.find { it.startsWith("$key=") }
            ?.substringAfter("=")
            ?.replace("\"", "")
    }

    val contentDispositionName: String? by lazy { getHeaderValue("Content-Disposition", "name") }
    val contentDispositionFilename: String? by lazy { getHeaderValue("Content-Disposition", "filename") }
}

// Responsible for parsing multipart streams
class MultipartParser(private val boundary: String) {
    fun parse(inputStream: InputStream): List<MultipartPart> {
        val boundaryBytes = "--$boundary".toByteArray()
        val endBoundaryBytes = "--$boundary--".toByteArray()
        val allData = inputStream.readBytes()
        val parts = mutableListOf<MultipartPart>()

        var currentPos = findBoundary(allData, boundaryBytes, 0)
        if (currentPos == -1) return emptyList()
        currentPos += boundaryBytes.size

        while (true) {
            val nextPos = findBoundary(allData, boundaryBytes, currentPos)
            if (nextPos == -1) break

            val partData = allData.sliceArray(currentPos until nextPos)
            val part = parseSinglePart(partData)
            parts.add(part)

            currentPos = nextPos + boundaryBytes.size
            if (allData.sliceArray(nextPos until (nextPos + endBoundaryBytes.size)).contentEquals(endBoundaryBytes)) {
                break
            }
        }
        return parts
    }

    private fun parseSinglePart(partData: ByteArray): MultipartPart {
        val crlf = "\r\n".toByteArray()
        val doubleCrlf = "\r\n\r\n".toByteArray()

        val headerEndIndex = findBoundary(partData, doubleCrlf, 0)
        if (headerEndIndex == -1) throw IllegalArgumentException("Invalid part format: no headers")

        val headerBytes = partData.sliceArray(0 until headerEndIndex)
        val bodyBytes = partData.sliceArray((headerEndIndex + doubleCrlf.size) until partData.size - crlf.size)

        val headers = String(headerBytes).lines()
            .filter { it.contains(":") }
            .associate {
                val (key, value) = it.split(":", limit = 2)
                key.trim() to value.trim()
            }

        return MultipartPart(headers, bodyBytes)
    }

    private fun findBoundary(data: ByteArray, boundary: ByteArray, start: Int): Int {
        for (i in start..(data.size - boundary.size)) {
            val slice = data.sliceArray(i until i + boundary.size)
            if (slice.contentEquals(boundary)) {
                return i
            }
        }
        return -1
    }
}

// Responsible for processing CSV data into User objects
class CsvUserImporter {
    fun importUsers(csvInputStream: InputStream): List<User> {
        return csvInputStream.bufferedReader().useLines { lines ->
            lines.drop(1) // Skip header
                .mapNotNull { line ->
                    try {
                        val fields = line.split(',')
                        User(
                            id = UUID.fromString(fields[0]),
                            email = fields[1],
                            password_hash = fields[2],
                            role = UserRole.valueOf(fields[3]),
                            is_active = fields[4].toBoolean(),
                            created_at = Instant.parse(fields[5])
                        )
                    } catch (e: Exception) {
                        null // Ignore invalid lines
                    }
                }.toList()
        }
    }
}

// Responsible for image manipulation
class ImageProcessor {
    fun createThumbnail(originalData: ByteArray, width: Int, height: Int): ByteArray {
        val inputStream = ByteArrayInputStream(originalData)
        val originalImage = ImageIO.read(inputStream)
        val thumbnail = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g: Graphics2D = thumbnail.createGraphics()
        g.drawImage(originalImage, 0, 0, width, height, null)
        g.dispose()
        val outputStream = ByteArrayOutputStream()
        ImageIO.write(thumbnail, "png", outputStream)
        return outputStream.toByteArray()
    }
}

// Central service orchestrating file operations
class FileOperationService {
    fun handleUpload(requestStream: InputStream, contentTypeHeader: String): Map<String, Any> {
        val boundary = contentTypeHeader.substringAfter("boundary=")
        val parser = MultipartParser(boundary)
        val parts = parser.parse(requestStream)
        
        val results = mutableMapOf<String, Any>()

        val csvPart = parts.find { it.contentDispositionFilename?.endsWith(".csv") == true }
        if (csvPart != null) {
            val importer = CsvUserImporter()
            val users = importer.importUsers(ByteArrayInputStream(csvPart.body))
            results["importedUsers"] = users
        }

        val imagePart = parts.find { it.headers["Content-Type"]?.startsWith("image/") == true }
        if (imagePart != null) {
            val processor = ImageProcessor()
            val thumbnail = processor.createThumbnail(imagePart.body, 64, 64)
            results["thumbnailBytes"] = thumbnail
        }
        
        return results
    }

    fun downloadFile(file: File, responseStream: OutputStream) {
        file.inputStream().use { it.copyTo(responseStream) }
    }

    fun manageTempFile(action: (File) -> Unit) {
        val tempFile = File.createTempFile("app-data-", ".tmp")
        try {
            action(tempFile)
        } finally {
            tempFile.delete()
        }
    }
}

// --- Main function to demonstrate usage ---
fun main() {
    println("--- Classic OOP Demo ---")
    val service = FileOperationService()

    // 1. File Upload, CSV Parsing, Image Processing
    val boundary = "MyBoundary123"
    val contentType = "multipart/form-data; boundary=$boundary"
    val csvContent = "id,email,password_hash,role,is_active,created_at\n" +
                     "1a7b8b52-3c3c-4e4e-8f8f-1a1a1a1a1a1a,admin@test.com,hash1,ADMIN,true,2023-01-01T12:00:00Z"
    val dummyImageBytes = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB), "png", it)
    }.toByteArray()

    val requestBody = """
    --$boundary
    Content-Disposition: form-data; name="users"; filename="users.csv"
    Content-Type: text/csv

    $csvContent
    --$boundary
    Content-Disposition: form-data; name="avatar"; filename="avatar.png"
    Content-Type: image/png

    """.trimIndent().toByteArray() + dummyImageBytes + "\r\n--$boundary--\r\n".toByteArray()

    val uploadResult = service.handleUpload(ByteArrayInputStream(requestBody), contentType)
    val importedUsers = uploadResult["importedUsers"] as? List<*>
    val thumbnail = uploadResult["thumbnailBytes"] as? ByteArray

    println("Upload handled. Imported ${importedUsers?.size} users.")
    println("Generated thumbnail of ${thumbnail?.size} bytes.")

    // 2. Temporary File Management & File Download
    service.manageTempFile { tempFile ->
        println("Working with temp file: ${tempFile.path}")
        tempFile.writeText("Downloadable content.")
        
        val downloadStream = ByteArrayOutputStream()
        service.downloadFile(tempFile, downloadStream)
        println("Downloaded content: '${downloadStream.toString(StandardCharsets.UTF_8)}'")
    }
    println("Temp file management complete.")
}