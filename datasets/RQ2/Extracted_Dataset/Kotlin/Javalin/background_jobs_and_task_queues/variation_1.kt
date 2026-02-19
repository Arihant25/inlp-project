package com.example.variation1

import io.javalin.Javalin
import io.javalin.http.Context
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.*
import kotlin.math.pow

// --- Domain Model ---
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

// --- Background Job Infrastructure ---
enum class JobType {
    SEND_WELCOME_EMAIL,
    PROCESS_POST_IMAGE,
    GENERATE_DAILY_REPORT
}

enum class JobStatus {
    PENDING, RUNNING, COMPLETED, FAILED
}

data class Job(
    val id: UUID = UUID.randomUUID(),
    val type: JobType,
    val payload: Map<String, Any>,
    var status: JobStatus = JobStatus.PENDING,
    var attempts: Int = 0,
    var lastError: String? = null
)

// --- In-Memory "Database" and Services ---
val userDb = ConcurrentHashMap<UUID, User>()
val postDb = ConcurrentHashMap<UUID, Post>()
val jobStatusStore = ConcurrentHashMap<UUID, Job>()
val jobQueue: BlockingQueue<Job> = LinkedBlockingQueue()

// --- Mock Services ---
object MockEmailService {
    fun sendEmail(to: String, subject: String, body: String) {
        println("--> Sending email to $to: '$subject'")
        // Simulate a transient failure
        if (Math.random() > 0.5) {
            throw RuntimeException("SMTP server connection failed")
        }
        println("--> Email sent successfully to $to")
    }
}

object MockImageProcessor {
    fun process(postId: UUID) {
        println("--> Starting image processing for post $postId...")
        Thread.sleep(1000) // Simulate work
        println("--> Image processing for post $postId complete.")
    }
}

// --- Job Submission and Processing Logic ---
fun submitJob(type: JobType, payload: Map<String, Any>): Job {
    val job = Job(type = type, payload = payload)
    jobStatusStore[job.id] = job
    jobQueue.put(job)
    println("Submitted job ${job.id} of type ${job.type}")
    return job
}

fun processJob(job: Job) {
    job.status = JobStatus.RUNNING
    jobStatusStore[job.id] = job
    println("Processing job ${job.id} (Type: ${job.type}, Attempt: ${job.attempts + 1})")

    try {
        when (job.type) {
            JobType.SEND_WELCOME_EMAIL -> {
                val email = job.payload["email"] as String
                MockEmailService.sendEmail(email, "Welcome!", "Thanks for signing up.")
            }
            JobType.PROCESS_POST_IMAGE -> {
                val postId = job.payload["postId"] as UUID
                MockImageProcessor.process(postId)
            }
            JobType.GENERATE_DAILY_REPORT -> {
                println("--> Generating daily report... Found ${userDb.size} users.")
                Thread.sleep(2000) // Simulate report generation
                println("--> Daily report generated.")
            }
        }
        job.status = JobStatus.COMPLETED
        jobStatusStore[job.id] = job
        println("Job ${job.id} completed successfully.")
    } catch (e: Exception) {
        handleJobFailure(job, e)
    }
}

fun handleJobFailure(job: Job, error: Exception) {
    job.attempts++
    job.lastError = error.message
    val maxRetries = 3

    if (job.attempts < maxRetries) {
        val delayMs = 1000 * (2.0.pow(job.attempts)).toLong()
        println("Job ${job.id} failed. Retrying in ${delayMs}ms... (Attempt ${job.attempts})")
        job.status = JobStatus.PENDING // Re-queue for retry
        // In a real system, you'd use a delayed queue. Here we simulate with a scheduled executor.
        Executors.newSingleThreadScheduledExecutor().schedule({
            jobQueue.put(job)
        }, delayMs, TimeUnit.MILLISECONDS)
    } else {
        job.status = JobStatus.FAILED
        println("Job ${job.id} failed after $maxRetries attempts. Giving up.")
    }
    jobStatusStore[job.id] = job
}

fun startWorkerThread() {
    val worker = Thread {
        while (true) {
            try {
                val job = jobQueue.take() // Blocks until a job is available
                processJob(job)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                println("Worker thread interrupted.")
                break
            }
        }
    }
    worker.isDaemon = true
    worker.name = "JobWorker-1"
    worker.start()
    println("Job worker thread started.")
}

fun schedulePeriodicJobs() {
    val scheduler = Executors.newSingleThreadScheduledExecutor()
    scheduler.scheduleAtFixedRate({
        println("Scheduler: Submitting daily report job.")
        submitJob(JobType.GENERATE_DAILY_REPORT, emptyMap())
    }, 10, 30, TimeUnit.SECONDS) // Run every 30 seconds for demo
    println("Periodic job scheduler started.")
}

// --- API Endpoints ---
fun createUser(ctx: Context) {
    val email = "user${userDb.size + 1}@example.com"
    val newUser = User(
        id = UUID.randomUUID(),
        email = email,
        password_hash = "hashed_password",
        role = UserRole.USER,
        is_active = true,
        created_at = Timestamp.from(Instant.now())
    )
    userDb[newUser.id] = newUser
    val job = submitJob(JobType.SEND_WELCOME_EMAIL, mapOf("email" to newUser.email, "userId" to newUser.id))
    ctx.json(mapOf("user" to newUser, "jobId" to job.id)).status(201)
}

fun createPost(ctx: Context) {
    val newPost = Post(
        id = UUID.randomUUID(),
        user_id = userDb.keys.first(), // Assume at least one user exists
        title = "My New Post",
        content = "This is the content.",
        status = PostStatus.PUBLISHED
    )
    postDb[newPost.id] = newPost
    val job = submitJob(JobType.PROCESS_POST_IMAGE, mapOf("postId" to newPost.id))
    ctx.json(mapOf("post" to newPost, "jobId" to job.id)).status(201)
}

fun getJobStatus(ctx: Context) {
    val jobId = UUID.fromString(ctx.pathParam("id"))
    val job = jobStatusStore[jobId]
    if (job != null) {
        ctx.json(job)
    } else {
        ctx.status(404).json(mapOf("error" to "Job not found"))
    }
}

fun main() {
    // Start background processing
    startWorkerThread()
    schedulePeriodicJobs()

    // Start web server
    Javalin.create().apply {
        post("/users", ::createUser)
        post("/posts", ::createPost)
        get("/jobs/{id}", ::getJobStatus)
    }.start(7070)

    println("Server started on port 7070.")
}