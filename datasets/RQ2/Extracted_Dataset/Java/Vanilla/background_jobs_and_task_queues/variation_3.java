package background_jobs.v3;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;

// --- Domain Schema ---
enum UserRole { ADMIN, USER }
enum PostStatus { DRAFT, PUBLISHED }

record User(UUID id, String email, String password_hash, UserRole role, boolean is_active, Timestamp created_at) {}
record Post(UUID id, UUID user_id, String title, String content, PostStatus status) {}

// --- Job System Implementation (Singleton-based Central Dispatcher) ---

interface Job {
    void execute() throws Exception;
    int getMaxRetries();
    String getName();
}

enum JobReportStatus { SUBMITTED, IN_FLIGHT, COMPLETED, FAILED }

class JobReport {
    final UUID jobId;
    volatile JobReportStatus status;
    int attempts;

    JobReport(UUID jobId) {
        this.jobId = jobId;
        this.status = JobReportStatus.SUBMITTED;
        this.attempts = 0;
    }
}

/**
 * A singleton dispatcher for managing all background job processing.
 */
public final class TaskDispatcher {
    private static final TaskDispatcher INSTANCE = new TaskDispatcher();

    private final BlockingQueue<Runnable> workQueue;
    private final ThreadPoolExecutor workerPool;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentMap<UUID, JobReport> jobReports;

    private TaskDispatcher() {
        int corePoolSize = 2;
        int maxPoolSize = 5;
        long keepAliveTime = 60L;
        this.workQueue = new LinkedBlockingQueue<>(100);
        this.workerPool = new ThreadPoolExecutor(corePoolSize, maxPoolSize, keepAliveTime, TimeUnit.SECONDS, workQueue);
        this.scheduler = Executors.newScheduledThreadPool(2);
        this.jobReports = new ConcurrentHashMap<>();
        System.out.println("TaskDispatcher initialized.");
    }

    public static TaskDispatcher getInstance() {
        return INSTANCE;
    }



    private class JobWrapper implements Runnable {
        private final Job job;
        private final JobReport report;

        JobWrapper(Job job, JobReport report) {
            this.job = job;
            this.report = report;
        }

        @Override
        public void run() {
            report.attempts++;
            report.status = JobReportStatus.IN_FLIGHT;
            try {
                job.execute();
                report.status = JobReportStatus.COMPLETED;
            } catch (Exception e) {
                System.err.printf("Job '%s' (%s) failed on attempt %d/%d.%n", job.getName(), report.jobId, report.attempts, job.getMaxRetries());
                if (report.attempts < job.getMaxRetries()) {
                    reschedule(this);
                } else {
                    report.status = JobReportStatus.FAILED;
                    System.err.printf("Job '%s' (%s) reached max retries and failed permanently.%n", job.getName(), report.jobId);
                }
            }
        }

        private void reschedule(JobWrapper wrapper) {
            long delay = 500L * (long) Math.pow(2, wrapper.report.attempts);
            System.out.printf("Retrying job '%s' in %d ms.%n", wrapper.job.getName(), delay);
            scheduler.schedule(wrapper, delay, TimeUnit.MILLISECONDS);
        }
    }

    public UUID dispatch(Job job) {
        UUID jobId = UUID.randomUUID();
        JobReport report = new JobReport(jobId);
        jobReports.put(jobId, report);
        workerPool.execute(new JobWrapper(job, report));
        System.out.printf("Dispatched job '%s' (%s).%n", job.getName(), jobId);
        return jobId;
    }

    public void scheduleAtFixedRate(Job job, long initialDelay, long period, TimeUnit unit) {
        JobReport mockReport = new JobReport(UUID.randomUUID()); // Not tracked for periodic tasks
        scheduler.scheduleAtFixedRate(new JobWrapper(job, mockReport), initialDelay, period, unit);
        System.out.printf("Scheduled periodic job '%s'.%n", job.getName());
    }

    public JobReportStatus getJobStatus(UUID jobId) {
        JobReport report = jobReports.get(jobId);
        return (report != null) ? report.status : null;
    }

    public void shutdown() {
        System.out.println("Shutting down TaskDispatcher...");
        workerPool.shutdown();
        scheduler.shutdown();
        try {
            workerPool.awaitTermination(1, TimeUnit.MINUTES);
            scheduler.awaitTermination(1, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            workerPool.shutdownNow();
            scheduler.shutdownNow();
        }
        System.out.println("TaskDispatcher is shut down.");
    }
}

// --- Concrete Job Implementations for Dispatcher ---

class EmailJob implements Job {
    private final User user;
    public EmailJob(User user) { this.user = user; }
    
    @Override
    public void execute() throws Exception {
        System.out.printf("--> Executing EmailJob for %s%n", user.email());
        Thread.sleep(1000);
        if (System.currentTimeMillis() % 4 == 0) { // 25% failure rate
            throw new Exception("Mail server unavailable");
        }
        System.out.printf("<-- Finished EmailJob for %s%n", user.email());
    }
    
    @Override public int getMaxRetries() { return 4; }
    @Override public String getName() { return "SendWelcomeEmail"; }
}

class ImagePipelineJob implements Job {
    private final Post post;
    public ImagePipelineJob(Post post) { this.post = post; }

    @Override
    public void execute() throws Exception {
        System.out.printf("--> Starting ImagePipelineJob for post '%s'%n", post.title());
        Thread.sleep(2500);
        System.out.printf("<-- Finished ImagePipelineJob for post '%s'%n", post.title());
    }

    @Override public int getMaxRetries() { return 2; }
    @Override public String getName() { return "ProcessPostImages"; }
}

class CleanupJob implements Job {
    @Override
    public void execute() {
        System.out.println("--> Running periodic CleanupJob...");
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        System.out.println("<-- CleanupJob complete.");
    }

    @Override public int getMaxRetries() { return 1; }
    @Override public String getName() { return "SystemCleanup"; }
}

// --- Main Application ---
class Variation3 {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("--- Variation 3: Singleton-based Central Dispatcher ---");

        // Mock Data
        User user = new User(UUID.randomUUID(), "singleton.user@host.com", "hash", UserRole.ADMIN, true, Timestamp.from(Instant.now()));
        Post post = new Post(UUID.randomUUID(), user.id(), "Singleton Pattern", "...", PostStatus.DRAFT);

        // Get dispatcher instance and dispatch jobs
        TaskDispatcher dispatcher = TaskDispatcher.getInstance();
        
        dispatcher.scheduleAtFixedRate(new CleanupJob(), 5, 15, TimeUnit.SECONDS);

        UUID emailJobId = dispatcher.dispatch(new EmailJob(user));
        UUID imageJobId = dispatcher.dispatch(new ImagePipelineJob(post));

        // Monitor
        for (int i = 0; i < 15; i++) {
            Thread.sleep(2000);
            System.out.printf("[MONITOR] Email Job: %s, Image Job: %s%n",
                dispatcher.getJobStatus(emailJobId),
                dispatcher.getJobStatus(imageJobId));
        }

        dispatcher.shutdown();
    }
}