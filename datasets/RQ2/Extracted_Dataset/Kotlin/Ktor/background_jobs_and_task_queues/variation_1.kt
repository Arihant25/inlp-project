package com.example.variation1

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

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

// --- Job Queue System ---
enum class JobStatus { PENDING, RUNNING, COMPLETED, FAILED }

data class Job(
    val id: UUID = UUID.randomUUID(),
    val task: Task,
    var status: JobStatus = JobStatus.PENDING,
    var attempts: Int = 0,
    var lastError: String? = null
)

sealed class Task {
    data class SendWelcomeEmail(val userId: UUID) : Task()
    data class ProcessPostImage(val postId: UUID, val imageUrl: String) : Task()
    object CleanupOldDrafts : Task()
}

// --- Mock Services & Data ---
object MockUserService {
    private val users = ConcurrentHashMap<UUID, User>()
    fun findById(id: UUID): User? = users[id]
    fun save(user: User) { users[id] = user }
}

object MockEmailService {
    suspend fun sendEmail(email: String, subject: String, body: String) {
        println("Sending email to $email: Subject: $subject")
        delay(1000) // Simulate network latency
        if (email.contains("fail")) throw RuntimeException("Simulated email provider failure")
        println("Email sent successfully to $email")
    }
}

object MockImageProcessor {
    suspend fun resize(url: String): String {
        println("Resizing image: $url")
        delay(1500)
        return "resized_$url"
    }
    suspend fun watermark(url: String): String {
        println("Watermarking image: $url")
        delay(1000)
        return "watermarked_$url"
    }
    suspend fun upload(url: String): String {
        println("Uploading image: $url")
        delay(2000)
        return "cdn.example.com/$url"
    }
}

// --- Job Management (Service-Oriented Approach) ---
object JobService {
    private val jobQueue = Channel<Job>(Channel.UNLIMITED)
    private val jobStore = ConcurrentHashMap<UUID, Job>()
    private const val MAX_RETRIES = 5

    fun startWorkers(scope: CoroutineScope, workerCount: Int = 4) {
        repeat(workerCount) {
            scope.launch(Dispatchers.IO) {
                for (job in jobQueue) {
                    processJob(job)
                }
            }
        }
    }

    fun startPeriodicTasks(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(3_600_000) // 1 hour
                val cleanupJob = Job(task = Task.CleanupOldDrafts)
                submitJob(cleanupJob)
            }
        }
    }

    suspend fun submitJob(job: Job): UUID {
        jobStore[job.id] = job
        jobQueue.send(job)
        return job.id
    }

    fun getJobStatus(id: UUID): Job? = jobStore[id]

    private suspend fun processJob(job: Job) {
        jobStore[job.id]?.status = JobStatus.RUNNING
        try {
            executeTask(job.task)
            jobStore[job.id]?.status = JobStatus.COMPLETED
            println("Job ${job.id} completed successfully.")
        } catch (e: Exception) {
            handleFailure(job, e)
        }
    }

    private suspend fun handleFailure(job: Job, e: Exception) {
        val currentJobState = jobStore[job.id] ?: return
        currentJobState.attempts++
        currentJobState.lastError = e.message

        if (currentJobState.attempts < MAX_RETRIES) {
            val delayMillis = 1000 * (2.0.pow(currentJobState.attempts)).toLong()
            println("Job ${job.id} failed. Retrying in ${delayMillis}ms... (Attempt ${currentJobState.attempts})")
            delay(delayMillis)
            jobQueue.send(currentJobState) // Re-queue for retry
        } else {
            currentJobState.status = JobStatus.FAILED
            println("Job ${job.id} failed permanently after $MAX_RETRIES attempts.")
        }
    }

    private suspend fun executeTask(task: Task) {
        when (task) {
            is Task.SendWelcomeEmail -> {
                val user = MockUserService.findById(task.userId) ?: throw Exception("User not found: ${task.userId}")
                MockEmailService.sendEmail(user.email, "Welcome!", "Thanks for joining our platform.")
            }
            is Task.ProcessPostImage -> {
                val resized = MockImageProcessor.resize(task.imageUrl)
                val watermarked = MockImageProcessor.watermark(resized)
                MockImageProcessor.upload(watermarked)
            }
            is Task.CleanupOldDrafts -> {
                println("Executing periodic job: Cleaning up old drafts...")
                delay(5000) // Simulate DB query
                println("Cleanup complete.")
            }
        }
    }
}

// --- Ktor Application ---
fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        configureApplication()
    }.start(wait = true)
}

fun Application.configureApplication() {
    // Start background workers and periodic tasks
    JobService.startWorkers(this, 4)
    JobService.startPeriodicTasks(this)

    routing {
        get("/") {
            call.respondText("Background Job Server is running.")
        }

        post("/users") {
            val newUser = User(UUID.randomUUID(), "new.user@example.com", "hash", UserRole.USER, true, Instant.now())
            MockUserService.save(newUser)
            val job = Job(task = Task.SendWelcomeEmail(newUser.id))
            val jobId = JobService.submitJob(job)
            call.respondText("User created. Welcome email job scheduled: $jobId")
        }

        post("/posts") {
            val newPost = Post(UUID.randomUUID(), UUID.randomUUID(), "My New Post", "Content here", PostStatus.PUBLISHED)
            val job = Job(task = Task.ProcessPostImage(newPost.id, "source_image.jpg"))
            val jobId = JobService.submitJob(job)
            call.respondText("Post created. Image processing job scheduled: $jobId")
        }

        get("/jobs/{id}") {
            val id = call.parameters["id"]?.let { UUID.fromString(it) }
            if (id == null) {
                call.respondText("Invalid job ID format.")
                return@get
            }
            val job = JobService.getJobStatus(id)
            if (job != null) {
                call.respond(job)
            } else {
                call.respondText("Job not found.")
            }
        }
    }
}