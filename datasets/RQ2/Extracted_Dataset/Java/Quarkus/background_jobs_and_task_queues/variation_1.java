package com.example.variation1;

import io.quarkus.scheduler.Scheduled;
import io.smallrye.reactive.messaging.annotations.Blocking;
import io.smallrye.reactive.messaging.annotations.Merge;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Outgoing;
import org.jboss.logging.Logger;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.ExponentialBackoff;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

// --- DOMAIN SCHEMA ---
class Domain {
    public enum UserRole { ADMIN, USER }
    public enum PostStatus { DRAFT, PUBLISHED }
    public enum JobState { PENDING, RUNNING, COMPLETED, FAILED }

    public static class User {
        UUID id; String email; String password_hash; UserRole role; boolean is_active; Timestamp created_at;
        public User(String email) { this.id = UUID.randomUUID(); this.email = email; this.is_active = true; this.created_at = Timestamp.from(Instant.now()); }
    }

    public static class Post {
        UUID id; UUID user_id; String title; String content; PostStatus status;
        public Post(UUID user_id, String title) { this.id = UUID.randomUUID(); this.user_id = user_id; this.title = title; this.status = PostStatus.PUBLISHED; }
    }

    public static class Job {
        UUID id; JobState state; String description;
        public Job(UUID id, String description) { this.id = id; this.state = JobState.PENDING; this.description = description; }
    }
}

// --- JOB STATUS TRACKING ---
@Singleton
class JobStatusTracker {
    private static final Logger LOG = Logger.getLogger(JobStatusTracker.class);
    private final ConcurrentMap<UUID, Domain.Job> jobs = new ConcurrentHashMap<>();

    public void submit(Domain.Job job) {
        jobs.put(job.id, job);
        LOG.infof("Job SUBMITTED: %s (%s)", job.id, job.description);
    }

    public void updateState(UUID jobId, Domain.JobState state) {
        jobs.computeIfPresent(jobId, (id, job) -> {
            job.state = state;
            LOG.infof("Job %s -> %s", state, job.id);
            return job;
        });
    }

    public Domain.Job getStatus(UUID jobId) {
        return jobs.get(jobId);
    }
}

// --- SERVICES & WORKERS ---
@ApplicationScoped
class EmailService {
    private static final Logger LOG = Logger.getLogger(EmailService.class);
    private final AtomicInteger attemptCounter = new AtomicInteger(1);

    @Inject
    JobStatusTracker jobTracker;

    @Incoming("email-queue")
    @Retry(maxRetries = 3, delay = 100, jitter = 50, backoff = @ExponentialBackoff(factor = 2))
    public CompletionStage<Void> sendWelcomeEmail(Message<Domain.User> message) {
        Domain.User user = message.getPayload();
        UUID jobId = (UUID) message.getMetadata(OutgoingKafkaRecordMetadata.class).orElse(new OutgoingKafkaRecordMetadata.Builder().withKey(UUID.randomUUID()).build()).getKey();
        
        jobTracker.updateState(jobId, Domain.JobState.RUNNING);
        LOG.infof("Attempt #%d: Sending welcome email to %s for job %s", attemptCounter.get(), user.email, jobId);

        // Simulate a flaky service that fails the first 2 times
        if (attemptCounter.getAndIncrement() < 3) {
            LOG.errorf("Email service failed for user %s. Retrying...", user.email);
            throw new RuntimeException("Email service unavailable");
        }

        try {
            Thread.sleep(500); // Simulate network latency
            LOG.infof("Successfully sent email to %s", user.email);
            jobTracker.updateState(jobId, Domain.JobState.COMPLETED);
            attemptCounter.set(1); // Reset for next message
            return message.ack();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            jobTracker.updateState(jobId, Domain.JobState.FAILED);
            return message.nack(e);
        }
    }
}

@ApplicationScoped
class ImageProcessingService {
    private static final Logger LOG = Logger.getLogger(ImageProcessingService.class);

    @Inject
    JobStatusTracker jobTracker;

    @Incoming("image-processing-pipeline-in")
    @Outgoing("image-processing-pipeline-resized")
    @Blocking // Marks this as a blocking operation
    public Message<Domain.Post> resizeImage(Message<Domain.Post> message) throws InterruptedException {
        Domain.Post post = message.getPayload();
        UUID jobId = (UUID) message.getMetadata(OutgoingKafkaRecordMetadata.class).orElseThrow().getKey();
        jobTracker.updateState(jobId, Domain.JobState.RUNNING);
        LOG.infof("[Job %s] Step 1: Resizing image for post '%s'", jobId, post.title);
        Thread.sleep(1000); // Simulate heavy work
        LOG.infof("[Job %s] Step 1: Resizing COMPLETE", jobId);
        return message;
    }

    @Incoming("image-processing-pipeline-resized")
    @Outgoing("image-processing-pipeline-watermarked")
    @Blocking
    public Message<Domain.Post> applyWatermark(Message<Domain.Post> message) throws InterruptedException {
        Domain.Post post = message.getPayload();
        UUID jobId = (UUID) message.getMetadata(OutgoingKafkaRecordMetadata.class).orElseThrow().getKey();
        LOG.infof("[Job %s] Step 2: Applying watermark for post '%s'", jobId, post.title);
        Thread.sleep(800);
        LOG.infof("[Job %s] Step 2: Watermark COMPLETE", jobId);
        return message;
    }

    @Incoming("image-processing-pipeline-watermarked")
    @Blocking
    public CompletionStage<Void> uploadToCdn(Message<Domain.Post> message) {
        return CompletableFuture.runAsync(() -> {
            try {
                Domain.Post post = message.getPayload();
                UUID jobId = (UUID) message.getMetadata(OutgoingKafkaRecordMetadata.class).orElseThrow().getKey();
                LOG.infof("[Job %s] Step 3: Uploading to CDN for post '%s'", jobId, post.title);
                Thread.sleep(1200);
                LOG.infof("[Job %s] Step 3: Upload COMPLETE. Pipeline finished.", jobId);
                jobTracker.updateState(jobId, Domain.JobState.COMPLETED);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).thenRun(() -> message.ack());
    }
}

@ApplicationScoped
class PeriodicTaskService {
    private static final Logger LOG = Logger.getLogger(PeriodicTaskService.class);

    @Scheduled(every = "20s", identity = "periodic-user-cleanup")
    void runUserCleanup() {
        LOG.info("SCHEDULER: Running periodic task: Deactivating inactive users...");
        // In a real app, this would query the database.
        LOG.info("SCHEDULER: Found 0 inactive users to deactivate. Task complete.");
    }
}

// --- API TO TRIGGER JOBS ---
@Path("/v1/jobs")
@Produces(MediaType.APPLICATION_JSON)
public class JobTriggerResource {

    @Inject
    @Channel("email-queue")
    Emitter<Domain.User> emailEmitter;

    @Inject
    @Channel("image-processing-pipeline-in")
    Emitter<Domain.Post> imageEmitter;

    @Inject
    JobStatusTracker jobTracker;

    @GET
    @Path("/user-signup")
    public String triggerUserSignup() {
        Domain.User newUser = new Domain.User("test.user@example.com");
        UUID jobId = UUID.randomUUID();
        Domain.Job job = new Domain.Job(jobId, "Send welcome email to " + newUser.email);
        jobTracker.submit(job);

        // Create a message with metadata for tracking
        Message<Domain.User> message = Message.of(newUser)
            .addMetadata(OutgoingKafkaRecordMetadata.builder().withKey(jobId).build());
        
        emailEmitter.send(message);
        return "Email job submitted with ID: " + jobId;
    }

    @GET
    @Path("/new-post")
    public String triggerNewPost() {
        Domain.Post newPost = new Domain.Post(UUID.randomUUID(), "My Awesome Trip");
        UUID jobId = UUID.randomUUID();
        Domain.Job job = new Domain.Job(jobId, "Process image for post: " + newPost.title);
        jobTracker.submit(job);

        Message<Domain.Post> message = Message.of(newPost)
            .addMetadata(OutgoingKafkaRecordMetadata.builder().withKey(jobId).build());

        imageEmitter.send(message);
        return "Image processing pipeline started with Job ID: " + jobId;
    }
}