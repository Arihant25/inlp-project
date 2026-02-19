package background_jobs.v2;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;

// --- Domain Schema ---
enum UserRole { ADMIN, USER }
enum PostStatus { DRAFT, PUBLISHED }

record User(UUID id, String email, String password_hash, UserRole role, boolean is_active, Timestamp created_at) {}
record Post(UUID id, UUID user_id, String title, String content, PostStatus status) {}

// --- Job System Implementation (Functional / Command Pattern) ---

enum TaskStatus { QUEUED, IN_PROGRESS, DONE, FAILED_RETRY, FAILED_FINAL }

/**
 * A wrapper for a Runnable, adding metadata for tracking and retries.
 */
class Task {
    final UUID id;
    final String name;
    final Runnable action;
    volatile TaskStatus status;
    int attempt;
    final int maxAttempts;

    Task(String name, Runnable action, int maxAttempts) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.action = action;
        this.maxAttempts = maxAttempts;
        this.status = TaskStatus.QUEUED;
        this.attempt = 0;
    }
}

/**
 * Manages a queue of tasks and a pool of worker threads.
 * Focuses on executing Runnables and handling retry logic externally.
 */
class TaskEngine {
    private final BlockingQueue<Task> taskQ = new LinkedBlockingQueue<>();
    private final ExecutorService execSvc;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentMap<UUID, TaskStatus> statusMap = new ConcurrentHashMap<>();

    public TaskEngine(int concurrency) {
        this.execSvc = Executors.newFixedThreadPool(concurrency);
    }

    public void startWorkers() {
        for (int i = 0; i < ((ThreadPoolExecutor) execSvc).getCorePoolSize(); i++) {
            execSvc.submit(this::workerLoop);
        }
        System.out.printf("TaskEngine started with %d workers.%n", ((ThreadPoolExecutor) execSvc).getCorePoolSize());
    }

    private void workerLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Task task = taskQ.take();
                task.attempt++;
                updateStatus(task, TaskStatus.IN_PROGRESS);

                try {
                    task.action.run();
                    updateStatus(task, TaskStatus.DONE);
                } catch (Exception e) {
                    System.err.printf("Task '%s' (%s) failed on attempt %d: %s%n", task.name, task.id, task.attempt, e.getMessage());
                    if (task.attempt < task.maxAttempts) {
                        updateStatus(task, TaskStatus.FAILED_RETRY);
                        rescheduleWithBackoff(task);
                    } else {
                        updateStatus(task, TaskStatus.FAILED_FINAL);
                        System.err.printf("Task '%s' (%s) has failed permanently.%n", task.name, task.id);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void rescheduleWithBackoff(Task task) {
        long delay = 1000L * (long) Math.pow(2, task.attempt -1);
        System.out.printf("Rescheduling task '%s' in %dms.%n", task.name, delay);
        scheduler.schedule(() -> taskQ.offer(task), delay, TimeUnit.MILLISECONDS);
    }

    public UUID submit(String name, Runnable action, int maxAttempts) {
        Task task = new Task(name, action, maxAttempts);
        statusMap.put(task.id, task.status);
        taskQ.offer(task);
        System.out.printf("Submitted task '%s' (%s).%n", name, task.id);
        return task.id;
    }

    public void scheduleRecurring(String name, Runnable action, long period, TimeUnit unit) {
        // For recurring tasks, we wrap the action to resubmit itself.
        // This is a simple way to do it without complex job classes.
        Runnable recurringAction = () -> {
            try {
                action.run();
            } catch (Exception e) {
                System.err.printf("Recurring task '%s' failed: %s%n", name, e.getMessage());
            }
        };
        scheduler.scheduleAtFixedRate(recurringAction, period, period, unit);
        System.out.printf("Scheduled recurring task '%s' to run every %d %s.%n", name, period, unit.toString().toLowerCase());
    }

    private void updateStatus(Task task, TaskStatus newStatus) {
        task.status = newStatus;
        statusMap.put(task.id, newStatus);
    }

    public TaskStatus getTaskStatus(UUID id) {
        return statusMap.get(id);
    }

    public void stop() throws InterruptedException {
        System.out.println("Stopping TaskEngine...");
        execSvc.shutdown();
        scheduler.shutdown();
        if (!execSvc.awaitTermination(30, TimeUnit.SECONDS)) execSvc.shutdownNow();
        if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) scheduler.shutdownNow();
        System.out.println("TaskEngine stopped.");
    }
}

// --- Mock Services for Functional Style ---
class Emailer {
    public void sendWelcome(User user) {
        System.out.printf("--> [Emailer] Sending welcome email to %s...%n", user.email());
        try {
            // Simulate network latency and potential failure
            Thread.sleep(1200);
            if (Math.random() < 0.3) { // 30% chance of failure
                throw new RuntimeException("Failed to connect to mail server");
            }
            System.out.printf("<-- [Emailer] Email sent to %s.%n", user.email());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

class ImageProcessor {
    public void processPostImages(Post post) {
        System.out.printf("--> [ImageProcessor] Processing images for post: %s%n", post.title());
        try {
            Thread.sleep(1000); // Step 1
            System.out.printf("    [ImageProcessor] Resized images for '%s'.%n", post.title());
            Thread.sleep(1500); // Step 2
            System.out.printf("    [ImageProcessor] Watermarked images for '%s'.%n", post.title());
            Thread.sleep(1000); // Step 3
            System.out.printf("<-- [ImageProcessor] Finished processing for '%s'.%n", post.title());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

// --- Main Application ---
public class Variation2 {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("--- Variation 2: Functional / Command Pattern ---");

        // Mock Data & Services
        User user = new User(UUID.randomUUID(), "jane.doe@email.com", "hash", UserRole.USER, true, Timestamp.from(Instant.now()));
        Post post = new Post(UUID.randomUUID(), user.id(), "Functional Java", "...", PostStatus.PUBLISHED);
        Emailer emailer = new Emailer();
        ImageProcessor imageProcessor = new ImageProcessor();

        // Setup
        TaskEngine engine = new TaskEngine(4);
        engine.startWorkers();

        // Schedule and submit tasks using lambdas
        engine.scheduleRecurring("DB_CLEANUP", () -> System.out.println("[Recurring] Cleaning up old records..."), 10, TimeUnit.SECONDS);

        UUID emailTaskId = engine.submit("SendWelcomeEmail", () -> emailer.sendWelcome(user), 3);
        UUID imageTaskId = engine.submit("ProcessPostImages", () -> imageProcessor.processPostImages(post), 2);

        // Monitor
        for (int i = 0; i < 20; i++) {
            Thread.sleep(1500);
            TaskStatus emailStatus = engine.getTaskStatus(emailTaskId);
            TaskStatus imageStatus = engine.getTaskStatus(imageTaskId);
            System.out.printf("[MONITOR] Email Task: %s, Image Task: %s%n", emailStatus, imageStatus);
            if ((emailStatus == TaskStatus.DONE || emailStatus == TaskStatus.FAILED_FINAL) &&
                (imageStatus == TaskStatus.DONE || imageStatus == TaskStatus.FAILED_FINAL)) {
                break;
            }
        }

        // Shutdown
        engine.stop();
    }
}