package com.example.variation3

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Configuration
import org.springframework.data.repository.Repository
import org.springframework.retry.annotation.Backoff
import org.springframework.retry.annotation.EnableRetry
import org.springframework.retry.annotation.Retryable
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

// --- package: com.example.variation3.domain.model ---

enum class Role { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }

data class User(val id: UUID, val email: String, val passwordHash: String, val role: Role, val isActive: Boolean, val createdAt: Instant)
data class Post(val id: UUID, val userId: UUID, val title: String, val content: String, var status: PostStatus) {
    fun publish(postPublisher: PostPublisher): Job.JobId {
        this.status = PostStatus.PUBLISHED
        // Publishing a post triggers background tasks like image processing
        return postPublisher.processPublishedPost(this)
    }
}

class Job private constructor(
    val id: JobId,
    val description: String,
    var status: Status,
    val createdAt: Instant,
    var log: MutableList<String>
) {
    @JvmInline
    value class JobId(val value: UUID)
    enum class Status { PENDING, RUNNING, COMPLETED, FAILED }

    companion object {
        fun create(description: String): Job {
            return Job(JobId(UUID.randomUUID()), description, Status.PENDING, Instant.now(), mutableListOf("Job created"))
        }
    }

    fun start() {
        this.status = Status.RUNNING
        this.log.add("[${Instant.now()}] Job started.")
    }

    fun complete() {
        this.status = Status.COMPLETED
        this.log.add("[${Instant.now()}] Job completed successfully.")
    }



    fun fail(reason: String) {
        this.status = Status.FAILED
        this.log.add("[${Instant.now()}] Job failed: $reason")
    }
    
    fun addLog(message: String) {
        this.log.add("[${Instant.now()}] $message")
    }
}

// --- package: com.example.variation3.domain.ports ---

interface JobRepository {
    fun findById(id: Job.JobId): Job?
    fun save(job: Job)
}

interface PostPublisher {
    fun processPublishedPost(post: Post): Job.JobId
}

interface UserNotifier {
    fun notifyUser(user: User, message: String): Job.JobId
}

// --- package: com.example.variation3.infrastructure ---

@Component
class InMemoryJobRepository : JobRepository {
    private val store = ConcurrentHashMap<Job.JobId, Job>()
    override fun findById(id: Job.JobId): Job? = store[id]
    override fun save(job: Job) { store[job.id] = job }
}

@Service
class AsyncPostPublisher(
    private val jobRepository: JobRepository
) : PostPublisher {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    override fun processPublishedPost(post: Post): Job.JobId {
        val job = Job.create("Processing for published post ${post.id}")
        jobRepository.save(job)
        
        try {
            job.start()
            jobRepository.save(job)

            job.addLog("Resizing image...")
            jobRepository.save(job)
            Thread.sleep(1500)

            job.addLog("Applying watermark...")
            jobRepository.save(job)
            Thread.sleep(1000)

            job.addLog("Uploading to CDN...")
            jobRepository.save(job)
            uploadWithRetry(job)

            job.complete()
            jobRepository.save(job)
        } catch (e: Exception) {
            log.error("Job ${job.id.value} failed", e)
            job.fail(e.message ?: "Unknown error")
            jobRepository.save(job)
        }
        return job.id
    }

    @Retryable(backoff = Backoff(delay = 1000))
    fun uploadWithRetry(job: Job) {
        if (Random.nextBoolean()) {
            job.addLog("CDN upload failed. Retrying...")
            jobRepository.save(job)
            throw RuntimeException("CDN service unavailable")
        }
        Thread.sleep(2000)
        job.addLog("CDN upload successful.")
        jobRepository.save(job)
    }
}

@Service
class AsyncUserNotifier(private val jobRepository: JobRepository) : UserNotifier {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    override fun notifyUser(user: User, message: String): Job.JobId {
        val job = Job.create("Sending notification to ${user.email}")
        jobRepository.save(job)
        job.start()
        jobRepository.save(job)
        log.info("[Job ${job.id.value}] Sending notification: '$message'")
        Thread.sleep(2000)
        job.complete()
        jobRepository.save(job)
        return job.id
    }
}

@Component
class PeriodicSystemTasks(private val jobRepository: JobRepository) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0/15 * * * ?") // Every 15 minutes
    fun auditJobStates() {
        log.info("Running periodic job audit...")
        // In a real system, this might find and restart stalled jobs.
        // For this example, we just log the creation of an audit "task".
        val job = Job.create("System job state audit")
        job.start()
        job.complete()
        jobRepository.save(job)
        log.info("Job audit task ${job.id.value} completed.")
    }
}

// --- package: com.example.variation3.application ---

@Service
class PostApplicationService(
    private val postPublisher: PostPublisher
) {
    // Mock finding a post
    private val mockPost = Post(UUID.randomUUID(), UUID.randomUUID(), "DDD Post", "Content", PostStatus.DRAFT)

    fun publishPost(postId: UUID): Job.JobId {
        // In a real app, you'd fetch the post from a repository
        return mockPost.publish(postPublisher)
    }
}

// --- package: com.example.variation3 ---

@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableRetry
class DddBackgroundJobsApp

fun main(args: Array<String>) {
    runApplication<DddBackgroundJobsApp>(*args)
}

@RestController
@RequestMapping("/posts")
class PostController(
    private val postService: PostApplicationService,
    private val jobRepository: JobRepository
) {
    @PostMapping("/{postId}/publish")
    fun publishPost(@PathVariable postId: UUID): Map<String, String> {
        val jobId = postService.publishPost(postId)
        return mapOf("message" to "Post publication process started.", "jobId" to jobId.value.toString())
    }

    @GetMapping("/jobs/{jobId}")
    fun getJob(@PathVariable jobId: UUID): Job? {
        return jobRepository.findById(Job.JobId(jobId))
    }
}