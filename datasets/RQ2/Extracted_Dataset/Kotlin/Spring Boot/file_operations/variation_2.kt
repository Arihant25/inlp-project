package com.example.fileops.functional

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVPrinter
import org.imgscalr.Scalr
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.awt.image.BufferedImage
import java.io.InputStream
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

// --- Kotlin Extension Functions for File Operations ---

fun MultipartFile.toUsers(): Result<List<User>> = runCatching {
    this.inputStream.use { stream ->
        CSVParser(stream.reader(), CSVFormat.DEFAULT.withFirstRecordAsHeader().withIgnoreHeaderCase().withTrim())
            .map { record ->
                User(
                    id = UUID.randomUUID(),
                    email = record.get("email"),
                    passwordHash = "hashed_${UUID.randomUUID()}",
                    role = UserRole.valueOf(record.get("role").uppercase()),
                    isActive = record.get("is_active").toBoolean(),
                    createdAt = Timestamp.from(Instant.now())
                )
            }
    }
}

fun BufferedImage.resize(width: Int, height: Int): BufferedImage = Scalr.resize(this, Scalr.Method.ULTRA_QUALITY, width, height)

fun Path.useAndDelete(block: (Path) -> Unit) {
    try {
        block(this)
    } finally {
        Files.deleteIfExists(this)
    }
}

// --- Processor Component ---

@Component
class FileProcessor {
    private val log = LoggerFactory.getLogger(FileProcessor::class.java)

    private val mockPostsDb = listOf(
        Post(UUID.randomUUID(), UUID.randomUUID(), "Functional Kotlin", "Content...", PostStatus.PUBLISHED),
        Post(UUID.randomUUID(), UUID.randomUUID(), "Spring Boot Tips", "More content...", PostStatus.DRAFT)
    )

    fun importUsers(file: MultipartFile): List<User> {
        return file.toUsers().getOrElse {
            log.error("CSV parsing failed", it)
            throw IllegalStateException("Failed to parse CSV: ${it.message}")
        }.also {
            log.info("Imported ${it.size} users.")
            // Persist users here
        }
    }

    fun attachImageToPost(postId: UUID, imageFile: MultipartFile): String {
        val tempFile = Files.createTempFile("resized-", imageFile.originalFilename)
        
        tempFile.useAndDelete { path ->
            runCatching {
                val format = imageFile.originalFilename?.substringAfterLast('.', "png") ?: "png"
                ImageIO.read(imageFile.inputStream)
                    .resize(300, 300)
                    .let { ImageIO.write(it, format, path.toFile()) }
                log.info("Image for post $postId processed and saved to temp file: $path")
                // Upload to cloud storage and return URL
            }.getOrElse {
                log.error("Image processing failed for post $postId", it)
                throw RuntimeException("Image processing failed", it)
            }
        }
        return "s3://bucket/images/$postId/${tempFile.fileName}"
    }

    fun streamPostsReport(): StreamingResponseBody = StreamingResponseBody { out ->
        OutputStreamWriter(out, StandardCharsets.UTF_8).use { writer ->
            CSVPrinter(writer, CSVFormat.DEFAULT.withHeader("ID", "Title", "Status")).use { printer ->
                mockPostsDb.forEach { post -> printer.printRecord(post.id, post.title, post.status) }
            }
        }
        log.info("Streamed post report.")
    }
}

// --- Controller ---

@RestController
@RequestMapping("/api/v2/files")
class FileHandlerController(private val processor: FileProcessor) {

    @PostMapping("/users/import", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun importUsers(@RequestParam("data") file: MultipartFile): ResponseEntity<Any> =
        ResponseEntity.ok(
            mapOf(
                "message" to "User import successful.",
                "imported_count" to processor.importUsers(file).size
            )
        )

    @PostMapping("/posts/{id}/image", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun attachImage(
        @PathVariable id: UUID,
        @RequestParam("image") image: MultipartFile
    ): ResponseEntity<Any> =
        ResponseEntity.ok(
            mapOf(
                "message" to "Image attached successfully.",
                "url" to processor.attachImageToPost(id, image)
            )
        )

    @GetMapping("/posts/report/download", produces = ["text/csv"])
    fun downloadReport(): ResponseEntity<StreamingResponseBody> {
        val headers = HttpHeaders().apply {
            this.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"posts_report.csv\"")
        }
        return ResponseEntity.ok().headers(headers).body(processor.streamPostsReport())
    }
}