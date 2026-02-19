package com.example.demo.classic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

// ----------------- MAIN APPLICATION -----------------
@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableRetry
public class ClassicApp {
    public static void main(String[] args) {
        SpringApplication.run(ClassicApp.class, args);
    }
}

// ----------------- DOMAIN SCHEMA -----------------
enum UserRole { ADMIN, USER }
enum PostStatus { DRAFT, PUBLISHED }

class User {
    private UUID id;
    private String email;
    private String password_hash;
    private UserRole role;
    private boolean is_active;
    private Timestamp created_at;

    public User(UUID id, String email) {
        this.id = id;
        this.email = email;
        this.created_at = Timestamp.from(Instant.now());
        this.is_active = true;
        this.role = UserRole.USER;
    }
    public String getEmail() { return email; }
    public UUID getId() { return id; }
}

class Post {
    private UUID id;
    private UUID user_id;
    private String title;
    private String content;
    private PostStatus status;
    public Post(UUID id, UUID user_id, String title) {
        this.id = id;
        this.user_id = user_id;
        this.title = title;
        this.status = PostStatus.DRAFT;
    }
    public UUID getId() { return id; }
}

// ----------------- JOB STATUS TRACKING -----------------
enum JobState { PENDING, RUNNING, COMPLETED, FAILED }

class JobTracker {
    private static final Map<UUID, JobState> jobStatuses = new ConcurrentHashMap<>();

    public static void startJob(UUID jobId) {
        jobStatuses.put(jobId, JobState.PENDING);
    }

    public static void updateJobState(UUID jobId, JobState state) {
        jobStatuses.put(jobId, state);
    }

    public static JobState getJobState(UUID jobId) {
        return jobStatuses.getOrDefault(jobId, null);
    }
}

// ----------------- SERVICES -----------------
@Service
class EmailService {
    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    private int emailAttemptCounter = 0;

    @Async
    @Retryable(
        value = { RuntimeException.class },
        maxAttempts = 4,
        backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    public CompletableFuture<Void> sendWelcomeEmailAsync(User user, UUID jobId) {
        JobTracker.updateJobState(jobId, JobState.RUNNING);
        log.info("Attempting to send welcome email to {} for job {}", user.getEmail(), jobId);
        try {
            // Simulate a flaky email service
            emailAttemptCounter++;
            if (emailAttemptCounter <= 2) {
                log.warn("Email service unavailable. Attempt {}/4. Retrying...", emailAttemptCounter);
                throw new RuntimeException("Email service connection failed");
            }
            Thread.sleep(2000); // Simulate network latency
            log.info("Successfully sent welcome email to {}", user.getEmail());
            JobTracker.updateJobState(jobId, JobState.COMPLETED);
            emailAttemptCounter = 0; // Reset for next user
        } catch (Exception e) {
            log.error("Failed to send email for job {} after retries.", jobId, e);
            JobTracker.updateJobState(jobId, JobState.FAILED);
        }
        return CompletableFuture.completedFuture(null);
    }
}

@Service
class ImageProcessingService {
    private static final Logger log = LoggerFactory.getLogger(ImageProcessingService.class);

    @Async
    public CompletableFuture<Void> processPostImagePipeline(Post post, UUID jobId) {
        JobTracker.updateJobState(jobId, JobState.RUNNING);
        log.info("Starting image processing pipeline for post {} (Job ID: {})", post.getId(), jobId);
        try {
            resizeImage(post).join();
            applyWatermark(post).join();
            generateThumbnail(post).join();
            log.info("Image processing pipeline COMPLETED for post {}", post.getId());
            JobTracker.updateJobState(jobId, JobState.COMPLETED);
        } catch (Exception e) {
            log.error("Image processing pipeline FAILED for post {}", post.getId(), e);
            JobTracker.updateJobState(jobId, JobState.FAILED);
        }
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> resizeImage(Post post) throws InterruptedException {
        log.info("[Post {}] Step 1: Resizing image...", post.getId());
        Thread.sleep(1500);
        log.info("[Post {}] Step 1: Resizing complete.", post.getId());
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> applyWatermark(Post post) throws InterruptedException {
        log.info("[Post {}] Step 2: Applying watermark...", post.getId());
        Thread.sleep(1000);
        log.info("[Post {}] Step 2: Watermark applied.", post.getId());
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> generateThumbnail(Post post) throws InterruptedException {
        log.info("[Post {}] Step 3: Generating thumbnail...", post.getId());
        Thread.sleep(500);
        log.info("[Post {}] Step 3: Thumbnail generated.", post.getId());
        return CompletableFuture.completedFuture(null);
    }
}

@Service
class ScheduledReportingService {
    private static final Logger log = LoggerFactory.getLogger(ScheduledReportingService.class);

    @Scheduled(fixedRate = 30, timeUnit = TimeUnit.SECONDS)
    public void generateHourlyActivityReport() {
        log.info("--- [SCHEDULED TASK] ---");
        log.info("Generating hourly user activity report...");
        // Simulate report generation
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("Hourly report generated successfully.");
        log.info("--- [END SCHEDULED TASK] ---");
    }
}

// ----------------- CONTROLLER -----------------
@RestController
@RequestMapping("/v1/jobs")
class JobController {
    private final EmailService emailService;
    private final ImageProcessingService imageProcessingService;

    @Autowired
    public JobController(EmailService emailService, ImageProcessingService imageProcessingService) {
        this.emailService = emailService;
        this.imageProcessingService = imageProcessingService;
    }

    @PostMapping("/send-welcome-email")
    public Map<String, String> triggerWelcomeEmail() {
        UUID jobId = UUID.randomUUID();
        User mockUser = new User(UUID.randomUUID(), "test.user@example.com");
        JobTracker.startJob(jobId);
        emailService.sendWelcomeEmailAsync(mockUser, jobId);
        return Map.of("message", "Welcome email job queued.", "jobId", jobId.toString());
    }

    @PostMapping("/process-post-image")
    public Map<String, String> triggerImageProcessing() {
        UUID jobId = UUID.randomUUID();
        Post mockPost = new Post(UUID.randomUUID(), UUID.randomUUID(), "My New Post");
        JobTracker.startJob(jobId);
        imageProcessingService.processPostImagePipeline(mockPost, jobId);
        return Map.of("message", "Image processing job queued.", "jobId", jobId.toString());
    }

    @GetMapping("/status/{jobId}")
    public Map<String, Object> getJobStatus(@PathVariable UUID jobId) {
        JobState state = JobTracker.getJobState(jobId);
        if (state == null) {
            return Map.of("error", "Job not found", "jobId", jobId);
        }
        return Map.of("jobId", jobId, "status", state);
    }
}