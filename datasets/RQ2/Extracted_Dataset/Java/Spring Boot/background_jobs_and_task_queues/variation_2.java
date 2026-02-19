package com.example.demo.functional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

// ----------------- MAIN APPLICATION -----------------
@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableRetry
public class FunctionalApp {
    public static void main(String[] args) {
        SpringApplication.run(FunctionalApp.class, args);
    }
}

// ----------------- DOMAIN SCHEMA (using Records for immutability) -----------------
enum UserRole { ADMIN, USER }
enum PostStatus { DRAFT, PUBLISHED }

record User(UUID id, String email, String password_hash, UserRole role, boolean is_active, Timestamp created_at) {
    public static User create(String email) {
        return new User(UUID.randomUUID(), email, "hashed_pass", UserRole.USER, true, Timestamp.from(Instant.now()));
    }
}

record Post(UUID id, UUID user_id, String title, String content, PostStatus status) {
    public static Post create(UUID userId, String title) {
        return new Post(UUID.randomUUID(), userId, title, "Some content", PostStatus.DRAFT);
    }
}

// ----------------- JOB TRACKING INFRASTRUCTURE -----------------
enum JobStatus { QUEUED, IN_PROGRESS, SUCCESS, FAILED }
record Job(UUID id, String name, JobStatus status, Instant lastUpdated) {}

@Component
class JobTracker {
    private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();

    public Job createJob(String name) {
        Job job = new Job(UUID.randomUUID(), name, JobStatus.QUEUED, Instant.now());
        jobs.put(job.id(), job);
        return job;
    }

    public void updateStatus(UUID id, JobStatus status) {
        jobs.computeIfPresent(id, (key, job) -> new Job(job.id(), job.name(), status, Instant.now()));
    }

    public Job getJob(UUID id) {
        return jobs.get(id);
    }
}

// ----------------- CONFIGURATION -----------------
@Configuration
class TaskConfig {
    @Bean
    public RetryTemplate emailRetryTemplate() {
        RetryTemplate retryTemplate = new RetryTemplate();

        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(500);
        backOffPolicy.setMultiplier(2.0);
        backOffPolicy.setMaxInterval(5000);
        retryTemplate.setBackOffPolicy(backOffPolicy);

        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy();
        retryPolicy.setMaxAttempts(4);
        retryTemplate.setRetryPolicy(retryPolicy);

        return retryTemplate;
    }
}

// ----------------- TASK COMPONENTS -----------------
@Component
class UserNotifier {
    private static final Logger log = LoggerFactory.getLogger(UserNotifier.class);

    @Async
    public CompletableFuture<Boolean> sendWelcomeNotification(User user, Job job, RetryTemplate retryTemplate) {
        return CompletableFuture.supplyAsync(() -> {
            jobTracker.updateStatus(job.id(), JobStatus.IN_PROGRESS);
            try {
                return retryTemplate.execute((RetryCallback<Boolean, RuntimeException>) context -> {
                    log.info("Attempting to send welcome email to {} [Attempt {}]", user.email(), context.getRetryCount() + 1);
                    if (context.getRetryCount() < 2) { // Simulate failure on first 2 attempts
                        throw new RuntimeException("Email service is down");
                    }
                    Thread.sleep(1000); // Simulate I/O
                    log.info("Successfully sent welcome email to {}", user.email());
                    return true;
                });
            } catch (Exception e) {
                log.error("Failed to send email for job {} after all retries.", job.id(), e);
                return false;
            }
        });
    }
    
    // Injected via constructor for testability
    private final JobTracker jobTracker;
    public UserNotifier(JobTracker jobTracker) { this.jobTracker = jobTracker; }
}

@Component
class ImageTransformer {
    private static final Logger log = LoggerFactory.getLogger(ImageTransformer.class);

    @Async
    public CompletableFuture<Void> process(Post post, Job job) {
        jobTracker.updateStatus(job.id(), JobStatus.IN_PROGRESS);
        log.info("Starting image pipeline for post: {} (Job ID: {})", post.title(), job.id());
        return CompletableFuture.runAsync(() -> {
            try {
                log.info("[{}] Resizing...", job.id());
                Thread.sleep(1500);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }).thenRunAsync(() -> {
            try {
                log.info("[{}] Watermarking...", job.id());
                Thread.sleep(1000);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }).thenRunAsync(() -> {
            try {
                log.info("[{}] Generating thumbnail...", job.id());
                Thread.sleep(500);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }).whenComplete((res, err) -> {
            if (err != null) {
                log.error("Image pipeline failed for job {}", job.id(), err);
                jobTracker.updateStatus(job.id(), JobStatus.FAILED);
            } else {
                log.info("Image pipeline succeeded for job {}", job.id());
                jobTracker.updateStatus(job.id(), JobStatus.SUCCESS);
            }
        });
    }

    private final JobTracker jobTracker;
    public ImageTransformer(JobTracker jobTracker) { this.jobTracker = jobTracker; }
}

@Component
class ScheduledAnalytics {
    private static final Logger log = LoggerFactory.getLogger(ScheduledAnalytics.class);

    @Scheduled(cron = "0/20 * * * * *") // Every 20 seconds
    public void runPeriodicAnalytics() {
        log.info("[SCHEDULED] Running periodic analytics job...");
        // Simulate work
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("[SCHEDULED] Analytics job finished.");
    }
}

// ----------------- API & SERVICE LAYER -----------------
@Service
class TaskOrchestrator {
    private final JobTracker jobTracker;
    private final UserNotifier userNotifier;
    private final ImageTransformer imageTransformer;
    private final RetryTemplate emailRetryTemplate;

    public TaskOrchestrator(JobTracker jobTracker, UserNotifier userNotifier, ImageTransformer imageTransformer, RetryTemplate emailRetryTemplate) {
        this.jobTracker = jobTracker;
        this.userNotifier = userNotifier;
        this.imageTransformer = imageTransformer;
        this.emailRetryTemplate = emailRetryTemplate;
    }

    public Job dispatchWelcomeEmail(User user) {
        Job job = jobTracker.createJob("WelcomeEmail");
        userNotifier.sendWelcomeNotification(user, job, emailRetryTemplate)
            .thenAccept(success -> jobTracker.updateStatus(job.id(), success ? JobStatus.SUCCESS : JobStatus.FAILED));
        return job;
    }

    public Job dispatchImageProcessing(Post post) {
        Job job = jobTracker.createJob("ImageProcessing");
        imageTransformer.process(post, job);
        return job;
    }
}

@RestController
@RequestMapping("/v2/tasks")
class TaskController {
    private final TaskOrchestrator orchestrator;
    private final JobTracker jobTracker;

    public TaskController(TaskOrchestrator orchestrator, JobTracker jobTracker) {
        this.orchestrator = orchestrator;
        this.jobTracker = jobTracker;
    }

    @PostMapping("/users/notify")
    public Job triggerUserNotification() {
        User user = User.create("new.user@example.com");
        return orchestrator.dispatchWelcomeEmail(user);
    }

    @PostMapping("/posts/process-image")
    public Job triggerImageProcessing() {
        Post post = Post.create(UUID.randomUUID(), "A Functional Post");
        return orchestrator.dispatchImageProcessing(post);
    }

    @GetMapping("/jobs/{id}")
    public Job getJobStatus(@PathVariable UUID id) {
        return jobTracker.getJob(id);
    }
}