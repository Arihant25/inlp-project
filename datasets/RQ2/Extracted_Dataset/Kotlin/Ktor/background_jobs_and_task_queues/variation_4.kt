package com.example.variation4

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

// --- Job Queue System (Minimalist / DSL-style) ---

typealias TaskExecutor = suspend () -> Unit

data class JobHandle(
    val id: UUID,
    val name: String,
    var status: String = "QUEUED",
    var attempt: Int = 0,
    var error: String? = null
)

class JobContext(val handle: JobHandle) {
    suspend fun log(message: String) {
        println("[Job ${handle.id} | ${handle.name}] $message")
        delay(10) // Simulate logging I/O
    }
}

// --- Mock Services ---
object ApiClient {
    suspend fun notifyUser(email: String) {
        println("Notifying $email...")
        delay(900)
        if (email.startsWith("fail")) throw RuntimeException("Notification service unavailable")
        println("Notification for $email sent.")
    }
    suspend fun transcodeVideo(source: String): String {
        println("Transcoding $source...")
        delay(4000)
        println("Transcoding finished.")
        return "transcoded_$source"
    }
}

// --- DSL Implementation ---
object TaskQueue {
    private val queue = Channel<Pair<JobHandle, TaskExecutor>>(Channel.UNLIMITED)
    private val registry = ConcurrentHashMap<UUID, JobHandle>()
    private const val MAX_RETRIES = 3

    fun init(scope: CoroutineScope, workers: Int = 3) {
        repeat(workers) {
            scope.launch(Dispatchers.IO) { worker() }
        }
        scope.launch(Dispatchers.IO) {
            while(isActive) {
                delay(6 * 60 * 60 * 1000) // Every 6 hours
                dispatch("DB Maintenance") {
                    log("Running database maintenance task.")
                    delay(15000)
                    log("Maintenance complete.")
                }
            }
        }
    }

    suspend fun dispatch(name: String, task: suspend JobContext.() -> Unit): JobHandle {
        val handle = JobHandle(UUID.randomUUID(), name)
        registry[handle.id] = handle
        val executor: TaskExecutor = { task(JobContext(handle)) }
        queue.send(handle to executor)
        return handle
    }

    fun find(id: UUID): JobHandle? = registry[id]

    private suspend fun worker() {
        for ((handle, task) in queue) {
            registry[handle.id]?.status = "RUNNING"
            try {
                retry(handle) { task() }
                registry[handle.id]?.status = "COMPLETED"
            } catch (e: Exception) {
                registry[handle.id]?.apply {
                    status = "FAILED"
                    error = "Max retries exceeded: ${e.message}"
                }
            }
        }
    }

    private suspend fun retry(handle: JobHandle, block: suspend () -> Unit) {
        while (true) {
            try {
                handle.attempt++
                registry[handle.id]?.attempt = handle.attempt
                block()
                return // Success
            } catch (e: Exception) {
                if (handle.attempt >= MAX_RETRIES) throw e
                val backoff = 500L * (2.0.pow(handle.attempt)).toLong()
                val errorMsg = "Attempt ${handle.attempt} failed: ${e.message}. Retrying in ${backoff}ms."
                println("[Job ${handle.id} | ${handle.name}] $errorMsg")
                registry[handle.id]?.error = errorMsg
                delay(backoff)
            }
        }
    }
}

// --- Ktor Application ---
fun main() {
    embeddedServer(Netty, port = 8083, host = "0.0.0.0") {
        TaskQueue.init(this, workers = 2)

        routing {
            get("/") {
                call.respondText("DSL-style Job Server is running.")
            }

            post("/users") {
                val user = User(UUID.randomUUID(), "user@example.com", "hash", UserRole.USER, true, Instant.now())
                val handle = TaskQueue.dispatch("Send Welcome Email") {
                    log("Preparing to send welcome email to ${user.email}")
                    ApiClient.notifyUser(user.email)
                    log("Email task finished.")
                }
                call.respondText("User created. Job queued: ${handle.id}")
            }

            post("/posts") {
                val post = Post(UUID.randomUUID(), UUID.randomUUID(), "Video Post", "...", PostStatus.PUBLISHED)
                val handle = TaskQueue.dispatch("Process Post Video") {
                    log("Starting video processing for post ${post.id}")
                    val result = ApiClient.transcodeVideo("video.mp4")
                    log("Video processing complete. Result: $result")
                }
                call.respondText("Post created. Video processing job queued: ${handle.id}")
            }

            get("/jobs/{id}") {
                val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                if (id == null) {
                    call.respondText("Invalid job ID.")
                    return@get
                }
                TaskQueue.find(id)?.let { call.respond(it) } ?: call.respondText("Job not found.")
            }
        }
    }.start(wait = true)
}