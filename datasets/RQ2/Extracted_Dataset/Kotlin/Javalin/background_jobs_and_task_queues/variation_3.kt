package com.example.variation3

import io.javalin.Javalin
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

// --- Domain Model & Type Aliases ---
typealias JobId = UUID
typealias UserId = UUID
typealias PostId = UUID

enum class UserRole { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }

data class User(val id: UserId, val email: String, val password_hash: String, val role: UserRole, val is_active: Boolean, val created_at: Timestamp)
data class Post(val id: PostId, val user_id: UserId, val title: String, val content: String, val status: PostStatus)

// --- Coroutine-centric Job System ---
sealed interface Job {
    val id: JobId
    data class SendWelcomeEmail(override val id: JobId, val email: String) : Job
    data class ProcessPostImage(override val id: JobId, val postId: PostId) : Job
    data class GenerateReport(override val id: JobId) : Job
}

enum class JobStatusValue { QUEUED, PROCESSING, DONE, FAILED }
data class JobStatus(val id: JobId, val status: JobStatusValue, val info: String, val attempt: Int = 1)

// --- Utility for Retries ---
suspend fun <T> withRetry(
    times: Int = 3,
    initialDelay: Long = 1000,
    maxDelay: Long = 10000,
    factor: Double = 2.0,
    block: suspend (attempt: Int) -> T
): T {
    var currentDelay = initialDelay
    repeat(times - 1) { attempt ->
        try {
            return block(attempt + 1)
        } catch (e: Exception) {
            // Log exception
        }
        delay(currentDelay)
        currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelay)
    }
    return block(times) // Last attempt, exceptions will propagate
}

// --- Job Manager using Flows ---
object JobManager {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val jobChannel = MutableSharedFlow<Job>(extraBufferCapacity = 100)
    private val _statusFlow = MutableStateFlow<Map<JobId, JobStatus>>(emptyMap())
    val statusFlow: StateFlow<Map<JobId, JobStatus>> = _statusFlow.asStateFlow()

    init {
        println("JobManager initialized.")
        startWorkers()
        startPeriodicScheduler()
    }

    fun submit(job: Job) {
        scope.launch {
            updateStatus(JobStatus(job.id, JobStatusValue.QUEUED, "Job submitted to queue"))
            jobChannel.emit(job)
        }
    }

    private fun startWorkers(concurrency: Int = 4) {
        repeat(concurrency) { workerId ->
            scope.launch {
                println("Worker #$workerId started.")
                jobChannel.collect { job ->
                    process(job)
                }
            }
        }
    }

    private fun startPeriodicScheduler() {
        scope.launch {
            tickerFlow(30.seconds).collect {
                println("Scheduler: Submitting report generation job.")
                submit(Job.GenerateReport(JobId.randomUUID()))
            }
        }
    }

    private suspend fun process(job: Job) {
        try {
            withRetry { attempt ->
                updateStatus(JobStatus(job.id, JobStatusValue.PROCESSING, "Attempt $attempt", attempt))
                when (job) {
                    is Job.SendWelcomeEmail -> {
                        println("Processing email for ${job.email}")
                        delay(500) // Simulate I/O
                        if (Math.random() > 0.5) error("Failed to connect to mail server")
                        println("Email sent to ${job.email}")
                    }
                    is Job.ProcessPostImage -> {
                        println("Processing image for post ${job.postId}")
                        delay(1500) // Simulate heavy work
                        println("Image processed for post ${job.postId}")
                    }
                    is Job.GenerateReport -> {
                        println("Generating system report...")
                        delay(2000)
                        println("Report generated.")
                    }
                }
            }
            updateStatus(JobStatus(job.id, JobStatusValue.DONE, "Successfully completed"))
        } catch (e: Exception) {
            updateStatus(JobStatus(job.id, JobStatusValue.FAILED, "Failed: ${e.message}"))
        }
    }

    private fun updateStatus(status: JobStatus) {
        _statusFlow.update { it + (status.id to status) }
    }

    private fun tickerFlow(period: kotlin.time.Duration, initialDelay: kotlin.time.Duration = kotlin.time.Duration.ZERO) = flow {
        delay(initialDelay)
        while (currentCoroutineContext().isActive) {
            emit(Unit)
            delay(period)
        }
    }
}

// --- Main Application ---
fun main() {
    // In-memory stores
    val users = ConcurrentHashMap<UserId, User>()
    val posts = ConcurrentHashMap<PostId, Post>()

    // Initialize the Job Manager (starts workers and schedulers)
    JobManager

    val app = Javalin.create().apply {
        post("/users") { ctx ->
            val email = "user${users.size + 1}@example.com"
            val user = User(UserId.randomUUID(), email, "hash", UserRole.USER, true, Timestamp.from(Instant.now()))
            users[user.id] = user
            val job = Job.SendWelcomeEmail(JobId.randomUUID(), user.email)
            JobManager.submit(job)
            ctx.status(202).json(mapOf("user_id" to user.id, "job_id" to job.id))
        }

        post("/posts") { ctx ->
            val userId = users.keys.firstOrNull()
            if (userId == null) {
                ctx.status(400).json(mapOf("error" to "No users exist to create a post."))
                return@post
            }
            val post = Post(PostId.randomUUID(), userId, "Coroutine-based Post", "Content...", PostStatus.DRAFT)
            posts[post.id] = post
            val job = Job.ProcessPostImage(JobId.randomUUID(), post.id)
            JobManager.submit(job)
            ctx.status(202).json(mapOf("post_id" to post.id, "job_id" to job.id))
        }

        get("/jobs/{id}") { ctx ->
            val jobId = JobId.fromString(ctx.pathParam("id"))
            val status = JobManager.statusFlow.value[jobId]
            if (status != null) {
                ctx.json(status)
            } else {
                ctx.status(404).json(mapOf("error" to "Job not found"))
            }
        }
    }.start(7072)

    println("Server started on port 7072")
}