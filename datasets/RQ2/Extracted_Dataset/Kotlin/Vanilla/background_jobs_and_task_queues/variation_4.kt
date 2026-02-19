package com.example.jobs.command

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
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

// --- Job System Components (Command Pattern) ---

// Shared context for commands
class JobContext(val commandInvoker: CommandInvoker)

// The Command interface
interface Command {
    fun execute()
}

// Concrete Commands
class SendWelcomeEmailCommand(private val user: User) : Command {
    override fun execute() {
        println("EXECUTE SendWelcomeEmailCommand for ${user.email}")
        Thread.sleep(900)
        if (Random.nextDouble() < 0.3) {
            throw RuntimeException("Mail server is busy")
        }
        println("SUCCESS SendWelcomeEmailCommand for ${user.email}")
    }
}

class ImageProcessingPipelineCommand(private val imageId: UUID, private val context: JobContext) : Command {
    override fun execute() {
        println("EXECUTE ImageProcessingPipelineCommand (Resize) for $imageId")
        Thread.sleep(1200)
        println("SUCCESS ImageProcessingPipelineCommand (Resize) for $imageId")
        // Chain the next command
        val nextCommand = WatermarkImageCommand(imageId)
        context.commandInvoker.submit(nextCommand)
    }
}

class WatermarkImageCommand(private val imageId: UUID) : Command {
    override fun execute() {
        println("EXECUTE WatermarkImageCommand for $imageId")
        Thread.sleep(800)
        println("SUCCESS WatermarkImageCommand for $imageId")
    }
}

class CleanupInactiveUsersCommand(private val mockDb: Map<UUID, User>) : Command {
    override fun execute() {
        println("EXECUTE CleanupInactiveUsersCommand")
        val inactiveCount = mockDb.values.count { !it.is_active }
        println("PERIODIC TASK: Found $inactiveCount inactive users to clean up.")
        Thread.sleep(500)
    }
}

// Job wrapper for metadata
enum class JobState { QUEUED, IN_FLIGHT, COMPLETED, FAILED_RETRY, FAILED_PERMANENT }
data class Job(
    val id: UUID = UUID.randomUUID(),
    val command: Command,
    var state: JobState = JobState.QUEUED,
    var attempt: Int = 0,
    var lastError: String? = null
)

// The Invoker
class CommandInvoker(numWorkers: Int) {
    private val jobQueue: BlockingQueue<Job> = LinkedBlockingQueue()
    private val jobStatusMap = ConcurrentHashMap<UUID, Job>()
    private val workerPool: ExecutorService = Executors.newFixedThreadPool(numWorkers)
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val maxAttempts = 5
    private val baseRetryDelayMs = 500L

    fun start() {
        repeat(numWorkers) {
            workerPool.submit { workerLoop() }
        }
        println("CommandInvoker started with $numWorkers workers.")
    }

    fun submit(command: Command): UUID {
        val job = Job(command = command)
        jobStatusMap[job.id] = job
        jobQueue.put(job)
        println("Submitted command ${command::class.simpleName} with Job ID ${job.id}")
        return job.id
    }

    private fun workerLoop() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                val job = jobQueue.take()
                job.state = JobState.IN_FLIGHT
                job.attempt++
                try {
                    job.command.execute()
                    job.state = JobState.COMPLETED
                } catch (e: Exception) {
                    handleFailure(job, e)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun handleFailure(job: Job, e: Exception) {
        println("Job ${job.id} failed on attempt ${job.attempt}: ${e.message}")
        if (job.attempt < maxAttempts) {
            job.state = JobState.FAILED_RETRY
            job.lastError = e.message
            val delay = baseRetryDelayMs * (2.0.pow(job.attempt - 1)).toLong()
            scheduler.schedule({ jobQueue.put(job) }, delay, TimeUnit.MILLISECONDS)
        } else {
            job.state = JobState.FAILED_PERMANENT
            job.lastError = "Max retries exceeded. Last error: ${e.message}"
            println("Job ${job.id} has failed permanently.")
        }
    }

    fun getJobStatus(id: UUID): Job? = jobStatusMap[id]

    fun schedulePeriodicCommand(command: Command, initialDelay: Long, period: Long, unit: TimeUnit) {
        scheduler.scheduleAtFixedRate({
            try {
                command.execute()
            } catch (e: Exception) {
                println("Periodic command ${command::class.simpleName} failed: ${e.message}")
            }
        }, initialDelay, period, unit)
    }

    fun shutdown() {
        workerPool.shutdownNow()
        scheduler.shutdownNow()
        println("CommandInvoker shut down.")
    }
}

fun main() {
    println("--- Variation 4: Command Pattern ---")
    val invoker = CommandInvoker(4)
    val context = JobContext(invoker)
    invoker.start()

    // Mock data for periodic task
    val mockUserDb = mapOf(
        UUID.randomUUID() to User(UUID.randomUUID(), "a@a.com", "h", UserRole.USER, true, Timestamp.from(Instant.now())),
        UUID.randomUUID() to User(UUID.randomUUID(), "b@b.com", "h", UserRole.USER, false, Timestamp.from(Instant.now()))
    )

    // Schedule a periodic command
    invoker.schedulePeriodicCommand(CleanupInactiveUsersCommand(mockUserDb), 5, 10, TimeUnit.SECONDS)

    // Submit some commands
    val user = User(UUID.randomUUID(), "new.user@example.com", "hash", UserRole.USER, true, Timestamp.from(Instant.now()))
    val emailJobId = invoker.submit(SendWelcomeEmailCommand(user))
    val imageJobId = invoker.submit(ImageProcessingPipelineCommand(UUID.randomUUID(), context))

    Thread.sleep(15000)

    println("\n--- Final Job Statuses ---")
    invoker.getJobStatus(emailJobId)?.let {
        println("Email Job ($emailJobId): State=${it.state}, Attempts=${it.attempt}, Error='${it.lastError}'")
    }
    invoker.getJobStatus(imageJobId)?.let {
        println("Image Job ($imageJobId): State=${it.state}, Attempts=${it.attempt}, Error='${it.lastError}'")
    }

    invoker.shutdown()
}