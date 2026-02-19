package com.example.demo.ddd;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// ----------------- MAIN APPLICATION -----------------
@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableRetry
public class DddApp {
    public static void main(String[] args) {
        SpringApplication.run(DddApp.class, args);
    }
}

// ----------------- DOMAIN: USER AGGREGATE -----------------
enum UserRole { ADMIN, USER }
class User {
    private UUID id;
    private String email;
    public User(UUID id, String email) { this.id = id; this.email = email; }
    public UUID getId() { return id; }
    public String getEmail() { return email; }
}

// ----------------- DOMAIN: POST AGGREGATE -----------------
enum PostStatus { DRAFT, PUBLISHED }
class Post {
    private UUID id;
    private UUID userId;
    private String title;
    public Post(UUID id, UUID userId, String title) { this.id = id; this.userId = userId; this.title = title; }
    public UUID getId() { return id; }
}

// ----------------- DOMAIN: JOB AGGREGATE -----------------
enum JobStatus { SUBMITTED, PROCESSING, COMPLETED, FAILED }
class Job {
    private UUID id;
    private String description;
    private JobStatus status;
    private Instant updatedAt;
    public Job(String description) {
        this.id = UUID.randomUUID();
        this.description = description;
        this.status = JobStatus.SUBMITTED;
        this.updatedAt = Instant.now();
    }
    public void updateStatus(JobStatus newStatus) {
        this.status = newStatus;
        this.updatedAt = Instant.now();
    }
    public UUID getId() { return id; }
    public JobStatus getStatus() { return status; }
    public String getDescription() { return description; }
}

@Repository
class InMemoryJobRepository {
    private final Map<UUID, Job> store = new ConcurrentHashMap<>();
    public void save(Job job) { store.put(job.getId(), job); }
    public Optional<Job> findById(UUID id) { return Optional.ofNullable(store.get(id)); }
}

// ----------------- DOMAIN EVENTS -----------------
class UserRegisteredEvent extends ApplicationEvent {
    private final User user;
    private final Job job;
    public UserRegisteredEvent(Object source, User user, Job job) {
        super(source);
        this.user = user;
        this.job = job;
    }
    public User getUser() { return user; }
    public Job getJob() { return job; }
}

class PostPublishedEvent extends ApplicationEvent {
    private final Post post;
    private final Job job;
    public PostPublishedEvent(Object source, Post post, Job job) {
        super(source);
        this.post = post;
        this.job = job;
    }
    public Post getPost() { return post; }
    public Job getJob() { return job; }
}

// ----------------- APPLICATION SERVICES -----------------
@Service
class UserService {
    private final ApplicationEventPublisher eventPublisher;
    private final InMemoryJobRepository jobRepository;
    public UserService(ApplicationEventPublisher eventPublisher, InMemoryJobRepository jobRepository) {
        this.eventPublisher = eventPublisher;
        this.jobRepository = jobRepository;
    }
    public Job registerUser(String email) {
        User newUser = new User(UUID.randomUUID(), email);
        Job job = new Job("Send Welcome Email to " + email);
        jobRepository.save(job);
        eventPublisher.publishEvent(new UserRegisteredEvent(this, newUser, job));
        return job;
    }
}

@Service
class PostService {
    private final ApplicationEventPublisher eventPublisher;
    private final InMemoryJobRepository jobRepository;
    public PostService(ApplicationEventPublisher eventPublisher, InMemoryJobRepository jobRepository) {
        this.eventPublisher = eventPublisher;
        this.jobRepository = jobRepository;
    }
    public Job publishPost(UUID userId, String title) {
        Post newPost = new Post(UUID.randomUUID(), userId, title);
        Job job = new Job("Process Images for Post: " + title);
        jobRepository.save(job);
        eventPublisher.publishEvent(new PostPublishedEvent(this, newPost, job));
        return job;
    }
}

// ----------------- EVENT LISTENERS (BACKGROUND JOBS) -----------------
@Component
class NotificationListener {
    private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);
    private final InMemoryJobRepository jobRepository;
    private int attempt = 0;

    public NotificationListener(InMemoryJobRepository jobRepository) { this.jobRepository = jobRepository; }

    @Async
    @EventListener
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2.5))
    public void handleUserRegistration(UserRegisteredEvent event) {
        Job job = event.getJob();
        job.updateStatus(JobStatus.PROCESSING);
        jobRepository.save(job);
        log.info("Job {}: Processing UserRegisteredEvent for {}", job.getId(), event.getUser().getEmail());
        try {
            attempt++;
            if (attempt < 3) {
                log.warn("Job {}: Email service failed. Retrying...", job.getId());
                throw new IllegalStateException("Flaky email service");
            }
            Thread.sleep(2000); // Simulate sending email
            log.info("Job {}: Welcome email sent to {}", job.getId(), event.getUser().getEmail());
            job.updateStatus(JobStatus.COMPLETED);
            attempt = 0; // Reset for next event
        } catch (Exception e) {
            log.error("Job {}: Failed to send welcome email after all retries.", job.getId(), e);
            job.updateStatus(JobStatus.FAILED);
            throw e; // Re-throw to allow Spring Retry to handle it
        } finally {
            jobRepository.save(job);
        }
    }
}

@Component
class PostProcessingListener {
    private static final Logger log = LoggerFactory.getLogger(PostProcessingListener.class);
    private final InMemoryJobRepository jobRepository;
    public PostProcessingListener(InMemoryJobRepository jobRepository) { this.jobRepository = jobRepository; }

    @Async
    @EventListener
    public void handlePostPublication(PostPublishedEvent event) {
        Job job = event.getJob();
        job.updateStatus(JobStatus.PROCESSING);
        jobRepository.save(job);
        log.info("Job {}: Starting image pipeline for post {}", job.getId(), event.getPost().getId());
        try {
            log.info("Job {}: Resizing image...", job.getId());
            Thread.sleep(1500);
            log.info("Job {}: Applying watermark...", job.getId());
            Thread.sleep(1000);
            log.info("Job {}: Pipeline complete.", job.getId());
            job.updateStatus(JobStatus.COMPLETED);
        } catch (InterruptedException e) {
            log.error("Job {}: Image pipeline interrupted.", job.getId(), e);
            job.updateStatus(JobStatus.FAILED);
            Thread.currentThread().interrupt();
        } finally {
            jobRepository.save(job);
        }
    }
}

@Component
class ScheduledDomainTasks {
    private static final Logger log = LoggerFactory.getLogger(ScheduledDomainTasks.class);
    @Scheduled(fixedDelayString = "PT15S") // Every 15 seconds
    public void cleanupExpiredDrafts() {
        log.info("[SCHEDULED] Checking for expired draft posts to clean up...");
        // Logic to find and delete old drafts
        log.info("[SCHEDULED] Cleanup complete.");
    }
}

// ----------------- INTERFACES (CONTROLLER) -----------------
@RestController
@RequestMapping("/v3/commands")
class JobTriggerController {
    private final UserService userService;
    private final PostService postService;
    private final InMemoryJobRepository jobRepository;

    public JobTriggerController(UserService userService, PostService postService, InMemoryJobRepository jobRepository) {
        this.userService = userService;
        this.postService = postService;
        this.jobRepository = jobRepository;
    }

    @PostMapping("/register-user")
    public Job registerUser(@RequestBody Map<String, String> payload) {
        return userService.registerUser(payload.get("email"));
    }

    @PostMapping("/publish-post")
    public Job publishPost() {
        return postService.publishPost(UUID.randomUUID(), "Event-Driven Post");
    }

    @GetMapping("/jobs/{id}")
    public Job getJobStatus(@PathVariable UUID id) {
        return jobRepository.findById(id).orElse(null);
    }
}