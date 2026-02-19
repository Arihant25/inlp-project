package background_jobs.v4;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;

// --- Domain Schema ---
enum UserRole { ADMIN, USER }
enum PostStatus { DRAFT, PUBLISHED }

record User(UUID id, String email, String password_hash, UserRole role, boolean is_active, Timestamp created_at) {}
record Post(UUID id, UUID user_id, String title, String content, PostStatus status) {}

// --- Job System Implementation (Event-Driven / Explicit State Machine) ---

enum JobState { PENDING, RUNNING, AWAITING_RETRY, COMPLETED, FAILED_PERMANENTLY }

/**
 * An abstract job that manages its own state transitions.
 */
abstract class StatefulJob implements Runnable {
    public final UUID id = UUID.randomUUID();
    protected volatile JobState state = JobState.PENDING;
    protected int attemptCount = 0;
    
    private final int maxAttempts;
    private final JobProcessor processor;

    protected StatefulJob(JobProcessor processor, int maxAttempts) {
        this.processor = processor;
        this.maxAttempts = maxAttempts;
    }

    // Template method pattern
    @Override
    public final void run() {
        if (state == JobState.FAILED_PERMANENTLY || state == JobState.COMPLETED) return;

        transitionTo(JobState.RUNNING);
        attemptCount++;
        try {
            process();
            onSuccess();
        } catch (Exception e) {
            onFailure(e);
        }
    }

    private void onSuccess() {
        System.out.printf("Job %s (%s) completed successfully.%n", this.getClass().getSimpleName(), id);
        transitionTo(JobState.COMPLETED);
    }

    private void onFailure(Exception e) {
        System.err.printf("Job %s (%s) failed on attempt %d. Reason: %s%n", this.getClass().getSimpleName(), id, attemptCount, e.getMessage());
        if (attemptCount >= maxAttempts) {
            transitionTo(JobState.FAILED_PERMANENTLY);
            System.err.printf("Job %s (%s) has failed permanently.%n", this.getClass().getSimpleName(), id);
        } else {
            transitionTo(JobState.AWAITING_RETRY);
            long backoffDelay = 1000L * (long) Math.pow(2, attemptCount - 1);
            System.out.printf("Job %s (%s) will be retried in %dms.%n", this.getClass().getSimpleName(), id, backoffDelay);
            processor.scheduleForRetry(this, backoffDelay);
        }
    }

    private void transitionTo(JobState newState) {
        this.state = newState;
        processor.updateJobState(this.id, newState);
    }

    public JobState getState() {
        return this.state;
    }

    /** Subclasses implement their specific logic here. */
    protected abstract void process() throws Exception;
}

/**
 * The processor is a relatively "dumb" executor of StatefulJobs.
 * It manages the queues and threads, but the job itself dictates its lifecycle.
 */
class JobProcessor {
    private final ExecutorService workerPool;
    private final ScheduledExecutorService scheduler;
    private final BlockingQueue<StatefulJob> pendingJobQueue;
    private final ConcurrentMap<UUID, JobState> jobStateTracker;

    public JobProcessor(int numWorkers) {
        this.workerPool = Executors.newFixedThreadPool(numWorkers);
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.pendingJobQueue = new LinkedBlockingQueue<>();
        this.jobStateTracker = new ConcurrentHashMap<>();
    }

    public void start() {
        int poolSize = ((ThreadPoolExecutor) workerPool).getCorePoolSize();
        for (int i = 0; i < poolSize; i++) {
            workerPool.submit(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        StatefulJob job = pendingJobQueue.take();
                        job.run();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            });
        }
        System.out.printf("JobProcessor started with %d workers.%n", poolSize);
    }

    public UUID submit(StatefulJob job) {
        jobStateTracker.put(job.id, job.getState());
        pendingJobQueue.offer(job);
        System.out.printf("Submitted job %s (%s).%n", job.getClass().getSimpleName(), job.id);
        return job.id;
    }
    
    public void schedulePeriodic(Runnable task, long delay, long period, TimeUnit unit) {
        scheduler.scheduleAtFixedRate(task, delay, period, unit);
        System.out.printf("Scheduled periodic task %s.%n", task.getClass().getSimpleName());
    }

    void scheduleForRetry(StatefulJob job, long delayMs) {
        scheduler.schedule(() -> pendingJobQueue.offer(job), delayMs, TimeUnit.MILLISECONDS);
    }

    void updateJobState(UUID jobId, JobState state) {
        jobStateTracker.put(jobId, state);
    }

    public JobState getJobState(UUID jobId) {
        return jobStateTracker.get(jobId);
    }

    public void shutdown() throws InterruptedException {
        System.out.println("Shutting down JobProcessor...");
        workerPool.shutdown();
        scheduler.shutdown();
        if (!workerPool.awaitTermination(30, TimeUnit.SECONDS)) workerPool.shutdownNow();
        if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) scheduler.shutdownNow();
        System.out.println("JobProcessor shut down.");
    }
}

// --- Concrete StatefulJob Implementations ---

class AsyncEmailSenderJob extends StatefulJob {
    private final User user;
    public AsyncEmailSenderJob(User user, JobProcessor processor) {
        super(processor, 3);
        this.user = user;
    }

    @Override
    protected void process() throws Exception {
        System.out.printf("--> Attempting to send email to %s...%n", user.email());
        Thread.sleep(800);
        if (Math.random() > 0.5) { // 50% success rate
            System.out.printf("<-- Email successfully sent to %s.%n", user.email());
        } else {
            throw new RuntimeException("SMTP server timed out");
        }
    }
}

class PostImageProcessorJob extends StatefulJob {
    private final Post post;
    public PostImageProcessorJob(Post post, JobProcessor processor) {
        super(processor, 2);
        this.post = post;
    }

    @Override
    protected void process() throws Exception {
        System.out.printf("--> Processing images for post: %s%n", post.title());
        Thread.sleep(3000);
        System.out.printf("<-- Finished image processing for post: %s%n", post.title());
    }
}

// --- Main Application ---
public class Variation4 {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("--- Variation 4: Event-Driven / Explicit State Machine ---");

        // Mock Data
        User user = new User(UUID.randomUUID(), "stateful.user@corp.net", "hash", UserRole.USER, true, Timestamp.from(Instant.now()));
        Post post = new Post(UUID.randomUUID(), user.id(), "State Machines in Java", "...", PostStatus.PUBLISHED);

        // Setup
        JobProcessor processor = new JobProcessor(3);
        processor.start();

        // Schedule periodic task (as a simple Runnable)
        Runnable cleanupTask = () -> System.out.println("[Periodic Task] Deleting temporary files...");
        processor.schedulePeriodic(cleanupTask, 5, 10, TimeUnit.SECONDS);

        // Submit stateful jobs
        UUID emailJobId = processor.submit(new AsyncEmailSenderJob(user, processor));
        UUID imageJobId = processor.submit(new PostImageProcessorJob(post, processor));

        // Monitor job states
        while (true) {
            Thread.sleep(2500);
            JobState emailState = processor.getJobState(emailJobId);
            JobState imageState = processor.getJobState(imageJobId);
            System.out.printf("[MONITOR] Email Job State: %s, Image Job State: %s%n", emailState, imageState);

            boolean emailDone = emailState == JobState.COMPLETED || emailState == JobState.FAILED_PERMANENTLY;
            boolean imageDone = imageState == JobState.COMPLETED || imageState == JobState.FAILED_PERMANENTLY;

            if (emailDone && imageDone) {
                break;
            }
        }

        // Shutdown
        processor.shutdown();
    }
}