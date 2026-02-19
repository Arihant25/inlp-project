<?php

// --- Domain Schema & Mocks ---

enum UserRole { case ADMIN; case USER; }
enum PostStatus { case DRAFT; case PUBLISHED; }

class UserEntity {
    public function __construct(public string $id, public string $email) {}
}

class PostEntity {
    public function __construct(public string $id, public string $userId, public string $title) {}
}

// --- Mock Services ---
class MockEmailer {
    public static function dispatch(string $email, string $subject, string $body): bool {
        echo "DISPATCHING EMAIL to {$email}...\n";
        sleep(1);
        if (rand(0, 10) < 3) { // 30% failure rate
            echo "EMAIL FAILED: Network error for {$email}.\n";
            return false;
        }
        echo "EMAIL SENT to {$email}.\n";
        return true;
    }
}

class MockImagePipeline {
    public static function run(string $image_path): bool {
        echo "IMAGE PIPELINE started for {$image_path}.\n";
        sleep(3); // Simulate complex processing
        echo "IMAGE PIPELINE finished for {$image_path}.\n";
        return true;
    }
}

// --- Background Job System (Service-oriented/Manager Pattern) ---

final class JobManager {
    private static array $queue = [];
    private static array $scheduled = [];
    private static array $job_registry = [];
    private static array $status_log = [];
    private static bool $is_running = false;

    const STATUS_QUEUED = 'QUEUED';
    const STATUS_RUNNING = 'RUNNING';
    const STATUS_SUCCESS = 'SUCCESS';
    const STATUS_RETRY = 'RETRY';
    const STATUS_DEAD = 'DEAD';

    public static function registerJob(string $job_name, callable $handler): void {
        self::$job_registry[$job_name] = $handler;
    }

    public static function dispatch(string $job_name, array $args, int $retries = 3): ?string {
        if (!isset(self::$job_registry[$job_name])) {
            error_log("Attempted to dispatch unregistered job: {$job_name}");
            return null;
        }
        $job_id = uniqid('job_');
        $job_data = [
            'id' => $job_id,
            'name' => $job_name,
            'args' => $args,
            'attempt' => 0,
            'max_retries' => $retries,
            'run_at' => time(),
        ];
        self::$queue[] = $job_data;
        self::$status_log[$job_id] = self::STATUS_QUEUED;
        echo "DISPATCHED: Job {$job_id} ({$job_name})\n";
        return $job_id;
    }

    public static function schedule(string $job_name, array $args, int $interval_sec): string {
        $schedule_id = uniqid('sched_');
        self::$scheduled[$schedule_id] = [
            'id' => $schedule_id,
            'job_name' => $job_name,
            'args' => $args,
            'interval' => $interval_sec,
            'last_run' => time(), // Set to now to run on first cycle
        ];
        echo "SCHEDULED: Task {$schedule_id} ({$job_name}) to run every {$interval_sec}s\n";
        return $schedule_id;
    }

    public static function getStatus(string $job_id): ?string {
        return self::$status_log[$job_id] ?? null;
    }

    public static function runWorker(): void {
        if (self::$is_running) {
            return;
        }
        self::$is_running = true;
        echo "JobManager Worker is running...\n";

        while (true) {
            // 1. Check for scheduled tasks
            foreach (self::$scheduled as &$task) {
                if (time() >= $task['last_run'] + $task['interval']) {
                    self::dispatch($task['job_name'], $task['args'], 0); // Scheduled tasks don't retry by default
                    $task['last_run'] = time();
                }
            }
            unset($task);

            // 2. Find a job to process
            $job_to_run = null;
            $job_key = null;
            foreach (self::$queue as $key => $job) {
                if (time() >= $job['run_at']) {
                    $job_to_run = $job;
                    $job_key = $key;
                    break;
                }
            }

            if ($job_to_run === null) {
                sleep(1); // No jobs ready, wait
                continue;
            }

            // 3. Execute the job
            unset(self::$queue[$job_key]); // Dequeue
            self::$queue = array_values(self::$queue); // Re-index

            $id = $job_to_run['id'];
            $name = $job_to_run['name'];
            $handler = self::$job_registry[$name];
            
            self::$status_log[$id] = self::STATUS_RUNNING;
            $job_to_run['attempt']++;
            echo "PROCESSING: Job {$id} ({$name}), Attempt {$job_to_run['attempt']}\n";

            $result = false;
            try {
                $result = $handler(...$job_to_run['args']);
            } catch (\Throwable $e) {
                error_log("Job {$id} failed with exception: " . $e->getMessage());
                $result = false;
            }

            // 4. Handle result
            if ($result === true) {
                self::$status_log[$id] = self::STATUS_SUCCESS;
                echo "SUCCESS: Job {$id}\n";
            } else {
                if ($job_to_run['attempt'] <= $job_to_run['max_retries']) {
                    $delay = 10 * ($job_to_run['attempt'] ** 2); // Exponential backoff
                    $job_to_run['run_at'] = time() + $delay;
                    self::$queue[] = $job_to_run; // Re-queue
                    self::$status_log[$id] = self::STATUS_RETRY;
                    echo "RETRYING: Job {$id} in {$delay}s\n";
                } else {
                    self::$status_log[$id] = self::STATUS_DEAD;
                    echo "DEAD: Job {$id} failed after {$job_to_run['max_retries']} retries.\n";
                }
            }
        }
    }
}

// --- Job Definitions ---

JobManager::registerJob('send-user-welcome-email', function(string $email) {
    return MockEmailer::dispatch($email, 'Welcome!', 'Thank you for joining our platform.');
});

JobManager::registerJob('process-post-images', function(string $image_path) {
    return MockImagePipeline::run($image_path);
});

JobManager::registerJob('audit-inactive-users', function() {
    echo "AUDITING: Checking for inactive users...\n";
    sleep(2);
    echo "AUDITING: Complete. 0 users deactivated.\n";
    return true;
});

// --- Main Application Logic ---

// A new user signs up
$user = new UserEntity(uniqid(), 'charlie@example.com');
$jobId = JobManager::dispatch('send-user-welcome-email', [$user->email]);

// A user publishes a post with an image
$post = new PostEntity(uniqid(), $user->id, 'My Awesome Post');
JobManager::dispatch('process-post-images', ['/uploads/new_image.png']);

// Schedule a recurring task
JobManager::schedule('audit-inactive-users', [], 60); // Every minute

// Start the worker (this would be a separate, long-running process)
JobManager::runWorker();

?>