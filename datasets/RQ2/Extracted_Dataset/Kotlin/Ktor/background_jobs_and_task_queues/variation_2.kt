package com.example.variation2

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

// --- Domain Schema ---
enum class UserRole { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }

data class User(val id: UUID, val email: String, val password_hash: String, val role: UserRole, val is_active: Boolean, val created_at: Instant)
data class Post(val id: UUID, val user_id: UUID, val title: String, val content: String, val status: PostStatus)

// --- Job Queue System (OOP / Ktor Plugin Approach) ---
enum class JobState { PENDING, IN_PROGRESS, SUCCEEDED, FAILED }

data class JobRecord(
    val jobId: UUID = UUID.randomUUID(),
    val taskPayload: Task,
    var state: JobState = JobState.PENDING,
    var attemptCount: Int = 0,
    var failureReason: String? = null
)

sealed interface Task {
    data class EmailUserOnSignup(val userEmail: String, val userName: String) : Task
    data class GeneratePostThumbnail(val postId: UUID, val sourceImage: String) : Task
    object PruneInactiveUsers : Task
}

// --- Mock Services ---
object MockDataStore {
    val users = ConcurrentHashMap<String, User>()
    fun findUserByEmail(email: String): User? = users[email]
}

object MockExternalServices {
    suspend fun dispatchEmail(to: String, subject: String) {
        println("EMAIL_DISPATCHER: Sending '$subject' to $to")
        delay(1200)
        if (Math.random() < 0.2) throw IllegalStateException("SMTP server connection timed out")
        println("EMAIL_DISPATCHER: Email to $to sent.")
    }

    suspend fun processImagePipeline(source: String): String {
        println("IMAGE_PIPELINE: Starting for $source")
        delay(2500)
        println("IMAGE_PIPELINE: Finished for $source")
        return "processed_$source"
    }
}

class BackgroundJobManager(private val configuration: Configuration) {
    private val taskChannel = Channel<JobRecord>(Channel.UNLIMITED)
    private val jobRegistry = ConcurrentHashMap<UUID, JobRecord>()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    class Configuration {
        var workerPoolSize: Int = 4
        var maxRetries: Int = 3
        var periodicTaskIntervalMs: Long = 10 * 60 * 1000 // 10 minutes
    }

    fun start() {
        repeat(configuration.workerPoolSize) { workerId ->
            coroutineScope.launch { workerLoop(workerId) }
        }
        coroutineScope.launch { schedulePeriodicTasks() }
        println("BackgroundJobManager started with ${configuration.workerPoolSize} workers.")
    }

    fun stop() {
        coroutineScope.cancel()
        println("BackgroundJobManager stopped.")
    }

    suspend fun enqueueTask(task: Task): UUID {
        val job = JobRecord(taskPayload = task)
        jobRegistry[job.jobId] = job
        taskChannel.send(job)
        return job.jobId
    }

    fun getJobDetails(jobId: UUID): JobRecord? = jobRegistry[jobId]

    private suspend fun workerLoop(workerId: Int) {
        println("Worker #$workerId started.")
        for (job in taskChannel) {
            jobRegistry[job.jobId]?.state = JobState.IN_PROGRESS
            try {
                println("Worker #$workerId processing job ${job.jobId}...")
                executeTaskWithRetry(job)
                jobRegistry[job.jobId]?.state = JobState.SUCCEEDED
                println("Worker #$workerId finished job ${job.jobId} successfully.")
            } catch (e: Exception) {
                jobRegistry[job.jobId]?.apply {
                    state = JobState.FAILED
                    failureReason = "Permanent failure: ${e.message}"
                }
                println("Worker #$workerId failed job ${job.jobId} permanently.")
            }
        }
    }

    private suspend fun executeTaskWithRetry(job: JobRecord) {
        while (job.attemptCount <= configuration.maxRetries) {
            try {
                job.attemptCount++
                jobRegistry[job.jobId]?.attemptCount = job.attemptCount
                runTask(job.taskPayload)
                return // Success
            } catch (e: Exception) {
                if (job.attemptCount > configuration.maxRetries) {
                    throw e // Re-throw to mark as permanent failure
                }
                val backoff = 1000L * (2.0.pow(job.attemptCount - 1)).toLong()
                println("Job ${job.jobId} failed on attempt ${job.attemptCount}. Retrying in ${backoff}ms.")
                delay(backoff)
            }
        }
    }

    private suspend fun runTask(task: Task) {
        when (task) {
            is Task.EmailUserOnSignup -> MockExternalServices.dispatchEmail(task.userEmail, "Welcome, ${task.userName}!")
            is Task.GeneratePostThumbnail -> MockExternalServices.processImagePipeline(task.sourceImage)
            is Task.PruneInactiveUsers -> {
                println("PERIODIC_TASK: Pruning inactive users...")
                delay(5000)
                println("PERIODIC_TASK: Pruning complete.")
            }
        }
    }

    private suspend fun schedulePeriodicTasks() {
        while (coroutineScope.isActive) {
            delay(configuration.periodicTaskIntervalMs)
            enqueueTask(Task.PruneInactiveUsers)
        }
    }
}

val BackgroundJobPlugin = createApplicationPlugin(
    name = "BackgroundJobPlugin",
    createConfiguration = { BackgroundJobManager.Configuration() }
) {
    val jobManager = BackgroundJobManager(pluginConfig)
    application.environment.monitor.subscribe(ApplicationStarted) {
        jobManager.start()
    }
    application.environment.monitor.subscribe(ApplicationStopping) {
        jobManager.stop()
    }
    application.attributes.put(AttributeKey("JobManager"), jobManager)
}

fun Application.getJobManager(): BackgroundJobManager = attributes[AttributeKey("JobManager")]

// --- Ktor Application ---
fun main() {
    embeddedServer(Netty, port = 8081, host = "0.0.0.0", module = Application::mainModule).start(wait = true)
}

fun Application.mainModule() {
    install(BackgroundJobPlugin) {
        workerPoolSize = 2
        maxRetries = 4
    }

    routing {
        get("/") {
            call.respondText("OOP/Plugin-based Job Server is running.")
        }

        post("/register") {
            val email = "test.user.${UUID.randomUUID().toString().take(4)}@example.com"
            val jobId = getJobManager().enqueueTask(Task.EmailUserOnSignup(email, "New User"))
            call.respondText("Registration successful. Email job queued with ID: $jobId")
        }

        post("/media/upload") {
            val jobId = getJobManager().enqueueTask(Task.GeneratePostThumbnail(UUID.randomUUID(), "image_path.png"))
            call.respondText("Image upload received. Thumbnail generation job queued with ID: $jobId")
        }

        get("/jobs/status/{id}") {
            val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (id == null) {
                call.respondText("Invalid or missing job ID.")
                return@get
            }
            val jobDetails = getJobManager().getJobDetails(id)
            if (jobDetails != null) {
                call.respond(jobDetails)
            } else {
                call.respondText("Job with ID $id not found.")
            }
        }
    }
}