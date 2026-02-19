package com.example.variation1

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Configuration
import org.springframework.retry.annotation.EnableRetry
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.AsyncResult
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.retry.annotation.Retryable
import org.springframework.retry.annotation.Backoff
import java.sql.SQLException
import kotlin.random.Random

// --- Domain Model ---

enum class Role { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }
enum class JobStatus { PENDING, RUNNING, COMPLETED, FAILED }
enum class JobType { EMAIL_SEND, IMAGE_PROCESSING, SYSTEM_CLEANUP }

data class User(
    val id: UUID,
    val email: String,
    val passwordHash: String,
    val role: Role,
    val isActive: Boolean,
    val createdAt: Instant
)

data class Post(
    val id: UUID,
    val userId: UUID,
    val title: String,
    val content: String,
    val status: PostStatus
)

data class Job(
    val id: UUID,
    val type: JobType,
    var status: JobStatus,
    val createdAt: Instant,
    var finishedAt: Instant? = null,
    var details: String? = null
)

// --- Mock Repositories ---

@Service
class MockUserRepository {
    private val users = ConcurrentHashMap<UUID, User>()
    fun findById(id: UUID): User? = users[id]
    fun save(user: User) { users[user.id] = user }
    fun findInactiveUsers(): List<User> = users.values.filter { !it.isActive }
}

@Service
class MockPostRepository {
    private val posts = ConcurrentHashMap<UUID, Post>()
    fun findById(id: UUID): Post? = posts[id]
    fun save(post: Post) { posts[post.id] = post }
}

@Service
class MockJobRepository {
    private val jobs = ConcurrentHashMap<UUID, Job>()
    fun findById(id: UUID): Job? = jobs[id]
    fun save(job: Job): Job {
        jobs[job.id] = job
        return job
    }
}


// --- Application ---

@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableRetry
class BackgroundJobsApplication

fun main(args: Array<String>) {
    runApplication<BackgroundJobsApplication>(*args)
}

// --- Service-Oriented Implementation ---

@Service
class JobTrackingService(private val jobRepository: MockJobRepository) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun createJob(type: JobType, details: String? = null): Job {
        val newJob = Job(UUID.randomUUID(), type, JobStatus.PENDING, Instant.now(), details = details)
        logger.info("Created job ${newJob.id} of type $type")
        return jobRepository.save(newJob)
    }

    fun updateJobStatus(jobId: UUID, status: JobStatus, details: String? = null) {
        jobRepository.findById(jobId)?.let {
            it.status = status
            if (details != null) it.details = details
            if (status == JobStatus.COMPLETED || status == JobStatus.FAILED) {
                it.finishedAt = Instant.now()
            }
            jobRepository.save(it)
            logger.info("Updated job $jobId status to $status")
        }
    }
}

@Service
class EmailService {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Async
    @Retryable(
        value = [SQLException::class],
        maxAttempts = 3,
        backoff = Backoff(delay = 1000, multiplier = 2.0)
    )
    fun sendWelcomeEmailAsync(user: User, jobId: UUID) {
        logger.info("[Job $jobId] Attempting to send welcome email to ${user.email}...")
        
        // Simulate a transient failure
        if (Random.nextBoolean()) {
            logger.warn("[Job $jobId] Simulated network failure sending email.")
            throw SQLException("Failed to connect to mail server")
        }

        Thread.sleep(2000) // Simulate network latency
        logger.info("[Job $jobId] Successfully sent welcome email to ${user.email}")
    }
}

@Service
class ImageProcessingService(private val jobTrackingService: JobTrackingService) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun processPostImagePipeline(post: Post): UUID {
        val job = jobTrackingService.createJob(JobType.IMAGE_PROCESSING, "Processing image for post: ${post.id}")
        jobTrackingService.updateJobStatus(job.id, JobStatus.RUNNING)

        processImageSteps(post, job.id).handle { _, ex ->
            if (ex != null) {
                jobTrackingService.updateJobStatus(job.id, JobStatus.FAILED, ex.message)
                logger.error("[Job ${job.id}] Image processing pipeline failed", ex)
            } else {
                jobTrackingService.updateJobStatus(job.id, JobStatus.COMPLETED)
                logger.info("[Job ${job.id}] Image processing pipeline completed successfully.")
            }
        }
        return job.id
    }

    @Async
    protected fun processImageSteps(post: Post, jobId: UUID): Future<Void> {
        try {
            resizeImage(jobId).get()
            applyWatermark(jobId).get()
            uploadToCdn(jobId).get()
            return AsyncResult(null)
        } catch (e: Exception) {
            throw IllegalStateException("Pipeline failed", e)
        }
    }

    @Async
    protected fun resizeImage(jobId: UUID): Future<Unit> {
        logger.info("[Job $jobId] Step 1: Resizing image...")
        Thread.sleep(1500)
        logger.info("[Job $jobId] Step 1: Resizing complete.")
        return AsyncResult(Unit)
    }

    @Async
    protected fun applyWatermark(jobId: UUID): Future<Unit> {
        logger.info("[Job $jobId] Step 2: Applying watermark...")
        Thread.sleep(1000)
        logger.info("[Job $jobId] Step 2: Watermark applied.")
        return AsyncResult(Unit)
    }

    @Async
    @Retryable(maxAttempts = 2, backoff = Backoff(delay = 2000))
    protected fun uploadToCdn(jobId: UUID): Future<Unit> {
        logger.info("[Job $jobId] Step 3: Uploading to CDN...")
        if (Random.nextInt(10) < 4) { // 40% chance of failure
            logger.warn("[Job $jobId] Step 3: CDN upload failed, will retry...")
            throw RuntimeException("CDN unavailable")
        }
        Thread.sleep(2500)
        logger.info("[Job $jobId] Step 3: Upload to CDN complete.")
        return AsyncResult(Unit)
    }
}

@Service
class ScheduledTasksService(
    private val userRepository: MockUserRepository,
    private val jobTrackingService: JobTrackingService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0 5 * * ?") // Run every day at 5 AM
    fun cleanupInactiveUsersJob() {
        val job = jobTrackingService.createJob(JobType.SYSTEM_CLEANUP, "Daily inactive user cleanup")
        logger.info("[Job ${job.id}] Starting scheduled cleanup of inactive users.")
        jobTrackingService.updateJobStatus(job.id, JobStatus.RUNNING)
        
        try {
            val inactiveUsers = userRepository.findInactiveUsers()
            logger.info("[Job ${job.id}] Found ${inactiveUsers.size} inactive users to process.")
            Thread.sleep(5000) // Simulate processing
            jobTrackingService.updateJobStatus(job.id, JobStatus.COMPLETED, "Processed ${inactiveUsers.size} users.")
        } catch (e: Exception) {
            jobTrackingService.updateJobStatus(job.id, JobStatus.FAILED, e.message)
        }
    }
}

// --- API Controller ---

@RestController
@RequestMapping("/api")
class TaskController(
    private val emailService: EmailService,
    private val imageProcessingService: ImageProcessingService,
    private val jobTrackingService: JobTrackingService,
    private val jobRepository: MockJobRepository
) {
    @PostMapping("/users/{userId}/send-welcome")
    fun sendWelcomeEmail(@PathVariable userId: UUID): Map<String, String> {
        val user = User(userId, "test@example.com", "hash", Role.USER, true, Instant.now())
        val job = jobTrackingService.createJob(JobType.EMAIL_SEND, "Sending welcome email to ${user.email}")
        jobTrackingService.updateJobStatus(job.id, JobStatus.RUNNING)
        
        emailService.sendWelcomeEmailAsync(user, job.id)
        // Note: In a real app, you'd use CompletableFuture to track completion and update status.
        // For this example, we assume it will eventually complete or fail via retry.
        
        return mapOf("message" to "Welcome email job scheduled.", "jobId" to job.id.toString())
    }

    @PostMapping("/posts/{postId}/process-image")
    fun processImage(@PathVariable postId: UUID): Map<String, String> {
        val post = Post(postId, UUID.randomUUID(), "My Post", "Content", PostStatus.PUBLISHED)
        val jobId = imageProcessingService.processPostImagePipeline(post)
        return mapOf("message" to "Image processing pipeline started.", "jobId" to jobId.toString())
    }

    @GetMapping("/jobs/{jobId}")
    fun getJobStatus(@PathVariable jobId: UUID): Job? {
        return jobRepository.findById(jobId)
    }
}