package com.example.variation4

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskExecutor
import org.springframework.retry.annotation.Backoff
import org.springframework.retry.annotation.EnableRetry
import org.springframework.retry.annotation.Retryable
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

// --- Domain Model ---
enum class Role { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }
data class User(val id: UUID, val email: String, val passwordHash: String, val role: Role, val isActive: Boolean, val createdAt: Instant)
data class Post(val id: UUID, val userId: UUID, val title: String, val content: String, val status: PostStatus)

// --- Job Management Abstraction ---
enum class JobState { SUBMITTED, ACTIVE, SUCCESS, ERROR }
data class JobRecord(
    val id: UUID,
    val name: String,
    var state: JobState,
    val submittedAt: Instant,
    var log: String = ""
)

@Service
class JobManager {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val jobStore = ConcurrentHashMap<UUID, JobRecord>()

    fun findJob(id: UUID): JobRecord? = jobStore[id]

    fun <T> submit(jobName: String, task: () -> T): Pair<UUID, CompletableFuture<T>> {
        val record = JobRecord(UUID.randomUUID(), jobName, JobState.SUBMITTED, Instant.now())
        jobStore[record.id] = record
        logger.info("Submitted job ${record.id} ($jobName)")

        val future = CompletableFuture.supplyAsync {
            record.state = JobState.ACTIVE
            record.log += "Started at ${Instant.now()}.\n"
            jobStore[record.id] = record
            task()
        }.whenComplete { result, ex ->
            if (ex != null) {
                record.state = JobState.ERROR
                record.log += "Failed: ${ex.message}\n"
                logger.error("Job ${record.id} ($jobName) failed", ex)
            } else {
                record.state = JobState.SUCCESS
                record.log += "Completed successfully with result: $result\n"
                logger.info("Job ${record.id} ($jobName) succeeded")
            }
            jobStore[record.id] = record
        }
        return record.id to future
    }
}

// --- Configuration-Centric Setup ---

@Configuration
@EnableAsync
@EnableScheduling
@EnableRetry
class TaskExecutionConfig {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Bean("emailTaskExecutor")
    fun emailTaskExecutor(): TaskExecutor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = 2
        executor.maxPoolSize = 5
        executor.setQueueCapacity(25)
        executor.setThreadNamePrefix("EmailExec-")
        executor.setRejectedExecutionHandler { r, _ ->
            logger.warn("Email task queue is full. Task $r rejected.")
        }
        executor.initialize()
        return executor
    }

    @Bean("imageProcessingTaskExecutor")
    fun imageProcessingTaskExecutor(): TaskExecutor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = 4
        executor.maxPoolSize = 10
        executor.setQueueCapacity(50)
        executor.setThreadNamePrefix("ImageProc-")
        executor.initialize()
        return executor
    }
}

// --- Business Logic Handlers ---

@Service
class NotificationHandler {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Async("emailTaskExecutor")
    @Retryable(maxAttempts = 4, backoff = Backoff(delay = 2000, multiplier = 1.5))
    fun sendWelcomeNotification(user: User): CompletableFuture<String> {
        logger.info("Attempting to send welcome email to ${user.email} on thread ${Thread.currentThread().name}")
        if (Random.nextInt(10) < 6) { // 60% failure rate to test retry
            throw IllegalStateException("SMTP server not responding")
        }
        Thread.sleep(1500)
        val result = "Email sent to ${user.email}"
        logger.info(result)
        return CompletableFuture.completedFuture(result)
    }
}

@Service
class ImageProcessingHandler {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Async("imageProcessingTaskExecutor")
    fun processImage(post: Post): CompletableFuture<Boolean> {
        logger.info("Starting image processing for post ${post.id} on thread ${Thread.currentThread().name}")
        try {
            // Simulate pipeline steps
            Thread.sleep(2000) // Step 1: Resize
            logger.info("[Post ${post.id}] Image resized.")
            Thread.sleep(1000) // Step 2: Watermark
            logger.info("[Post ${post.id}] Watermark applied.")
            Thread.sleep(3000) // Step 3: Upload
            logger.info("[Post ${post.id}] Uploaded to storage.")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return CompletableFuture.failedFuture(e)
        }
        return CompletableFuture.completedFuture(true)
    }
}

@Service
class SystemMaintenanceScheduler {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${cleanup.interval:600000}") // Every 10 mins, configurable
    fun performCleanup() {
        logger.info("Performing periodic system maintenance on thread ${Thread.currentThread().name}")
        // Simulate cleanup task
        Thread.sleep(10000)
        logger.info("Maintenance complete.")
    }
}

// --- Application Service Orchestrator ---

@Service
class UserService(
    private val jobManager: JobManager,
    private val notificationHandler: NotificationHandler
) {
    fun registerUser(email: String, pass: String): UUID {
        val user = User(UUID.randomUUID(), email, pass, Role.USER, true, Instant.now())
        // In real app, save user to DB here
        
        val (jobId, _) = jobManager.submit("Send Welcome Email to $email") {
            notificationHandler.sendWelcomeNotification(user).get() // .get() to block inside the job
        }
        return jobId
    }
}

@Service
class PostService(
    private val jobManager: JobManager,
    private val imageProcessingHandler: ImageProcessingHandler
) {
    fun createPost(title: String, content: String): UUID {
        val post = Post(UUID.randomUUID(), UUID.randomUUID(), title, content, PostStatus.PUBLISHED)
        // In real app, save post to DB here
        
        val (jobId, _) = jobManager.submit("Process Image for Post: $title") {
            imageProcessingHandler.processImage(post).get()
        }
        return jobId
    }
}

// --- API Controller ---

@SpringBootApplication
class ConfigCentricApp

fun main(args: Array<String>) {
    runApplication<ConfigCentricApp>(*args)
}

@RestController
@RequestMapping("/v1")
class ApiController(
    private val userService: UserService,
    private val postService: PostService,
    private val jobManager: JobManager
) {
    @PostMapping("/users")
    fun createUser(): Map<String, String> {
        val jobId = userService.registerUser("config.user@example.com", "password")
        return mapOf("message" to "User registration initiated.", "jobId" to jobId.toString())
    }

    @PostMapping("/posts")
    fun createPost(): Map<String, String> {
        val jobId = postService.createPost("Config-Driven Post", "Some content here.")
        return mapOf("message" to "Post creation and image processing initiated.", "jobId" to jobId.toString())
    }

    @GetMapping("/jobs/{jobId}")
    fun getJob(@PathVariable jobId: UUID): JobRecord? {
        return jobManager.findJob(jobId)
    }
}