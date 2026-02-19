package com.example.jobs.actor

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.pow
import kotlin.random.Random

// --- Domain Schema ---
enum class UserRole { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }

data class User(
    val id: UUID,
    val email: String,
    val password_hash: String,
    val role: UserRole,
    val is_active: Boolean,
    val created_at: Timestamp
)

data class Post(
    val id: UUID,
    val user_id: UUID,
    val title: String,
    val content: String,
    val status: PostStatus
)

// --- Job System Components (Actor-like Style) ---

// Messages that can be sent to actors
sealed class Message
data class ExecuteJob(val job: Job) : Message()
data class JobStatusUpdate(val jobId: UUID, val status: Status, val details: String?) : Message()
object Shutdown : Message()
object PrintStatus : Message()

// Job definitions
sealed class Job(val id: UUID)
data class EmailJob(val user: User) : Job(UUID.randomUUID())
data class ImageResizeJob(val imageId: UUID) : Job(UUID.randomUUID())
data class ImageWatermarkJob(val imageId: UUID) : Job(UUID.randomUUID())
data class CleanupJob(val cutoff: Instant) : Job(UUID.randomUUID())

// Status tracking
enum class Status { SUBMITTED, ACTIVE, COMPLETED, FAILED, RETRY }
data class JobRecord(var status: Status, var attempts: Int = 0, var details: String? = null)

// The "Status Tracker" actor
object StatusTracker {
    private val mailbox = LinkedBlockingQueue<Message>()
    private val jobRecords = ConcurrentHashMap<UUID, JobRecord>()
    private val thread = Thread(::runLoop)

    fun start() {
        thread.start()
    }

    fun send(message: Message) {
        mailbox.put(message)
    }

    private fun runLoop() {
        while (true) {
            when (val msg = mailbox.take()) {
                is JobStatusUpdate -> {
                    val record = jobRecords.getOrPut(msg.jobId) { JobRecord(Status.SUBMITTED) }
                    record.status = msg.status
                    record.details = msg.details
                    if (msg.status == Status.RETRY || msg.status == Status.ACTIVE) {
                        record.attempts++
                    }
                }
                is PrintStatus -> {
                    println("\n--- Current Job Statuses ---")
                    jobRecords.forEach { (id, record) ->
                        println("Job $id: ${record.status}, Attempts: ${record.attempts}, Details: ${record.details ?: "N/A"}")
                    }
                    println("--------------------------")
                }
                is Shutdown -> break
                else -> println("[StatusTracker] Received unknown message")
            }
        }
    }
}

// The "Worker" actor
class Worker(private val name: String, private val dispatcher: Dispatcher) : Runnable {
    private val mailbox = LinkedBlockingQueue<Message>()
    val thread = Thread(this, "worker-$name")

    fun send(message: Message) {
        mailbox.put(message)
    }

    override fun run() {
        while (true) {
            when (val msg = mailbox.take()) {
                is ExecuteJob -> processJob(msg.job)
                is Shutdown -> break
                else -> println("[$name] Received unknown message")
            }
        }
        println("[$name] Shutting down.")
    }

    private fun processJob(job: Job) {
        StatusTracker.send(JobStatusUpdate(job.id, Status.ACTIVE, "Worker $name processing"))
        try {
            when (job) {
                is EmailJob -> {
                    println("[$name] Sending email to ${job.user.email}")
                    Thread.sleep(1000)
                    if (Random.nextInt(10) < 5) throw Exception("SMTP Timeout")
                }
                is ImageResizeJob -> {
                    println("[$name] Resizing image ${job.imageId}")
                    Thread.sleep(2000)
                    // Chain next job
                    dispatcher.dispatch(ImageWatermarkJob(job.imageId))
                }
                is ImageWatermarkJob -> {
                    println("[$name] Watermarking image ${job.imageId}")
                    Thread.sleep(1500)
                }
                is CleanupJob -> {
                    println("[$name] Running cleanup for posts before ${job.cutoff}")
                    Thread.sleep(500)
                }
            }
            StatusTracker.send(JobStatusUpdate(job.id, Status.COMPLETED, "Success"))
        } catch (e: Exception) {
            dispatcher.handleFailure(job, e)
        }
    }
}

// The "Dispatcher" that routes jobs to workers
class Dispatcher(workerCount: Int) {
    private val workers = List(workerCount) { Worker("w$it", this) }
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val maxRetries = 3
    private var nextWorker = 0

    fun start() {
        StatusTracker.start()
        workers.forEach { it.thread.start() }
        scheduler.scheduleAtFixedRate({
            dispatch(CleanupJob(Instant.now()))
        }, 5, 20, TimeUnit.SECONDS)
        println("Dispatcher started with ${workers.size} workers.")
    }

    fun dispatch(job: Job) {
        StatusTracker.send(JobStatusUpdate(job.id, Status.SUBMITTED, "Queued"))
        workers[nextWorker].send(ExecuteJob(job))
        nextWorker = (nextWorker + 1) % workers.size
    }

    fun handleFailure(job: Job, e: Exception) {
        val attempts = StatusTracker.jobRecords[job.id]?.attempts ?: 1
        if (attempts < maxRetries) {
            val delay = 1000L * (2.0.pow(attempts)).toLong()
            StatusTracker.send(JobStatusUpdate(job.id, Status.RETRY, "Failed: ${e.message}. Retrying in ${delay}ms."))
            scheduler.schedule({ dispatch(job) }, delay, TimeUnit.MILLISECONDS)
        } else {
            StatusTracker.send(JobStatusUpdate(job.id, Status.FAILED, "Exceeded max retries. Final error: ${e.message}"))
        }
    }

    fun shutdown() {
        workers.forEach { it.send(Shutdown) }
        scheduler.shutdownNow()
        StatusTracker.send(Shutdown)
        println("Dispatcher shutting down.")
    }
}

fun main() {
    println("--- Variation 3: Actor-like Model ---")
    val dispatcher = Dispatcher(3)
    dispatcher.start()

    val user1 = User(UUID.randomUUID(), "user1@example.com", "hash", UserRole.USER, true, Timestamp.from(Instant.now()))
    val user2 = User(UUID.randomUUID(), "user2@example.com", "hash", UserRole.USER, true, Timestamp.from(Instant.now()))

    dispatcher.dispatch(EmailJob(user1))
    dispatcher.dispatch(ImageResizeJob(UUID.randomUUID()))
    dispatcher.dispatch(EmailJob(user2))

    Thread.sleep(10000)
    StatusTracker.send(PrintStatus)
    Thread.sleep(10000)

    dispatcher.shutdown()
}