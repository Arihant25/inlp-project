package com.example.fileops.utility

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVPrinter
import org.imgscalr.Scalr
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.imageio.ImageIO

// --- Domain Model ---

enum class UserRole { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }

data class User(
    val id: UUID,
    val email: String,
    val passwordHash: String,
    val role: UserRole,
    val isActive: Boolean,
    val createdAt: Timestamp
)

data class Post(
    val id: UUID,
    val userId: UUID,
    val title: String,
    val content: String,
    val status: PostStatus
)

// --- Utility Objects ---

object CsvUtil {
    fun parseUsersFromStream(inputStream: InputStream): List<User> {
        return InputStreamReader(inputStream, StandardCharsets.UTF_8).use { reader ->
            val parser = CSVParser(reader, CSVFormat.DEFAULT.withFirstRecordAsHeader().withIgnoreHeaderCase().withTrim())
            parser.records.map { record ->
                User(
                    id = UUID.randomUUID(),
                    email = record.get("email"),
                    passwordHash = "---", // Password should be hashed separately
                    role = UserRole.valueOf(record.get("role").uppercase()),
                    isActive = record.get("is_active").toBoolean(),
                    createdAt = Timestamp.from(Instant.now())
                )
            }
        }
    }

    fun writePostsToStream(outputStream: OutputStream, posts: List<Post>) {
        OutputStreamWriter(outputStream, StandardCharsets.UTF_8).use { writer ->
            CSVPrinter(writer, CSVFormat.DEFAULT.withHeader("ID", "UserID", "Title", "Status")).use { csvPrinter ->
                posts.forEach { post ->
                    csvPrinter.printRecord(post.id, post.userId, post.title, post.status)
                }
            }
        }
    }
}

object ImageUtil {
    fun resize(inputStream: InputStream, width: Int, height: Int, format: String): Path {
        val tempFile = TempFileUtil.create("resized-", ".$format")
        val sourceImage = ImageIO.read(inputStream)
        val resizedImage = Scalr.resize(sourceImage, Scalr.Method.BALANCED, width, height)
        ImageIO.write(resizedImage, format, tempFile.toFile())
        return tempFile
    }
}

object TempFileUtil {
    fun create(prefix: String, suffix: String): Path {
        return Files.createTempFile(prefix, suffix)
    }

    fun <T> withTempFile(prefix: String, suffix: String, block: (Path) -> T): T {
        val path = create(prefix, suffix)
        try {
            return block(path)
        } finally {
            Files.deleteIfExists(path)
        }
    }
}

// --- Controller Layer ---

@RestController
@RequestMapping("/api/v4/files")
class FileResource {

    private val log = LoggerFactory.getLogger(FileResource::class.java)

    // In a real app, this would come from a service/repository
    private val mockPostData = listOf(
        Post(UUID.randomUUID(), UUID.randomUUID(), "Utility Pattern", "Content...", PostStatus.PUBLISHED),
        Post(UUID.randomUUID(), UUID.randomUUID(), "Helper Classes", "More content...", PostStatus.DRAFT)
    )

    @PostMapping("/users/upload", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun handleUserUpload(@RequestParam("user_data") file: MultipartFile): ResponseEntity<Map<String, Any>> {
        if (file.isEmpty) {
            return ResponseEntity.badRequest().body(mapOf("error" to "File is empty"))
        }

        return try {
            val users = CsvUtil.parseUsersFromStream(file.inputStream)
            log.info("Parsed ${users.size} users from ${file.originalFilename}")
            // Here, you would save the users to the database
            ResponseEntity.ok(mapOf("status" to "success", "users_processed" to users.size))
        } catch (e: Exception) {
            log.error("Failed to process user upload", e)
            ResponseEntity.status(500).body(mapOf("error" to "Failed to parse CSV: ${e.message}"))
        }
    }

    @PostMapping("/posts/{id}/artwork", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun handlePostArtworkUpload(
        @PathVariable id: UUID,
        @RequestParam("artwork") imageFile: MultipartFile
    ): ResponseEntity<Map<String, String>> {
        val format = imageFile.originalFilename?.substringAfterLast('.', "png") ?: "png"
        
        return TempFileUtil.withTempFile("artwork-", ".$format") { tempPath ->
            try {
                val resizedPath = ImageUtil.resize(imageFile.inputStream, 500, 500, format)
                log.info("Resized image for post $id to path: $resizedPath")
                // Here, upload the file from resizedPath to a permanent storage like S3
                ResponseEntity.ok(mapOf(
                    "status" to "success",
                    "postId" to id.toString(),
                    "artwork_url" to "/media/artworks/${resizedPath.fileName}"
                ))
            } catch (e: Exception) {
                log.error("Failed to process artwork for post $id", e)
                ResponseEntity.status(500).body(mapOf("error" to "Image processing failed: ${e.message}"))
            }
        }
    }

    @GetMapping("/posts/export", produces = ["text/csv"])
    fun handlePostsExport(): ResponseEntity<StreamingResponseBody> {
        val stream = StreamingResponseBody { outputStream ->
            try {
                CsvUtil.writePostsToStream(outputStream, mockPostData)
                log.info("Successfully streamed post data export.")
            } catch (e: Exception) {
                log.error("Error during post data export streaming", e)
            }
        }

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"posts_export.csv\"")
            .contentType(MediaType.parseMediaType("text/csv"))
            .body(stream)
    }
}