package background_jobs.v1;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;

// --- Domain Schema ---
enum UserRole { ADMIN, USER }
enum PostStatus { DRAFT, PUBLISHED }

record User(UUID id, String email, String password_hash, UserRole role, boolean is_active, Timestamp created_at) {}
record Post(UUID id, UUID user_id, String title, String content, PostStatus status) {}

// --- Job System Implementation (Classic OOP / Service-Oriented) ---

enum JobStatus { PENDING, RUNNING, COMPLETED, FAILED }

/**
 * Abstract base class for all background jobs.
 * Encapsulates common properties like ID, status, and retry logic.
 */
abstract class Job implements Runnable {
    protected final UUID jobId;
    protected volatile JobStatus status;
    protected int attemptCount;
    private final int maxAttempts;
    private final JobManager jobManager;

    public Job(JobManager jobManager, int maxAttempts) {
        this.jobId = UUID.randomUUID();
        this.status = JobStatus.PENDING;
        this.attemptCount = 0;
        this.maxAttempts = maxAttempts;
        this.jobManager = jobManager;
    }

    public UUID getJobId() {
        return jobId;
    }

    public JobStatus getStatus() {
        return status;
    }

    protected void setStatus(JobStatus status) {
        this.status = status;
        jobManager.updateJobStatus(this.jobId, status);
    }

    @Override
    public final void run() {
        attemptCount++;
        setStatus(JobStatus.RUNNING);
        try {
            execute();
            setStatus(JobStatus.COMPLETED);
        } catch (Exception e) {
            System.err.printf("Job %s failed on attempt %d/%d. Error: %s%n", jobId, attemptCount, maxAttempts, e.getMessage());
            if (attemptCount < maxAttempts) {
                handleRetry();
            } else {
                setStatus(JobStatus.FAILED);
                System.err.printf("Job %s has permanently failed after %d attempts.%n", jobId, maxAttempts);
            }
        }
    }

    private void handleRetry() {
        long delay = (long) (1000 * Math.pow(2, attemptCount)); // Exponential backoff
        System.out.printf("Job %s will be retried in %d ms.%n", jobId, delay);
        setStatus(JobStatus.PENDING); // Reset status for re-queuing
        jobManager.scheduleForRetry(this, delay, TimeUnit.MILLISECONDS);
    }

    /**
     * The core logic of the job to be implemented by subclasses.
     * @throws Exception if the job fails and should be retried.
     */
    public abstract void execute() throws Exception;
}

/**
 * Manages the lifecycle of jobs, including queuing, execution, and scheduling.
 */
class JobManager {
    private final BlockingQueue<Job> jobQueue;
    private final ExecutorService workerPool;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentMap<UUID, JobStatus> jobStatusTracker;
    private final int workerCount;

    public JobManager(int workerCount) {
        this.workerCount = workerCount;
        this.jobQueue = new LinkedBlockingQueue<>();
        this.workerPool = Executors.newFixedThreadPool(workerCount);
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.jobStatusTracker = new ConcurrentHashMap<>();
    }

    public void start() {
        for (int i = 0; i < workerCount; i++) {
            workerPool.submit(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Job job = jobQueue.take();
                        job.run();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        System.out.println("Worker thread interrupted.");
                        break;
                    }
                }
            });
        }
        System.out.printf("%d worker threads started.%n", workerCount);
    }

    public UUID submit(Job job) {
        jobStatusTracker.put(job.getJobId(), job.getStatus());
        jobQueue.offer(job);
        System.out.printf("Submitted job %s of type %s.%n", job.getJobId(), job.getClass().getSimpleName());
        return job.getJobId();
    }

    public void schedulePeriodic(Job job, long initialDelay, long period, TimeUnit unit) {
        scheduler.scheduleAtFixedRate(job, initialDelay, period, unit);
        System.out.printf("Scheduled periodic job %s to run every %d %s.%n", job.getClass().getSimpleName(), period, unit.toString().toLowerCase());
    }
    
    void scheduleForRetry(Job job, long delay, TimeUnit unit) {
        scheduler.schedule(() -> jobQueue.offer(job), delay, unit);
    }

    public void updateJobStatus(UUID jobId, JobStatus status) {
        jobStatusTracker.put(jobId, status);
    }

    public JobStatus getJobStatus(UUID jobId) {
        return jobStatusTracker.get(jobId);
    }

    public void shutdown() {
        System.out.println("Shutting down JobManager...");
        workerPool.shutdown();
        scheduler.shutdown();
        try {
            if (!workerPool.awaitTermination(60, TimeUnit.SECONDS)) {
                workerPool.shutdownNow();
            }
            if (!scheduler.awaitTermination(60, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            workerPool.shutdownNow();
            scheduler.shutdownNow();
        }
        System.out.println("JobManager shut down.");
    }
}

// --- Concrete Job Implementations ---

class SendWelcomeEmailJob extends Job {
    private final User user;

    public SendWelcomeEmailJob(User user, JobManager manager) {
        super(manager, 3); // 3 attempts max
        this.user = user;
    }

    @Override
    public void execute() throws Exception {
        System.out.printf("--> [Email Job %s] Sending welcome email to %s...%n", jobId, user.email());
        // Simulate a potentially failing network operation
        if (Math.random() > 0.3) { // 70% chance of success
            Thread.sleep(1000);
            System.out.printf("<-- [Email Job %s] Successfully sent email to %s.%n", jobId, user.email());
        } else {
            throw new Exception("SMTP server connection failed");
        }
    }
}

class ImageProcessingPipelineJob extends Job {
    private final Post post;

    public ImageProcessingPipelineJob(Post post, JobManager manager) {
        super(manager, 2);
        this.post = post;
    }

    @Override
    public void execute() throws Exception {
        System.out.printf("--> [Image Job %s] Starting image processing for post '%s'...%n", jobId, post.title());
        try {
            // Simulate a multi-step process
            resizeImage();
            applyWatermark();
            uploadToCdn();
            System.out.printf("<-- [Image Job %s] Image processing pipeline completed for post '%s'.%n", jobId, post.title());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Exception("Image processing was interrupted", e);
        }
    }

    private void resizeImage() throws InterruptedException {
        System.out.printf("    [Image Job %s] Step 1: Resizing image...%n", jobId);
        Thread.sleep(1500);
    }

    private void applyWatermark() throws InterruptedException {
        System.out.printf("    [Image Job %s] Step 2: Applying watermark...%n", jobId);
        Thread.sleep(1000);
    }

    private void uploadToCdn() throws InterruptedException {
        System.out.printf("    [Image Job %s] Step 3: Uploading to CDN...%n", jobId);
        Thread.sleep(2000);
    }
}

class DatabaseCleanupJob extends Job {
    public DatabaseCleanupJob(JobManager manager) {
        super(manager, 1); // No retries for a cleanup task
    }

    @Override
    public void execute() {
        System.out.println("--> [Cleanup Job] Running periodic database cleanup...");
        // Simulate DB query
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.out.println("<-- [Cleanup Job] Database cleanup finished.");
    }
}


// --- Main Application ---
public class Variation1 {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("--- Variation 1: Classic OOP / Service-Oriented ---");

        // Mock Data
        User newUser = new User(UUID.randomUUID(), "test@example.com", "hash", UserRole.USER, true, Timestamp.from(Instant.now()));
        Post newPost = new Post(UUID.randomUUID(), newUser.id(), "My New Adventure", "...", PostStatus.DRAFT);

        // Setup
        JobManager jobManager = new JobManager(3);
        jobManager.start();

        // Schedule periodic task
        jobManager.schedulePeriodic(new DatabaseCleanupJob(jobManager), 5, 10, TimeUnit.SECONDS);

        // Submit async jobs
        UUID emailJobId = jobManager.submit(new SendWelcomeEmailJob(newUser, jobManager));
        UUID imageJobId = jobManager.submit(new ImageProcessingPipelineJob(newPost, jobManager));

        // Monitor job statuses
        for (int i = 0; i < 15; i++) {
            Thread.sleep(2000);
            JobStatus emailStatus = jobManager.getJobStatus(emailJobId);
            JobStatus imageStatus = jobManager.getJobStatus(imageJobId);
            System.out.printf("[MONITOR] Email Job: %s, Image Job: %s%n", emailStatus, imageStatus);
            if ((emailStatus == JobStatus.COMPLETED || emailStatus == JobStatus.FAILED) &&
                (imageStatus == JobStatus.COMPLETED || imageStatus == JobStatus.FAILED)) {
                break;
            }
        }

        // Shutdown
        jobManager.shutdown();
    }
}