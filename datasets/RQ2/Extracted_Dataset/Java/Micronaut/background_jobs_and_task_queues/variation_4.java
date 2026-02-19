package com.example.variant4;

import io.micronaut.context.annotation.Context;
import io.micronaut.retry.annotation.Retryable;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

// --- Domain Model ---
enum Role { ADMIN, USER }
enum PostStatus { DRAFT, PUBLISHED }
record User(UUID id, String email) {}
record Post(UUID id, UUID userId) {}

// --- Job Queue Infrastructure ---
enum JobType { SEND_EMAIL, PROCESS_IMAGE, GENERATE_REPORT }
enum JobStatus { PENDING, PROCESSING, COMPLETED, FAILED }

// A generic job envelope
record Job(UUID id, JobType type, Object payload, JobStatus status, String details) {}

@Singleton
class InMemoryJobQueue {
    private final BlockingQueue<UUID> queue = new LinkedBlockingQueue<>();
    private final Map<UUID, Job> jobStore = new ConcurrentHashMap<>();

    public void enqueue(Job job) {
        jobStore.put(job.id(), job);
        queue.add(job.id());
    }

    public UUID take() throws InterruptedException {
        return queue.take();
    }


    public Job findJob(UUID id) {
        return jobStore.get(id);
    }

    public void updateJob(Job job) {
        jobStore.put(job.id(), job);
    }
}

// --- Job Producer ---
@Singleton
class JobProducer {
    private static final Logger log = LoggerFactory.getLogger(JobProducer.class);

    @Inject
    private InMemoryJobQueue jobQueue;

    public UUID submitEmailJob(User user) {
        Job job = new Job(UUID.randomUUID(), JobType.SEND_EMAIL, user, JobStatus.PENDING, "Send welcome email");
        jobQueue.enqueue(job);
        log.info("Enqueued job {} of type {} for user {}", job.id(), job.type(), user.email());
        return job.id();
    }

    public UUID submitImageJob(Post post) {
        Job job = new Job(UUID.randomUUID(), JobType.PROCESS_IMAGE, post, JobStatus.PENDING, "Process post image");
        jobQueue.enqueue(job);
        log.info("Enqueued job {} of type {} for post {}", job.id(), job.type(), post.id());
        return job.id();
    }
}

// --- Job Processor (contains the business logic) ---
@Singleton
class JobProcessor {
    private static final Logger log = LoggerFactory.getLogger(JobProcessor.class);

    public void processEmail(User user) throws InterruptedException {
        log.info("Processing SEND_EMAIL job for user {}", user.email());
        TimeUnit.SECONDS.sleep(2); // Simulate sending email
        log.info("Email sent to {}", user.email());
    }

    @Retryable(attempts = "3", delay = "1s")
    public void processImage(Post post) throws Exception {
        log.info("Attempting to process PROCESS_IMAGE job for post {}", post.id());
        // Simulate a flaky, multi-step process
        TimeUnit.MILLISECONDS.sleep(500);
        log.info("...resizing image for post {}", post.id());
        if (Math.random() > 0.4) {
            throw new RuntimeException("ImageMagick service failed");
        }
        TimeUnit.MILLISECONDS.sleep(500);
        log.info("...uploading image for post {}", post.id());
        log.info("Successfully processed image for post {}", post.id());
    }
    
    public void processReport() throws InterruptedException {
        log.info("Processing GENERATE_REPORT job...");
        TimeUnit.SECONDS.sleep(5); // Simulate long-running report
        log.info("Report generation complete.");
    }
}

// --- Job Consumer (the worker) ---
@Singleton
@Context
class JobConsumer {
    private static final Logger log = LoggerFactory.getLogger(JobConsumer.class);

    @Inject private InMemoryJobQueue jobQueue;
    @Inject private JobProcessor jobProcessor;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @PostConstruct
    void start() {
        log.info("JobConsumer starting up...");
        executor.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    UUID jobId = jobQueue.take();
                    Job job = jobQueue.findJob(jobId);
                    if (job != null) {
                        processJob(job);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("JobConsumer interrupted, shutting down.");
                    break;
                }
            }
        });
    }

    private void processJob(Job job) {
        log.info("Dequeued job {} for processing.", job.id());
        jobQueue.updateJob(job.copy(job.id(), job.type(), job.payload(), JobStatus.PROCESSING, "Processing started"));
        try {
            switch (job.type()) {
                case SEND_EMAIL -> jobProcessor.processEmail((User) job.payload());
                case PROCESS_IMAGE -> jobProcessor.processImage((Post) job.payload());
                case GENERATE_REPORT -> jobProcessor.processReport();
                default -> throw new IllegalArgumentException("Unknown job type: " + job.type());
            }
            jobQueue.updateJob(job.copy(job.id(), job.type(), job.payload(), JobStatus.COMPLETED, "Processing finished successfully"));
        } catch (Exception e) {
            log.error("Job {} failed with error: {}", job.id(), e.getMessage());
            jobQueue.updateJob(job.copy(job.id(), job.type(), job.payload(), JobStatus.FAILED, e.getMessage()));
        }
    }

    @PreDestroy
    void stop() {
        log.info("JobConsumer shutting down...");
        executor.shutdownNow();
    }
    
    // Helper to create a new record with updated values
    private Job copy(UUID id, JobType type, Object payload, JobStatus status, String details) {
        return new Job(id, type, payload, status, details);
    }
}

// --- Scheduled Task that uses the queue ---
@Singleton
class ScheduledJobs {
    private static final Logger log = LoggerFactory.getLogger(ScheduledJobs.class);
    @Inject private InMemoryJobQueue jobQueue;

    @Scheduled(cron = "0 0/30 * * * *") // Every 30 minutes
    void scheduleReportGeneration() {
        Job job = new Job(UUID.randomUUID(), JobType.GENERATE_REPORT, null, JobStatus.PENDING, "Generate system health report");
        jobQueue.enqueue(job);
        log.info("SCHEDULER: Enqueued periodic report generation job {}", job.id());
    }
}