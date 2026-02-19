package com.example.jobs.oop

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ConcurrentHashMap
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

// --- Job System Components (OOP Style) ---

enum class JobStatus { PENDING, RUNNING, COMPLETED, FAILED, RETRYING }

data class JobTicket(
    val jobId: UUID,
    var status: JobStatus,
    var attempts: Int,
    var lastError: String? = null
)

interface Job {
    val jobId: UUID
    fun execute()
}

class JobStatusTracker {
    private val statuses = ConcurrentHashMap<UUID, JobTicket>()

    fun track(jobId: UUID) {
        statuses[jobId] = JobTicket(jobId, JobStatus.PENDING, 0)
    }

    fun updateStatus(jobId: UUID, status: JobStatus, error: String? = null) {
        statuses[jobId]?.let {
            it.status = status
            if (error != null) {
                it.lastError = error
            }
        }
    }

    fun recordAttempt(jobId: UUID): Int {
        val ticket = statuses.getOrPut(jobId) { JobTicket(jobId, JobStatus.PENDING, 0) }
        ticket.attempts++
        return ticket.attempts
    }

    fun getTicket(jobId: UUID): JobTicket? = statuses[jobId]

    fun getStaleJobs(): List<JobTicket> {
        return statuses.values.filter { it.status == JobStatus.RUNNING || it.status == JobStatus.PENDING }
    }
}

class SendEmailJob(override val jobId: UUID, private val user: User) : Job {
    override fun execute() {
        println("[${Thread.currentThread().name}] Starting SendEmailJob ${jobId} for ${user.email}")
        Thread.sleep(1000) // Simulate network latency
        if (Random.nextBoolean()) {
            throw IllegalStateException("SMTP server connection failed")
        }
        println("[${Thread.currentThread().name}] Finished SendEmailJob ${jobId} for ${user.email}")
    }
}

class ImageProcessingJob(
    override val jobId: UUID,
    private val imageId: UUID,
    private val jobQueueManager: JobQueueManager
) : Job {
    override fun execute() {
        println("[${Thread.currentThread().name}] Starting ImageProcessingJob ${jobId} (Step 1: Resize) for image ${imageId}")
        Thread.sleep(1500)
        println("[${Thread.currentThread().name}] Finished resizing. Enqueuing next step.")
        // Chain the next job in the pipeline
        jobQueueManager.submitJob(WatermarkImageJob(UUID.randomUUID(), imageId))
    }
}

class WatermarkImageJob(override val jobId: UUID, private val imageId: UUID) : Job {
    override fun execute() {
        println("[${Thread.currentThread().name}] Starting WatermarkImageJob ${jobId} (Step 2: Watermark) for image ${imageId}")
        Thread.sleep(1000)
        println("[${Thread.currentThread().name}] Finished WatermarkImageJob ${jobId}")
    }
}

class JobWorker(
    private val jobQueue: BlockingQueue<Job>,
    private val jobStatusTracker: JobStatusTracker,
    private val jobQueueManager: JobQueueManager
) : Runnable {
    override fun run() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                val job = jobQueue.take()
                jobStatusTracker.updateStatus(job.jobId, JobStatus.RUNNING)
                try {
                    job.execute()
                    jobStatusTracker.updateStatus(job.jobId, JobStatus.COMPLETED)
                } catch (e: Exception) {
                    println("[${Thread.currentThread().name}] Job ${job.jobId} failed: ${e.message}")
                    val attempts = jobStatusTracker.recordAttempt(job.jobId)
                    jobQueueManager.handleFailedJob(job, attempts, e)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                println("[${Thread.currentThread().name}] Worker interrupted.")
                break
            }
        }
    }
}

class JobQueueManager(private val workerCount: Int) {
    private val jobQueue: BlockingQueue<Job> = LinkedBlockingQueue()
    private val jobStatusTracker = JobStatusTracker()
    private val workerExecutor = Executors.newFixedThreadPool(workerCount)
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
    private val maxRetries = 5
    private val initialBackoffMillis = 1000L

    fun start() {
        repeat(workerCount) {
            workerExecutor.submit(JobWorker(jobQueue, jobStatusTracker, this))
        }
        // Schedule a periodic task
        scheduler.scheduleAtFixedRate({
            println("[Scheduler] Running periodic task: Cleaning up stale jobs...")
            val staleJobs = jobStatusTracker.getStaleJobs()
            if (staleJobs.isNotEmpty()) {
                println("[Scheduler] Found ${staleJobs.size} stale jobs.")
            } else {
                println("[Scheduler] No stale jobs found.")
            }
        }, 10, 30, TimeUnit.SECONDS)
        println("JobQueueManager started with $workerCount workers.")
    }

    fun submitJob(job: Job) {
        jobStatusTracker.track(job.jobId)
        jobQueue.put(job)
        println("Submitted job ${job.jobId} of type ${job::class.simpleName}")
    }

    fun handleFailedJob(job: Job, attempts: Int, exception: Exception) {
        if (attempts < maxRetries) {
            val delay = initialBackoffMillis * (2.0.pow(attempts - 1)).toLong()
            jobStatusTracker.updateStatus(job.jobId, JobStatus.RETRYING, exception.message)
            println("Retrying job ${job.jobId} in ${delay}ms (attempt $attempts/$maxRetries)")
            scheduler.schedule({ submitJob(job) }, delay, TimeUnit.MILLISECONDS)
        } else {
            jobStatusTracker.updateStatus(job.jobId, JobStatus.FAILED, "Exceeded max retries: ${exception.message}")
            println("Job ${job.jobId} failed permanently after $attempts attempts.")
        }
    }

    fun getJobStatus(jobId: UUID): JobTicket? = jobStatusTracker.getTicket(jobId)

    fun shutdown() {
        workerExecutor.shutdownNow()
        scheduler.shutdownNow()
        println("JobQueueManager shut down.")
    }
}

fun main() {
    println("--- Variation 1: OOP/Service-Oriented ---")
    val jobManager = JobQueueManager(4)
    jobManager.start()

    val sampleUser = User(UUID.randomUUID(), "test@example.com", "hash", UserRole.USER, true, Timestamp.from(Instant.now()))
    
    val emailJobId = UUID.randomUUID()
    jobManager.submitJob(SendEmailJob(emailJobId, sampleUser))

    val imageJobId = UUID.randomUUID()
    jobManager.submitJob(ImageProcessingJob(imageJobId, UUID.randomUUID(), jobManager))

    Thread.sleep(15000) // Let jobs run for a bit

    println("\n--- Final Job Statuses ---")
    println("Email Job ($emailJobId): ${jobManager.getJobStatus(emailJobId)}")
    println("Image Job ($imageJobId): ${jobManager.getJobStatus(imageJobId)}")
    
    jobManager.shutdown()
}