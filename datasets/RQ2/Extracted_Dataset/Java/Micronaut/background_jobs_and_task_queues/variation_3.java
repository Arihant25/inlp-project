package com.example.variant3;

import io.micronaut.retry.annotation.Retryable;
import io.micronaut.scheduling.annotation.Async;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

// --- Domain Model ---
enum Role { ADMIN, USER }
enum PostStatus { DRAFT, PUBLISHED }
class User {
    public UUID id;
    public String email;
    public User(UUID id, String email) { this.id = id; this.email = email; }
}
class Post {
    public UUID id;
    public UUID user_id;
    public Post(UUID id, UUID userId) { this.id = id; this.user_id = userId; }
}

// --- Job Status Model ---
enum JobStatus { QUEUED, RUNNING, FINISHED, FAILED }
class JobTicket {
    public final UUID jobId;
    public JobStatus status;
    public String message;
    public JobTicket(UUID id) { this.jobId = id; this.status = JobStatus.QUEUED; }
}

// --- All-in-One Manager Approach ---

@Singleton
public class BackgroundJobManager {

    private static final Logger LOG = LoggerFactory.getLogger(BackgroundJobManager.class);
    private final Map<UUID, JobTicket> jobTickets = new ConcurrentHashMap<>();

    // --- Public API for Job Submission ---

    public JobTicket submitWelcomeEmailJob(User user) {
        JobTicket ticket = createTicket();
        LOG.info("Job {} queued: Send welcome email to {}", ticket.jobId, user.email);
        this.executeEmailTask(ticket, user.email);
        return ticket;
    }

    public JobTicket submitImageProcessingJob(Post post) {
        JobTicket ticket = createTicket();
        LOG.info("Job {} queued: Process image for post {}", ticket.jobId, post.id);
        this.executeImagePipeline(ticket, post.id);
        return ticket;
    }

    public JobTicket getJobStatus(UUID jobId) {
        return jobTickets.get(jobId);
    }

    private JobTicket createTicket() {
        JobTicket ticket = new JobTicket(UUID.randomUUID());
        jobTickets.put(ticket.jobId, ticket);
        return ticket;
    }

    // --- Async Task Implementations ---

    @Async("io") // Use the I/O thread pool
    protected void executeEmailTask(JobTicket ticket, String email) {
        updateJobStatus(ticket, JobStatus.RUNNING, "Sending email...");
        try {
            TimeUnit.SECONDS.sleep(2); // Simulate network call
            updateJobStatus(ticket, JobStatus.FINISHED, "Email sent to " + email);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            updateJobStatus(ticket, JobStatus.FAILED, "Task interrupted: " + e.getMessage());
        }
    }

    @Async("io")
    protected void executeImagePipeline(JobTicket ticket, UUID postId) {
        updateJobStatus(ticket, JobStatus.RUNNING, "Starting image pipeline...");
        try {
            // Step 1: Download image (simulated)
            TimeUnit.MILLISECONDS.sleep(500);
            updateJobStatus(ticket, JobStatus.RUNNING, "Resizing image...");

            // Step 2: Resize image (simulated)
            TimeUnit.MILLISECONDS.sleep(500);
            updateJobStatus(ticket, JobStatus.RUNNING, "Applying watermark...");

            // Step 3: Apply watermark (simulated)
            TimeUnit.MILLISECONDS.sleep(500);
            updateJobStatus(ticket, JobStatus.RUNNING, "Uploading to storage...");

            // Step 4: Upload to a flaky external service
            String cdnUrl = uploadWithRetry(postId);
            
            updateJobStatus(ticket, JobStatus.FINISHED, "Pipeline complete. URL: " + cdnUrl);
        } catch (Exception e) {
            updateJobStatus(ticket, JobStatus.FAILED, "Pipeline failed: " + e.getMessage());
        }
    }

    // --- Retry Logic ---

    @Retryable(attempts = "3", delay = "500ms", backoff = @Retryable.Backoff(multiplier = 2.0))
    protected String uploadWithRetry(UUID postId) {
        LOG.info("Attempting to upload image for post {}", postId);
        if (Math.random() > 0.5) {
            LOG.warn("Upload failed for post {}. Retrying...", postId);
            throw new IllegalStateException("CDN service is temporarily down.");
        }
        LOG.info("Upload successful for post {}", postId);
        return "https://cdn.example.com/" + postId + ".jpg";
    }

    // --- Scheduled Tasks ---

    @Scheduled(fixedRate = "15m")
    void runHourlyMaintenance() {
        LOG.info("SCHEDULER: Starting periodic maintenance task.");
        // Clean up old, completed job tickets to prevent memory leak
        jobTickets.values().removeIf(ticket ->
            ticket.status == JobStatus.FINISHED || ticket.status == JobStatus.FAILED
        );
        LOG.info("SCHEDULER: Maintenance complete. Current jobs: {}", jobTickets.size());
    }

    // --- Utility Methods ---

    private void updateJobStatus(JobTicket ticket, JobStatus status, String message) {
        ticket.status = status;
        ticket.message = message;
        LOG.info("Job {} status updated to {}: {}", ticket.jobId, status, message);
    }
}