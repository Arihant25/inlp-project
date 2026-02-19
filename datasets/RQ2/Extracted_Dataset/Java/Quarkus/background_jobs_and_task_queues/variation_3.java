package com.example.variation3;

import io.quarkus.scheduler.Scheduled;
import io.quarkus.vertx.ConsumeEvent;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import org.jboss.logging.Logger;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.sql.Timestamp;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

// --- DOMAIN MODEL ---
class EventBusDomain {
    enum UserRole { ADMIN, USER }
    enum PostStatus { DRAFT, PUBLISHED }
    enum JobStatus { QUEUED, IN_PROGRESS, DONE, FAILED }

    record User(UUID id, String email, String password_hash, UserRole role, boolean is_active, Timestamp created_at) {}
    record Post(UUID id, UUID user_id, String title, String content, PostStatus status) {}
    record Job(UUID id, JobStatus status, String details) {}
    record JobEvent(UUID jobId, Object payload) {}
}

// --- EVENT ADDRESS CONSTANTS ---
final class EventAddresses {
    public static final String NOTIFY_USER = "user.notification.email";
    public static final String PROCESS_IMAGE_RESIZE = "image.process.resize";
    public static final String PROCESS_IMAGE_WATERMARK = "image.process.watermark";
    public static final String PROCESS_IMAGE_UPLOAD = "image.process.upload";
}

// --- JOB TRACKING SERVICE ---
@Singleton
class JobTrackingService {
    private static final Logger LOG = Logger.getLogger(JobTrackingService.class);
    final ConcurrentMap<UUID, EventBusDomain.Job> jobMap = new ConcurrentHashMap<>();

    public void registerJob(UUID jobId, String details) {
        jobMap.put(jobId, new EventBusDomain.Job(jobId, EventBusDomain.JobStatus.QUEUED, details));
        LOG.infof("Job Registered: %s (%s)", jobId, details);
    }

    public void updateJobStatus(UUID jobId, EventBusDomain.JobStatus status) {
        jobMap.computeIfPresent(jobId, (id, job) -> {
            LOG.infof("Job Status Change: %s -> %s", id, status);
            return new EventBusDomain.Job(id, status, job.details());
        });
    }
}

// --- EVENT CONSUMERS (THE WORKERS) ---
@ApplicationScoped
class EventConsumers {
    private static final Logger LOG = Logger.getLogger(EventConsumers.class);
    private final AtomicInteger emailFailures = new AtomicInteger(0);

    @Inject JobTrackingService jobTracker;
    @Inject EventBus eventBus;

    @ConsumeEvent(value = EventAddresses.NOTIFY_USER, blocking = true)
    @Retry(maxRetries = 3, delay = 200, jitter = 100)
    @Timeout(5000)
    public void consumeUserNotification(EventBusDomain.JobEvent event) throws Exception {
        jobTracker.updateJobStatus(event.jobId(), EventBusDomain.JobStatus.IN_PROGRESS);
        var user = (EventBusDomain.User) event.payload();
        LOG.infof("Attempting to send email for job %s to %s", event.jobId(), user.email());

        if (emailFailures.incrementAndGet() <= 2) {
            LOG.warnf("Email service failure for job %s. Retrying...", event.jobId());
            throw new RuntimeException("Simulated network failure");
        }

        Thread.sleep(500); // Simulate sending email
        LOG.infof("Email sent successfully for job %s", event.jobId());
        jobTracker.updateJobStatus(event.jobId(), EventBusDomain.JobStatus.DONE);
        emailFailures.set(0); // Reset for next job
    }

    @ConsumeEvent(value = EventAddresses.PROCESS_IMAGE_RESIZE, blocking = true)
    public void consumeImageResize(EventBusDomain.JobEvent event) throws InterruptedException {
        jobTracker.updateJobStatus(event.jobId(), EventBusDomain.JobStatus.IN_PROGRESS);
        LOG.infof("Image Pipeline [1/3]: Resizing image for job %s", event.jobId());
        Thread.sleep(1000);
        // Send to next step in the pipeline
        eventBus.send(EventAddresses.PROCESS_IMAGE_WATERMARK, event);
    }

    @ConsumeEvent(value = EventAddresses.PROCESS_IMAGE_WATERMARK, blocking = true)
    public void consumeImageWatermark(EventBusDomain.JobEvent event) throws InterruptedException {
        LOG.infof("Image Pipeline [2/3]: Applying watermark for job %s", event.jobId());
        Thread.sleep(1000);
        eventBus.send(EventAddresses.PROCESS_IMAGE_UPLOAD, event);
    }

    @ConsumeEvent(value = EventAddresses.PROCESS_IMAGE_UPLOAD, blocking = true)
    public void consumeImageUpload(EventBusDomain.JobEvent event) throws InterruptedException {
        LOG.infof("Image Pipeline [3/3]: Uploading to storage for job %s", event.jobId());
        Thread.sleep(1000);
        LOG.infof("Image Pipeline for job %s is complete.", event.jobId());
        jobTracker.updateJobStatus(event.jobId(), EventBusDomain.JobStatus.DONE);
    }
}

// --- SCHEDULED TASK ---
@ApplicationScoped
class ScheduledJobs {
    private static final Logger LOG = Logger.getLogger(ScheduledJobs.class);

    @Scheduled(every = "45s", identity = "stale-jobs-checker")
    public void checkForStaleJobs() {
        LOG.info("SCHEDULER: Checking for stale jobs...");
        // Logic to find jobs in IN_PROGRESS for too long
        LOG.info("SCHEDULER: No stale jobs found.");
    }
}

// --- API TO DISPATCH EVENTS ---
@Path("/v3/events")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EventDispatcherResource {

    @Inject EventBus bus;
    @Inject JobTrackingService jobTracker;

    @POST
    @Path("/users")
    public EventBusDomain.Job dispatchUserCreationEvent() {
        var user = new EventBusDomain.User(UUID.randomUUID(), "vertx.user@example.com", "hash", EventBusDomain.UserRole.USER, true, null);
        var jobId = UUID.randomUUID();
        jobTracker.registerJob(jobId, "Send welcome email to " + user.email());
        bus.send(EventAddresses.NOTIFY_USER, new EventBusDomain.JobEvent(jobId, user));
        return new EventBusDomain.Job(jobId, EventBusDomain.JobStatus.QUEUED, "Email notification queued.");
    }

    @POST
    @Path("/posts")
    public EventBusDomain.Job dispatchPostCreationEvent() {
        var post = new EventBusDomain.Post(UUID.randomUUID(), UUID.randomUUID(), "Vert.x Event Bus", "Content", EventBusDomain.PostStatus.PUBLISHED);
        var jobId = UUID.randomUUID();
        jobTracker.registerJob(jobId, "Process image for post: " + post.title());
        bus.send(EventAddresses.PROCESS_IMAGE_RESIZE, new EventBusDomain.JobEvent(jobId, post));
        return new EventBusDomain.Job(jobId, EventBusDomain.JobStatus.QUEUED, "Image processing pipeline queued.");
    }
}