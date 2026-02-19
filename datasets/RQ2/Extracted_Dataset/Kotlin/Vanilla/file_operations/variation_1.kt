package fileops.functional

import java.awt.RenderingHints
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

// --- Multipart Form Data Representation ---
data class MultipartPart(
    val headers: Map<String, String>,
    val body: ByteArray
) {
    val name: String? by lazy {
        headers["Content-Disposition"]
            ?.split(";")
            ?.find { it.trim().startsWith("name=") }
            ?.substringAfter("=")
            ?.trim()
            ?.replace("\"", "")
    }
    val filename: String? by lazy {
        headers["Content-Disposition"]
            ?.split(";")
            ?.find { it.trim().startsWith("filename=") }
            ?.substringAfter("=")
            ?.trim()
            ?.replace("\"", "")
    }
}

// --- Core File Operations (Functional Approach) ---

fun parseMultipartFormData(inputStream: InputStream, boundary: String): List<MultipartPart> {
    val boundaryBytes = ("--$boundary").toByteArray(StandardCharsets.UTF_8)
    val parts = mutableListOf<MultipartPart>()
    val reader = inputStream.buffered()

    // Find the first boundary
    var line = readLineAsBytes(reader)
    while (line != null && !line.contentEquals(boundaryBytes)) {
        line = readLineAsBytes(reader)
    }

    if (line == null) return emptyList()

    // Process parts
    while (true) {
        val headers = mutableMapOf<String, String>()
        var headerLine = readLineAsString(reader)
        while (headerLine != null && headerLine.isNotEmpty()) {
            val (key, value) = headerLine.split(":", limit = 2)
            headers[key.trim()] = value.trim()
            headerLine = readLineAsString(reader)
        }

        val body = readPartBody(reader, boundaryBytes)
        parts.add(MultipartPart(headers, body))

        // Check if it was the last boundary (--boundary--)
        if (reader.read() == '-'.code && reader.read() == '-'.code) {
            break
        }
    }
    return parts
}

fun parseUsersFromCsv(csvData: ByteArray): List<User> {
    val users = mutableListOf<User>()
    val reader = ByteArrayInputStream(csvData).bufferedReader()
    reader.readLine() // Skip header
    reader.forEachLine { line ->
        try {
            val fields = line.split(",")
            if (fields.size == 6) {
                users.add(
                    User(
                        id = UUID.fromString(fields[0]),
                        email = fields[1],
                        password_hash = fields[2],
                        role = UserRole.valueOf(fields[3]),
                        is_active = fields[4].toBoolean(),
                        created_at = Instant.parse(fields[5])
                    )
                )
            }
        } catch (e: Exception) {
            // Log or handle malformed lines
            println("Skipping malformed CSV line: $line")
        }
    }
    return users
}

fun resizeImage(imageData: ByteArray, width: Int, height: Int, format: String = "png"): ByteArray {
    val originalImage = ImageIO.read(ByteArrayInputStream(imageData))
    val resizedImage = BufferedImage(width, height, originalImage.type)
    val g2d = resizedImage.createGraphics()
    g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g2d.drawImage(originalImage, 0, 0, width, height, null)
    g2d.dispose()

    val outputStream = ByteArrayOutputStream()
    ImageIO.write(resizedImage, format, outputStream)
    return outputStream.toByteArray()
}

fun streamFileToOutput(file: File, outputStream: OutputStream) {
    file.inputStream().use { input ->
        input.copyTo(outputStream)
    }
}

fun <T> withTempFile(prefix: String, suffix: String, block: (File) -> T): T {
    val tempFile = File.createTempFile(prefix, suffix)
    try {
        return block(tempFile)
    } finally {
        if (tempFile.exists()) {
            tempFile.delete()
        }
    }
}

// --- Helper functions for parsing ---
private fun readLineAsBytes(stream: InputStream): ByteArray? {
    val buffer = ByteArrayOutputStream()
    var byte: Int
    while (stream.read().also { byte = it } != -1) {
        if (byte == '\r'.code) {
            val nextByte = stream.read()
            if (nextByte == '\n'.code) {
                return buffer.toByteArray()
            }
            buffer.write('\r'.code)
            if (nextByte != -1) buffer.write(nextByte)
        } else {
            buffer.write(byte)
        }
    }
    return if (buffer.size() > 0) buffer.toByteArray() else null
}

private fun readLineAsString(stream: InputStream): String? {
    return readLineAsBytes(stream)?.toString(StandardCharsets.UTF_8)
}

private fun readPartBody(stream: InputStream, boundary: ByteArray): ByteArray {
    val bodyStream = ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    var bytesRead: Int

    // This is a simplified body reader. A robust implementation would need
    // a more sophisticated boundary detection algorithm that can span across buffer reads.
    // For this example, we read until we find a line starting with the boundary.
    val boundaryWithPrefix = "\r\n".toByteArray(StandardCharsets.UTF_8) + boundary
    val tempStream = ByteArrayOutputStream()
    stream.copyTo(tempStream)
    val allBytes = tempStream.toByteArray()
    val boundaryIndex = findByteSequence(allBytes, boundaryWithPrefix)

    if (boundaryIndex != -1) {
        bodyStream.write(allBytes, 0, boundaryIndex)
    } else {
        bodyStream.write(allBytes) // Fallback
    }

    return bodyStream.toByteArray()
}

private fun findByteSequence(source: ByteArray, target: ByteArray): Int {
    if (target.isEmpty()) return 0
    for (i in 0..(source.size - target.size)) {
        var found = true
        for (j in target.indices) {
            if (source[i + j] != target[j]) {
                found = false
                break
            }
        }
        if (found) return i
    }
    return -1
}


// --- Main function to demonstrate usage ---
fun main() {
    println("--- Functional/Procedural Demo ---")

    // 1. File Upload Handling (Multipart Parsing)
    val boundary = "WebAppBoundary"
    val multipartData = """
    --$boundary
    Content-Disposition: form-data; name="user_csv"; filename="users.csv"
    Content-Type: text/csv

    id,email,password_hash,role,is_active,created_at
    1a7b8b52-3c3c-4e4e-8f8f-1a1a1a1a1a1a,admin@test.com,hash1,ADMIN,true,2023-01-01T12:00:00Z
    2b8c9c63-4d4d-5f5f-9g9g-2b2b2b2b2b2b,user@test.com,hash2,USER,false,2023-02-01T10:00:00Z

    --$boundary
    Content-Disposition: form-data; name="profile_pic"; filename="avatar.png"
    Content-Type: image/png

    [...binary image data...]
    --$boundary--
    """.trimIndent().replace("[...binary image data...]", "PNG_DATA")

    val multipartStream = ByteArrayInputStream(multipartData.toByteArray(StandardCharsets.UTF_8))
    val parts = parseMultipartFormData(multipartStream, boundary)
    println("Parsed ${parts.size} multipart parts.")
    val csvPart = parts.find { it.filename == "users.csv" }
    val imagePart = parts.find { it.filename == "avatar.png" }

    // 2. CSV Parsing
    if (csvPart != null) {
        val users = parseUsersFromCsv(csvPart.body)
        println("Parsed ${users.size} users from CSV: ${users.map { it.email }}")
    }

    // 3. Image Resizing
    // Create a dummy image for processing
    val dummyImage = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
    val dummyImageBytes = ByteArrayOutputStream().use { ImageIO.write(dummyImage, "png", it); it.toByteArray() }
    
    val resizedImageBytes = resizeImage(dummyImageBytes, 50, 50)
    println("Resized image from ${dummyImageBytes.size} bytes to ${resizedImageBytes.size} bytes.")

    // 4. Temporary File Management & 5. File Download
    withTempFile("download_", ".txt") { tempFile ->
        println("Created temporary file: ${tempFile.absolutePath}")
        tempFile.writeText("This is the content of the file to be downloaded.")

        println("Simulating download...")
        val downloadOutputStream = ByteArrayOutputStream()
        streamFileToOutput(tempFile, downloadOutputStream)
        println("Downloaded content: '${downloadOutputStream.toString(StandardCharsets.UTF_8)}'")
        println("Temp file exists: ${tempFile.exists()}")
    }
    // After the block, the temp file is deleted.
    println("Exited withTempFile block.")
}