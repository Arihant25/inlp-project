package com.example.variation3

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

data class User(val id: UUID, val email: String, val password_hash: String, val role: UserRole, val is_active: Boolean, val created_at: Instant)
data class Post(val id: UUID, val user_id: UUID, val title: String, val content: String, val status: PostStatus)

// --- Job Queue System (Actor-like with Channels) ---

// Represents commands sent to the job system
sealed class JobCommand {
    data class Dispatch(val payload: JobPayload) : JobCommand()
}

// Represents the actual work to be done
sealed class JobPayload {
    data class SendTransactionalEmail(val to: String, val subject: String) : JobPayload()
    data class ProcessImagePipeline(val postId: UUID, val imagePath: String) : JobPayload()
    object PerformNightlyCleanup : JobPayload()
}

// Represents the state of a job
enum class JobProgress { QUEUED, ACTIVE, DONE, FAILED }
data class JobState(
    val id: UUID,
    val payload: JobPayload,
    var progress: JobProgress = JobProgress.QUEUED,
    var retries: Int = 0,
    var info: String? = null
)

// --- Mock Services ---
object MockApi {
    suspend fun sendEmail(to: String, subject: String) {
        println("--> Sending email to $to")
        delay(1000)
        if (Math.random() > 0.8) throw java.io.IOException("Network Error")
        println("<-- Email sent to $to")
    }
    suspend fun processImage(path: String): String {
        println("--> Processing image $path")
        delay(3000)
        println("<-- Image processed $path")
        return "cdn.com/$path"
    }
}

// --- Actor-like Job Coordinator ---
object JobCoordinator {
    private val commandChannel = Channel<Pair<JobCommand, CompletableDeferred<UUID>>>(Channel.UNLIMITED)
    private val jobStateStore = ConcurrentHashMap<UUID, JobState>()
    private val workerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private const val MAX_ATTEMPTS = 4

    fun launch(scope: CoroutineScope) {
        // The central dispatcher actor
        scope.launch(Dispatchers.Default) {
            for ((command, deferredResult) in commandChannel) {
                when (command) {
                    is JobCommand.Dispatch -> {
                        val jobState = JobState(UUID.randomUUID(), command.payload)
                        jobStateStore[jobState.id] = jobState
                        workerScope.launch { executeJob(jobState) }
                        deferredResult.complete(jobState.id)
                    }
                }
            }
        }
        // Periodic task scheduler
        scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(2 * 60 * 60 * 1000) // Every 2 hours
                dispatch(JobPayload.PerformNightlyCleanup)
            }
        }
    }

    suspend fun dispatch(payload: JobPayload): UUID {
        val deferred = CompletableDeferred<UUID>()
        commandChannel.send(JobCommand.Dispatch(payload) to deferred)
        return deferred.await()
    }

    fun getStatus(id: UUID): JobState? = jobStateStore[id]

    private suspend fun executeJob(jobState: JobState) {
        updateState(jobState.id) { it.progress = JobProgress.ACTIVE }
        while (jobState.retries < MAX_ATTEMPTS) {
            try {
                when (val payload = jobState.payload) {
                    is JobPayload.SendTransactionalEmail -> MockApi.sendEmail(payload.to, payload.subject)
                    is JobPayload.ProcessImagePipeline -> MockApi.processImage(payload.imagePath)
                    is JobPayload.PerformNightlyCleanup -> {
                        println("--> Running nightly cleanup")
                        delay(10000)
                        println("<-- Nightly cleanup finished")
                    }
                }
                updateState(jobState.id) {
                    it.progress = JobProgress.DONE
                    it.info = "Completed successfully"
                }
                return // Success
            } catch (e: Exception) {
                jobState.retries++
                if (jobState.retries >= MAX_ATTEMPTS) {
                    updateState(jobState.id) {
                        it.progress = JobProgress.FAILED
                        it.info = "Failed after ${jobState.retries} attempts: ${e.message}"
                    }
                    return // Permanent failure
                } else {
                    val backoff = 500L * (2.0.pow(jobState.retries)).toLong()
                    updateState(jobState.id) { it.info = "Attempt ${it.retries} failed. Retrying in ${backoff}ms." }
                    delay(backoff)
                }
            }
        }
    }

    private fun updateState(id: UUID, block: (JobState) -> Unit) {
        jobStateStore[id]?.let(block)
    }
}

// --- Ktor Application ---
fun main() {
    embeddedServer(Netty, port = 8082, host = "0.0.0.0", module = Application::actorModule).start(wait = true)
}

fun Application.actorModule() {
    JobCoordinator.launch(this)

    routing {
        get("/") {
            call.respondText("Actor-like Job Server is running.")
        }

        post("/users/welcome") {
            val email = "newbie@example.org"
            val jobId = JobCoordinator.dispatch(JobPayload.SendTransactionalEmail(email, "Welcome Aboard!"))
            call.respondText("Welcome email job dispatched. ID: $jobId")
        }

        post("/posts/process") {
            val postId = UUID.randomUUID()
            val jobId = JobCoordinator.dispatch(JobPayload.ProcessImagePipeline(postId, "uploads/original.jpg"))
            call.respondText("Image processing for post $postId dispatched. ID: $jobId")
        }

        get("/jobs/{id}/status") {
            val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (id == null) {
                call.respondText("Invalid job ID.")
                return@get
            }
            val status = JobCoordinator.getStatus(id)
            if (status != null) {
                call.respond(status)
            } else {
                call.respondText("Job not found.")
            }
        }
    }
}