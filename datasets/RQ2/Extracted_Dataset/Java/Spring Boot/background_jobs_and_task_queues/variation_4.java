package com.example.demo.modern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
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
@EnableConfigurationProperties(ModernApp.RetrySettings.class)
public class ModernApp {

    @ConfigurationProperties(prefix = "app.jobs.retry")
    public record RetrySettings(int maxAttempts, long initialDelay, double multiplier) {}

    public static void main(String[] args) {
        SpringApplication.run(ModernApp.class, args);
    }
}

// ----------------- CONFIGURATION -----------------
@Configuration
class TaskExecutorConfig {
    public static final String IO_EXECUTOR = "ioTaskExecutor";
    public static final String CPU_EXECUTOR = "cpuTaskExecutor";

    @Bean(name = IO_EXECUTOR)
    public TaskExecutor ioBoundTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("io-task-");
        executor.initialize();
        return executor;
    }

    @Bean(name = CPU_EXECUTOR)
    public TaskExecutor cpuBoundTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int coreCount = Runtime.getRuntime().availableProcessors();
        executor.setCorePoolSize(coreCount);
        executor.setMaxPoolSize(coreCount * 2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("cpu-task-");
        executor.initialize();
        return executor;
    }
}

@Configuration
class ScheduledTasksConfig {
    private static final Logger log = LoggerFactory.getLogger(ScheduledTasksConfig.class);

    @Scheduled(cron = "${app.jobs.cron.publish-posts:0 0 * * * *}") // Hourly, default
    public void publishScheduledPosts() {
        log.info("[SCHEDULED] Running job to publish scheduled posts...");
        // ... implementation
        log.info("[SCHEDULED] Job finished.");
    }
}

// ----------------- DOMAIN SCHEMA -----------------
enum UserRole { ADMIN, USER }
enum PostStatus { DRAFT, PUBLISHED }
record User(UUID id, String email, String password_hash, UserRole role, boolean is_active, Timestamp created_at) {}
record Post(UUID id, UUID user_id, String title, String content, PostStatus status) {}

// ----------------- JOB MANAGEMENT -----------------
enum JobState { QUEUED, ACTIVE, DONE, ERROR }
record JobInfo(UUID id, String type, JobState state, String details) {}

@Service
class JobRegistry {
    private final Map<UUID, JobInfo> registry = new ConcurrentHashMap<>();

    public JobInfo register(String type) {
        UUID id = UUID.randomUUID();
        JobInfo job = new JobInfo(id, type, JobState.QUEUED, "Job has been queued for execution.");
        registry.put(id, job);
        return job;
    }

    public void update(UUID id, JobState state, String details) {
        registry.computeIfPresent(id, (key, old) -> new JobInfo(old.id(), old.type(), state, details));
    }

    public JobInfo findById(UUID id) {
        return registry.get(id);
    }
}

// ----------------- TASK COMPONENTS -----------------
@Component
class EmailTask {
    private static final Logger log = LoggerFactory.getLogger(EmailTask.class);
    private final JobRegistry registry;
    private final ModernApp.RetrySettings retrySettings;

    EmailTask(JobRegistry registry, ModernApp.RetrySettings retrySettings) {
        this.registry = registry;
        this.retrySettings = retrySettings;
    }

    @Async(TaskExecutorConfig.IO_EXECUTOR)
    @Retryable(
        maxAttemptsExpression = "#{@retrySettings.maxAttempts()}",
        backoff = @Backoff(delayExpression = "#{@retrySettings.initialDelay()}", multiplierExpression = "#{@retrySettings.multiplier()}")
    )
    public void sendWelcomeEmail(UUID jobId, User user) {
        registry.update(jobId, JobState.ACTIVE, "Attempting to send email.");
        log.info("Job {}: Sending welcome email to {} on thread {}", jobId, user.email(), Thread.currentThread().getName());
        try {
            // Simulate a flaky I/O operation
            if (Math.random() > 0.3) { // 70% chance of failure
                throw new IllegalStateException("SMTP server not responding");
            }
            Thread.sleep(1200);
            log.info("Job {}: Email sent successfully.", jobId);
            registry.update(jobId, JobState.DONE, "Email sent successfully.");
        } catch (Exception e) {
            log.warn("Job {}: Failed to send email. Retrying...", jobId);
            registry.update(jobId, JobState.ACTIVE, "Email send failed, retrying. Reason: " + e.getMessage());
            throw new RuntimeException(e); // Re-throw for retry mechanism
        }
    }
}

@Component
class ImageProcessingTask {
    private static final Logger log = LoggerFactory.getLogger(ImageProcessingTask.class);
    private final JobRegistry registry;

    ImageProcessingTask(JobRegistry registry) { this.registry = registry; }

    @Async(TaskExecutorConfig.CPU_EXECUTOR)
    public CompletableFuture<Void> runPipeline(UUID jobId, Post post) {
        registry.update(jobId, JobState.ACTIVE, "Pipeline started.");
        log.info("Job {}: Starting image pipeline for post {} on thread {}", jobId, post.id(), Thread.currentThread().getName());
        
        return CompletableFuture.runAsync(() -> {
            try {
                registry.update(jobId, JobState.ACTIVE, "Resizing image.");
                Thread.sleep(1500); // Simulate CPU-bound work
            } catch (InterruptedException e) { throw new RuntimeException(e); }
        }).thenRunAsync(() -> {
            try {
                registry.update(jobId, JobState.ACTIVE, "Applying filter.");
                Thread.sleep(1000); // Simulate CPU-bound work
            } catch (InterruptedException e) { throw new RuntimeException(e); }
        }).whenComplete((res, err) -> {
            if (err != null) {
                registry.update(jobId, JobState.ERROR, "Pipeline failed: " + err.getMessage());
                log.error("Job {}: Pipeline failed.", jobId, err);
            } else {
                registry.update(jobId, JobState.DONE, "Pipeline completed successfully.");
                log.info("Job {}: Pipeline finished.", jobId);
            }
        });
    }
}

// ----------------- API & DISPATCHER -----------------
@Service
class TaskDispatcher {
    private final EmailTask emailTask;
    private final ImageProcessingTask imageTask;
    private final JobRegistry registry;

    TaskDispatcher(EmailTask emailTask, ImageProcessingTask imageTask, JobRegistry registry) {
        this.emailTask = emailTask;
        this.imageTask = imageTask;
        this.registry = registry;
    }

    public JobInfo dispatchEmailJob(User user) {
        JobInfo job = registry.register("WELCOME_EMAIL");
        try {
            emailTask.sendWelcomeEmail(job.id(), user);
        } catch (Exception e) {
            // This catch block will handle the final failure after all retries
            registry.update(job.id(), JobState.ERROR, "Failed after all retries: " + e.getMessage());
        }
        return job;
    }

    public JobInfo dispatchImageJob(Post post) {
        JobInfo job = registry.register("IMAGE_PIPELINE");
        imageTask.runPipeline(job.id(), post);
        return job;
    }
}

@RestController
@RequestMapping("/v4/dispatch")
class TaskDispatchController {
    private final TaskDispatcher dispatcher;
    private final JobRegistry registry;

    TaskDispatchController(TaskDispatcher dispatcher, JobRegistry registry) {
        this.dispatcher = dispatcher;
        this.registry = registry;
    }

    @PostMapping("/email")
    public JobInfo triggerEmail() {
        User user = new User(UUID.randomUUID(), "config.user@example.com", "", UserRole.USER, true, null);
        return dispatcher.dispatchEmailJob(user);
    }

    @PostMapping("/image")
    public JobInfo triggerImageProcessing() {
        Post post = new Post(UUID.randomUUID(), UUID.randomUUID(), "Modern Art", "", PostStatus.PUBLISHED);
        return dispatcher.dispatchImageJob(post);
    }

    @GetMapping("/jobs/{id}")
    public JobInfo getJob(@PathVariable UUID id) {
        return registry.findById(id);
    }
}