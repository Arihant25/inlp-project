package com.example.variation2;

import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.annotations.Blocking;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Outgoing;
import org.jboss.logging.Logger;
import org.eclipse.microprofile.faulttolerance.Retry;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

// --- DOMAIN (as immutable records) ---
final class DomainRecords {
    public enum UserRole { ADMIN, USER }
    public enum PostStatus { DRAFT, PUBLISHED }
    public enum JobStatus { PENDING, RUNNING, COMPLETED, FAILED }

    public record User(UUID id, String email, String password_hash, UserRole role, boolean is_active, Timestamp created_at) {
        public static User create(String email) {
            return new User(UUID.randomUUID(), email, "hashed_pass", UserRole.USER, true, Timestamp.from(Instant.now()));
        }
    }

    public record Post(UUID id, UUID user_id, String title, String content, PostStatus status) {
        public static Post create(UUID userId, String title) {
            return new Post(UUID.randomUUID(), userId, title, "Some content", PostStatus.PUBLISHED);
        }
    }
    
    public record Job(UUID id, JobStatus status, String description) {}
    public record JobRequest(UUID jobId, Object payload) {}
}

// --- INFRASTRUCTURE: JOB STORE ---
@Singleton
class JobStore {
    private static final Logger log = Logger.getLogger(JobStore.class);
    private final ConcurrentMap<UUID, DomainRecords.Job> store = new ConcurrentHashMap<>();

    public void create(UUID jobId, String description) {
        store.put(jobId, new DomainRecords.Job(jobId, DomainRecords.JobStatus.PENDING, description));
        log.infof("Job created: %s", jobId);
    }

    public Uni<Void> updateStatus(UUID jobId, DomainRecords.JobStatus status) {
        return Uni.createFrom().item(() -> {
            store.computeIfPresent(jobId, (id, job) -> new DomainRecords.Job(id, status, job.description()));
            log.infof("Job %s status -> %s", jobId, status);
            return null;
        });
    }
}

// --- TASK PROCESSORS (Functional/Reactive Style) ---
@ApplicationScoped
class TaskProcessors {
    private static final Logger log = Logger.getLogger(TaskProcessors.class);
    private final AtomicBoolean emailServiceIsFlaky = new AtomicBoolean(true);

    @Inject
    JobStore jobStore;

    // --- Email Sending with Retry ---
    @Incoming("async-email-tasks")
    @Retry(maxRetries = 2, delay = 200)
    public Uni<Void> onUserRegistered(DomainRecords.JobRequest request) {
        var user = (DomainRecords.User) request.payload();
        return jobStore.updateStatus(request.jobId(), DomainRecords.JobStatus.RUNNING)
            .onItem().invoke(() -> log.infof("Processing email for %s (Job: %s)", user.email(), request.jobId()))
            .onItem().transformToUni(v -> sendEmail(user))
            .onItem().transformToUni(v -> jobStore.updateStatus(request.jobId(), DomainRecords.JobStatus.COMPLETED))
            .onFailure().recoverWithUni(failure -> {
                log.errorf(failure, "Email task failed for job %s", request.jobId());
                return jobStore.updateStatus(request.jobId(), DomainRecords.JobStatus.FAILED);
            });
    }

    private Uni<Void> sendEmail(DomainRecords.User user) {
        return Uni.createFrom().item(() -> {
            if (emailServiceIsFlaky.getAndSet(false)) { // Fail on the first attempt
                throw new IllegalStateException("Email service is down!");
            }
            log.infof("Email successfully sent to %s", user.email());
            emailServiceIsFlaky.set(true); // Reset for next job
            return null;
        });
    }

    // --- Image Processing Pipeline ---
    @Incoming("image-pipeline-start")
    @Outgoing("image-pipeline-resized")
    @Blocking
    public Message<DomainRecords.JobRequest> processImage(Message<DomainRecords.JobRequest> msg) throws InterruptedException {
        var request = msg.getPayload();
        jobStore.updateStatus(request.jobId(), DomainRecords.JobStatus.RUNNING).await().indefinitely();
        log.infof("PIPELINE [1/3] Resizing image for job %s", request.jobId());
        Thread.sleep(1000);
        return msg;
    }

    @Incoming("image-pipeline-resized")
    @Outgoing("image-pipeline-watermarked")
    @Blocking
    public DomainRecords.JobRequest watermarkImage(DomainRecords.JobRequest request) throws InterruptedException {
        log.infof("PIPELINE [2/3] Watermarking image for job %s", request.jobId());
        Thread.sleep(1000);
        return request;
    }

    @Incoming("image-pipeline-watermarked")
    public Uni<Void> uploadAndComplete(DomainRecords.JobRequest request) {
        log.infof("PIPELINE [3/3] Uploading image for job %s", request.jobId());
        return Uni.createFrom().completionStage(
            CompletableFuture.runAsync(() -> {
                try { Thread.sleep(1000); } catch (InterruptedException e) { /* ignore */ }
            })
        ).onItem().transformToUni(v -> jobStore.updateStatus(request.jobId(), DomainRecords.JobStatus.COMPLETED));
    }
}

// --- SCHEDULED TASKS ---
@ApplicationScoped
class ScheduledReporter {
    private static final Logger log = Logger.getLogger(ScheduledReporter.class);

    @Scheduled(cron = "0/30 * * * * ?")
    void generateSystemHealthReport() {
        log.info("PERIODIC JOB: Generating system health report...");
        // Simulate report generation
        log.info("PERIODIC JOB: System health is nominal. Report complete.");
    }
}

// --- API & TASK ORCHESTRATION ---
@Path("/v2/tasks")
@ApplicationScoped
public class TaskOrchestrator {

    @Inject
    JobStore jobStore;

    @Inject
    @Channel("async-email-tasks")
    Emitter<DomainRecords.JobRequest> emailTaskEmitter;

    @Inject
    @Channel("image-pipeline-start")
    Emitter<DomainRecords.JobRequest> imageTaskEmitter;

    @POST
    @Path("/users")
    public Response handleUserCreation() {
        var user = DomainRecords.User.create("new.user.reactive@example.com");
        var jobId = UUID.randomUUID();
        jobStore.create(jobId, "Send welcome email to " + user.email());
        emailTaskEmitter.send(new DomainRecords.JobRequest(jobId, user));
        return Response.accepted().entity("Job created: " + jobId).build();
    }

    @POST
    @Path("/posts")
    public Response handlePostCreation() {
        var post = DomainRecords.Post.create(UUID.randomUUID(), "Reactive Adventures");
        var jobId = UUID.randomUUID();
        jobStore.create(jobId, "Process image for post: " + post.title());
        imageTaskEmitter.send(new DomainRecords.JobRequest(jobId, post));
        return Response.accepted().entity("Job created: " + jobId).build();
    }
}