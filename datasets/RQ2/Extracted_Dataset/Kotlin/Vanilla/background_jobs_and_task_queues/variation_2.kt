package com.example.jobs.functional

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
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

// --- Job System Components (Functional Style) ---

enum class TaskState { WAITING, IN_PROGRESS, DONE, FAILED }

data class Task(
    val id: UUID = UUID.randomUUID(),
    val name: String,
    val action: () -> Unit
)

data class TaskStatus(
    val state: TaskState,
    val attempts: Int = 0,
    val info: String? = null
)

object TaskRunner {
    private const val MAX_ATTEMPTS = 4
    private const val BASE_BACKOFF_MS = 500L

    private val taskQueue = LinkedBlockingQueue<Task>()
    private val taskStatuses = ConcurrentHashMap<UUID, TaskStatus>()
    private lateinit var workerPool: java.util.concurrent.ExecutorService
    private lateinit var scheduler: ScheduledExecutorService

    fun start(workerCount: Int) {
        workerPool = Executors.newFixedThreadPool(workerCount)
        scheduler = Executors.newSingleThreadScheduledExecutor()

        repeat(workerCount) {
            workerPool.submit(::workerLoop)
        }

        scheduler.scheduleAtFixedRate(::runPeriodicAudit, 15, 15, TimeUnit.SECONDS)
        println("TaskRunner started with $workerCount workers.")
    }

    fun submit(name: String, action: () -> Unit): UUID {
        val task = Task(name = name, action = action)
        taskStatuses[task.id] = TaskStatus(TaskState.WAITING)
        taskQueue.add(task)
        println("Submitted task '${task.name}' with ID ${task.id}")
        return task.id
    }

    private fun workerLoop() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                val task = taskQueue.take()
                executeWithRetry(task)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }

    private fun executeWithRetry(task: Task, attempt: Int = 1) {
        try {
            taskStatuses[task.id] = TaskStatus(TaskState.IN_PROGRESS, attempt)
            println("Running task '${task.name}' (${task.id}), attempt $attempt")
            task.action()
            taskStatuses[task.id] = TaskStatus(TaskState.DONE, attempt, "Completed successfully")
            println("Task '${task.name}' (${task.id}) completed.")
        } catch (e: Exception) {
            println("Task '${task.name}' (${task.id}) failed on attempt $attempt: ${e.message}")
            if (attempt < MAX_ATTEMPTS) {
                val delay = BASE_BACKOFF_MS * (2.0.pow(attempt - 1)).toLong()
                taskStatuses[task.id] = TaskStatus(TaskState.WAITING, attempt, "Scheduled for retry in ${delay}ms")
                scheduler.schedule({ executeWithRetry(task, attempt + 1) }, delay, TimeUnit.MILLISECONDS)
            } else {
                taskStatuses[task.id] = TaskStatus(TaskState.FAILED, attempt, "Exceeded max attempts. Final error: ${e.message}")
                println("Task '${task.name}' (${task.id}) failed permanently.")
            }
        }
    }

    private fun runPeriodicAudit() {
        val waitingCount = taskStatuses.values.count { it.state == TaskState.WAITING }
        val inProgressCount = taskStatuses.values.count { it.state == TaskState.IN_PROGRESS }
        println("[AUDIT] Queue size: ${taskQueue.size}, Waiting: $waitingCount, In Progress: $inProgressCount")
    }

    fun getStatus(id: UUID): TaskStatus? = taskStatuses[id]

    fun stop() {
        workerPool.shutdownNow()
        scheduler.shutdownNow()
        println("TaskRunner stopped.")
    }
}

// --- Job Definitions ---

fun sendEmailAsync(user: User) {
    TaskRunner.submit("Send Welcome Email to ${user.email}") {
        println("...sending email to ${user.email}")
        Thread.sleep(1200)
        if (Random.nextInt(10) < 4) { // 40% chance of failure
            throw RuntimeException("Email service unavailable")
        }
        println("...email sent successfully to ${user.email}")
    }
}

fun processImagePipeline(imageId: UUID) {
    TaskRunner.submit("Image Pipeline: Step 1/3 - Download") {
        println("...downloading image $imageId")
        Thread.sleep(800)
        // On success, enqueue the next step
        TaskRunner.submit("Image Pipeline: Step 2/3 - Resize") {
            println("...resizing image $imageId")
            Thread.sleep(1500)
            TaskRunner.submit("Image Pipeline: Step 3/3 - Upload") {
                println("...uploading resized image $imageId")
                Thread.sleep(1000)
                println("...image pipeline complete for $imageId")
            }
        }
    }
}

fun main() {
    println("--- Variation 2: Functional/Top-Level Functions ---")
    TaskRunner.start(3)

    val sampleUser = User(UUID.randomUUID(), "dev@example.com", "hash", UserRole.ADMIN, true, Timestamp.from(Instant.now()))
    
    sendEmailAsync(sampleUser)
    processImagePipeline(UUID.randomUUID())
    val failingJobId = TaskRunner.submit("Guaranteed to fail") {
        println("...running a job that will always fail")
        Thread.sleep(500)
        throw Exception("Intentional failure for retry demo")
    }

    Thread.sleep(20000)

    println("\n--- Final Job Statuses ---")
    println("Failing Job ($failingJobId): ${TaskRunner.getStatus(failingJobId)}")

    TaskRunner.stop()
}