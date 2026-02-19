package com.example.fileops.command

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVPrinter
import org.imgscalr.Scalr
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.annotation.PostConstruct
import javax.imageio.ImageIO
import kotlin.reflect.KClass

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

// --- Command and Handler Definitions ---

sealed interface Command<R>
data class ImportUsersFromCsvCommand(val file: MultipartFile) : Command<List<User>>
data class ProcessPostImageCommand(val postId: UUID, val image: MultipartFile) : Command<String>
class GeneratePostsReportCommand : Command<StreamingResponseBody>

interface CommandHandler<C : Command<R>, R> {
    fun handle(command: C): R
    val commandType: KClass<C>
}

@Component
class ImportUsersFromCsvHandler : CommandHandler<ImportUsersFromCsvCommand, List<User>> {
    private val log = LoggerFactory.getLogger(javaClass)
    override val commandType = ImportUsersFromCsvCommand::class

    override fun handle(command: ImportUsersFromCsvCommand): List<User> {
        log.info("Handling ImportUsersFromCsvCommand")
        return command.file.inputStream.use { stream ->
            InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
                CSVParser(reader, CSVFormat.DEFAULT.withHeader().withIgnoreHeaderCase().withTrim())
                    .records.map {
                        User(
                            id = UUID.randomUUID(),
                            email = it.get("email"),
                            passwordHash = "hash_placeholder",
                            role = UserRole.valueOf(it.get("role").uppercase()),
                            isActive = it.get("is_active").toBoolean(),
                            createdAt = Timestamp.from(Instant.now())
                        )
                    }
            }
        }.also { log.info("Imported ${it.size} users.") }
    }
}

@Component
class ProcessPostImageHandler : CommandHandler<ProcessPostImageCommand, String> {
    private val log = LoggerFactory.getLogger(javaClass)
    override val commandType = ProcessPostImageCommand::class

    override fun handle(command: ProcessPostImageCommand): String {
        log.info("Handling ProcessPostImageCommand for post ${command.postId}")
        val tempFile = Files.createTempFile("img-proc-", command.image.originalFilename)
        try {
            val originalImage = ImageIO.read(command.image.inputStream)
            val resized = Scalr.resize(originalImage, 400)
            val format = command.image.originalFilename?.substringAfterLast('.', "jpg") ?: "jpg"
            ImageIO.write(resized, format, tempFile.toFile())
            // In a real app, upload tempFile to cloud storage
            return "processed_path/${tempFile.fileName}"
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }
}

@Component
class GeneratePostsReportHandler : CommandHandler<GeneratePostsReportCommand, StreamingResponseBody> {
    private val log = LoggerFactory.getLogger(javaClass)
    override val commandType = GeneratePostsReportCommand::class
    private val mockPosts = listOf(
        Post(UUID.randomUUID(), UUID.randomUUID(), "CQRS Pattern", "Content...", PostStatus.PUBLISHED),
        Post(UUID.randomUUID(), UUID.randomUUID(), "Event Sourcing", "More content...", PostStatus.PUBLISHED)
    )

    override fun handle(command: GeneratePostsReportCommand): StreamingResponseBody {
        log.info("Handling GeneratePostsReportCommand")
        return StreamingResponseBody { outputStream ->
            OutputStreamWriter(outputStream).use { writer ->
                CSVPrinter(writer, CSVFormat.DEFAULT.withHeader("ID", "UserID", "Title", "Status")).use { printer ->
                    mockPosts.forEach { post -> printer.printRecord(post.id, post.userId, post.title, post.status) }
                }
            }
        }
    }
}

// --- Command Dispatcher ---

@Service
class CommandDispatcher(private val context: ApplicationContext) {
    private lateinit var handlers: Map<KClass<out Command<*>>, CommandHandler<*, *>>

    @PostConstruct
    fun registerHandlers() {
        handlers = context.getBeansOfType(CommandHandler::class.java).values
            .associateBy { it.commandType }
    }

    @Suppress("UNCHECKED_CAST")
    fun <R> dispatch(command: Command<R>): R {
        val handler = handlers[command::class]
            ?: throw IllegalArgumentException("No handler found for command ${command::class.simpleName}")
        return (handler as CommandHandler<Command<R>, R>).handle(command)
    }
}

// --- Controller ---

@RestController
@RequestMapping("/api/v3/files")
class FileCommandController(private val dispatcher: CommandDispatcher) {

    @PostMapping("/users/import-csv", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun importUsers(@RequestParam("file") file: MultipartFile): ResponseEntity<Map<String, Any>> {
        val command = ImportUsersFromCsvCommand(file)
        val result = dispatcher.dispatch(command)
        return ResponseEntity.ok(mapOf("message" to "Users imported successfully", "count" to result.size))
    }

    @PostMapping("/posts/{postId}/image", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun processImage(
        @PathVariable postId: UUID,
        @RequestParam("image") image: MultipartFile
    ): ResponseEntity<Map<String, String>> {
        val command = ProcessPostImageCommand(postId, image)
        val path = dispatcher.dispatch(command)
        return ResponseEntity.ok(mapOf("message" to "Image processed", "path" to path))
    }

    @GetMapping("/posts/report", produces = ["text/csv"])
    fun downloadReport(): ResponseEntity<StreamingResponseBody> {
        val command = GeneratePostsReportCommand()
        val stream = dispatcher.dispatch(command)
        val headers = HttpHeaders().apply {
            add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=posts_report.csv")
        }
        return ResponseEntity.ok().headers(headers).body(stream)
    }
}