package com.example.variation4;

import io.quarkus.scheduler.Scheduled;
import io.smallrye.reactive.messaging.annotations.Blocking;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Outgoing;
import org.jboss.logging.Logger;
import org.eclipse.microprofile.faulttolerance.Retry;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Map;
import java.util.concurrent.atomic.AtomicInteger;

// --- CONSOLIDATED DOMAIN MODEL (using static inner classes) ---
class Domain {
    public enum UserRole { ADMIN, USER }
    public enum PostStatus { DRAFT, PUBLISHED }
    public enum JobStatus { PENDING, ACTIVE, DONE, FAILED }

    public static class User {
        public final UUID id; public final String email; public final String password_hash; public final UserRole role; public final boolean is_active; public final Timestamp created_at;
        public User(String email) { this.id = UUID.randomUUID(); this.email = email; this.password_hash = "secret"; this.role = UserRole.USER; this.is_active = true; this.created_at = Timestamp.from(Instant.now()); }
    }

    public static class Post {
        public final UUID id; public final UUID user_id; public final String title; public final String content; public final PostStatus status;
        public Post(UUID user_id, String title) { this.id = UUID.randomUUID(); this.user_id = user_id; this.title = title; this.content = "lorem ipsum"; this.status = PostStatus.PUBLISHED; }
    }
    
    public record Job(UUID id, JobStatus status, String taskName) {}
    public record TaskPayload(UUID jobId, Object data) {}
}

// --- ALL-IN-ONE MANAGER CLASS ---
@ApplicationScoped
class BackgroundJobManager {
    private static final Logger LOG = Logger.getLogger(BackgroundJobManager.class);
    private final Map<UUID, Domain.Job> job_status_map = new ConcurrentHashMap<>();
    private final AtomicInteger retry_counter = new AtomicInteger(0);

    // --- Emitters to send tasks to internal channels ---
    @Inject @Channel("manager-email-channel") Emitter<Domain.TaskPayload> email_task_emitter;
    @Inject @Channel("manager-image-pipe-start") Emitter<Domain.TaskPayload> image_task_emitter;

    // --- Public API for triggering jobs ---
    public UUID triggerWelcomeEmail(Domain.User user) {
        UUID jobId = UUID.randomUUID();
        job_status_map.put(jobId, new Domain.Job(jobId, Domain.JobStatus.PENDING, "Welcome Email"));
        LOG.infof("Manager: Queuing email job %s for %s", jobId, user.email);
        email_task_emitter.send(new Domain.TaskPayload(jobId, user));
        return jobId;
    }

    public UUID triggerImageProcessing(Domain.Post post) {
        UUID jobId = UUID.randomUUID();
        job_status_map.put(jobId, new Domain.Job(jobId, Domain.JobStatus.PENDING, "Image Processing"));
        LOG.infof("Manager: Queuing image processing job %s for post '%s'", jobId, post.title);
        image_task_emitter.send(new Domain.TaskPayload(jobId, post));
        return jobId;
    }

    public Domain.Job getJobStatus(UUID jobId) {
        return job_status_map.get(jobId);
    }

    // --- Scheduled Task Implementation ---
    @Scheduled(every = "1m", identity = "manager-health-check")
    void manager_internal_health_check() {
        LOG.info("MANAGER SCHEDULER: Performing internal health check. Active jobs: " +
            job_status_map.values().stream().filter(j -> j.status() == Domain.JobStatus.ACTIVE).count());
    }

    // --- Worker: Async Email Sending with Retry ---
    @Incoming("manager-email-channel")
    @Retry(maxRetries = 2)
    public CompletionStage<Void> manager_handle_email_task(Domain.TaskPayload payload) {
        job_status_map.computeIfPresent(payload.jobId(), (id, job) -> new Domain.Job(id, Domain.JobStatus.ACTIVE, job.taskName()));
        var user = (Domain.User) payload.data();
        LOG.infof("Manager Worker: Sending email for job %s", payload.jobId());

        return CompletableFuture.runAsync(() -> {
            if (retry_counter.incrementAndGet() <= 2) {
                LOG.errorf("Manager Worker: Email service failed for job %s. Retrying...", payload.jobId());
                throw new RuntimeException("Email service unavailable");
            }
            try {
                Thread.sleep(400); // Simulate I/O
                LOG.infof("Manager Worker: Email sent successfully for job %s to %s", payload.jobId(), user.email);
                job_status_map.computeIfPresent(payload.jobId(), (id, job) -> new Domain.Job(id, Domain.JobStatus.DONE, job.taskName()));
                retry_counter.set(0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                job_status_map.computeIfPresent(payload.jobId(), (id, job) -> new Domain.Job(id, Domain.JobStatus.FAILED, job.taskName()));
            }
        });
    }

    // --- Worker: Image Processing Pipeline ---
    @Incoming("manager-image-pipe-start")
    @Outgoing("manager-image-pipe-watermark")
    @Blocking
    public Domain.TaskPayload manager_image_step1_resize(Domain.TaskPayload payload) throws InterruptedException {
        job_status_map.computeIfPresent(payload.jobId(), (id, job) -> new Domain.Job(id, Domain.JobStatus.ACTIVE, job.taskName()));
        LOG.infof("Manager Pipeline [1/3]: Resizing image for job %s", payload.jobId());
        Thread.sleep(750);
        return payload;
    }

    @Incoming("manager-image-pipe-watermark")
    @Outgoing("manager-image-pipe-upload")
    @Blocking
    public Domain.TaskPayload manager_image_step2_watermark(Domain.TaskPayload payload) throws InterruptedException {
        LOG.infof("Manager Pipeline [2/3]: Watermarking image for job %s", payload.jobId());
        Thread.sleep(750);
        return payload;
    }

    @Incoming("manager-image-pipe-upload")
    @Blocking
    public void manager_image_step3_upload(Domain.TaskPayload payload) throws InterruptedException {
        LOG.infof("Manager Pipeline [3/3]: Uploading image for job %s", payload.jobId());
        Thread.sleep(750);
        LOG.infof("Manager Pipeline: Job %s finished.", payload.jobId());
        job_status_map.computeIfPresent(payload.jobId(), (id, job) -> new Domain.Job(id, Domain.JobStatus.DONE, job.taskName()));
    }
}

// --- API RESOURCE (thin wrapper around the manager) ---
@Path("/v4/manager")
public class ManagerApiResource {

    @Inject
    BackgroundJobManager jobManager;

    @GET
    @Path("/run/email")
    public String runEmailJob() {
        var user = new Domain.User("managed.user@example.com");
        UUID jobId = jobManager.triggerWelcomeEmail(user);
        return "Email job dispatched by manager. Job ID: " + jobId;
    }

    @GET
    @Path("/run/image")
    public String runImageJob() {
        var post = new Domain.Post(UUID.randomUUID(), "Managed Post");
        UUID jobId = jobManager.triggerImageProcessing(post);
        return "Image processing job dispatched by manager. Job ID: " + jobId;
    }

    @GET
    @Path("/status/{id}")
    public Domain.Job getStatus(String id) {
        return jobManager.getJobStatus(UUID.fromString(id));
    }
}