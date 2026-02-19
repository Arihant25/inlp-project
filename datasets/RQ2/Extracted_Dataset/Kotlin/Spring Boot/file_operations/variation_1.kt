package com.example.fileops.classic

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVPrinter
import org.imgscalr.Scalr
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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

// --- Service Layer (Interface and Implementation) ---

interface FileOperationsService {
    fun importUsersFromCsv(file: MultipartFile): List<User>
    fun processAndStorePostImage(postId: UUID, imageFile: MultipartFile): String
    fun generatePostsCsvReport(): StreamingResponseBody
}

@Service
class FileOperationsServiceImpl : FileOperationsService {

    private val logger = LoggerFactory.getLogger(FileOperationsServiceImpl::class.java)

    // In a real app, this would interact with a repository/database
    private val mockPosts = listOf(
        Post(UUID.randomUUID(), UUID.randomUUID(), "Spring Boot Basics", "Content here...", PostStatus.PUBLISHED),
        Post(UUID.randomUUID(), UUID.randomUUID(), "Kotlin Coroutines", "More content...", PostStatus.PUBLISHED)
    )

    override fun importUsersFromCsv(file: MultipartFile): List<User> {
        if (file.isEmpty) {
            throw IllegalArgumentException("Cannot process an empty file.")
        }
        val users = mutableListOf<User>()
        try {
            InputStreamReader(file.inputStream, StandardCharsets.UTF_8).use { reader ->
                CSVParser(reader, CSVFormat.DEFAULT.withFirstRecordAsHeader().withIgnoreHeaderCase().withTrim()).use { csvParser ->
                    for (csvRecord in csvParser) {
                        val user = User(
                            id = UUID.randomUUID(),
                            email = csvRecord.get("email"),
                            passwordHash = "mock_hash_${csvRecord.get("email")}", // In real life, hash the password
                            role = UserRole.valueOf(csvRecord.get("role").uppercase()),
                            isActive = csvRecord.get("is_active").toBoolean(),
                            createdAt = Timestamp.from(Instant.now())
                        )
                        users.add(user)
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to parse CSV file", e)
            throw RuntimeException("Error parsing CSV file: ${e.message}")
        }
        logger.info("Successfully imported ${users.size} users.")
        // Here you would typically save the users to a database
        return users
    }

    override fun processAndStorePostImage(postId: UUID, imageFile: MultipartFile): String {
        val tempDir = Files.createTempDirectory("post-images-")
        val originalFile = tempDir.resolve("original_${imageFile.originalFilename}").toFile()
        val resizedFile = tempDir.resolve("resized_200x200_${imageFile.originalFilename}").toFile()

        try {
            imageFile.transferTo(originalFile)
            val originalImage = ImageIO.read(originalFile)
            val resizedImage = Scalr.resize(originalImage, Scalr.Method.QUALITY, 200, 200)
            
            val formatName = imageFile.originalFilename?.substringAfterLast('.', "png") ?: "png"
            ImageIO.write(resizedImage, formatName, resizedFile)
            
            logger.info("Resized image for post $postId and saved to ${resizedFile.absolutePath}")
            // In a real app, you'd upload this to S3/GCS and return the URL
            return resizedFile.absolutePath
        } catch (e: Exception) {
            logger.error("Failed to process image for post $postId", e)
            throw RuntimeException("Could not process image: ${e.message}")
        } finally {
            // Clean up temporary files
            originalFile.delete()
            resizedFile.delete()
            Files.delete(tempDir)
        }
    }

    override fun generatePostsCsvReport(): StreamingResponseBody {
        return StreamingResponseBody { outputStream ->
            OutputStreamWriter(outputStream, StandardCharsets.UTF_8).use { writer ->
                CSVPrinter(writer, CSVFormat.DEFAULT.withHeader("ID", "UserID", "Title", "Status")).use { csvPrinter ->
                    mockPosts.forEach { post ->
                        csvPrinter.printRecord(post.id, post.userId, post.title, post.status)
                    }
                }
            }
            logger.info("Generated and streamed posts CSV report.")
        }
    }
}

// --- Controller Layer ---

@RestController
@RequestMapping("/api/v1/files")
class FileOperationsController(private val fileOperationsService: FileOperationsService) {

    private val logger = LoggerFactory.getLogger(FileOperationsController::class.java)

    @PostMapping("/users/upload-csv", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadUserCsv(@RequestParam("file") file: MultipartFile): ResponseEntity<Map<String, Any>> {
        logger.info("Received request to upload user CSV: ${file.originalFilename}")
        val importedUsers = fileOperationsService.importUsersFromCsv(file)
        return ResponseEntity.ok(mapOf(
            "message" to "Successfully imported ${importedUsers.size} users.",
            "count" to importedUsers.size
        ))
    }

    @PostMapping("/posts/{postId}/upload-image", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadPostImage(
        @PathVariable postId: UUID,
        @RequestParam("image") imageFile: MultipartFile
    ): ResponseEntity<Map<String, String>> {
        logger.info("Received request to upload image for post: $postId")
        val imagePath = fileOperationsService.processAndStorePostImage(postId, imageFile)
        return ResponseEntity.status(HttpStatus.CREATED).body(mapOf(
            "message" to "Image processed successfully.",
            "path" to imagePath
        ))
    }

    @GetMapping("/posts/download-report", produces = ["text/csv"])
    fun downloadPostsReport(): ResponseEntity<StreamingResponseBody> {
        logger.info("Received request to download posts report.")
        val headers = HttpHeaders().apply {
            add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=posts_report.csv")
        }
        val stream = fileOperationsService.generatePostsCsvReport()
        return ResponseEntity.ok().headers(headers).body(stream)
    }
}