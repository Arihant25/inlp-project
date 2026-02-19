package com.example.variant2;

import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.retry.annotation.Retryable;
import io.micronaut.runtime.event.annotation.EventListener;
import io.micronaut.scheduling.annotation.Async;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

// --- Domain Model & Events (using Records for immutability) ---

enum Role { ADMIN, USER }
enum PostStatus { DRAFT, PUBLISHED }
enum JobState { SUBMITTED, IN_PROGRESS, DONE, ERROR }

record User(UUID id, String email, String password_hash, Role role, boolean is_active, Timestamp created_at) {}
record Post(UUID id, UUID user_id, String title, String content, PostStatus status) {}

// Events
record UserRegisteredEvent(User user, UUID trackingId) {}
record PostPublishedEvent(Post post, UUID trackingId) {}
record WeeklyDigestEvent(UUID trackingId) {}

// --- Job Status Tracking ---

record JobInfo(UUID id, JobState state, String details) {}

@Singleton
class JobStatusTracker {
    private final Map<UUID, JobInfo> jobStatusMap = new ConcurrentHashMap<>();

    public void updateStatus(UUID trackingId, JobState state, String details) {
        jobStatusMap.put(trackingId, new JobInfo(trackingId, state, details));
    }

    public JobInfo getStatus(UUID trackingId) {
        return jobStatusMap.get(trackingId);
    }
}

// --- Event Publishing Service ---

@Singleton
class UserActionService {
    @Inject
    private ApplicationEventPublisher eventPublisher;
    @Inject
    private JobStatusTracker jobStatusTracker;

    public UUID registerUser(String email, String password) {
        User newUser = new User(UUID.randomUUID(), email, "hashed_"+password, Role.USER, true, new Timestamp(System.currentTimeMillis()));
        UUID trackingId = UUID.randomUUID();
        jobStatusTracker.updateStatus(trackingId, JobState.SUBMITTED, "Welcome email for " + email);
        eventPublisher.publishEvent(new UserRegisteredEvent(newUser, trackingId));
        return trackingId;
    }
}

// --- Event Listeners for Background Processing ---

@Singleton
class NotificationEventListener {
    private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);
    @Inject
    private JobStatusTracker jobStatusTracker;

    @Async
    @EventListener
    public void onUserRegistration(UserRegisteredEvent event) {
        log.info("EVENT LISTENER: Received UserRegisteredEvent for {}, trackingId={}", event.user().email(), event.trackingId());
        jobStatusTracker.updateStatus(event.trackingId(), JobState.IN_PROGRESS, "Sending welcome email...");
        try {
            // Simulate email sending
            TimeUnit.SECONDS.sleep(2);
            log.info("Email sent to {}", event.user().email());
            jobStatusTracker.updateStatus(event.trackingId(), JobState.DONE, "Email sent successfully.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Failed to send email for trackingId={}", event.trackingId(), e);
            jobStatusTracker.updateStatus(event.trackingId(), JobState.ERROR, e.getMessage());
        }
    }
}

@Singleton
class ProcessingEventListener {
    private static final Logger log = LoggerFactory.getLogger(ProcessingEventListener.class);
    @Inject
    private JobStatusTracker jobStatusTracker;

    @Async
    @EventListener
    public void onPostPublished(PostPublishedEvent event) {
        log.info("EVENT LISTENER: Received PostPublishedEvent for post {}, trackingId={}", event.post().id(), event.trackingId());
        jobStatusTracker.updateStatus(event.trackingId(), JobState.IN_PROGRESS, "Processing post image...");
        try {
            // Simulate a multi-step process
            processAndUploadImageWithRetry(event.post());
            jobStatusTracker.updateStatus(event.trackingId(), JobState.DONE, "Image processed and uploaded.");
        } catch (Exception e) {
            log.error("Image processing pipeline failed for trackingId={}", event.trackingId(), e);
            jobStatusTracker.updateStatus(event.trackingId(), JobState.ERROR, "Image processing failed: " + e.getMessage());
        }
    }

    @Retryable(attempts = "4", multiplier = "1.5")
    protected void processAndUploadImageWithRetry(Post post) throws Exception {
        log.info("Attempting to process image for post {}...", post.id());
        // Simulate a flaky external service
        if (Math.random() < 0.75) {
            log.warn("Image processing service failed for post {}. Retrying...", post.id());
            throw new Exception("External processing service is unavailable");
        }
        TimeUnit.SECONDS.sleep(2);
        log.info("Image processed successfully for post {}", post.id());
    }
}

// --- Scheduled Task Publisher ---

@Singleton
class ScheduledEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(ScheduledEventPublisher.class);
    @Inject
    private ApplicationEventPublisher eventPublisher;
    @Inject
    private JobStatusTracker jobStatusTracker;

    @Scheduled(cron = "0 0 5 * * MON") // Every Monday at 5 AM
    void triggerWeeklyDigest() {
        UUID trackingId = UUID.randomUUID();
        log.info("SCHEDULER: Triggering WeeklyDigestEvent with trackingId={}", trackingId);
        jobStatusTracker.updateStatus(trackingId, JobState.SUBMITTED, "Generate weekly digest email.");
        eventPublisher.publishEvent(new WeeklyDigestEvent(trackingId));
    }

    @Async
    @EventListener
    public void onWeeklyDigest(WeeklyDigestEvent event) {
        log.info("EVENT LISTENER: Processing weekly digest for trackingId={}", event.trackingId());
        // Logic to generate and send digest...
        jobStatusTracker.updateStatus(event.trackingId(), JobState.DONE, "Weekly digest sent.");
    }
}