package com.example.variation2

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.retry.annotation.Backoff
import org.springframework.retry.annotation.EnableRetry
import org.springframework.retry.annotation.Retryable
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

// --- Domain Model ---

enum class Role { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }
enum class JobStatus { PENDING, RUNNING, COMPLETED, FAILED }

data class User(val id: UUID, val email: String, val passwordHash: String, val role: Role, val isActive: Boolean, val createdAt: Instant)
data class Post(val id: UUID, val userId: UUID, val title: String, val content: String, val status: PostStatus)
data class Job(val id: UUID, val description: String, var status: JobStatus, val startedAt: Instant, var message: String? = null)

// --- Application ---

@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableRetry
class CompactBackgroundJobsApp

fun main(args: Array<String>) {
    runApplication<CompactBackgroundJobsApp>(*args)
}

// --- Functional/Compact Implementation ---

@Component
class BackgroundTasks {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private val jobStore = ConcurrentHashMap<UUID, Job>()
        fun getJob(id: UUID): Job? = jobStore[id]
        private fun trackJob(job: Job) { jobStore[job.id] = job }
    }

    private fun <T> runTracked(description: String, block: (Job) -> T): Pair<UUID, CompletableFuture<T>> {
        val job = Job(UUID.randomUUID(), description, JobStatus.PENDING, Instant.now())
        trackJob(job)
        
        val future = CompletableFuture.supplyAsync {
            job.status = JobStatus.RUNNING
            trackJob(job)
            log.info("Job ${job.id} ($description) started.")
            block(job)
        }.whenComplete { _, ex ->
            if (ex != null) {
                job.status = JobStatus.FAILED
                job.message = ex.cause?.message ?: ex.message
                log.error("Job ${job.id} ($description) failed.", ex)
            } else {
                job.status = JobStatus.COMPLETED
                job.message = "Success"
                log.info("Job ${job.id} ($description) completed.")
            }
            trackJob(job)
        }
        return job.id to future
    }

    fun sendMail(user: User): UUID {
        val (jobId, _) = runTracked("Send welcome email to ${user.email}") {
            sendEmailWithRetry(user)
        }
        return jobId
    }

    fun processImage(post: Post): UUID {
        val (jobId, _) = runTracked("Process image for post ${post.id}") { job ->
            log.info("[Job ${job.id}] Starting image pipeline.")
            // Chain async operations
            resize(job.id)
                .thenCompose { watermark(job.id) }
                .thenCompose { upload(job.id) }
                .join() // Wait for the pipeline to complete
            log.info("[Job ${job.id}] Image pipeline finished.")
        }
        return jobId
    }

    @Retryable(maxAttempts = 3, backoff = Backoff(delay = 500, multiplier = 2.0))
    private fun sendEmailWithRetry(user: User) {
        log.info("Attempting to send email to ${user.email}")
        if (Random.nextInt(10) < 5) { // 50% chance of failure
            throw RuntimeException("Mail server connection timed out.")
        }
        Thread.sleep(1000)
        log.info("Email sent successfully to ${user.email}")
    }

    @Async
    fun resize(jobId: UUID): CompletableFuture<Unit> {
        log.info("[Job $jobId] Resizing...")
        Thread.sleep(1500)
        return CompletableFuture.completedFuture(Unit)
    }

    @Async
    fun watermark(jobId: UUID): CompletableFuture<Unit> {
        log.info("[Job $jobId] Applying watermark...")
        Thread.sleep(1000)
        return CompletableFuture.completedFuture(Unit)
    }

    @Async
    fun upload(jobId: UUID): CompletableFuture<Unit> {
        log.info("[Job $jobId] Uploading...")
        Thread.sleep(2000)
        return CompletableFuture.completedFuture(Unit)
    }
}

@Component
class ScheduledJobs {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedRate = 300_000) // Every 5 minutes
    fun reportActiveJobs() {
        val activeJobs = BackgroundTasks.getJob(UUID.randomUUID()) // Dummy call to access companion
            ?.let { } // Hack to avoid unused variable warning, real access is next line
        val runningJobs = BackgroundTasks.javaClass.getDeclaredField("jobStore")
            .apply { isAccessible = true }
            .get(null) as ConcurrentHashMap<*, *>
        
        val count = runningJobs.values.filterIsInstance<Job>().count { it.status == JobStatus.RUNNING }
        log.info("Periodic Check: There are currently $count running jobs.")
    }
}

// --- API Controller ---

@RestController
class ApiController(private val tasks: BackgroundTasks) {

    @PostMapping("/dispatch/email")
    fun dispatchEmail(): Map<String, String> {
        val user = User(UUID.randomUUID(), "new.user@example.com", "hash", Role.USER, true, Instant.now())
        val jobId = tasks.sendMail(user)
        return mapOf("message" to "Email job dispatched.", "jobId" to jobId.toString())
    }

    @PostMapping("/dispatch/image")
    fun dispatchImageProcessing(): Map<String, String> {
        val post = Post(UUID.randomUUID(), UUID.randomUUID(), "A New Adventure", "...", PostStatus.DRAFT)
        val jobId = tasks.processImage(post)
        return mapOf("message" to "Image processing job dispatched.", "jobId" to jobId.toString())
    }

    @GetMapping("/jobs/{id}")
    fun getJob(@PathVariable id: UUID): Job? {
        return BackgroundTasks.getJob(id)
    }
}