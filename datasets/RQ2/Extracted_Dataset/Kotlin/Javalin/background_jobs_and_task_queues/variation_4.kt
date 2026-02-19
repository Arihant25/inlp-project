package com.example.variation4

import io.javalin.Javalin
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.*
import kotlin.math.pow

// --- Domain Model ---
enum class UserRole { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }

data class User(val id: UUID, val email: String, val password_hash: String, val role: UserRole, val is_active: Boolean, val created_at: Timestamp)
data class Post(val id: UUID, val user_id: UUID, val title: String, val content: String, val status: PostStatus)

// --- Enterprise Job Pattern: Command & Strategy ---

// Strategy for handling retries
interface BackoffStrategy {
    fun getDelay(attempt: Int): Long
}

class ExponentialBackoffStrategy(private val initialDelay: Long = 1000L) : BackoffStrategy {
    override fun getDelay(attempt: Int): Long {
        if (attempt <= 0) return initialDelay
        return initialDelay * (2.0.pow(attempt - 1)).toLong()
    }
}

// Command pattern for jobs
abstract class JobCommand(val id: UUID = UUID.randomUUID()) {
    abstract val description: String
    open val maxRetries: Int = 3
    open val backoffStrategy: BackoffStrategy = ExponentialBackoffStrategy()
    abstract fun execute()
}

// Job Status Tracking
enum class JobExecutionStatus { PENDING, IN_PROGRESS, SUCCEEDED, FAILED_RETRY, FAILED_PERMANENTLY }
data class JobStatusInfo(val id: UUID, val description: String, var status: JobExecutionStatus, var attempt: Int = 0, var lastError: String? = null)

interface JobStatusTracker {
    fun getStatus(id: UUID): JobStatusInfo?
    fun track(command: JobCommand)
    fun update(id: UUID, status: JobExecutionStatus, attempt: Int, error: String? = null)
}

class InMemoryJobStatusTracker : JobStatusTracker {
    private val statuses = ConcurrentHashMap<UUID, JobStatusInfo>()
    override fun getStatus(id: UUID): JobStatusInfo? = statuses[id]
    override fun track(command: JobCommand) {
        statuses[command.id] = JobStatusInfo(command.id, command.description, JobExecutionStatus.PENDING)
    }
    override fun update(id: UUID, status: JobExecutionStatus, attempt: Int, error: String?) {
        statuses[id]?.let {
            it.status = status
            it.attempt = attempt
            it.lastError = error
        }
    }
}

// --- Concrete Job Commands ---
class SendWelcomeEmailCommand(private val user: User) : JobCommand() {
    override val description: String = "Send welcome email to ${user.email}"
    override fun execute() {
        println("[JOB] Executing: $description")
        // Simulate failure
        if (ThreadLocalRandom.current().nextBoolean()) {
            throw RuntimeException("SMTP server unavailable")
        }
        println("[JOB] Completed: $description")
    }
}

class ImageProcessingPipelineCommand(private val post: Post) : JobCommand() {
    override val description: String = "Image processing pipeline for post '${post.title}'"
    override val maxRetries: Int = 2 // This job is less critical
    override fun execute() {
        println("[JOB] Executing: $description")
        println("  - Step 1: Resizing image...")
        Thread.sleep(800)
        println("  - Step 2: Applying watermark...")
        Thread.sleep(800)
        println("  - Step 3: Uploading to storage...")
        Thread.sleep(400)
        println("[JOB] Completed: $description")
    }
}

class GenerateAnalyticsReportCommand : JobCommand() {
    override val description: String = "Generate daily analytics report"
    override val maxRetries: Int = 1
    override fun execute() {
        println("[JOB] Executing: $description")
        Thread.sleep(2500)
        println("[JOB] Completed: $description")
    }
}

// --- Job Queue and Execution Engine ---
class JobExecutionEngine(
    private val jobQueue: BlockingQueue<JobCommand>,
    private val statusTracker: JobStatusTracker,
    workerCount: Int
) : AutoCloseable {
    private val executorService: ExecutorService = Executors.newFixedThreadPool(workerCount)
    private val scheduledExecutor = Executors.newSingleThreadScheduledExecutor()

    fun start() {
        repeat(workerCount) {
            executorService.submit { workerLoop() }
        }
        println("Job Execution Engine started with $workerCount workers.")
    }

    private fun workerLoop() {
        while (!executorService.isShutdown) {
            try {
                val command = jobQueue.take()
                executeCommand(command, 1)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }

    private fun executeCommand(command: JobCommand, attempt: Int) {
        statusTracker.update(command.id, JobExecutionStatus.IN_PROGRESS, attempt)
        try {
            command.execute()
            statusTracker.update(command.id, JobExecutionStatus.SUCCEEDED, attempt)
        } catch (e: Exception) {
            if (attempt < command.maxRetries) {
                val delay = command.backoffStrategy.getDelay(attempt)
                statusTracker.update(command.id, JobExecutionStatus.FAILED_RETRY, attempt, e.message)
                println("[ENGINE] Job ${command.id} failed on attempt $attempt. Retrying in ${delay}ms.")
                scheduledExecutor.schedule({ executeCommand(command, attempt + 1) }, delay, TimeUnit.MILLISECONDS)
            } else {
                statusTracker.update(command.id, JobExecutionStatus.FAILED_PERMANENTLY, attempt, e.message)
                println("[ENGINE] Job ${command.id} failed permanently after $attempt attempts.")
            }
        }
    }

    override fun close() {
        executorService.shutdownNow()
        scheduledExecutor.shutdownNow()
    }
}

class JobQueueService(
    private val jobQueue: BlockingQueue<JobCommand>,
    private val statusTracker: JobStatusTracker
) {
    fun submit(command: JobCommand): UUID {
        statusTracker.track(command)
        jobQueue.put(command)
        println("[QUEUE] Submitted job ${command.id} ('${command.description}')")
        return command.id
    }
}

// --- Application Entrypoint ---
fun main() {
    // --- Composition Root ---
    val userStore = ConcurrentHashMap<UUID, User>()
    val postStore = ConcurrentHashMap<UUID, Post>()

    val jobQueue = LinkedBlockingQueue<JobCommand>()
    val statusTracker = InMemoryJobStatusTracker()
    val jobQueueService = JobQueueService(jobQueue, statusTracker)
    val executionEngine = JobExecutionEngine(jobQueue, statusTracker, 4)
    executionEngine.start()

    // Periodic Job Scheduler
    val periodicScheduler = Executors.newSingleThreadScheduledExecutor()
    periodicScheduler.scheduleAtFixedRate({
        jobQueueService.submit(GenerateAnalyticsReportCommand())
    }, 5, 30, TimeUnit.SECONDS)

    // --- API Layer ---
    Javalin.create().routes {
        post("/users") { ctx ->
            val user = User(UUID.randomUUID(), "user${userStore.size}@corp.com", "pwhash", UserRole.USER, true, Timestamp.from(Instant.now()))
            userStore[user.id] = user
            val command = SendWelcomeEmailCommand(user)
            val jobId = jobQueueService.submit(command)
            ctx.status(202).json(mapOf("status" to "User creation initiated", "jobId" to jobId))
        }
        post("/posts") { ctx ->
            val userId = userStore.keys.firstOrNull() ?: run {
                ctx.status(400).json(mapOf("error" to "Create a user first")); return@post
            }
            val post = Post(UUID.randomUUID(), userId, "Enterprise Post", "...", PostStatus.PUBLISHED)
            postStore[post.id] = post
            val command = ImageProcessingPipelineCommand(post)
            val jobId = jobQueueService.submit(command)
            ctx.status(202).json(mapOf("status" to "Post creation initiated", "jobId" to jobId))
        }
        get("/jobs/{id}") { ctx ->
            val jobId = UUID.fromString(ctx.pathParam("id"))
            statusTracker.getStatus(jobId)?.let { ctx.json(it) } ?: ctx.status(404)
        }
    }.start(7073)

    println("Server started on port 7073")
}