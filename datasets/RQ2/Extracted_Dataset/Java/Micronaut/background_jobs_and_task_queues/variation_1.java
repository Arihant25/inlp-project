package com.example.variant1;

import io.micronaut.aop.Adapter;
import io.micronaut.retry.annotation.Retryable;
import io.micronaut.scheduling.annotation.Async;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

// --- Domain Model ---

enum Role { ADMIN, USER }
enum PostStatus { DRAFT, PUBLISHED }
enum JobStatus { PENDING, PROCESSING, COMPLETED, FAILED }

record User(UUID id, String email, String password_hash, Role role, boolean is_active, Timestamp created_at) {}
record Post(UUID id, UUID user_id, String title, String content, PostStatus status) {}
record Job(UUID id, String description, JobStatus status, String result) {}

// --- In-Memory Data Stores (Mocks) ---

@Singleton
class InMemoryJobRepository {
    private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();
    public void save(Job job) { jobs.put(job.id(), job); }
    public Job findById(UUID id) { return jobs.get(id); }
}

@Singleton
class MockPostRepository {
    private static final Logger LOG = LoggerFactory.getLogger(MockPostRepository.class);
    public void deleteOldDrafts() {
        LOG.info("DATABASE MOCK: Deleting posts older than 30 days.");
    }
}

// --- Service Layer ---

interface NotificationService {
    CompletableFuture<String> sendWelcomeEmail(User user);
    void sendPasswordResetEmailWithRetry(String email);
}

@Singleton
class EmailNotificationServiceImpl implements NotificationService {
    private static final Logger LOG = LoggerFactory.getLogger(EmailNotificationServiceImpl.class);

    @Async
    @Override
    public CompletableFuture<String> sendWelcomeEmail(User user) {
        LOG.info("Starting to send welcome email to {}", user.email());
        try {
            // Simulate network latency
            TimeUnit.SECONDS.sleep(2);
            LOG.info("Successfully sent welcome email to {}", user.email());
            return CompletableFuture.completedFuture("Email sent to " + user.email());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error("Email sending interrupted for {}", user.email(), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    @Retryable(attempts = "3", delay = "1s")
    @Override
    public void sendPasswordResetEmailWithRetry(String email) {
        LOG.info("Attempting to send password reset to {}...", email);
        // Simulate a flaky service that fails 66% of the time
        if (Math.random() > 0.33) {
            LOG.warn("Failed to send password reset to {}. Retrying...", email);
            throw new RuntimeException("Email service unavailable");
        }
        LOG.info("Successfully sent password reset to {}", email);
    }
}

@Singleton
class ImageProcessingService {
    private static final Logger LOG = LoggerFactory.getLogger(ImageProcessingService.class);

    @Async
    public CompletableFuture<String> processImagePipeline(UUID postId, byte[] imageData) {
        LOG.info("Starting image processing pipeline for post {}", postId);
        try {
            resizeImage(imageData);
            applyWatermark(imageData);
            String cdnUrl = uploadToStorage(imageData);
            LOG.info("Image processing pipeline completed for post {}. URL: {}", postId, cdnUrl);
            return CompletableFuture.completedFuture(cdnUrl);
        } catch (Exception e) {
            LOG.error("Image processing pipeline failed for post {}", postId, e);
            return CompletableFuture.failedFuture(e);
        }
    }

    private void resizeImage(byte[] imageData) throws InterruptedException {
        LOG.info("Step 1: Resizing image...");
        TimeUnit.MILLISECONDS.sleep(500);
    }

    private void applyWatermark(byte[] imageData) throws InterruptedException {
        LOG.info("Step 2: Applying watermark...");
        TimeUnit.MILLISECONDS.sleep(500);
    }

    private String uploadToStorage(byte[] imageData) throws InterruptedException {
        LOG.info("Step 3: Uploading to cloud storage...");
        TimeUnit.SECONDS.sleep(1);
        return "https://cdn.example.com/" + UUID.randomUUID() + ".jpg";
    }
}

@Singleton
class JobOrchestrator {
    @Inject
    private InMemoryJobRepository jobRepository;
    @Inject
    private NotificationService notificationService;
    @Inject
    private ImageProcessingService imageProcessingService;

    public UUID submitWelcomeEmailJob(User user) {
        UUID jobId = UUID.randomUUID();
        Job job = new Job(jobId, "Send welcome email to " + user.email(), JobStatus.PENDING, null);
        jobRepository.save(job);

        notificationService.sendWelcomeEmail(user)
            .whenComplete((result, throwable) -> {
                if (throwable != null) {
                    jobRepository.save(new Job(jobId, job.description(), JobStatus.FAILED, throwable.getMessage()));
                } else {
                    jobRepository.save(new Job(jobId, job.description(), JobStatus.COMPLETED, result));
                }
            });
        return jobId;
    }

    public UUID submitImageProcessingJob(Post post) {
        UUID jobId = UUID.randomUUID();
        Job job = new Job(jobId, "Process image for post " + post.id(), JobStatus.PENDING, null);
        jobRepository.save(job);
        
        byte[] mockImageData = "mock-image-data".getBytes();
        imageProcessingService.processImagePipeline(post.id(), mockImageData)
            .whenComplete((result, throwable) -> {
                if (throwable != null) {
                    jobRepository.save(new Job(jobId, job.description(), JobStatus.FAILED, throwable.getMessage()));
                } else {
                    jobRepository.save(new Job(jobId, job.description(), JobStatus.COMPLETED, result));
                }
            });
        return jobId;
    }
}

// --- Scheduled Tasks ---

@Singleton
class PeriodicTasks {
    private static final Logger LOG = LoggerFactory.getLogger(PeriodicTasks.class);

    @Inject
    private MockPostRepository postRepository;

    @Scheduled(fixedDelay = "1h", initialDelay = "5m")
    void cleanupOldDrafts() {
        LOG.info("Scheduler: Kicking off job to clean up old draft posts.");
        postRepository.deleteOldDrafts();
    }
}