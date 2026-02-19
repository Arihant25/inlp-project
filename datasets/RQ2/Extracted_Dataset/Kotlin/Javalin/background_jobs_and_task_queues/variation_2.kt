package com.example.variation2

import io.javalin.Javalin
import io.javalin.apibuilder.ApiBuilder.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

// --- Domain Model ---
enum class UserRole { ADMIN, USER }
enum class PostStatus { DRAFT, PUBLISHED }

data class User(
    val id: UUID, val email: String, val password_hash: String,
    val role: UserRole, val is_active: Boolean, val created_at: Timestamp
)

data class Post(
    val id: UUID, val user_id: UUID, val title: String,
    val content: String, val status: PostStatus
)

// --- Job System Interfaces & Data Classes ---
enum class JobState { PENDING, RUNNING, COMPLETED, FAILED }

data class Job(
    val id: UUID,
    val description: String,
    var state: JobState = JobState.PENDING,
    var attempt: Int = 0,
    var lastError: String? = null,
    val task: suspend () -> Unit
)

interface JobRepository {
    fun findById(id: UUID): Job?
    fun save(job: Job)
}

interface JobQueue {
    suspend fun enqueue(job: Job)
    suspend fun dequeue(): Job
}

// --- Service Layer ---
interface EmailService {
    suspend fun sendWelcomeEmail(email: String)
}

interface ImageProcessingService {
    suspend fun processPostImage(postId: UUID)
}

// --- Infrastructure & Implementation ---
class InMemoryJobRepository : JobRepository {
    private val store = ConcurrentHashMap<UUID, Job>()
    override fun findById(id: UUID): Job? = store[id]
    override fun save(job: Job) {
        store[id] = job
    }
}

class CoroutineChannelJobQueue : JobQueue {
    private val channel = Channel<Job>(Channel.UNLIMITED)
    override suspend fun enqueue(job: Job) = channel.send(job)
    override suspend fun dequeue(): Job = channel.receive()
}

class MockEmailServiceImpl : EmailService {
    override suspend fun sendWelcomeEmail(email: String) {
        println("EMAIL_SVC: Sending welcome email to $email")
        delay(500) // Simulate network latency
        if (Math.random() > 0.6) throw IllegalStateException("Email provider API is down")
        println("EMAIL_SVC: Successfully sent email to $email")
    }
}

class MockImageProcessingServiceImpl : ImageProcessingService {
    override suspend fun processPostImage(postId: UUID) {
        println("IMAGE_SVC: Resizing image for post $postId")
        delay(1000)
        println("IMAGE_SVC: Applying watermark for post $postId")
        delay(1000)
        println("IMAGE_SVC: Uploading to CDN for post $postId")
        delay(500)
        println("IMAGE_SVC: Image pipeline complete for post $postId")
    }
}

// --- Job Worker & Scheduler ---
class JobWorker(
    private val jobQueue: JobQueue,
    private val jobRepository: JobRepository,
    private val scope: CoroutineScope
) {
    fun start() {
        scope.launch {
            println("WORKER: Job worker started.")
            for (job in (jobQueue as CoroutineChannelJobQueue).channel) { // Simplified dequeue loop
                launch { executeWithRetry(job) }
            }
        }
    }

    private suspend fun executeWithRetry(job: Job, maxRetries: Int = 3) {
        job.state = JobState.RUNNING
        job.attempt++
        jobRepository.save(job)
        println("WORKER: Executing job ${job.id} ('${job.description}'), attempt ${job.attempt}")

        try {
            job.task()
            job.state = JobState.COMPLETED
            jobRepository.save(job)
            println("WORKER: Job ${job.id} completed.")
        } catch (e: Exception) {
            job.lastError = e.message
            if (job.attempt < maxRetries) {
                val backoff = 1000L * (2.0.pow(job.attempt - 1)).toLong()
                println("WORKER: Job ${job.id} failed. Retrying in ${backoff}ms.")
                job.state = JobState.PENDING
                jobRepository.save(job)
                delay(backoff)
                jobQueue.enqueue(job)
            } else {
                job.state = JobState.FAILED
                jobRepository.save(job)
                println("WORKER: Job ${job.id} failed permanently after $maxRetries attempts.")
            }
        }
    }
}

class JobScheduler(private val jobService: JobService, private val scope: CoroutineScope) {
    fun startDailyReportJob() {
        scope.launch {
            while (isActive) {
                println("SCHEDULER: Enqueuing daily report job.")
                jobService.enqueueDailyReport()
                delay(30_000) // Schedule for every 30 seconds for demo
            }
        }
    }
}

// --- Application Service to coordinate actions ---
class JobService(
    private val jobQueue: JobQueue,
    private val jobRepository: JobRepository,
    private val emailService: EmailService,
    private val imageService: ImageProcessingService
) {
    suspend fun enqueueWelcomeEmail(user: User): UUID {
        val job = Job(
            id = UUID.randomUUID(),
            description = "Send welcome email to ${user.email}",
            task = { emailService.sendWelcomeEmail(user.email) }
        )
        jobRepository.save(job)
        jobQueue.enqueue(job)
        return job.id
    }

    suspend fun enqueueImageProcessing(post: Post): UUID {
        val job = Job(
            id = UUID.randomUUID(),
            description = "Process image for post '${post.title}'",
            task = { imageService.processPostImage(post.id) }
        )
        jobRepository.save(job)
        jobQueue.enqueue(job)
        return job.id
    }

    suspend fun enqueueDailyReport(): UUID {
        val job = Job(
            id = UUID.randomUUID(),
            description = "Generate daily user report",
            task = {
                println("REPORT_TASK: Starting report generation...")
                delay(2000)
                println("REPORT_TASK: Report generation complete.")
            }
        )
        jobRepository.save(job)
        jobQueue.enqueue(job)
        return job.id
    }
}

// --- Main Application and Controller ---
fun main() {
    // --- Dependency Injection Setup ---
    val userDb = ConcurrentHashMap<UUID, User>()
    val postDb = ConcurrentHashMap<UUID, Post>()

    val jobRepository = InMemoryJobRepository()
    val jobQueue = CoroutineChannelJobQueue()
    val emailService = MockEmailServiceImpl()
    val imageService = MockImageProcessingServiceImpl()
    val jobService = JobService(jobQueue, jobRepository, emailService, imageService)

    val backgroundScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val worker = JobWorker(jobQueue, jobRepository, backgroundScope)
    val scheduler = JobScheduler(jobService, backgroundScope)

    // --- Start Background Processes ---
    worker.start()
    scheduler.startDailyReportJob()

    // --- API Setup ---
    Javalin.create { config ->
        config.router.apiBuilder {
            path("/users") {
                post { ctx ->
                    val newUser = User(UUID.randomUUID(), "user${userDb.size}@test.com", "hash", UserRole.USER, true, Timestamp.from(Instant.now()))
                    userDb[newUser.id] = newUser
                    val jobId = runBlocking { jobService.enqueueWelcomeEmail(newUser) }
                    ctx.json(mapOf("message" to "User created", "userId" to newUser.id, "jobId" to jobId)).status(201)
                }
            }
            path("/posts") {
                post { ctx ->
                    if (userDb.isEmpty()) {
                        ctx.status(400).json(mapOf("error" to "Create a user first"))
                        return@post
                    }
                    val newPost = Post(UUID.randomUUID(), userDb.keys.first(), "A Great Post", "Content here", PostStatus.PUBLISHED)
                    postDb[newPost.id] = newPost
                    val jobId = runBlocking { jobService.enqueueImageProcessing(newPost) }
                    ctx.json(mapOf("message" to "Post created", "postId" to newPost.id, "jobId" to jobId)).status(201)
                }
            }
            path("/jobs/{id}") {
                get { ctx ->
                    val jobId = UUID.fromString(ctx.pathParam("id"))
                    val job = jobRepository.findById(jobId)
                    if (job != null) {
                        ctx.json(mapOf(
                            "id" to job.id, "description" to job.description, "state" to job.state,
                            "attempt" to job.attempt, "lastError" to job.lastError
                        ))
                    } else {
                        ctx.status(404).json(mapOf("error" to "Job not found"))
                    }
                }
            }
        }
    }.start(7071)
    println("Server started on port 7071")
}